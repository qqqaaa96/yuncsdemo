package sy.great.toolbox.tools.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 工具定义。
 *
 * 每个工具包含：
 *   name        —— 工具名（唯一）
 *   description —— 给 AI 看的说明（写清楚什么时候用、返回什么）
 *   inputSchema —— JSON Schema，AI 据此构造参数
 *   run         —— 实际执行逻辑
 */
class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val run: (McpToolContext, JSONObject) -> JSONObject,
)

/**
 * 工具执行上下文。
 *
 * workDir 是工作目录（/storage/emulated/0/ELF分析处/mcp/），
 * 所有文件操作都限制在这个目录内，防止 AI 越权读写。
 */
class McpToolContext(
    val workDir: java.io.File,
) {
    /**
     * 把用户传入的路径解析成工作目录内的绝对路径。
     *
     * 安全：拒绝 .. 逃逸和绝对路径外部。
     */
    fun resolve(path: String): java.io.File {
        val f = java.io.File(path)
        val target = if (f.isAbsolute) f else java.io.File(workDir, path)
        val canonical = target.canonicalFile
        val base = workDir.canonicalFile
        if (!canonical.path.startsWith(base.path)) {
            throw IllegalArgumentException("路径超出工作目录：$path")
        }
        return canonical
    }

    /** 列出工作目录里的 ELF 文件 */
    fun listElfFiles(): List<java.io.File> {
        val out = ArrayList<java.io.File>()
        workDir.listFiles()?.forEach { f ->
            if (f.isFile && looksLikeElf(f)) out.add(f)
        }
        return out.sortedBy { it.name }
    }

    private fun looksLikeElf(f: java.io.File): Boolean {
        return try {
            f.inputStream().use { input ->
                val b = ByteArray(4)
                if (input.read(b) != 4) return false
                b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte() &&
                    b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()
            }
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * 参数 schema 构造辅助。
 *
 * 手写 JSONObject 太啰嗦且容易写错，这里做几个 DSL 函数。
 */
object Schema {

    /** 构造 object schema */
    fun obj(vararg props: Pair<String, JSONObject>): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        props.forEach { (name, schema) ->
            properties.put(name, schema)
        }
        // 所有声明的字段都当作必填（可选字段在描述里写清）
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", required)
            .put("additionalProperties", false)
    }

    /** 字符串参数 */
    fun str(desc: String, default: String? = null): JSONObject {
        val o = JSONObject().put("type", "string").put("description", desc)
        if (default != null) o.put("default", default)
        return o
    }

    /** 整数参数 */
    fun int(desc: String, default: Int? = null): JSONObject {
        val o = JSONObject().put("type", "integer").put("description", desc)
        if (default != null) o.put("default", default)
        return o
    }

    /** 布尔参数 */
    fun bool(desc: String, default: Boolean? = null): JSONObject {
        val o = JSONObject().put("type", "boolean").put("description", desc)
        if (default != null) o.put("default", default)
        return o
    }

    /** 枚举参数 */
    fun oneOf(desc: String, vararg values: String): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("description", desc)
            .put("enum", JSONArray(values.toList()))
}
