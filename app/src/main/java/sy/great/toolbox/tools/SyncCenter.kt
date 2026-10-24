package sy.great.toolbox.tools

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 实时同步中心。
 *
 * 解决的问题：
 *   以前 App 只在启动时拉一次 version.json，
 *   管理员改了称号 / 发了公告 / 发了新版本，用户必须重启 App 才能看到。
 *
 * 方案：单接口轮询。
 *   App 每 20 秒调一次 api.php?action=sync，
 *   服务端返回所有可能变化的维度 + 各自的 rev（版本标识）：
 *     · annRev  公告 id
 *     · verRev  versionCode
 *     · tagRev  当前用户称号内容哈希
 *   客户端对比 rev，只有变了才更新 Compose 状态，
 *   所以 UI 是响应式的，但网络请求量很小。
 *
 * 为什么不用 WebSocket / SSE：
 *   · 服务端是 php -S 单进程，长连接会卡死其他请求
 *   · 经 frp 中转的 WebSocket 容易断线重连
 *   · 20 秒轮询对一个玩具规模的 App 完全够用，一次请求 < 1KB
 *
 * 暴露的 Compose State：
 *   currentAnnouncement  待展示的公告（null = 不展示）
 *   latestVersion        最新版本信息
 *   currentTag           当前用户称号（含炫彩流光）
 */
object SyncCenter {

    // ---------------- Compose 可观察状态 ----------------

    /** 待展示的公告；null 表示当前没有要弹的 */
    var currentAnnouncement by mutableStateOf<VersionInfo.Announcement?>(null)
        private set

    /** 用户称号 */
    data class Tag(
        val text: String,
        val color: String,
        /** 炫彩流光颜色列表，size >= 2 时启用渐变流光 */
        val fxColors: List<String>,
    ) {
        val hasFx: Boolean get() = fxColors.size >= 2
    }

    /** 当前称号；null 表示无称号 */
    var currentTag by mutableStateOf<Tag?>(null)
        private set

    /** 最新版本 */
    var latestVersion by mutableStateOf<VersionInfo.Info?>(null)
        private set

    /**
     * 名字展示配置（独立于称号）。
     *
     * mode：显示 username / uid / email
     * fxColors：≥2 时启用流光
     * color：不开流光时的单色，空串用主题色
     */
    data class NameStyle(
        val mode: String = "username",
        val color: String = "",
        val fxColors: List<String> = emptyList(),
    ) {
        val hasFx: Boolean get() = fxColors.size >= 2
    }

    /** 当前用户的名字样式 */
    var currentNameStyle by mutableStateOf(NameStyle())
        private set

    /** 积分 / 签到状态 */
    data class Stat(
        val points: Int,
        val streak: Int,
        val total: Int,
        val canCheckin: Boolean,
        // 经验 / 等级
        val exp: Int = 0,
        val level: Int = 1,
        val expBase: Int = 0,
        val expNext: Int = 100,
    )

    /** 当前用户统计；null 表示未登录或未同步 */
    var currentStat by mutableStateOf<Stat?>(null)
        private set

    /** 外部写入（签到成功后立即更新，不等轮询） */
    fun applyStat(stat: Stat?) {
        currentStat = stat
    }

    // ---------------- 内部 rev 记录 ----------------

    private var lastAnnRev = ""
    private var lastVerRev = -1
    private var lastTagRev = ""
    private var lastPointsRev = ""
    private var lastNameRev = ""

    /** 已被用户关闭的公告 id（本次运行内不再弹） */
    private var dismissedAnnId = ""

    /** 上次同步时间戳 */
    var lastSyncAt = 0L
        private set

    // ---------------- 同步 ----------------

