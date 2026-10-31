package sy.great.toolbox.tools

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 网络测速（下载 / 上传 / 延迟）。
 *
 * 为什么必须发真实数据：
 *   带宽是物理容量，不实际拉满流量就量不出来。
 *   本地读接口情况、解析 DNS、发小包都只能测延迟，测不了带宽。
 *
 * 数据源：
 *   走自己的服务器 api.php?action=speedtest&bytes=N。
 *   选自己服务器的原因：
 *     · 不依赖第三方测速站（可能限速 / 返回固定文件被运营商缓存）
 *     · 返回随机字节 + Cache-Control: no-store，每次都是真流量
 *     · 上传同理，服务器丢弃数据不落盘，不占磁盘
 *
 * 如实说明（UI 里也要写）：
 *   · 测出的是「手机 ↔ 自己服务器」的带宽，受服务器上行带宽 / frp 隧道限制。
 *   · 若服务器带宽小于家用宽带，结果会偏小——这是链路瓶颈，不是手机慢。
 */
object SpeedTest {

    // ============ 测速端点 ============
    //
    // 为什么默认不用自己的服务器：
    //   frp 内网穿透是带宽瓶颈。不管手机多快，走隧道只能测出隧道带宽，
    //   测出的数字既不反映手机能力，也不反映宽带能力。
    //
    // 所以改成：公共骨干网源优先，自有服务器兜底。
    //   · Cloudflare 的 __down / __up 是全球 Anycast 测速端点，
    //     单连接就能跑满千兆，是目前最可用的免费源。
    //   · 自有服务器排在后面，公共源不可用时仍然能测（虽然会偏小）。

    // ============ 全部走公网，不经本项目服务器 ============
    //
    // 设计原则：测速流量一律由手机直接打到公共骨干网节点，
    // 不经过 api.php，不用自己的服务器带宽。
    //
    // 国内可达性排序：
    //   阿里云 OSS / 腾讯云 COS 国内节点 > 华为云 > Cloudflare > CacheFly
    //   前者在国内不墙、延迟低；后两者作为海外/兜底补充。

    /** 下载源（按优先级，全部为公共测速端点） */
    private fun downCandidates(bytes: Int): List<String> = listOf(
        // 1. Cloudflare 官方测速端点：返回指定字节数随机数据，no-store 禁缓存
        "https://speed.cloudflare.com/__down?bytes=" + bytes,
        // 2. CacheFly 100MB 固定测试文件（全球 CDN，国内多数地区可达）
        "https://cachefly.cachefly.net/100mb.test",
        // 3. Hetzner 测速文件（欧洲，兼作海外线路参考）
        "https://speed.hetzner.de/100MB.bin",
        // 4. Tele2（瑞典）公共测速文件
        "http://speedtest.tele2.net/100MB.zip",
    )

    /** 上传源（按优先级，全部为公共端点） */
    private fun upCandidates(): List<String> = listOf(
        // Cloudflare 上传端点：直接丢弃 body，不落盘
        "https://speed.cloudflare.com/__up",
        // httpbin 兜底：POST 任意体返回 JSON
        "https://httpbin.org/post",
    )

    /**
     * 延迟探测端点（按优先级，全部为公共端点）。
     *
     * 为什么需要多个：
     *   单一源一旦被墙/超时就整个测速失败。
     *   这里每个样本按顺序试，任一个通就算成功。
     */
    private fun pingCandidates(): List<String> = listOf(
        // Cloudflare Anycast：全球任播，通常是延迟最低的公共节点
        "https://speed.cloudflare.com/__down?bytes=4",
        "https://cachefly.cachefly.net/100mb.test",
        "https://speed.hetzner.de/100MB.bin",
    )

    /** 测速阶段 */
    enum class Phase { IDLE, PING, DOWNLOAD, UPLOAD, DONE }

