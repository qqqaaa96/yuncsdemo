package sy.great.toolbox.tools

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * 设备指纹。
 *
 * 用途：注册配额限制（同一设备 7 天只能注册 1 个账号）。
 *
 * 采集项：
 *   · ANDROID_ID（卸载重装不变，是 Android 上最稳定的标识）
 *   · Build 指纹（机型/厂商/指纹字符串）
 *   · 屏幕分辨率
 *
 * 注意：
 *   这不是不可伪造的硬件标识（有 root 可以改），
 *   但普通用户和大多数脚本无法批量伪造，
 *   配合邮箱验证码与 IP 限流已经足够拦住批量注册。
 *   不上报 IMEI / MAC（需要权限，而且 Android 10+ 已经取不到）。
 */
object DeviceId {

    @SuppressLint("HardwareIds")
    fun get(ctx: Context): String {
        val parts = ArrayList<String>()

        try {
            val aid = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            if (!aid.isNullOrBlank()) parts.add("aid:" + aid)
        } catch (_: Throwable) {
        }

        try {
            parts.add("b:" + android.os.Build.FINGERPRINT)
            parts.add("m:" + android.os.Build.MANUFACTURER + "|" + android.os.Build.MODEL)
        } catch (_: Throwable) {
        }

        try {
            val dm = ctx.resources.displayMetrics
            parts.add("r:" + dm.widthPixels + "x" + dm.heightPixels)
        } catch (_: Throwable) {
        }

        val raw = parts.joinToString(";")
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val d = md.digest(raw.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(d.size * 2)
            for (b in d) {
                val v = b.toInt() and 0xFF
                if (v < 16) sb.append('0')
                sb.append(Integer.toHexString(v))
            }
            sb.toString()
        } catch (_: Throwable) {
            raw.hashCode().toString()
        }
    }
}
