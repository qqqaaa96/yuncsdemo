package sy.great.toolbox.tools.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MCP HTTP 服务器（纯 Kotlin，无第三方库）。
 *
 * 协议：
 *   POST /mcp  —— JSON-RPC 2.0
 *   GET  /mcp  —— 返回服务信息（便于浏览器/探测）
 *
 * 端口：
 *   127.0.0.1:8989  —— 本机（adb forward 或同机 AI 客户端）
 *   0.0.0.0:8787    —— 局域网（同一 WiFi 的电脑可连）
 *
 * 支持的 JSON-RPC 方法：
 *   initialize           握手
 *   tools/list           返回工具清单
 *   tools/call           执行工具
 *   ping                 健康检查
 *
 * 为什么不用 NanoHTTPD / Ktor：
 *   项目没有任何 HTTP 服务器依赖，为了一个功能引一整套框架不划算；
 *   而且 MCP 只用 POST + 固定路径，手写 Socket 完全够用。
 */
class McpServer(
    private val workDir: File,
    private val token: String,
) {

    companion object {
        const val PORT_LOCAL = 8989
        const val PORT_LAN = 8787
        const val PATH = "/mcp"
    }

    /** 运行状态（页面上展示） */
    data class State(
        val running: Boolean = false,
        val localPort: Int = PORT_LOCAL,
        val lanPort: Int = PORT_LAN,
        val lanAddress: String = "",
        val token: String = "",
        val requests: Long = 0,
        val toolCalls: Long = 0,
        val errors: Long = 0,
        val lastError: String = "",
        val lastRequestAt: Long = 0,
        val clients: List<String> = emptyList(),
    )

    @Volatile
    var state = State(token = token)
        private set

    private val stopped = AtomicBoolean(true)
    private var localSocket: ServerSocket? = null
    private var lanSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(8)

    /** 工具清单（由 McpTools 注入） */
    var tools: List<McpTool> = emptyList()

    // ---------------- 启停 ----------------

    fun start(): Boolean {
        if (!stopped.compareAndSet(true, false)) return true

        val ctx = McpToolContext(workDir)

        // 本机端口
        try {
            localSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT_LOCAL))
            }
            pool.execute { acceptLoop(localSocket!!, "127.0.0.1") }
        } catch (e: Throwable) {
            stopped.set(true)
            state = state.copy(running = false, lastError = "本机端口 $PORT_LOCAL 启动失败：${e.message}")
            return false
        }

        // 局域网端口
        try {
            lanSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT_LAN))
            }
            pool.execute { acceptLoop(lanSocket!!, "0.0.0.0") }
        } catch (e: Throwable) {
            // 局域网失败不影响本机可用
            state = state.copy(lastError = "局域网端口 $PORT_LAN 启动失败：${e.message}")
        }

        state = state.copy(
            running = true,
            localPort = PORT_LOCAL,
            lanPort = PORT_LAN,
            lanAddress = localIpv4(),
            token = token,
        )
        return true
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        try { localSocket?.close() } catch (_: Throwable) {}
        try { lanSocket?.close() } catch (_: Throwable) {}
        localSocket = null
        lanSocket = null
        state = state.copy(running = false)
    }

    fun isRunning(): Boolean = !stopped.get()

    // ---------------- 接收 ----------------

    private fun acceptLoop(server: ServerSocket, tag: String) {
        while (!stopped.get()) {
            val client = try {
                server.accept()
            } catch (_: Throwable) {
                break
            }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()

            val request = readHttpRequest(input) ?: run {
                writeResponse(output, 400, "Bad Request", "")
                return
            }

            val remote = client.inetAddress?.hostAddress ?: "?"
            state = state.copy(
                requests = state.requests + 1,
                lastRequestAt = System.currentTimeMillis(),
                clients = (state.clients + remote).distinct().takeLast(8),
            )

            // 路径检查
            if (!request.path.startsWith(PATH)) {
                writeResponse(output, 404, "Not Found", """{"error":"use $PATH"}""")
                return
            }

            // GET：返回服务信息
            if (request.method == "GET") {
                val info = JSONObject()
                    .put("name", "csdemo-elf-mcp")
                    .put("version", "1.0")
                    .put("path", PATH)
                    .put("tools", tools.size)
                    .put("running", true)
                writeResponse(output, 200, "OK", info.toString(), "application/json")
                return
            }

            if (request.method != "POST") {
                writeResponse(output, 405, "Method Not Allowed", "")
                return
            }

            // token 认证（可选：token 为空则跳过）
            if (token.isNotEmpty()) {
                val auth = request.headers["authorization"] ?: ""
                val bearer = if (auth.startsWith("Bearer ", ignoreCase = true)) auth.substring(7).trim() else ""
                val headerToken = request.headers["x-mcp-token"] ?: ""
                if (bearer != token && headerToken != token) {
                    state = state.copy(errors = state.errors + 1, lastError = "认证失败")
                    writeResponse(output, 401, "Unauthorized", """{"error":"invalid token"}""")
                    return
                }
            }

            // JSON-RPC
            val body = request.body
            if (body.isBlank()) {
                writeResponse(output, 400, "Bad Request", """{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}""")
                return
            }

            val response = dispatchJsonRpc(body)
            writeResponse(output, 200, "OK", response.toString(), "application/json")
        } catch (e: Throwable) {
            state = state.copy(errors = state.errors + 1, lastError = e.message ?: "?")
        } finally {
            try { client.close() } catch (_: Throwable) {}
        }
    }

    // ---------------- JSON-RPC ----------------

    private fun dispatchJsonRpc(body: String): JSONObject {
        val req = try {
            JSONObject(body)
        } catch (_: Throwable) {
            return error(null, -32700, "Parse error")
        }

        val id = if (req.has("id")) req.opt("id") else null
        val method = req.optString("method", "")

        if (req.optString("jsonrpc") != "2.0" || method.isBlank()) {
            return error(id, -32600, "Invalid Request")
        }

        return try {
            val result: Any = when (method) {
                "initialize" -> initializeResult()
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", toolsArray())
                "tools/call" -> callTool(req.optJSONObject("params") ?: JSONObject())
                // 通知类方法与未实现的列出类方法，返回空结果
                "notifications/initialized", "notifications/cancelled" -> JSONObject()
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> return error(id, -32601, "Method not found: $method")
            }
            JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result)
        } catch (e: Throwable) {
            state = state.copy(errors = state.errors + 1, lastError = e.message ?: "?")
            error(id, -32603, "Internal error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", "2024-11-05")
        .put(
            "capabilities",
            JSONObject()
                .put("tools", JSONObject())
        )
        .put(
            "serverInfo",
            JSONObject()
                .put("name", "csdemo-elf-mcp")
                .put("version", "1.0")
        )

    private fun toolsArray(): JSONArray {
        val arr = JSONArray()
        tools.forEach { t ->
            arr.put(
                JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("inputSchema", t.inputSchema)
            )
        }
        return arr
    }

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name", "")
        val args = params.optJSONObject("arguments") ?: JSONObject()

        val tool = tools.firstOrNull { it.name == name }
            ?: return JSONObject()
                .put("content", JSONArray().put(textContent("未知工具：$name")))
                .put("isError", true)

        state = state.copy(toolCalls = state.toolCalls + 1)

        val ctx = McpToolContext(workDir)
        val out = try {
            tool.run(ctx, args)
        } catch (e: Throwable) {
            state = state.copy(errors = state.errors + 1, lastError = "$name: ${e.message}")
            return JSONObject()
                .put("content", JSONArray().put(textContent("执行失败：${e.message ?: e.javaClass.simpleName}")))
                .put("isError", true)
        }

        return JSONObject()
            .put("content", JSONArray().put(textContent(out.toString(2))))
            .put("isError", out.optBoolean("error", false))
    }

    private fun textContent(text: String): JSONObject = JSONObject()
        .put("type", "text")
        .put("text", text)

    private fun error(id: Any?, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    // ---------------- HTTP ----------------

    private class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun readHttpRequest(input: BufferedInputStream): HttpRequest? {
        // 读请求行
        val line = readLine(input) ?: return null
        val parts = line.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val path = parts[1]

        // 读 headers
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) {
                headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
        }

        // 读 body
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0) {
            val buf = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = input.read(buf, read, len - read)
                if (n <= 0) break
                read += n
            }
            String(buf, 0, read, Charsets.UTF_8)
        } else {
            // chunked 编码（部分客户端会用），简单支持
            if (headers["transfer-encoding"]?.contains("chunked") == true) {
                readChunked(input)
            } else ""
        }

        return HttpRequest(method, path, headers, body)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readChunked(input: BufferedInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.trim().toIntOrNull(16) ?: break
            if (size == 0) {
                readLine(input) // 末尾空行
                break
            }
            val buf = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(buf, read, size - read)
                if (n <= 0) break
                read += n
            }
            sb.append(String(buf, 0, read, Charsets.UTF_8))
            readLine(input) // 块后 CRLF
        }
        return sb.toString()
    }

    private fun writeResponse(
        output: OutputStream,
        code: Int,
        reason: String,
        body: String,
        contentType: String = "application/json; charset=utf-8",
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("\r\n")
        }
        try {
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        } catch (_: Throwable) {
        }
    }

    /** 取局域网 IPv4 地址 */
    private fun localIpv4(): String {
        return try {
            val nifs = java.net.NetworkInterface.getNetworkInterfaces() ?: return ""
            for (nif in java.util.Collections.list(nifs)) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val a = ia.address
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress) {
                        return a.hostAddress ?: ""
                    }
                }
            }
            ""
        } catch (_: Throwable) {
            ""
        }
    }
}