    /**
     * 实时进度。
     *
     * 所有速度单位都是 Mbps（兆比特每秒），与运营商标称一致。
     * 进度 progress 为 0..1，用于驱驱动表盘。
     */
    data class Tick(
        val phase: Phase = Phase.IDLE,
        val progress: Float = 0f,
        /** 当前瞬时速度 Mbps */
        val currentMbps: Double = 0.0,
        /** 本阶段平均速度 Mbps */
        val avgMbps: Double = 0.0,
        /** 已完成字节 */
        val bytes: Long = 0,
        /** 已用时毫秒 */
        val elapsedMs: Long = 0,
        /** 延迟 ms（0 = 未测出） */
        val latencyMs: Int = 0,
        /** 抖动 ms */
        val jitterMs: Int = 0,
        /** 丢包率 % */
        val lossPct: Double = 0.0,
        /** 错误信息 */
        val error: String = "",
    ) {
        /** 表盘强度：以 200Mbps 为满量程，超出按 1 算 */
        val intensity: Float get() = (currentMbps / 200.0).toFloat().coerceIn(0f, 1f)
    }

    /** 最终结果 */
    data class Result(
        val ok: Boolean,
        val error: String = "",
        val downMbps: Double = 0.0,
        val upMbps: Double = 0.0,
        val latencyMs: Int = 0,
        val jitterMs: Int = 0,
        /** 丢包率 % */
        val lossPct: Double = 0.0,
        /** 本次测速使用的网络类型（Wi-Fi / 移动数据 / 以太网） */
        val netLabel: String = "",
        /** 下载阶段总字节 */
        val downBytes: Long = 0,
        /** 上传阶段总字节 */
        val upBytes: Long = 0,
        /** 下载总耗时毫秒 */
        val downMs: Long = 0,
        val upMs: Long = 0,
    )

    private val cancelled = AtomicBoolean(false)

    /** 取消当前测速 */
    fun cancel() {
        cancelled.set(true)
    }

    // ---------------- 网络类型 ----------------

    /** 当前走的是哪种网络 */
    enum class NetKind(val label: String) {
        WIFI("Wi-Fi"),
        CELLULAR("移动数据"),
        ETHERNET("以太网"),
        VPN("VPN"),
        NONE("无网络"),
        UNKNOWN("未知网络"),
    }

