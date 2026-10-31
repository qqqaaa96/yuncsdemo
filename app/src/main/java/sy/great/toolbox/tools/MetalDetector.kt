package sy.great.toolbox.tools

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 金属探测器（磁力计方案）。
 *
 * 原理：
 *   手机磁力计（Sensor.TYPE_MAGNETIC_FIELD）测的是环境磁场强度，单位 μT。
 *   地球磁场约 25~65 μT。靠近铁、钢、镍等铁磁性金属时，
 *   磁场会被扭曲，读数明显偏离基准值。
 *
 * 必须如实说明的限制：
 *   · 只能探测【铁磁性】金属（铁、钢、镍、钴、钕铁硼磁铁）
 *   · 探测不了金、银、铜、铝、不锈钢（304/316）——不是灵敏度问题，是物理原理做不到
 *   · 手机壳 / 磁吸壳 / 扬声器 / 无线充电线圈会干扰读数，使用前要取下
 *
 * 交互：
 *   · 进入后自动校零（取约 1.5 秒平均值当基准）
 *   · 偏离量超过阈值 → 震动 + 提示音，偏离越大频率越高
 */
class MetalDetector(private val ctx: Context) : SensorEventListener {

    companion object {
        /** 校准时长（毫秒） */
        const val CALIBRATE_MS = 1500L

        /** 基础阈值（μT）：低于此值不报警 */
        const val THRESHOLD_LOW = 8f

        /**
         * 满格阈值（μT）：delta 达到此值即视为 100% 强度。
         *
         * 注意：这不是“报警开关”，而是强度映射的满量程端点。
         * delta 超过它之后，节奏 / 音量 / 震幅全部保持最强，不会再“跳变”。
         * 旧代码在这里做了 if/else 分叉，≥40 就只震动不出声，已删除。
         */
        const val THRESHOLD_HIGH = 40f

        // ---- 反馈参数（强度 0..1 连续映射，不再分档）----

        /** 最慢节奏：间隔 900ms（刚触发阈值时） */
        private const val INTERVAL_SLOW_MS = 900L

        /** 最快节奏：间隔 55ms（满格，近似连续蜂鸣） */
        private const val INTERVAL_FAST_MS = 55L

        /** 最轻震动的持续时长 */
        private const val VIB_MIN_MS = 18L

        /** 最重震动的持续时长 */
        private const val VIB_MAX_MS = 120L

        /** 最轻震动的振幅（1..255） */
        private const val AMP_MIN = 40

        /** 最重震动的振幅（1..255） */
        private const val AMP_MAX = 255

        /** 最轻提示音音量（ToneGenerator 0..100） */
        private const val VOL_MIN = 35

        /** 最满提示音音量 */
        private const val VOL_MAX = 100

        /** 提示音单次持续时长 */
        private const val TONE_MIN_MS = 30

        /** 提示音单次持续时长（满格） */
        private const val TONE_MAX_MS = 90

        /**
         * 强度曲线指数。
         *
         * 1.0 = 线性；<1 会让低强度段更快变急（更“早”有反馈），
         * 这里取 0.65，使 8~40 μT 区间内节奏变化更明显、更跟手。
         */
        private const val CURVE_POW = 0.65
    }

    /** 一次测量快照 */
    data class Reading(
        /** 三轴原始值 μT */
        val x: Float,
        val y: Float,
        val z: Float,
        /** 磁场总量 μT */
        val magnitude: Float,
        /** 与基准的偏离量 μT（恒 ≥ 0） */
        val delta: Float,
        /** 归一化强度 0..1（用于表盘） */
        val intensity: Float,
        /** 是否已校准 */
        val calibrated: Boolean,
        /** 是否正在报警 */
        val alerting: Boolean,
    )

    /** 状态回调（在主线程调用） */
    var onReading: ((Reading) -> Unit)? = null

    private val sensorManager: SensorManager? =
        ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val magnetometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val vibrator: Vibrator? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private var tone: ToneGenerator? = null

    /**
     * 当前提示音音量。
     *
     * ToneGenerator 的音量在构造时固定，运行期无法调整，
     * 所以强度跨档时直接重建实例（开销很小，且只在跨档时发生）。
     */
    private var toneVolume = -1

    /** 设备是否支持按振幅精细控制震动 */
    private val amplitudeControl: Boolean =
        run {
            val v = vibrator
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                v != null && v.hasAmplitudeControl()
        }

    // ---- 校准状态 ----
    private var calibrating = true
    private var calibStart = 0L
    private var calibSum = 0.0
    private var calibCount = 0
    private var baseline = 0f

    // ---- 报警节流 ----
    private var lastBeepAt = 0L
    private var lastVibrateAt = 0L

    /** 设备是否具备磁力计 */
    fun hasSensor(): Boolean = magnetometer != null

    /** 开始监听 */
    fun start(): Boolean {
        val sm = sensorManager ?: return false
        val s = magnetometer ?: return false
        resetCalibration()
        val ok = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        if (ok) {
            ensureTone(VOL_MIN)
        }
        return ok
    }

    /** 停止监听并释放资源 */
    fun stop() {
        sensorManager?.unregisterListener(this)
        releaseTone()
        stopVibrate()
    }

