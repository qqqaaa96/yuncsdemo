package sy.great.toolbox.tools

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码 / 条形码生成。
 *
 * 基于 ZXing core 的 MultiFormatWriter，纯本地编码，不联网。
 *
 * 支持的格式：
 *   · QR_CODE     二维码（支持中英文、容错级别）
 *   · CODE_128    一维条码，密度高，通用性最好
 *   · CODE_39     一维条码，只支持大写字母 + 数字 + 少量符号
 *   · EAN_13      商品条码，必须 12~13 位数字
 *   · EAN_8       商品条码，8 位数字
 *   · UPC_A       北美商品条码，12 位数字
 *   · ITF         交叉二五码，偶数位数字
 *   · CODABAR     库德巴码，数字 + 少量符号
 */
object CodeMaker {

    /** 支持的码类型 */
    enum class Kind(val label: String, val format: BarcodeFormat, val hint: String) {
        QR("二维码", BarcodeFormat.QR_CODE, "任意文本 / 链接"),
        CODE128("Code 128", BarcodeFormat.CODE_128, "任意可打印 ASCII"),
        CODE39("Code 39", BarcodeFormat.CODE_39, "大写字母 + 数字 + - . 空格 $ / + %"),
        EAN13("EAN-13", BarcodeFormat.EAN_13, "12 或 13 位数字"),
        EAN8("EAN-8", BarcodeFormat.EAN_8, "7 或 8 位数字"),
        UPCA("UPC-A", BarcodeFormat.UPC_A, "11 或 12 位数字"),
        ITF("ITF", BarcodeFormat.ITF, "偶数位数字"),
        CODABAR("Codabar", BarcodeFormat.CODABAR, "数字 + - $ : / . +"),
    }

    /**
     * 生成码图。
     *
     * @param content 内容
     * @param kind    类型
     * @param sizePx  输出边长（二维码为正方形边长；条码为宽度）
     * @param dark    前景色
     * @param light   背景色
     * @return 生成失败时返回 null（内容不符合该码型要求）
     */
    fun make(
        content: String,
        kind: Kind,
        sizePx: Int = 800,
        dark: Int = Color.BLACK,
        light: Int = Color.WHITE,
    ): Bitmap? {
        if (content.isEmpty()) return null

        val w: Int
        val h: Int

        if (kind == Kind.QR) {
            w = sizePx
            h = sizePx
        } else {
            // 一维码：宽高比大约 3:1，太窄扫不出
            w = sizePx.coerceAtLeast(600)
            h = (w / 3).coerceAtLeast(200)
        }

        return try {
            val hints = HashMap<EncodeHintType, Any>()

            if (kind == Kind.QR) {
                // 容错级别 M（15%）平衡了容量与抗污损
                hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
                hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
                // 白边宽度，扫不出来时很关键
                hints[EncodeHintType.MARGIN] = 2
            } else {
                hints[EncodeHintType.MARGIN] = 20
            }

            val matrix = MultiFormatWriter().encode(
                content,
                kind.format,
                w,
                h,
                hints,
            )

            val bw = matrix.width
            val bh = matrix.height
            val pixels = IntArray(bw * bh)

            for (y in 0 until bh) {
                val offset = y * bw
                for (x in 0 until bw) {
                    pixels[offset + x] = if (matrix.get(x, y)) dark else light
                }
            }

            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, bw, 0, 0, bw, bh)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 内容是否符合该码型的基本要求。
     *
     * 只做能提前拦住明显错误的检查：
     * 具体编码能否成功仍由 ZXing 决定。
     */
    fun validate(content: String, kind: Kind): String? {
        if (content.isEmpty()) return "内容不能为空"

        return when (kind) {
            Kind.EAN13 -> {
                if (!content.all { it.isDigit() }) "EAN-13 只能包含数字"
                else if (content.length !in 12..13) "EAN-13 需要 12 或 13 位数字"
                else null
            }
            Kind.EAN8 -> {
                if (!content.all { it.isDigit() }) "EAN-8 只能包含数字"
                else if (content.length !in 7..8) "EAN-8 需要 7 或 8 位数字"
                else null
            }
            Kind.UPCA -> {
                if (!content.all { it.isDigit() }) "UPC-A 只能包含数字"
                else if (content.length !in 11..12) "UPC-A 需要 11 或 12 位数字"
                else null
            }
            Kind.ITF -> {
                if (!content.all { it.isDigit() }) "ITF 只能包含数字"
                else if (content.length % 2 != 0) "ITF 需要偶数位数字"
                else null
            }
            Kind.CODE39 -> {
                val ok = content.all { c ->
                    c.isUpperCase() || c.isDigit() || c in " -.$/+%"
                }
                if (!ok) "Code 39 只支持大写字母、数字和 - . 空格 $ / + %" else null
            }
            Kind.CODABAR -> {
                val ok = content.all { c -> c.isDigit() || c in "-\$:/.+" }
                if (!ok) "Codabar 只支持数字和 - \$ : / . +" else null
            }
            else -> null
        }
    }
}
