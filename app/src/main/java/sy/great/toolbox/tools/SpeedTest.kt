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
 * 数据源：全部为公共端点，手机直连，不经过本项目服务器。
 *
 * 源的选择策略（踩过两次坑，重要）：
 *   1. Cloudflare ?bytes=N 放在第一位
 *      —— 参数化端点，不依赖具体文件存在，永不会 404。
 *   2. 国内镜像站排后面
 *      —— 延迟更低、结果更准确，但路径可能变（404 会自动跳过）。
 *   3. 不用带版本号的 ISO 路径
 *      —— 发新版旧文件下架，会导致“所有源无响应”。
 *   4. 不用小文件（如 ls-lR.gz）
 *      —— 几 MB 跑不满时间窗，算出的速度无意义。
 *
 * 失败时会返回逐源诊断（源1:超时，源2:HTTP 404 …），
 * 便于定位到底是哪个源、挂在哪一步。
 */
object SpeedTest {

    // ============ 测速端点 ============
    //
    // 设计原则：测速流量一律由手机直接打到公共节点，
    // 不经过 api.php，不用自己的服务器带宽。

    /**
     * 下载源（按优先级）。
     *
     * 排序：Cloudflare 打头保证可用，国内镜像站随后争取准确度。
     */
    private fun downCandidates(bytes: Int): List<String> = listOf(
        // 选源原则（很关键，这是前几轮踩的坑）：
        //
        //   1. 【不用带版本号的 ISO 路径】
        //      ubuntu-releases/24.04/ubuntu-24.04.3-desktop.iso 这种，
        //      发新版旧文件下架 → 所有镜像同时 404 → “所有源无响应”。
        //
        //   2. 【不用小文件】
        //      ls-lR.gz 只有几 MB，千兆网 0.1 秒就跑完，
        //      跑不满时间窗，算出来的速度毫无意义。
        //
        //   3. 【优先用纯 CDN 静态资源】
        //      镜像站的 ISO 目录结构经常变，而 CDN 上的固定资源不会。

        // 1. Cloudflare（必须放第一位）
        //
        // 为什么让它打头而不是当兜底：
        //   它是【参数化端点】—— ?bytes=N 要多少给多少，
        //   不依赖任何具体文件存在，永远不会 404。
        //   国内镜像站的 ISO 路径随时可能变（已踩两次坑），
        //   但 Cloudflare 这个端点多年未变。
        //
        // 代价：延迟高（600ms+），测出的数字会偏保守。
        //   但“能测出数”比“测不准”重要 —— 先保证可用。
        "https://speed.cloudflare.com/__down?bytes=" + bytes,

        // ---- 以下为国内镜像站，若能命中则结果更准（延迟低）----

        // 2. 清华 TUNA：CentOS Vault 历史归档（永久保留，不删档）
        "https://mirrors.tuna.tsinghua.edu.cn/centos-vault/7.9.2009/isos/x86_64/CentOS-7-x86_64-DVD-2009.iso",
        // 3. 阿里云：Deepin 完整镜像
        "https://mirrors.aliyun.com/deepin-cd/23/deepin-desktop-community-23-amd64.iso",
        // 4. 华为云：Deepin 完整镜像
        "https://mirrors.huaweicloud.com/deepin-cd/23/deepin-desktop-community-23-amd64.iso",
        // 5. 中科大：CentOS Vault 同路径
        "https://mirrors.ustc.edu.cn/centos-vault/7.9.2009/isos/x86_64/CentOS-7-x86_64-DVD-2009.iso",
    )

    /**
     * 上传源（按优先级，全部为公共端点）。
     *
     * 优选标准：服务端【丢弃】请求体，不回显。
     * 不能用 httpbin.org/post —— 它会把整个 body 回显回来，
     * 传几 MB 就会出现“上传完了在等下传回显”的假死，
     * 测出的上行严重偏低甚至超时。
     */
    private fun upCandidates(): List<String> = listOf(
        // Cloudflare 上传端点：直接丢弃 body，不落盘
        "https://speed.cloudflare.com/__up",
        // LibreSpeed 公共节点（赞助商托管，直接读取并丢弃）
        "https://librespeed.org/backend/empty.php",
        // CacheFly 上传入口（部分节点支持 POST，失败会自动跳过）
        "https://cachefly.cachefly.net/100mb.test",
    )

