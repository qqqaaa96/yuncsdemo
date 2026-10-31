package sy.great.toolbox.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 排行榜 / 签到 / 隐私网络的网络层。
 */
object RankApi {

    // ---------------- 数据结构 ----------------

    data class RankItem(
        val rank: Int,
        val uid: String,
        /** users.id，用于本地头像缓存文件名；0 = 未知 */
        val userId: Int = 0,
        /** -1 表示对方隐矇了积分 */
        val points: Int,
        val name: String,
        /** 头像版本，0 = 无头像 */
        val avatarAt: Int,
        val tag: SyncCenter.Tag?,
        val streak: Int,
        val isMe: Boolean,
        /** 名字单色，空串用主题色 */
        val nameColor: String = "",
        /** 名字流光颜色，≥2 启用 */
        val nameFx: List<String> = emptyList(),
        /** 名字显示模式：username / uid / email */
        val nameMode: String = "uid",
    )

    data class RankResult(
        val ok: Boolean,
        val error: String = "",
        val updatedAt: String = "",
        val items: List<RankItem> = emptyList(),
        val myUid: String = "",
    )

    data class CheckinResult(
        val ok: Boolean,
        val error: String = "",
        val gain: Int = 0,
        val streak: Int = 0,
        val total: Int = 0,
        val bonusPct: Int = 0,
        val level: Int = 1,
        val user: JSONObject? = null,
    )

    // ---------------- 排行榜 ----------------