    /**
     * 检测当前网络类型。
     *
     * 为什么不用 NetworkInfo.getType()（旧 API）：
     *   Android 10+ 已废弃，且对 VPN / 以太网判断不准。
     *   NetworkCapabilities 是官方推荐的现代做法。
     *
     * 优先级（重要）：
     *   Wi-Fi > 以太网 > 移动数据。
     *   同时开 Wi-Fi 和移动数据时，Android 默认走 Wi-Fi，
     *   但我们不能只信 activeNetwork 一个返回值——
     *   某些 ROM 在 VPN / 网络切换瞬间会短暂指向移动数据。
     *   所以这里再主动扫一遗全部网络，只要发现已连上的 Wi-Fi 就优先认它。
     *   这样“Wi-Fi + 流量同时开”时不会误报成“移动数据”而弹流量警告。
     */
    fun detectNetKind(ctx: Context): NetKind {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return NetKind.UNKNOWN
            val net = cm.activeNetwork ?: return NetKind.NONE
            val cap = cm.getNetworkCapabilities(net) ?: return NetKind.NONE

            // ---- 1. 当前活跃网络优先判断 ----
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return NetKind.WIFI
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return NetKind.ETHERNET

            // ---- 2. 当前不是 Wi-Fi：再扫全部网络，确认真的没有可用 Wi-Fi ----
            // 只要存在一个已连上、能上网的 Wi-Fi，就按 Wi-Fi 算。
            try {
                for (n in cm.allNetworks) {
                    val c = cm.getNetworkCapabilities(n) ?: continue
                    if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return NetKind.WIFI
                }
            } catch (_: Throwable) {
            }

            // ---- 3. 剩下的按实际类型 ----
            when {
                cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetKind.CELLULAR
                cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetKind.VPN
                else -> NetKind.UNKNOWN
            }
        } catch (_: Throwable) {
            NetKind.UNKNOWN
        }
    }

    /**
     * 是否在消耗移动流量（需要弹提示）。
     *
     * 关键是必须与 detectNetKind 的优先级一致：
     *   只要判定为 Wi-Fi，就一定不是计费网络。
     *   否则会出现“显示 Wi-Fi 却弹流量警告”的矛盾。
     */
    fun isMetered(ctx: Context): Boolean {
        return when (detectNetKind(ctx)) {
            NetKind.WIFI, NetKind.ETHERNET -> false
            NetKind.CELLULAR, NetKind.VPN -> true
            NetKind.NONE, NetKind.UNKNOWN -> false
        }
    }

    /** 打开系统 Wi-Fi 设置页 */
    fun openWifiSettings(ctx: Context) {
        try {
            val i = Intent(Settings.ACTION_WIFI_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Throwable) {
            // 少数 ROM 没有这个页面，退回无线设置总页
            try {
                val i = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------- 延迟 / 抖动 ----------------

    /**
     * 一次延迟测量的完整结果。
     *
     * lossPct：丢包率百分比 0..100（发 10 次失败几次）。
     */
    data class LatencyResult(
        val latencyMs: Int = 0,
        val jitterMs: Int = 0,
        val lossPct: Double = 0.0,
        val sent: Int = 0,
        val received: Int = 0,
    )

    /**
     * 测延迟 / 抖动 / 丢包。
     *
     * 发 10 次小请求（4 字节）：
     *   · 延迟 = 成功样本的平均值
     *   · 抖动 = 相邻两次差的绝对值的平均（网络稳定性）
     *   · 丢包 = 失败次数 / 总次数
     *
     * 为什么发 10 次而不是 5 次：
     *   5 次算不出可信的丢包率（只能得到 0/20/40... 这种粗粒度）。
     *   10 次精度能到 10%，对家用网络诊断够用。
     */
    fun measureLatency(
        onTick: ((Tick) -> Unit)? = null,
    ): LatencyResult {
        val total = 10
        val samples = ArrayList<Int>(total)
        var failed = 0

        // 固定一份候选列表，整个测量过程复用，避免每次重新拼 URL
        val urls = pingCandidates()

        repeat(total) { i ->
            if (cancelled.get()) {
                return LatencyResult(
                    latencyMs = samples.lastOrNull() ?: 0,
                    sent = i,
                    received = samples.size,
                )
            }

            var ms = -1
            // 依次尝试候选源，第一个通的就采纳
            for (u in urls) {
                val t0 = System.nanoTime()
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(u).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        // 延迟探测单次超时不用太长，
                        // 否则一个被墙的源就会拖垮整轮
                        connectTimeout = 3000
                        readTimeout = 3000
                        setRequestProperty("Cache-Control", "no-cache")
                    }
                    conn.inputStream.use { it.readBytes() }
                    val v = ((System.nanoTime() - t0) / 1_000_000L).toInt()
                    if (v in 0..3000) {
                        ms = v
                        break
                    }
                } catch (_: Throwable) {
                    // 这个源不通，换下一个
                } finally {
                    try { conn?.disconnect() } catch (_: Throwable) {}
                }
            }

            if (ms >= 0) samples.add(ms) else failed++
            onTick?.invoke(
                Tick(
                    phase = Phase.PING,
                    progress = (i + 1) / total.toFloat() * 0.15f,
                    latencyMs = samples.lastOrNull() ?: 0,
                    lossPct = failed * 100.0 / (i + 1),
                )
            )
        }

        if (samples.isEmpty()) {
            return LatencyResult(
                latencyMs = 0,
                jitterMs = 0,
                lossPct = 100.0,
                sent = total,
                received = 0,
            )
        }

        val avg = samples.sum() / samples.size
        var jitter = 0
        if (samples.size > 1) {
            var sum = 0
            for (i in 1 until samples.size) sum += abs(samples[i] - samples[i - 1])
            jitter = sum / (samples.size - 1)
        }

        return LatencyResult(
            latencyMs = avg,
            jitterMs = jitter,
            lossPct = failed * 100.0 / total,
            sent = total,
            received = samples.size,
        )
    }

    // ---------------- 下载 ----------------

    /**
     * 下载测速。
     *
     * 策略：【不设字节上限】，只看时间窗。
     *   向服务端请求一个足够大的量（服务端上限 50MB/次），
     *   跑满 maxDurationMs 就主动断开。
     *   链路快就在时限内多拉几轮，总量不封顶；
     *   慢就自然少拉。这样得到的是“真实可持续速度”，
     *   而不会因为拉完了固定量而提前结束、算出偏低的值。
     *
     * 为什么不用多连接并发：
     *   frp 隧道单连接就能跑满，多连接反而容易触发服务端限流。
     *
     * 瞬时速度按 200ms 窗口统计，避免单次抖动让表盘乱跳。
     */
    private fun download(
        maxDurationMs: Long,
        onTick: (Tick) -> Unit,
        baseProgress: Float,
        spanProgress: Float,
    ): Triple<Double, Long, Long> {
        // 单次请求量。公共源和自有服务器都吃 100MB：
        // Cloudflare __down 不限，自有服务器上限也是 100MB。
        val perRequest = 100 * 1024 * 1024

        // 候选源：按顺序尝试，哪个能跑就用哪个。
        // 一旦选定就固定下来，不每轮重选（否则轮次间速度不可比）。
        val urlList = downCandidates(perRequest)
        var urlIdx = 0
        var total = 0L
        val t0 = System.nanoTime()
        var winStart = t0
        var winBytes = 0L
        var lastReported = 0.0

        // 多轮拉取，直到时限到或用户取消。
        // rounds 不设上限：50MB × N 轮，链路快就自然多拉。
        var round = 0
        while (true) {
            if (cancelled.get()) break
            val elapsedBefore = (System.nanoTime() - t0) / 1_000_000L
            if (elapsedBefore >= maxDurationMs) break

            var conn: HttpURLConnection? = null
            var gotThisRound = 0L
            try {
                val target = urlList.getOrNull(urlIdx)
                    ?: break
                conn = (URL(target).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    // 读超时要大于单轮预期时长，否则会被误判为断网
                    readTimeout = 15000
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) SpeedTest")
                }
                if (conn.responseCode !in 200..299) {
                    // 这个源不行，换下一个重试（不直接判失败）
                    urlIdx++
                    if (urlIdx >= urlList.size) {
                        val e = "HTTP " + conn.responseCode
                        onTick(Tick(phase = Phase.DOWNLOAD, error = e))
                        return Triple(0.0, 0L, 0L)
                    }
                    continue
                }

                val buf = ByteArray(64 * 1024)
                conn.inputStream.use { input ->
                    while (true) {
                        if (cancelled.get()) break
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        gotThisRound += n
                        winBytes += n

                        val now = System.nanoTime()
                        val totalMs = (now - t0) / 1_000_000L

                        // 每 200ms 报一次瞬时速度
                        if ((now - winStart) / 1_000_000L >= 200L) {
                            val winMs = (now - winStart) / 1_000_000L
                            lastReported = winBytes * 8.0 / winMs / 1000.0
                            winBytes = 0
                            winStart = now

                            val avgMbps = if (totalMs > 0) total * 8.0 / totalMs / 1000.0 else 0.0
                            // 总量不封顶，进度环改用“已用时间 / 总时限”表示
                            val timeFrac = (totalMs.toFloat() / maxDurationMs).coerceIn(0f, 1f)
                            onTick(
                                Tick(
                                    phase = Phase.DOWNLOAD,
                                    progress = baseProgress + spanProgress * timeFrac,
                                    currentMbps = lastReported,
                                    avgMbps = avgMbps,
                                    bytes = total,
                                    elapsedMs = totalMs,
                                )
                            )
                        }

                        if (totalMs >= maxDurationMs) break
                    }
                }
            } catch (t: Throwable) {
                // 单轮异常不直接判失败：已读到的字节仍然有效，
                // 但若从未读到任何数据才报错。
                if (total == 0L) {
                    onTick(
                        Tick(
                            phase = Phase.DOWNLOAD,
                            error = "下载失败：" + (t.message ?: t.javaClass.simpleName)
                        )
                    )
                    return Triple(0.0, 0L, 0L)
                }
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }

            round++
            // 这一轮一个字节都没拿到 → 服务端不行了，别死循环
            if (gotThisRound == 0L) break
        }

        val totalMs = (System.nanoTime() - t0) / 1_000_000L
        val mbps = if (totalMs > 0) total * 8.0 / totalMs / 1000.0 else 0.0
        return Triple(mbps, total, totalMs)
    }

    // ---------------- 上传 ----------------

    /**
     * 上传测速。
     *
     * 【不设字节上限】：在 maxDurationMs 内不停发送，跑满就断。
     * 服务端直接丢弃不落盘，不占磁盘。
     * 用 chunked（不设 Content-Length）避免 HttpURLConnection 预缓存整个请求体。
     */
    private fun upload(
        maxDurationMs: Long,
        onTick: (Tick) -> Unit,
        baseProgress: Float,
        spanProgress: Float,
    ): Triple<Double, Long, Long> {
        val chunk = ByteArray(1024 * 1024) { (it * 31 and 0xFF).toByte() }
        var total = 0L
        val t0 = System.nanoTime()

        // 候选源：公共源优先，自有服务器兜底。
        // 一旦选定就固定下来，不每轮重选（否则轮次间速度不可比）。
        val urlList = upCandidates()
        var urlIdx = 0

        // 同样多轮发送，直到时限到或取消。
        var round = 0
        while (true) {
            if (cancelled.get()) break
            val elapsedBefore = (System.nanoTime() - t0) / 1_000_000L
            if (elapsedBefore >= maxDurationMs) break

            var conn: HttpURLConnection? = null
            var sentThisRound = 0L
            try {
                val target = urlList.getOrNull(urlIdx)
                    ?: break
                conn = (URL(target).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 15000
                    doOutput = true
                    // 关键：chunked 上传，不预先缓冲，否则测不到真实上行
                    setChunkedStreamingMode(64 * 1024)
                    setRequestProperty("Content-Type", "application/octet-stream")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) SpeedTest")
                }
                conn.outputStream.use { out ->
                    var off = 0
                    while (off < chunk.size) {
                        if (cancelled.get()) break
                        val totalMsNow = (System.nanoTime() - t0) / 1_000_000L
                        if (totalMsNow >= maxDurationMs) break

                        val n = minOf(32 * 1024, chunk.size - off)
                        out.write(chunk, off, n)
                        off += n
                        total += n
                        sentThisRound += n

                        val totalMs = (System.nanoTime() - t0) / 1_000_000L
                        val avgMbps = if (totalMs > 0) total * 8.0 / totalMs / 1000.0 else 0.0
                        // 总量不封顶，进度环用“已用时间 / 总时限”
                        val timeFrac = (totalMs.toFloat() / maxDurationMs).coerceIn(0f, 1f)
                        onTick(
                            Tick(
                                phase = Phase.UPLOAD,
                                progress = baseProgress + spanProgress * timeFrac,
                                currentMbps = avgMbps,
                                avgMbps = avgMbps,
                                bytes = total,
                                elapsedMs = totalMs,
                            )
                        )
                    }
                    out.flush()
                }
                conn.inputStream?.use { it.readBytes() }
            } catch (_: Throwable) {
                // 上传失败保留已发字节；服务器不可用时不造假数字
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }

            round++
            // 这一轮一个字节都没发出去 → 换下一个源；都试完就退出
            if (sentThisRound == 0L) {
                urlIdx++
                if (urlIdx >= urlList.size) break
            }
        }

        val totalMs = (System.nanoTime() - t0) / 1_000_000L
        val mbps = if (totalMs > 0) total * 8.0 / totalMs / 1000.0 else 0.0
        return Triple(mbps, total, totalMs)
    }

    // ---------------- 完整流程 ----------------

    /**
     * 完整测速：延迟 → 下载 → 上传。阻塞调用，必须在 IO 线程跑。
     */
    fun run(
        ctx: Context,
        onTick: (Tick) -> Unit,
        downDurationMs: Long = 10_000L,
        upDurationMs: Long = 6_000L,
    ): Result {
        cancelled.set(false)

        // 网络类型在任何字节传输之前就固定下来，
        // 用于排行榜标注“用户用 Wi-Fi 还是流量测的”。
        val netLabel = detectNetKind(ctx).label

        // ---- 1. 延迟 / 抖动 / 丢包 ----
        onTick(Tick(phase = Phase.PING, progress = 0f))
        val latRes = measureLatency(onTick)
        val lat = latRes.latencyMs
        val jit = latRes.jitterMs
        val loss = latRes.lossPct

        if (cancelled.get()) return Result(false, "已取消")
        if (lat == 0) {
            return Result(
                false,
                "无法连接测速服务器",
                lossPct = loss,
                netLabel = netLabel,
            )
        }

        onTick(
            Tick(
                phase = Phase.DOWNLOAD,
                progress = 0.15f,
                latencyMs = lat,
                jitterMs = jit,
                lossPct = loss,
            )
        )

        // ---- 2. 下载 ----
        var lastDown = 0.0
        var downBytes = 0L
        var downMs = 0L
        val d = download(
            maxDurationMs = downDurationMs,
            onTick = { p ->
                lastDown = if (p.currentMbps > 0) p.currentMbps else lastDown
                downBytes = p.bytes
                downMs = p.elapsedMs
                onTick(p.copy(latencyMs = lat, jitterMs = jit, lossPct = loss))
            },
            baseProgress = 0.15f,
            spanProgress = 0.55f,
        )
        if (cancelled.get()) return Result(false, "已取消")
        if (d.second == 0L) {
            return Result(
                false,
                "下载测速失败（服务器未返回数据）",
                latencyMs = lat,
                jitterMs = jit,
                lossPct = loss,
            )
        }

        // ---- 3. 上传 ----
        onTick(
            Tick(
                phase = Phase.UPLOAD,
                progress = 0.7f,
                latencyMs = lat,
                jitterMs = jit,
                lossPct = loss,
            )
        )
        val u = upload(
            maxDurationMs = upDurationMs,
            onTick = { p ->
                onTick(p.copy(latencyMs = lat, jitterMs = jit, lossPct = loss))
            },
            baseProgress = 0.7f,
            spanProgress = 0.3f,
        )

        return Result(
            ok = true,
            downMbps = d.first,
            upMbps = u.first,
            latencyMs = lat,
            jitterMs = jit,
            lossPct = loss,
            netLabel = netLabel,
            downBytes = d.second,
            upBytes = u.second,
            downMs = d.third,
            upMs = u.third,
        )
    }

    // ---------------- 历史记录 ----------------

    private const val PREF = "csdemo_speedtest"
    private const val K_LAST = "last_json"

    /** 保存一次成功结果，供下次打开时展示 */
    fun saveLast(ctx: Context, r: Result) {
        try {
            val j = org.json.JSONObject()
                .put("down", r.downMbps)
                .put("up", r.upMbps)
                .put("lat", r.latencyMs)
                .put("jit", r.jitterMs)
                .put("loss", r.lossPct)
                .put("net", r.netLabel)
                .put("at", System.currentTimeMillis())
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(K_LAST, j.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    /** 读上一次结果；没有返回 null */
    fun loadLast(ctx: Context): org.json.JSONObject? {
        return try {
            val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(K_LAST, "") ?: ""
            if (s.isBlank()) null else org.json.JSONObject(s)
        } catch (_: Throwable) {
            null
        }
    }
}
