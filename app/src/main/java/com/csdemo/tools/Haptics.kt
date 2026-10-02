package com.csdemo.tools

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 震动反馈。
 * 仅用于“验证失败”等需要提醒的场景，成功不震动。
 */
object Haptics {

    /** 错误提示：两下短震 */
    fun error(ctx: Context) {
        try {
            val vib = vibrator(ctx) ?: return
            if (!vib.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 延迟 0 / 90ms 各一次，时长 40ms，幅度由系统控制
                vib.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 40, 70, 55),
                        intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE,
                            0, VibrationEffect.DEFAULT_AMPLITUDE),
                        -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(longArrayOf(0, 40, 70, 55), -1)
            }
        } catch (e: Exception) {
            // 忽略：部分设备无震动马达或权限受限
        }
    }

    /**
     * 按键反馈：强震动。
     *
     * 特点：时长稍长（28ms）、幅度拉满（255），
     * 并优先使用 predefined 效果（部分 ROM 会将其映射为系统级强触感）。
     */
    fun key(ctx: Context) {
        try {
            val vib = vibrator(ctx) ?: return
            if (!vib.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 10+：用 CLAIM 触感，强度更明确
                try {
                    vib.vibrate(
                        VibrationEffect.createPredefined(
                            VibrationEffect.EFFECT_CLICK
                        )
                    )
                    return
                } catch (e: Exception) {
                    // 部分设备不支持 predefined，继续走 createOneShot
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 强震：28ms，幅度 255
                vib.vibrate(VibrationEffect.createOneShot(28, 255))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(28)
            }
        } catch (e: Exception) {
        }
    }

    /** 兼容旧调用名 */
    fun tick(ctx: Context) = key(ctx)

    private fun vibrator(ctx: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                        as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            null
        }
    }
}
