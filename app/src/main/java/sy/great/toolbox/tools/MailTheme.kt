package sy.great.toolbox.tools

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 邮件卡片主题（可调节的液态玻璃参数）。
 *
 * 类比 AppSettings 的用法：
 *   · 读取：MailTheme.current.xxx（Compose State，变化自动重组）
 *   · 修改：MailTheme.setXxx(...)
 *
 * 默认值就是之前手调的那套：
 *   refractionHeight 25.92 · refractionAmount 56.16 · 色散 0.24
 *   blur 4.41 · 圆角 33.6
 */
object MailTheme {

    private const val PREF = "csdemo_mail_theme"

    private const val K_H = "refraction_height"
    private const val K_A = "refraction_amount"
    private const val K_CA = "chromatic_aberration"
    private const val K_BLUR = "blur"
    private const val K_CORNER = "corner_radius"

    /** 一套可调参数 */
    data class Params(
        /** 边缘折射区高度（dp） */
        val refractionHeightDp: Float = 25.92f,
        /** 折射偏移量（dp），越大变形越明显 */
        val refractionAmountDp: Float = 56.16f,
        /** 色散 0~1 */
        val chromaticAberration: Float = 0.24f,
        /** 高斯模糊（dp） */
        val blurDp: Float = 4.41f,
        /** 卡片圆角（dp） */
        val cornerRadiusDp: Float = 33.6f,
    )

    private var prefs: SharedPreferences? = null

    /** 当前参数（Compose 可观察） */
    var current by mutableStateOf(Params())
        private set

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs = p
        val d = Params()
        current = Params(
            refractionHeightDp = p.getFloat(K_H, d.refractionHeightDp),
            refractionAmountDp = p.getFloat(K_A, d.refractionAmountDp),
            chromaticAberration = p.getFloat(K_CA, d.chromaticAberration),
            blurDp = p.getFloat(K_BLUR, d.blurDp),
            cornerRadiusDp = p.getFloat(K_CORNER, d.cornerRadiusDp),
        )
    }

    fun setRefractionHeight(v: Float) {
        current = current.copy(refractionHeightDp = v.coerceIn(0f, 80f))
        prefs?.edit()?.putFloat(K_H, current.refractionHeightDp)?.apply()
    }

    fun setRefractionAmount(v: Float) {
        current = current.copy(refractionAmountDp = v.coerceIn(0f, 120f))
        prefs?.edit()?.putFloat(K_A, current.refractionAmountDp)?.apply()
    }

    fun setChromaticAberration(v: Float) {
        current = current.copy(chromaticAberration = v.coerceIn(0f, 1f))
        prefs?.edit()?.putFloat(K_CA, current.chromaticAberration)?.apply()
    }

    fun setBlur(v: Float) {
        current = current.copy(blurDp = v.coerceIn(0f, 20f))
        prefs?.edit()?.putFloat(K_BLUR, current.blurDp)?.apply()
    }

    fun setCornerRadius(v: Float) {
        current = current.copy(cornerRadiusDp = v.coerceIn(0f, 60f))
        prefs?.edit()?.putFloat(K_CORNER, current.cornerRadiusDp)?.apply()
    }

    /** 恢复默认 */
    fun reset() {
        val d = Params()
        current = d
        prefs?.edit()
            ?.putFloat(K_H, d.refractionHeightDp)
            ?.putFloat(K_A, d.refractionAmountDp)
            ?.putFloat(K_CA, d.chromaticAberration)
            ?.putFloat(K_BLUR, d.blurDp)
            ?.putFloat(K_CORNER, d.cornerRadiusDp)
            ?.apply()
    }
}
