package sy.great.toolbox.tools

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf

/**
 * 页面状态缓存。
 *
 * 解决的问题：
 *   进入一个页面 → 滚到某位置 → 返回 → 再进入，
 *   列表重新加载、滚动位置归零，体验很差。
 *
 * 方案：
 *   以「页面 key + 数据类型」为键，把列表数据、滚动位置存在内存里。
 *   页面重新进入时先读缓存渲染（瞬时出画面），再决定要不要后台刷新。
 *
 * 缓存不落盘：
 *   App 被杀就清空。这是故意的 ——
 *   避免展示过期数据（比如邮件已读状态、排行积分）。
 *
 * 使用约定：
 *   · key 用页面名，如 "mail" / "ranking"
 *   · 滚动位置用 Int（LazyList 的 firstVisibleItemIndex）
 *   · 需要强制刷新时调 invalidate(key)
 */
object PageCache {

    /** 列表数据：key → 任意类型（各页面自己转） */
    private val data = mutableStateMapOf<String, Any>()

    /** 滚动位置：key → firstVisibleItemIndex */
    private val scroll = mutableStateMapOf<String, Int>()

    /** 页面是否已经加载过（首次进入需要 loading） */
    private val loaded = mutableStateMapOf<String, Boolean>()

    /** 缓存时间：key → 毫秒时间戳 */
    private val stamp = mutableStateMapOf<String, Long>()

    /**
     * 缓存是否仍然新鲜。
     *
     * @param maxAgeMs 有效期；0 表示永不过期（只靠手动 invalidate）
     */
    fun isFresh(key: String, maxAgeMs: Long = 5 * 60_000L): Boolean {
        if (loaded[key] != true) return false
        if (maxAgeMs <= 0) return true
        val t = stamp[key] ?: 0L
        return System.currentTimeMillis() - t < maxAgeMs
    }

    /** 是否已经有可用数据（不看新鲜度） */
    fun has(key: String): Boolean = loaded[key] == true && data.containsKey(key)

    fun <T> put(key: String, value: T) {
        data[key] = value as Any
        loaded[key] = true
        stamp[key] = System.currentTimeMillis()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = data[key] as? T

    fun saveScroll(key: String, index: Int) {
        scroll[key] = index
    }

    fun scroll(key: String): Int = scroll[key] ?: 0

    /** 让某个页面的缓存失效（下次进入会重新拉） */
    fun invalidate(key: String) {
        loaded[key] = false
        stamp.remove(key)
    }

    /** 完全清空（退出登录时调） */
    fun clear() {
        data.clear()
        scroll.clear()
        loaded.clear()
        stamp.clear()
    }
}
