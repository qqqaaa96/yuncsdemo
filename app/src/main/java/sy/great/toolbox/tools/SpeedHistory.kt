package sy.great.toolbox.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 测速历史记录（本地）。
 *
 * 与帧率记录同一套思路：
 *   · 每次测速都追加一条，保留最近 50 条
 *   · 聚合出峰值 / 谷值 / 平均，供曲线图与统计卡片使用
 *   · 落盘到 SharedPreferences，重启不丢
 *
 * 为什么不存数据库：
 *   数据量很小（50 条 × 几个字段），JSON 就够；
 *   也避免为了一个功能引入 Room / SQLite 依赖。
 */
object SpeedHistory {

    private const val PREF = "csdemo_speed_history"
    private const val K_LIST = "list"

    /** 最多保留多少条历史 */
    const val MAX = 50

    /** 一条历史记录 */
    data class Record(
        /** 测速完成时间（毫秒） */
        val timeMs: Long,
        val downMbps: Double,
        val upMbps: Double,
        val latencyMs: Int,
        val jitterMs: Int,
        val netLabel: String,
        /** 本次消耗流量字节 */
        val bytes: Long,
        /** 本次是否刷新了历史峰值 */
        val isNewPeak: Boolean = false,
    )

    /** 聚合统计（供 UI 顶部卡片展示） */
    data class Stats(
        val count: Int = 0,
        val peakDown: Double = 0.0,
        val lowDown: Double = 0.0,
        val avgDown: Double = 0.0,
        val peakUp: Double = 0.0,
        val avgLat: Int = 0,
    )

    // ---------------- 读写 ----------------

    /** 读全部历史（按时间倒序，最新在前） */
    fun list(ctx: Context): List<Record> {
        return try {
            val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(K_LIST, "") ?: ""
            if (raw.isBlank()) return emptyList()

            val arr = JSONArray(raw)
            val out = ArrayList<Record>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Record(
                        timeMs = o.optLong("t", 0L),
                        downMbps = o.optDouble("d", 0.0),
                        upMbps = o.optDouble("u", 0.0),
                        latencyMs = o.optInt("lat", 0),
                        jitterMs = o.optInt("jit", 0),
                        netLabel = o.optString("net", ""),
                        bytes = o.optLong("b", 0L),
                        isNewPeak = o.optBoolean("p", false),
                    )
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 追加一条记录。
     *
     * @return 写入后的这条记录（带 isNewPeak 标记）
     */
    fun add(ctx: Context, r: SpeedTest.Result): Record {
        val prev = list(ctx)
        val prevPeak = prev.maxOfOrNull { it.downMbps } ?: 0.0
        val isNewPeak = r.downMbps > prevPeak

        val rec = Record(
            timeMs = System.currentTimeMillis(),
            downMbps = r.downMbps,
            upMbps = r.upMbps,
            latencyMs = r.latencyMs,
            jitterMs = r.jitterMs,
            netLabel = r.netLabel,
            bytes = r.downBytes + r.upBytes,
            isNewPeak = isNewPeak,
        )

        // 新的放最前面，超出上限截断尾部
        val merged = ArrayList<Record>(prev.size + 1)
        merged.add(rec)
        merged.addAll(prev)
        while (merged.size > MAX) merged.removeAt(merged.size - 1)

        save(ctx, merged)
        return rec
    }

    private fun save(ctx: Context, list: List<Record>) {
        try {
            val arr = JSONArray()
            list.forEach { r ->
                arr.put(
                    JSONObject()
                        .put("t", r.timeMs)
                        .put("d", r.downMbps)
                        .put("u", r.upMbps)
                        .put("lat", r.latencyMs)
                        .put("jit", r.jitterMs)
                        .put("net", r.netLabel)
                        .put("b", r.bytes)
                        .put("p", r.isNewPeak)
                )
            }
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(K_LIST, arr.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    /** 清空历史 */
    fun clear(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Throwable) {
        }
    }

    // ---------------- 聚合 ----------------

    /**
     * 算聚合统计。
     *
     * 谷值只统计 >1Mbps 的样本：
     *   网络瞬断会产出 0，把它算进谷值会让“最低速度”永远是 0，没有参考意义。
     */
    fun stats(list: List<Record>): Stats {
        if (list.isEmpty()) return Stats()

        val downs = list.map { it.downMbps }.filter { it > 0 }
        val valid = list.map { it.downMbps }.filter { it > 1.0 }
        val ups = list.map { it.upMbps }.filter { it > 0 }
        val lats = list.map { it.latencyMs }.filter { it > 0 }

        return Stats(
            count = list.size,
            peakDown = downs.maxOrNull() ?: 0.0,
            lowDown = valid.minOrNull() ?: 0.0,
            avgDown = if (downs.isEmpty()) 0.0 else downs.average(),
            peakUp = ups.maxOrNull() ?: 0.0,
            avgLat = if (lats.isEmpty()) 0 else lats.average().toInt(),
        )
    }

    /**
     * 曲线数据：取最近 n 条，按时间正序（旧 → 新）返回。
     *
     * 画图必须正序，否则曲线方向是反的。
     */
    fun curve(list: List<Record>, n: Int = 20): List<Double> {
        return list.take(n).map { it.downMbps }.reversed()
    }
}