    /**
     * 确保 ToneGenerator 存在且音量为 targetVol。
     *
     * 音量必须跨档才重建：每帧重建会不断申请音频焦点，
     * 表现为声音断续、甚至被系统短暂静音。
     */
    private fun ensureTone(targetVol: Int) {
        val v = targetVol.coerceIn(1, 100)
        // 20 一档，避免每帧抖动都重建
        val bucket = ((v + 19) / 20) * 20
        if (tone != null && toneVolume == bucket) return
        releaseTone()
        try {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, bucket)
            toneVolume = bucket
        } catch (_: Throwable) {
            tone = null
            toneVolume = -1
        }
    }

    private fun releaseTone() {
        try {
            tone?.release()
        } catch (_: Throwable) {
        }
        tone = null
        toneVolume = -1
    }

    /** 重新校零 */
    fun resetCalibration() {
        calibrating = true
        calibStart = 0L
        calibSum = 0.0
        calibCount = 0
        baseline = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val mag = sqrt(x * x + y * y + z * z)
        val now = System.currentTimeMillis()

        // ---- 校准阶段 ----
        if (calibrating) {
            if (calibStart == 0L) calibStart = now
            calibSum += mag
            calibCount++
            val elapsed = now - calibStart
            if (elapsed >= CALIBRATE_MS && calibCount > 0) {
                baseline = (calibSum / calibCount).toFloat()
                calibrating = false
            }
            onReading?.invoke(
                Reading(x, y, z, mag, 0f, 0f, calibrated = false, alerting = false)
            )
            return
        }

        // ---- 测量阶段 ----
        val delta = abs(mag - baseline)
        val intensity = (delta / THRESHOLD_HIGH).coerceIn(0f, 1f)
        val alerting = delta >= THRESHOLD_LOW

        if (alerting) {
            feedBack(delta, now)
        }

        onReading?.invoke(
            Reading(x, y, z, mag, delta, intensity, calibrated = true, alerting = alerting)
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 磁力计精度变化不影响主要逻辑，忽略。
    }

    /**
     * 统一反馈入口：节奏、震幅、音量全部由 delta 连续映射。
     *
     * 设计要点（对应修复的 Bug）：
     *   · 不再有 “delta >= 40 就只震动” 的分支，声音在任何强度下都会响；
     *   · 间隔 900ms → 55ms，满格时近似连续蜂鸣；
     *   · 震幅 40 → 255（支持振幅控制的设备），持续时长 18ms → 120ms；
     *   · 音量 35 → 100，满格时最响亮。
     *
     * delta 超过 THRESHOLD_HIGH 后 t 仍为 1，各项均维持最强，不会变差。
     */
    private fun feedBack(delta: Float, now: Long) {
        val span = (THRESHOLD_HIGH - THRESHOLD_LOW).coerceAtLeast(1f)
        val linear = ((delta - THRESHOLD_LOW) / span).coerceIn(0f, 1f)
        // 曲线：低强度段也尽早变急，避免“快靠近了才响”
        val t = Math.pow(linear.toDouble(), CURVE_POW.toDouble()).toFloat().coerceIn(0f, 1f)

        // ---- 节奏：越强越密集 ----
        val interval = (INTERVAL_SLOW_MS - (INTERVAL_SLOW_MS - INTERVAL_FAST_MS) * t).toLong()

        // ---- 震动 ----
        if (now - lastVibrateAt >= interval) {
            val vibMs = (VIB_MIN_MS + (VIB_MAX_MS - VIB_MIN_MS) * t).toLong()
            val amp = (AMP_MIN + (AMP_MAX - AMP_MIN) * t).toInt()
            vibrate(vibMs, amp)
            lastVibrateAt = now
        }

        // ---- 提示音：强度越高，音量越大、音长越长、间隔越短 ----
        if (now - lastBeepAt >= interval) {
            val vol = (VOL_MIN + (VOL_MAX - VOL_MIN) * t).toInt()
            val toneMs = (TONE_MIN_MS + (TONE_MAX_MS - TONE_MIN_MS) * t).toInt()
            beep(vol, toneMs)
            lastBeepAt = now
        }
    }

    private fun vibrate(ms: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 支持振幅控制的设备（大多数线性马达）用真实振幅，
                // 不支持的（转子马达）退回默认振幅，时长依然随强度变化。
                val amp = if (amplitudeControl) {
                    amplitude.coerceIn(1, 255)
                } else {
                    VibrationEffect.DEFAULT_AMPLITUDE
                }
                v.vibrate(VibrationEffect.createOneShot(ms.coerceAtLeast(1L), amp))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms.coerceAtLeast(1L))
            }
        } catch (_: Throwable) {
            // 部分设备禁用了震动，忽略
        }
    }

    private fun stopVibrate() {
        try {
            vibrator?.cancel()
        } catch (_: Throwable) {
        }
    }

    /**
     * 发声。
     *
     * 音量变化靠重建 ToneGenerator 实现（构造时固定）。
     * 用 TONE_CDMA_ALERT_CALL_GUARD（类似短促蜂鸣）比 TONE_PROP_BEEP 更刺耳、
     * 在高音量下更容易被听到。
     */
    private fun beep(volume: Int, durationMs: Int) {
        ensureTone(volume)
        val t = tone ?: return
        try {
            t.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, durationMs)
        } catch (_: Throwable) {
        }
    }
}