    fun ranking(ctx: Context): RankResult {
        val token = AuthApi.token(ctx)
        val r = get(ctx, "ranking", token)
            ?: return RankResult(false, "网络错误")

        if (!r.optBoolean("ok", false)) {
            return RankResult(false, r.optString("error", "拉取失败"))
        }

        // 兼容两种响应结构：
        //   · ok() 平铺：{"ok":true, "items":[...], "updatedAt":...}
        //   · 嵌套 data：{"ok":true, "data":{"items":[...]}}
        // 本项目 ok() 是平铺的，所以要以顶层为准。
        val data = r.optJSONObject("data")
        val arr = data?.optJSONArray("items") ?: r.optJSONArray("items")
            ?: return RankResult(false, "返回格式异常：" + r.toString().take(200))

        val updatedAt = (data?.optString("updatedAt", "") ?: "")
            .ifBlank { r.optString("updatedAt", "") }
        val myUid = (data?.optString("myUid", "") ?: "")
            .ifBlank { r.optString("myUid", "") }

        val items = ArrayList<RankItem>()

        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue

                var tag: SyncCenter.Tag? = null
                val t = o.optJSONObject("tag")
                if (t != null) {
                    val fx = ArrayList<String>()
                    val fa = t.optJSONArray("fxColors")
                    if (fa != null) {
                        for (j in 0 until fa.length()) {
                            val c = fa.optString(j, "")
                            if (c.isNotBlank()) fx.add(c)
                        }
                    }
                    tag = SyncCenter.Tag(
                        text = t.optString("text", ""),
                        color = t.optString("color", ""),
                        fxColors = fx,
                    )
                }

                items.add(
                    RankItem(
                        rank = o.optInt("rank", i + 1),
                        uid = o.optString("uid", ""),
                        userId = o.optInt("userId", 0),
                        points = o.optInt("points", 0),
                        name = o.optString("name", ""),
                        avatarAt = o.optInt("avatar", 0),
                        tag = tag,
                        streak = o.optInt("streak", 0),
                        isMe = o.optBoolean("isMe", false),
                        nameColor = o.optString("nameColor", ""),
                        nameFx = run {
                            val list = ArrayList<String>()
                            val fa = o.optJSONArray("nameFx")
                            if (fa != null) {
                                for (j in 0 until fa.length()) {
                                    val c = fa.optString(j, "")
                                    if (c.isNotBlank()) list.add(c)
                                }
                            }
                            list
                        },
                        nameMode = o.optString("nameMode", "uid"),
                    )
                )
            }
        }

        return RankResult(
            ok = true,
            updatedAt = updatedAt,
            items = items,
            myUid = myUid,
        )
    }

    // ---------------- 签到 ----------------

    fun checkin(ctx: Context): CheckinResult {
        val token = AuthApi.token(ctx)
        if (token.isEmpty()) return CheckinResult(false, "未登录")

        val r = get(ctx, "checkin", token)
            ?: return CheckinResult(false, "网络错误")

        if (!r.optBoolean("ok", false)) {
            return CheckinResult(false, r.optString("error", "签到失败"))
        }

        // ok() 是平铺的，优先读顶层
        val d = r.optJSONObject("data") ?: r

        return CheckinResult(
            ok = true,
            gain = d.optInt("gain", 0),
            streak = d.optInt("streak", 0),
            total = d.optInt("total", 0),
            bonusPct = d.optInt("bonus", 0),
            level = d.optJSONObject("user")?.optInt("level", 1) ?: 1,
            user = d.optJSONObject("user"),
        )
    }

    // ---------------- 测速排行榜 ----------------

    /** 排行榜里的一条测速记录 */
    data class SpeedItem(
        val rank: Int,
        val uid: String,
        /** users.id，用于本地头像缓存文件名 */
        val userId: Int = 0,
        val name: String,
        val avatarAt: Int = 0,
        /** 历史峰值下行 Mbps（排行榜排序依据） */
        val downMbps: Double = 0.0,
        /** 上行 Mbps */
        val upMbps: Double = 0.0,
        val latencyMs: Int = 0,
        val jitterMs: Int = 0,
        /** 历史谷值下行 Mbps（有效样本中的最低） */
        val lowMbps: Double = 0.0,
        /** 历史平均下行 Mbps */
        val avgMbps: Double = 0.0,
        /** 累计测速次数 */
        val runs: Int = 0,
        /** 波动曲线：最近 N 次下行速度（最多 20 个点） */
        val curve: List<Double> = emptyList(),
        /** 测速时用的网络：Wi-Fi / 移动数据 / 以太网 / VPN */
        val net: String = "",
        /** 本次测速消耗的流量字节数（下载 + 上传） */
        val bytes: Long = 0,
        /** 测速时间戳（秒） */
        val at: Long = 0,
        val isMe: Boolean = false,
    )

    data class SpeedRankResult(
        val ok: Boolean,
        val error: String = "",
        val items: List<SpeedItem> = emptyList(),
    )

    /**
     * 拉取测速排行榜。
     *
     * @param by 排序字段：down（下行）/ up（上行）/ ping（延迟）
     */
    fun speedRanking(ctx: Context, by: String = "down"): SpeedRankResult {
        val token = AuthApi.token(ctx)
        val r = get(ctx, "speed_rank&by=" + enc(by), token)
            ?: return SpeedRankResult(false, "网络错误")

        if (!r.optBoolean("ok", false)) {
            return SpeedRankResult(false, r.optString("error", "拉取失败"))
        }

        // ok() 是平铺的，先试顶层，再试 data
        val data = r.optJSONObject("data")
        val arr = data?.optJSONArray("items") ?: r.optJSONArray("items")
            ?: return SpeedRankResult(false, "返回格式异常")

        val items = ArrayList<SpeedItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            items.add(
                SpeedItem(
                    rank = o.optInt("rank", i + 1),
                    uid = o.optString("uid", ""),
                    userId = o.optInt("userId", 0),
                    name = o.optString("name", ""),
                    avatarAt = o.optInt("avatar", 0),
                    downMbps = o.optDouble("down", 0.0),
                    upMbps = o.optDouble("up", 0.0),
                    latencyMs = o.optInt("lat", 0),
                    jitterMs = o.optInt("jit", 0),
                    lowMbps = o.optDouble("low", 0.0),
                    avgMbps = o.optDouble("avg", 0.0),
                    runs = o.optInt("runs", 0),
                    curve = run {
                        val list = ArrayList<Double>()
                        val arr = o.optJSONArray("curve")
                        if (arr != null) {
                            for (j in 0 until arr.length()) {
                                list.add(arr.optDouble(j, 0.0))
                            }
                        }
                        list
                    },
                    net = o.optString("net", ""),
                    bytes = o.optLong("bytes", 0L),
                    at = o.optLong("at", 0L),
                    isMe = o.optBoolean("isMe", false),
                )
            )
        }
        return SpeedRankResult(true, items = items)
    }

    /**
     * 上报一次测速结果。
     *
     * 只保留每个用户的最高下行记录（服务端处理），
     * 所以可以放心多次测、多次上报。
     */
    fun uploadSpeed(ctx: Context, r: SpeedTest.Result): Boolean {
        val token = AuthApi.token(ctx)
        if (token.isEmpty()) return false

        val qs = buildString {
            append("speed_submit")
            append("&down=").append(fmt(r.downMbps))
            append("&up=").append(fmt(r.upMbps))
            append("&lat=").append(r.latencyMs)
            append("&jit=").append(r.jitterMs)
            append("&net=").append(enc(r.netLabel))
            append("&bytes=").append(r.downBytes + r.upBytes)
        }

        val resp = get(ctx, qs, token) ?: return false
        return resp.optBoolean("ok", false)
    }

    /** 保留两位小数，避免分数过长 */
    private fun fmt(v: Double): String = "%.2f".format(v)

    // ---------------- 隐私 ----------------

    /**
     * 保存隐私设置。
     *
     * @param values key 必须是后端白名单内的字段：
     *   showAvatar / showTag / showUid / showEmail / showStreak / showPoints
     */
    fun savePrivacy(ctx: Context, values: Map<String, Boolean>): Boolean {
        val token = AuthApi.token(ctx)
        if (token.isEmpty()) return false

        val qs = buildString {
            append("privacy_save")
            values.forEach { (k, v) ->
                append('&').append(enc(k)).append('=').append(if (v) '1' else '0')
            }
        }

        val r = get(ctx, qs, token) ?: return false
        if (!r.optBoolean("ok", false)) return false

        // 同步到本地用户缓存（兼容平铺 / data 嵌套）
        (r.optJSONObject("data") ?: r).optJSONObject("user")?.let {
            try { AuthApi.syncUser(ctx, it) } catch (_: Throwable) {}
        }
        return true
    }

    // ---------------- HTTP ----------------

    private fun get(ctx: Context, query: String, token: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(AuthApi.BASE + "/api.php?action=" + query)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 15000
                doInput = true
                setRequestProperty("Accept", "application/json")
                if (token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (text.isBlank()) null else JSONObject(text)
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun enc(s: String): String =
        try { URLEncoder.encode(s, "UTF-8") } catch (_: Throwable) { s }
}
