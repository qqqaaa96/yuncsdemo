package sy.great.toolbox.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * 图片格式转换。
 *
 * 支持：
 *   · 输入：JPEG / PNG / WebP / BMP / GIF（取首帧）
 *   · 输出：JPEG / PNG / WEBP
 *   · 质量 1~100（仅 JPEG / WebP 有效，PNG 为无损）
 *   · 等比缩放（长边限制）
 *
 * 实现基于 BitmapFactory + Bitmap.compress，不需要任何第三方库。
 *
 * 为什么不用第三方：
 *   图片转换系统 API 已经够用且稳定；
 *   引入 libjpeg-turbo / libwebp 会增加几 MB 体积，收益有限。
 */
object ImageConverter {

    enum class Format(val ext: String, val mime: String, val display: String) {
        JPEG("jpg", "image/jpeg", "JPEG"),
        PNG("png", "image/png", "PNG"),
        WEBP("webp", "image/webp", "WebP")
    }

    data class Options(
        val format: Format = Format.JPEG,
        /** 1~100，仅影响 JPEG / WebP */
        val quality: Int = 90,
        /** 长边像素上限；0 表示不缩放 */
        val maxSide: Int = 0
    )

    data class Result(
        val ok: Boolean,
        val bytes: ByteArray = ByteArray(0),
        /** 输出后的宽高 */
        val width: Int = 0,
        val height: Int = 0,
        val message: String = ""
    )

    /**
     * 转换。
     *
     * @param input 源文件
     */
    fun convert(input: File, opt: Options): Result {
        if (!input.exists()) return Result(false, message = "源文件不存在")
        if (input.length() == 0L) return Result(false, message = "源文件为空")

        // ---- 1. 先读尺寸，决定是否需要采样 ----
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(input.absolutePath, bounds)
        } catch (e: Throwable) {
            return Result(false, message = "无法识别图片：" + (e.message ?: ""))
        }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) {
            return Result(false, message = "不是有效图片，或格式不被系统支持")
        }

        // ---- 2. 计算采样率（避免大图 OOM）----
        val decodeOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        if (opt.maxSide > 0) {
            val longSide = max(srcW, srcH)
            var sample = 1
            while (longSide / sample > opt.maxSide * 2) sample *= 2
            decodeOpts.inSampleSize = sample
        }

        val src: Bitmap = try {
            BitmapFactory.decodeFile(input.absolutePath, decodeOpts)
                ?: return Result(false, message = "解码失败（可能是 HEIF/AVIF 等不支持格式）")
        } catch (e: OutOfMemoryError) {
            return Result(false, message = "图片太大，内存不足")
        } catch (e: Throwable) {
            return Result(false, message = "解码失败：" + (e.message ?: ""))
        }

        // ---- 3. 缩放（采样后可能仍超过目标）----
        var work = src
        if (opt.maxSide > 0) {
            val w = src.width
            val h = src.height
            val longSide = max(w, h)
            if (longSide > opt.maxSide) {
                val scale = opt.maxSide.toFloat() / longSide
                val nw = max(1, (w * scale).toInt())
                val nh = max(1, (h * scale).toInt())
                work = try {
                    Bitmap.createScaledBitmap(src, nw, nh, true)
                } catch (_: Throwable) {
                    src
                }
                if (work !== src) src.recycle()
            }
        }

        // ---- 4. 编码 ----
        // WebP：Android 10+ 才有损/无损分离，优先用有损；低于 10 用已废弃常量
        val compressFormat = when (opt.format) {
            Format.JPEG -> Bitmap.CompressFormat.JPEG
            Format.PNG -> Bitmap.CompressFormat.PNG
            Format.WEBP -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        }

        // JPEG 不支持透明：有 alpha 的图先铺白底，否则透明处会变黑
        var target = work
        if (opt.format == Format.JPEG && work.hasAlpha()) {
            val flat = Bitmap.createBitmap(work.width, work.height, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(flat)
            c.drawColor(android.graphics.Color.WHITE)
            c.drawBitmap(work, 0f, 0f, null)
            target = flat
        }

        val out = ByteArrayOutputStream()
        val q = opt.quality.coerceIn(1, 100)
        val okCompress = try {
            target.compress(compressFormat, q, out)
        } catch (e: Throwable) {
            false
        }

        val w = target.width
        val h = target.height
        if (target !== work) target.recycle()
        if (work !== src) work.recycle()
        src.recycle()

        if (!okCompress) {
            return Result(false, message = "编码失败（该设备可能不支持 " + opt.format.display + " 编码）")
        }

        val data = out.toByteArray()
        if (data.isEmpty()) return Result(false, message = "编码结果为空")

        return Result(true, data, w, h, "成功")
    }
}