    /**
     * 延迟探测端点（按优先级，全部为公共端点）。
     *
     * 为什么需要多个：
     *   单一源一旦被墙/超时就整个测速失败。
     *   这里每个样本按顺序试，任一个通就算成功。
     */
    private fun pingCandidates(): List<String> = listOf(
        // Cloudflare 放第一位：它带 ?bytes=4 参数，
        // 不依赖任何具体文件是否存在，返回 4 字节，永远不会 404。
        // 延迟测量对“国家”不敏感（只测 RTT），境外节点也适用。
        "https://speed.cloudflare.com/__down?bytes=4",
        // 国内镜像站作为备选（延迟更低，但依赖文件存在）
        "https://mirrors.aliyun.com/ubuntu/ls-lR.gz",
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ls-lR.gz",
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
     * 不再统计丢包率：
     *   HTTP 层的“失败次数”并不等于网络丢包（可能是超时、限流 429、
     *   或源不可达），当成丢包率会误导用户。
     *   真丢包需要 UDP 探测（iPerf3 / TURN），不在本 App 范围内。
     */
    data class LatencyResult(
        val latencyMs: Int = 0,
        val jitterMs: Int = 0,
        val sent: Int = 0,
        val received: Int = 0,
    )

    /**
     * 测延迟【只发 1 次】。
     *
     * 为什么只发 1 次：
     *   用户要的是“尽快开始测网速”。发 10 次在差网下可能要 30 秒，
     *   而延迟对测速结果没有任何影响（只用于展示）。
     *   1 次拿到 RTT 就够，把时间全留给下载 / 上传。
     *
     * 抖动不再从这里算（单次样本无法算抖动）。
     * 抖动改在 download() 阶段用收包间隔反向估算，见那个函数里的说明。
     */
    fun measureLatency(
        onTick: ((Tick) -> Unit)? = null,
    ): LatencyResult {
        val total = 1

        // 固定一份候选列表，避免每次重新拼 URL
        val urls = pingCandidates()

        repeat(total) { i ->
            if (cancelled.get()) {
                return LatencyResult(sent = i, received = 0)
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
                        // 关键：只要 1 字节。
                        // 不加 Range 的话，ls-lR.gz 有几 MB，
                        // 测出来的是「下载完整个文件」的耗时，不是 RTT。
                        setRequestProperty("Range", "bytes=0-0")
                    }
                    // 只读 1 字节就断开，不等文件传完
                    conn.inputStream.use { input ->
                        val one = ByteArray(1)
                        input.read(one)
                    }
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

            onTick?.invoke(
                Tick(
                    phase = Phase.PING,
                    progress = 0.15f,
                    latencyMs = if (ms >= 0) ms else 0,
                )
            )

            if (ms >= 0) {
                return LatencyResult(
                    latencyMs = ms,
                    jitterMs = 0,
                    sent = total,
                    received = 1,
                )
            }
        }

        // 所有源都不通
        return LatencyResult(latencyMs = 0, jitterMs = 0, sent = total, received = 0)
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
        // 单轮拉取量：靠 HTTP Range 限制。
        //
        // 为什么需要 Range：
        //   国内源是完整的 Ubuntu ISO（3~6GB），不加 Range 会一直下到底，
        //   既浪费流量又测不准。
        //   加上 "Range: bytes=start-end" 后每个请求只拿 20MB，
        //   下一轮从断点继续（还能避开 CDN 缓存对同一段的加速）。
        //
        // 20MB 在 100Mbps 下约 1.6 秒，跑满 10 秒时限需要多轮，
        //   正好符合“总量不封顶”的设计。
        val perRequest = 20 * 1024 * 1024
        var rangeStart = 0L

        // 候选源：按顺序尝试，哪个能跑就用哪个。
        // 一旦选定就固定下来，不每轮重选（否则轮次间速度不可比）。
        val urlList = downCandidates(perRequest)
        var urlIdx = 0
        var total = 0L
        val t0 = System.nanoTime()
        var winStart = t0
        var winBytes = 0L
        var lastReported = 0.0

        // 抖动估算：记录每个 200ms 窗口的瞬时速度，
        // 最后算相邻窗口速度变化的平均值。
        //
        // 为什么能当抖动用：
        //   延迟只测 1 次之后没有 RTT 序列可算抖动，
        //   而带宽的“瞬时速度忽高忽低”与链路抖动高度相关，
        //   是用户能感知的同一个现象（卡顿/忽快忽慢）。
        //   这里换算成 ms 便于与之前的展示口径一致。
        val winSpeeds = ArrayList<Double>(64)
        var jitterMs = 0

        // 逐源诊断：记录每个源为什么失败。
        // 只显示“所有源均无响应”无法定位问题，
        // 这里把每个源的 HTTP 码 / 异常类型都带上。
        val diag = ArrayList<String>(8)

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
                val rangeEnd = rangeStart + perRequest - 1
                conn = (URL(target).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    // 读超时要大于单轮预期时长，否则会被误判为断网
                    readTimeout = 15000
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) SpeedTest")
                    // 只取这一段，不下整个 ISO
                    setRequestProperty("Range", "bytes=" + rangeStart + "-" + rangeEnd)
                }
                if (conn.responseCode !in 200..299) {
                    // 这个源不行，换下一个重试
                    diag.add("源" + (urlIdx + 1) + ":HTTP " + conn.responseCode)
                    urlIdx++
                    if (urlIdx >= urlList.size) {
                        onTick(
                            Tick(
                                phase = Phase.DOWNLOAD,
                                error = "下载失败：" + diag.joinToString("，")
                            )
                        )
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

                            // 记录本窗口速度，供抖动估算
                            if (lastReported > 0.0) winSpeeds.add(lastReported)

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
                // 单轮异常不直接判失败。
                //
                // 关键：没拿到数据时【必须换下一个源】，不能直接 return。
                // 之前这里直接 return，导致永远只试 Cloudflare 一个源，
                // Cloudflare 不通就整个测速失败，后面的 CacheFly / Hetzner 形同虚设。
                if (total == 0L) {
                    // 记录这个源的具体异常（超时 / DNS / SSL 等）
                    val kind = when (t) {
                        is java.net.SocketTimeoutException -> "超时"
                        is java.net.UnknownHostException -> "DNS失败"
                        is java.net.ConnectException -> "连接拒绝"
                        is javax.net.ssl.SSLException -> "SSL错误"
                        else -> t.javaClass.simpleName
                    }
                    diag.add("源" + (urlIdx + 1) + ":" + kind)
                    urlIdx++
                    if (urlIdx >= urlList.size) {
                        onTick(
                            Tick(
                                phase = Phase.DOWNLOAD,
                                error = "下载失败：" + diag.joinToString("，")
                            )
                        )
                        return Triple(0.0, 0L, 0L)
                    }
                }
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }

            round++
            // 这一轮一个字节都没拿到 → 服务端不行了，别死循环
            if (gotThisRound == 0L) break

            // 推进到下一段。
            //
            // 必须推进！否则每轮都拉同一段：
            //   1. CDN / 运营商缓存会命中，测出虚高几十倍的速度
            //   2. 部分镜像站会对重复请求限流
            // 按实际拿到的字节数推进，保证下一段是真新数据。
            rangeStart += gotThisRound
        }

        val totalMs = (System.nanoTime() - t0) / 1_000_000L
        val mbps = if (totalMs > 0) total * 8.0 / totalMs / 1000.0 else 0.0

        // ---- 抖动估算（用速度波动换算出 ms）----
        //
        // 相邻 200ms 窗口的速度变化越大，链路越不稳。
        // 换算：以平均速度为一个“包周期”，
        //   速度偏差比例 × 200ms 得到可比较的毫秒数。
        // 这是工程上的近似值，不是 ICMP 抖动，但能反映同一现象。
        if (winSpeeds.size >= 2) {
            val avgSpeed = winSpeeds.average()
            if (avgSpeed > 0.001) {
                var sum = 0.0
                for (i in 1 until winSpeeds.size) {
                    sum += abs(winSpeeds[i] - winSpeeds[i - 1]) / avgSpeed
                }
                val avgDev = sum / (winSpeeds.size - 1)
                // 200ms 窗口 × 偏差比例，裁到 0..999ms
                jitterMs = (avgDev * 200.0).toInt().coerceIn(0, 999)
            }
        }

        // 通过最后一个 Tick 把抖动带给调用方
        onTick(
            Tick(
                phase = Phase.DOWNLOAD,
                progress = baseProgress + spanProgress,
                currentMbps = mbps,
                avgMbps = mbps,
                bytes = total,
                elapsedMs = totalMs,
                jitterMs = jitterMs,
            )
        )

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
        // 抖动来自下载阶段的速度波动（延迟只测 1 次，无法算 RTT 抖动）
        var jit = latRes.jitterMs

        if (cancelled.get()) return Result(false, "已取消")
        if (lat == 0) {
            return Result(
                false,
                "无法连接测速服务器",
                netLabel = netLabel,
            )
        }

        onTick(
            Tick(
                phase = Phase.DOWNLOAD,
                progress = 0.15f,
                latencyMs = lat,
                jitterMs = jit,
            )
        )

        // ---- 2. 下载 ----
        var lastDown = 0.0
        var downBytes = 0L
        var downMs = 0L
        // 收集下载阶段真正的错误原因（download 内部已经写好了具体原因，
        // 比如超时、HTTP 状态码、所有源不可用），这里不要用笼统文案覆盖它。
        var downError = ""
        val d = download(
            maxDurationMs = downDurationMs,
            onTick = { p ->
                if (p.error.isNotBlank()) downError = p.error
                // download 会在末尾算出抖动，这里接住它
                if (p.jitterMs > 0) jit = p.jitterMs
                lastDown = if (p.currentMbps > 0) p.currentMbps else lastDown
                downBytes = p.bytes
                downMs = p.elapsedMs
                onTick(p.copy(latencyMs = lat, jitterMs = jit))
            },
            baseProgress = 0.15f,
            spanProgress = 0.55f,
        )
        if (cancelled.get()) return Result(false, "已取消")
        if (d.second == 0L) {
            return Result(
                false,
                downError.ifBlank { "下载测速失败（所有测速源均无响应）" },
                latencyMs = lat,
                jitterMs = jit,
            )
        }

        // ---- 3. 上传 ----
        onTick(
            Tick(
                phase = Phase.UPLOAD,
                progress = 0.7f,
                latencyMs = lat,
                jitterMs = jit,
            )
        )
        val u = upload(
            maxDurationMs = upDurationMs,
            onTick = { p ->
                onTick(p.copy(latencyMs = lat, jitterMs = jit))
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
