package sy.great.toolbox.tools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.HttpURLConnection
import java.net.URL

/**
 * 服务器连接状态。
 *
 * 每 300ms 探测一次 `api.php?action=ping`，记录往返延迟。
 * 供主页「与服务器建立连接」那一行展示。
 *
 * 为什么 300ms：
 *   再快就没意义（TCP 往返本身就要几十 ms），
 *   再慢界面看着像卡住。300ms 在“实时”和“不浪费流量”之间比较平衡。
 */
object PingMonitor {

    /** 探测间隔（毫秒） */
    private const val INTERVAL_MS = 300L

    /** 单次请求超时 */
    private const val TIMEOUT_MS = 5000

    /** 连续失败多少次才判定为“建连失败”（避免网络抖动就报错） */
    private const val FAIL_THRESHOLD = 2

    sealed interface State {
        /** 正在连接（首次或重连中） */
        data object Connecting : State

        /** 连接正常 */
        data class Online(val latencyMs: Long, val region: String) : State

        /** 连接失败 */
        data class Failed(val detail: String) : State
    }

    /** 当前状态，UI 直接读这个 */
    var state by mutableStateOf<State>(State.Connecting)
        private set

    /** 归属地（只查一次，结果缓存） */
    private var region: String = ""

    private var started = false
    private var consecutiveFail = 0

    /** 启动轮询。重复调用无副作用。 */
    fun start() {
        if (started) return
        started = true

        Thread {
            while (true) {
                try {
                    val r = probe()
                    if (r != null) {
                        consecutiveFail = 0
                        state = State.Online(r.first, region)
                    } else {
                        consecutiveFail++
                        if (consecutiveFail >= FAIL_THRESHOLD) {
                            state = State.Failed("无法连接到服务器")
                        }
                    }
                } catch (e: Throwable) {
                    consecutiveFail++
                    if (consecutiveFail >= FAIL_THRESHOLD) {
                        state = State.Failed(e.message ?: e.javaClass.simpleName)
                    }
                }
                Thread.sleep(INTERVAL_MS)
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 探测一次。
     *
     * @return Pair(延迟毫秒, 归属地)；失败返回 null
     */
    private fun probe(): Pair<Long, String>? {
        var conn: HttpURLConnection? = null
        try {
            val startedAt = System.currentTimeMillis()
            val url = URL("${AuthApi.BASE}/api.php?action=ping")

            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "close")
            }

            val code = conn.responseCode
            if (code !in 200..299) return null

            // 读完响应，否则连接不会真正释放
            val body = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Throwable) {
                ""
            }

            val elapsed = System.currentTimeMillis() - startedAt

            // 首次拿到响应后，顺便取归属地（只做一次，后续复用缓存）
            if (region.isEmpty()) {
                region = guessRegion(body)
            }

            return Pair(elapsed, region)
        } catch (e: Throwable) {
            return null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
     * 从 ping 响应里取归属地。
     *
     * 服务端目前只回 ok，没有地理位置。
     * 这里不发起额外请求（避免每次多一次网络），
     * 只解析将来可能下发的 country 字段；没有就显示「未知」。
     */
    private fun guessRegion(body: String): String {
        return try {
            val j = org.json.JSONObject(body)
            val c = j.optString("country", "")
            if (c.isNotBlank()) c else "未知"
        } catch (_: Throwable) {
            "未知"
        }
    }

    /** 重置（切换服务器等场景用） */
    fun reset() {
        state = State.Connecting
        consecutiveFail = 0
        region = ""
    }
}
