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

        /** 强信号阈值（μT）：持续震动 */
        const val THRESHOLD_HIGH = 40f
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
            try {
                tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            } catch (_: Throwable) {
                tone = null
            }
        }
        return ok
    }

    /** 停止监听并释放资源 */
    fun stop() {
        sensorManager?.unregisterListener(this)
        tone?.release()
        tone = null
        stopVibrate()
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
            // 偏离越大，反馈越急：
            //   · 8~40 μT：间隔从 500ms 线性缩到 120ms
            //   · ≥40 μT：持续震动
            val span = (THRESHOLD_HIGH - THRESHOLD_LOW).coerceAtLeast(1f)
            val t = ((delta - THRESHOLD_LOW) / span).coerceIn(0f, 1f)
            val interval = (500 - 380 * t).toLong()

            if (delta >= THRESHOLD_HIGH) {
                // 强信号：连续震动
                vibrate(40)
                lastVibrateAt = now
                lastBeepAt = now
            } else {
                if (now - lastVibrateAt >= interval) {
                    vibrate((20 + 40 * t).toLong())
                    lastVibrateAt = now
                }
                if (now - lastBeepAt >= interval) {
                    beep()
                    lastBeepAt = now
                }
            }
        }

        onReading?.invoke(
            Reading(x, y, z, mag, delta, intensity, calibrated = true, alerting = alerting)
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 磁力计精度变化不影响主要逻辑，忽略。
    }

    private fun vibrate(ms: Long) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
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

    private fun beep() {
        try {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
        } catch (_: Throwable) {
        }
    }
}
