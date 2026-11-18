package sy.great.toolbox.tools.mcp

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONObject

/**
 * MCP 工具调用日志。
 *
 * 只存内存：进程被杀 / 大退 App 就清空，前台服务断线也不影响（同进程）。
 * 用户要求「只要不清后台就一直在」。
 */
object McpCallLog {

    /** 最多保留多少条（超过丢最旧的） */
    const val MAX = 200

    /** 一条调用记录 */
    data class Entry(
        val id: Long,
        /** 工具名，如 mcp_write_file */
        val tool: String,
        /** 中文显示名，如「写入文件」 */
        val title: String,
        /** 摘要（主要参数，列表里显示的第二行） */
        val summary: String,
        /** 参数列表（展开时显示） */
        val args: List<Pair<String, String>>,
        /** 结果摘要（展开时显示） */
        val result: String,
        /** 是否成功 */
        val success: Boolean,
        /** 开始时间（毫秒） */
        val startAt: Long,
        /** 耗时（毫秒） */
        val durationMs: Long,
    )

    /** 日志列表（Compose 可观察） */
    val entries = mutableStateListOf<Entry>()

    /** 当前错误条数（角标用，Compose 可观察） */
    val errorCount = mutableIntStateOf(0)

    /** 计数器，用于触发重组 */
    val version = mutableIntStateOf(0)

    private var nextId = 1L

    /** 新增一条（最新的插到最前面） */
    fun add(e: Entry) {
        entries.add(0, e)
        while (entries.size > MAX) {
            entries.removeAt(entries.size - 1)
        }
        recountErrors()
        version.intValue++
    }

    /** 重新统计错误条数（推掉旧记录后也要重算） */
    private fun recountErrors() {
        var n = 0
        entries.forEach { if (!it.success) n++ }
        errorCount.intValue = n
    }

    /** 生成新 id */
    fun newId(): Long = nextId++

    /** 清空 */
    fun clear() {
        entries.clear()
        errorCount.intValue = 0
        version.intValue++
    }

    /**
     * 把 JSON 参数转成可读的「键: 值」列表。
     *
     * 值太长时截断，避免把整个文件内容铺到 UI 里。
     */
    fun argsToList(args: JSONObject?): List<Pair<String, String>> {
        if (args == null) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        args.keys().forEach { k ->
            val v = args.opt(k)
            val text = when (v) {
                null, JSONObject.NULL -> "null"
                is String -> if (v.length > 200) v.take(200) + "…" else v
                else -> v.toString()
            }
            out.add(k to text)
        }
        return out
    }

    /** 从参数里取摘要（优先 file / path，其次第一个值） */
    fun summaryOf(args: JSONObject?): String {
        if (args == null) return ""
        val keys = listOf("file", "path", "function", "address")
        for (k in keys) {
            val v = args.optString(k, "")
            if (v.isNotBlank()) return v
        }
        val first = args.keys().asSequence().firstOrNull() ?: return ""
        return args.optString(first, "")
    }
}