    /**
     * 立即同步一次。
     *
     * 线程安全：内部用 synchronized，多个入口同时调不会重复请求。
     * 失败静默（网络波动不该弹错误）。
     */
    @Synchronized
    fun syncNow(ctx: Context) {
        val token = AuthApi.token(ctx)
        val json = fetch(ctx, token) ?: return

        lastSyncAt = System.currentTimeMillis()

        // ---- 公告 ----
        val annRev = json.optString("annRev", "")
        val annObj = json.optJSONObject("ann")
        if (annRev != lastAnnRev) {
            lastAnnRev = annRev

            if (annObj == null) {
                currentAnnouncement = null
            } else {
                val a = VersionInfo.Announcement(
                    enabled = true,
                    id = annObj.optString("id", ""),
                    title = annObj.optString("title", ""),
                    content = annObj.optString("content", ""),
                    showOnce = annObj.optBoolean("once", true),
                )

                // 规则：
                //   · 本次运行内被关过 → 不弹
                //   · showOnce 且之前看过同 id → 不弹
                //   · 否则弹
                val allowed = a.id != dismissedAnnId &&
                        sy.great.toolbox.ui.component.AnnouncementStore.shouldShow(ctx, a)

                currentAnnouncement = if (allowed) a else null
            }
        }

        // ---- 版本 ----
        val verRev = json.optInt("verRev", 0)
        if (verRev != lastVerRev) {
            lastVerRev = verRev
            val v = json.optJSONObject("ver")
            if (v != null) {
                val logs = ArrayList<String>()
                val arr = v.optJSONArray("changelog")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i, "")
                        if (s.isNotBlank()) logs.add(s)
                    }
                }
                latestVersion = VersionInfo.Info(
                    ok = true,
                    versionCode = v.optInt("versionCode", 0),
                    versionName = v.optString("versionName", ""),
                    downloadUrl = v.optString("downloadUrl", ""),
                    forceUpdate = v.optBoolean("force", false),
                    changelog = logs,
                )
            } else {
                latestVersion = null
            }
        }

        // ---- 积分 / 签到 ----
        val ptsRev = json.optString("pointsRev", "")
        if (ptsRev != lastPointsRev) {
            lastPointsRev = ptsRev
            val s = json.optJSONObject("stat")
            currentStat = if (s == null) null else Stat(
                points = s.optInt("points", 0),
                streak = s.optInt("streak", 0),
                total = s.optInt("total", 0),
                canCheckin = s.optBoolean("canCheckin", false),
                exp = s.optInt("exp", 0),
                level = s.optInt("level", 1),
                expBase = s.optInt("expBase", 0),
                expNext = s.optInt("expNext", 100),
            )
        }

        // ---- 名字样式（独立于称号）----
        val nameRev = json.optString("nameRev", "")
        if (nameRev != lastNameRev) {
            lastNameRev = nameRev
            val n = json.optJSONObject("name")
            if (n == null) {
                currentNameStyle = NameStyle()
            } else {
                val fx = ArrayList<String>()
                val fa = n.optJSONArray("fxColors")
                if (fa != null) {
                    for (i in 0 until fa.length()) {
                        val c = fa.optString(i, "")
                        if (c.isNotBlank()) fx.add(c)
                    }
                }
                currentNameStyle = NameStyle(
                    mode = n.optString("mode", "username"),
                    color = n.optString("color", ""),
                    fxColors = fx,
                )
            }
        }

        // ---- 称号 ----
        val tagRev = json.optString("tagRev", "")
        if (tagRev != lastTagRev) {
            lastTagRev = tagRev
            val t = json.optJSONObject("tag")
            currentTag = if (t == null) null else {
                val list = ArrayList<String>()
                val arr = t.optJSONArray("fxColors")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val c = arr.optString(i, "")
                        if (c.isNotBlank()) list.add(c)
                    }
                }
                Tag(
                    text = t.optString("text", ""),
                    color = t.optString("color", ""),
                    fxColors = list,
                )
            }
        }
    }

    /**
     * 外部直接写入称号状态（如 ProfilePage 从 me() 拉到后同步）。
     * 避免等 sync 轮询到达才有流光。
     */
    fun applyTag(tag: Tag?) {
        currentTag = tag
    }

    /** 用户关掉公告时调，记下来避免同一次运行重复弹 */
    fun dismissAnnouncement() {
        dismissedAnnId = currentAnnouncement?.id ?: ""
        currentAnnouncement = null
    }

    /** 退出登录 / 切账号时清空状态 */
    fun reset() {
        currentAnnouncement = null
        currentTag = null
        latestVersion = null
        lastAnnRev = ""
        lastVerRev = -1
        lastTagRev = ""
        lastPointsRev = ""
        lastNameRev = ""
        currentStat = null
        currentNameStyle = NameStyle()
        dismissedAnnId = ""
    }

    // ---------------- HTTP ----------------

    private fun fetch(ctx: Context, token: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(AuthApi.BASE + "/api.php?action=sync")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 10000
                doInput = true
                setRequestProperty("Accept", "application/json")
                if (token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            if (text.isBlank()) return null
            val r = JSONObject(text)
            if (!r.optBoolean("ok", false)) return null
            r.optJSONObject("data")
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}
