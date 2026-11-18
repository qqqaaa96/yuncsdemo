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
    /**
     * 认证令牌。
     *
     * 空字符串 = 不需要认证（本机回环默认如此）。
     * 非空时三种传法都支持：Bearer / X-Mcp-Token / ?token=。
     */
    private val token: String,
    /** 本机监听端口，可自定义 1024~65535 */
    private val localPort: Int = PORT_LOCAL,
    /** 局域网监听端口；0 或负数 = 不开启 */
    private val lanPort: Int = PORT_LAN,
) {

    companion object {
        /** 默认本机端口（用户指定） */
        const val PORT_LOCAL = 8903
        /** 默认局域网端口 */
        const val PORT_LAN = 8787
        const val PATH = "/mcp"

        /** 端口合法区间 */
        const val PORT_MIN = 1024
        const val PORT_MAX = 65535

        fun isValidPort(p: Int): Boolean = p in PORT_MIN..PORT_MAX

        /**
         * 取本机局域网 IPv4 地址。
         *
         * 放在 companion 里：页面在服务未启动时也能直接
         * McpServer.detectLanAddress() 拿到真实 IP，
         * 不需要先创建实例。
         */
        fun detectLanAddress(): String {
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

    /**
     * MCP 会话 ID。
     *
     * 新版 Streamable HTTP 传输要求：initialize 响应头带 Mcp-Session-Id，
     * 后续请求带上它。不实现的话部分客户端握手完就断。
     *
     * 服务端不校验它（只要自己能稳定给一个就行），
     * 因为单客户端场景没必要多会话。
     */
    private val sessionId: String = java.util.UUID.randomUUID().toString()

    // ---------------- 启停 ----------------

    fun start(): Boolean {
        // 已经在跑：直接返回成功
        if (!stopped.get()) return true

        // 先把旧 socket 彻底关干净，避免紧接 stop→start 时端口还被占着
        closeQuietly(localSocket)
        closeQuietly(lanSocket)
        localSocket = null
        lanSocket = null

        stopped.set(false)

        // 本机端口：bind 失败时重试（旧 socket 释放有延迟）
        val bound = bindWithRetry("127.0.0.1", localPort)
        if (bound == null) {
            stopped.set(true)
            state = state.copy(running = false, lastError = "端口 $localPort 启动失败：被占用或无权限")
            return false
        }
        localSocket = bound
        pool.execute { acceptLoop(bound, "127.0.0.1") }

        // 局域网端口（lanPort <= 0 表示不开启）
        var lanError = ""
        if (lanPort > 0) {
            val lanBound = bindWithRetry("0.0.0.0", lanPort)
            if (lanBound != null) {
                lanSocket = lanBound
                pool.execute { acceptLoop(lanBound, "0.0.0.0") }
            } else {
                // 局域网失败不影响本机可用
                lanError = "局域网端口 $lanPort 启动失败：被占用或无权限"
            }
        }

        state = state.copy(
            running = true,
            localPort = localPort,
            lanPort = if (lanSocket != null) lanPort else 0,
            lanAddress = if (lanSocket != null) localIpv4() else "",
            token = token,
            lastError = lanError,
        )
        return true
    }

    /**
     * 绑定端口，失败时重试几次。
     *
     * 为什么需要重试：stop() 关 socket 后系统不一定立刻释放端口，
     * 紧接着 start() 直接 bind 会拿不到（表现为“端口被占用”）。
     */
    private fun bindWithRetry(host: String, port: Int): ServerSocket? {
        val addr = InetAddress.getByName(host)
        repeat(5) { i ->
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(addr, port))
                }
            } catch (_: Throwable) {
                if (i < 4) Thread.sleep(200)
            }
        }
        return null
    }

    private fun closeQuietly(s: ServerSocket?) {
        try { s?.close() } catch (_: Throwable) {}
    }

    fun stop() {
        if (stopped.getAndSet(true)) return
        closeQuietly(localSocket)
        closeQuietly(lanSocket)
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

            if (request.method != "GET" && request.method != "POST") {
                writeResponse(output, 405, "Method Not Allowed", "")
                return
            }

            // token 认证（为空则跳过）
            //
            // 支持三种传法，因为不同 MCP 客户端的配置能力不一样：
            //   1. Authorization: Bearer <token>   —— 标准做法
            //   2. X-Mcp-Token: <token>            —— 自定义头
            //   3. URL 参数 ?token=<token>          —— 客户端只能填 URL 时用
            //
            // 第 3 种很重要：
            //   io.modelcontextprotocol 的 Kotlin SDK 只能配一个 URL，
            //   它不会自动加 Authorization 头 → 只配 URL 时应该能连上。
            if (token.isNotEmpty()) {
                val auth = request.headers["authorization"] ?: ""
                val bearer = if (auth.startsWith("Bearer ", ignoreCase = true))
                    auth.substring(7).trim() else ""
                val headerToken = request.headers["x-mcp-token"] ?: ""
                val queryToken = request.query["token"] ?: ""

                val ok = bearer == token || headerToken == token || queryToken == token
                if (!ok) {
                    state = state.copy(
                        errors = state.errors + 1,
                        lastError = "认证失败（检查 token 是否一致）"
                    )
                    writeResponse(output, 401, "Unauthorized", """{"error":"invalid token"}""")
                    return
                }
            }

            // GET：返回服务信息（放在 token 校验之后，避免泄露工具数量）
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

            // JSON-RPC
            val body = request.body
            if (body.isBlank()) {
                writeResponse(output, 400, "Bad Request", """{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}""")
                return
            }

            // 通知（无 id）不返回任何响应体。
            //
            // 规范：通知不需要回复；如果回了 {"id":null} 这种 JSON，
            // 客户端 SDK 会当普通响应去解析，然后报
            // "Invalid RequestId type: null"（这是真实踩过的坑）。
            val response = dispatchJsonRpc(body)
            if (response == null) {
                writeResponse(output, 202, "Accepted", "")
            } else {
                val headers = if (isInitialize(body)) mapOf("Mcp-Session-Id" to sessionId) else emptyMap()
                writeResponse(output, 200, "OK", response.toString(), "application/json", headers)
            }
        } catch (e: Throwable) {
            state = state.copy(errors = state.errors + 1, lastError = e.message ?: "?")
        } finally {
            try { client.close() } catch (_: Throwable) {}
        }
    }

    // ---------------- JSON-RPC ----------------

    /** 判断是否为 initialize 请求（需要在响应头里回 Mcp-Session-Id） */
    private fun isInitialize(body: String): Boolean {
        return try {
            JSONObject(body).optString("method", "") == "initialize"
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 处理 JSON-RPC 请求。
     *
     * 返回 null 表示这是通知（没有 id），**不应返回任何响应体**。
     * 规范要求对通知回 202 Accepted + 空 body；
     * 如果回了 id:null 的 JSON，客户端 SDK 会解析失败（已实际踩到）。
     */
    private fun dispatchJsonRpc(body: String): JSONObject? {
        val req = try {
            JSONObject(body)
        } catch (_: Throwable) {
            return error(null, -32700, "Parse error")
        }

        // 批量请求（数组）暂不支持，直接报错
        val method = req.optString("method", "")
        val hasId = req.has("id")
        val id = if (hasId) req.opt("id") else null

        if (req.optString("jsonrpc") != "2.0" || method.isBlank()) {
            return error(id, -32600, "Invalid Request")
        }

        return try {
            val result: Any = when (method) {
                "initialize" -> initializeResult()
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", toolsArray())
                "tools/call" -> callTool(req.optJSONObject("params") ?: JSONObject())
                // 通知类方法与未实现的列出类方法：有 id 才回结果，无 id 直接不回
                "notifications/initialized", "notifications/cancelled" ->
                    return if (hasId) resultResponse(id, JSONObject()) else null
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> return error(id, -32601, "Method not found: $method")
            }
            // 没有 id 的是通知：不回响应
            if (!hasId) null else resultResponse(id, result)
        } catch (e: Throwable) {
            state = state.copy(errors = state.errors + 1, lastError = e.message ?: "?")
            error(id, -32603, "Internal error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun resultResponse(id: Any?, result: Any): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", result)

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
        val startAt = System.currentTimeMillis()

        val tool = tools.firstOrNull { it.name == name }
        if (tool == null) {
            logCall(name, name, args, "未知工具：$name", false, startAt)
            return JSONObject()
                .put("content", JSONArray().put(textContent("未知工具：$name")))
                .put("isError", true)
        }

        state = state.copy(toolCalls = state.toolCalls + 1)

        val ctx = McpToolContext(workDir)
        val out = try {
            tool.run(ctx, args)
        } catch (e: Throwable) {
            state = state.copy(errors = state.errors + 1, lastError = "$name: ${e.message}")
            val msg = "执行失败：${e.message ?: e.javaClass.simpleName}"
            logCall(tool.name, tool.title, args, msg, false, startAt)
            return JSONObject()
                .put("content", JSONArray().put(textContent(msg)))
                .put("isError", true)
        }

        val isErr = out.optBoolean("error", false)
        val resultSummary = if (isErr) {
            out.optString("message", "执行失败")
        } else {
            summarizeResult(out)
        }
        logCall(tool.name, tool.title, args, resultSummary, !isErr, startAt)

        return JSONObject()
            .put("content", JSONArray().put(textContent(out.toString(2))))
            .put("isError", isErr)
    }

    /** 写一条调用日志 */
    private fun logCall(
        tool: String,
        title: String,
        args: JSONObject?,
        result: String,
        success: Boolean,
        startAt: Long,
    ) {
        try {
            McpCallLog.add(
                McpCallLog.Entry(
                    id = McpCallLog.newId(),
                    tool = tool,
                    title = title.ifBlank { tool },
                    summary = McpCallLog.summaryOf(args),
                    args = McpCallLog.argsToList(args),
                    result = result,
                    success = success,
                    startAt = startAt,
                    durationMs = System.currentTimeMillis() - startAt,
                )
            )
        } catch (_: Throwable) {
        }
    }

    /** 把工具返回的 JSON 转成一句人看得懂的摘要 */
    private fun summarizeResult(out: JSONObject): String {
        return try {
            when {
                out.has("files") -> "共 ${out.optInt("count")} 个文件"
                out.has("instructions") -> "反汇编 ${out.optInt("count")} 条指令"
                out.has("symbols") -> "共 ${out.optInt("total")} 个符号，返回 ${out.optInt("shown")}"
                out.has("strings") -> "共 ${out.optInt("total")} 条字符串，返回 ${out.optInt("shown")}"
                out.has("sections") -> "共 ${out.optInt("count")} 个节区"
                out.has("segments") -> "共 ${out.optInt("count")} 个段"
                out.has("blockCount") -> "${out.optInt("blockCount")} 个基本块，${out.optInt("edgeCount")} 条边"
                out.has("bytesWritten") -> "写入 ${out.optInt("bytesWritten")} 字节"
                out.has("hex") -> "读取 ${out.optInt("length")} 字节"
                out.has("sha256") -> "SHA-256 ${out.optString("sha256").take(16)}…"
                out.has("markdown") -> "反编译 ${out.optInt("functionCount")} 个函数"
                out.has("machineName") -> "${out.optString("machineName")}，入口 ${out.optString("entry")}"
                out.has("ok") -> "成功"
                else -> "完成"
            }
        } catch (_: Throwable) {
            "完成"
        }
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
        /** 不含 query 的路径，如 /mcp */
        val path: String,
        /** query 参数，已 URL 解码 */
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun readHttpRequest(input: BufferedInputStream): HttpRequest? {
        // 读请求行
        val line = readLine(input) ?: return null
        val parts = line.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawTarget = parts[1]

        // 拆出 path 与 query
        val qi = rawTarget.indexOf('?')
        val path = if (qi >= 0) rawTarget.substring(0, qi) else rawTarget
        val query = HashMap<String, String>()
        if (qi >= 0) {
            rawTarget.substring(qi + 1).split('&').forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val ei = pair.indexOf('=')
                if (ei > 0) {
                    val k = urlDecode(pair.substring(0, ei))
                    val v = urlDecode(pair.substring(ei + 1))
                    query[k] = v
                } else {
                    query[urlDecode(pair)] = ""
                }
            }
        }

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

        return HttpRequest(method, path, query, headers, body)
    }

    /** URL 解码（%XX 与 +） */
    private fun urlDecode(s: String): String {
        return try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (_: Throwable) {
            s
        }
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
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Expose-Headers: Mcp-Session-Id\r\n")
            extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        try {
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        } catch (_: Throwable) {
        }
    }

    /** 取局域网 IPv4 地址（实例内部用） */
    private fun localIpv4(): String = detectLanAddress()
}
