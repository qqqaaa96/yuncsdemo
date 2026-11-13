package sy.great.toolbox.tools

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 数字格式化。
 *
 * 规则（尽量符合计算器直觉）：
 *   · NaN / Infinity → “无法计算”
 *   · 整数 → 不带小数点
 *   · 小数 → 最多 10 位有效数字，去掉末尾 0
 *   · 极大/极小 → 用科学计数法（避免输出几十位）
 */
object CalcFormat {

    /** 数值格式化 */
    fun num(v: Double, digits: Int = 10): String {
        if (!v.isFinite()) return "无法计算"

        // 极接近 0 的负数当成 0（浮点误差）
        if (kotlin.math.abs(v) < 1e-12) return "0"

        val a = kotlin.math.abs(v)
        // 超出常规范围用科学计数法
        if (a >= 1e12 || a < 1e-6) {
            return String.format(java.util.Locale.US, "%.${digits - 1}E", v)
                .replace(Regex("E([+-])(\\d)$"), "E$10$2")
        }

        // 整数直接输出
        if (a == Math.floor(a) && a < 1e15) {
            return BigDecimal(v).setScale(0, RoundingMode.HALF_UP).toPlainString()
        }

        // 小数：限制有效位数后去尾 0
        val bd = BigDecimal(v).setScale(digits, RoundingMode.HALF_UP).stripTrailingZeros()
        return bd.toPlainString()
    }

    /** 固定小数位 */
    fun fixed(v: Double, digits: Int = 2): String {
        if (!v.isFinite()) return "无法计算"
        return String.format(java.util.Locale.US, "%.${digits}f", v)
    }
}
