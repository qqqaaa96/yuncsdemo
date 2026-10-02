package com.csdemo.tools

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * SVG → PNG 转换。
 *
 * Android 没有内置 SVG 渲染器，这里用系统自带的 WebView 渲染，
 * 再把 WebView 内容绘制到 Bitmap。
 *
 * 关键点：
 *   · WebView 必须在主线程创建与操作
 *   · 尺寸按 SVG 的 width/height 设置，保证不裁切
 *   · 大图需要放大上限保护，避免内存溢出
 */
object SvgToPng {

    data class Result(
        val ok: Boolean,
        val outputPath: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val message: String = ""
    )

    /** 单边最大像素（防止 OOM，超大图会被等比缩小） */
    private const val MAX_SIDE = 8000
    /** 总像素上限（约 6000 万像素，约 240MB RGBA） */
    private const val MAX_PIXELS = 60_000_000L

    /**
     * 把 SVG 转成 PNG。
     *
     * @param svg SVG 文本
     * @param outFile 输出 PNG 文件
     * @param scale 额外倍数（>1 更清晰但更吃内存）
     */
    fun convert(
        ctx: Context,
        svg: String,
        outFile: File,
        scale: Float = 1.6f
    ): Result {
        // 1. 解析 SVG 的宽高
        val size = parseSize(svg)
        if (size == null) {
            return Result(false, message = "无法解析 SVG 尺寸")
        }

        var w = ceil(size.first * scale).toInt()
        var h = ceil(size.second * scale).toInt()

        // 2. 尺寸保护
        var finalScale = scale
        if (w > MAX_SIDE || h > MAX_SIDE || w.toLong() * h > MAX_PIXELS) {
            val k1 = MAX_SIDE.toFloat() / maxOf(w, h)
            val k2 = kotlin.math.sqrt(MAX_PIXELS.toDouble() / (w.toDouble() * h.toDouble())).toFloat()
            val k = minOf(k1, k2)
            finalScale = scale * k
            w = ceil(size.first * finalScale).toInt()
            h = ceil(size.second * finalScale).toInt()
        }
        if (w <= 0 || h <= 0) {
            return Result(false, message = "SVG 尺寸无效")
        }

        // 3. 在内存中构造带缩放信息的 SVG
        val scaledSvg = injectScale(svg, finalScale)
        val html = wrapHtml(scaledSvg, w, h)

        // 4. 主线程渲染
        var bitmap: Bitmap? = null
        val latch = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())

        main.post {
            var web: WebView? = null
            try {
                web = createWebView(ctx)
                var finished = false

                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (finished) return
                        finished = true
                        // 稍等一帧，确保绘制完成
                        view?.postDelayed({
                            try {
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                val c = Canvas(bmp)
                                c.drawColor(Color.WHITE)
                                view.draw(c)
                                bitmap = bmp
                            } catch (e: OutOfMemoryError) {
                                bitmap = null
                            } catch (e: Exception) {
                                bitmap = null
                            }
                            latch.countDown()
                        }, 320)
                    }
                }
                web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            } catch (e: Exception) {
                try { web?.destroy() } catch (_: Exception) {}
                latch.countDown()
            }
        }

        val done = latch.await(25, TimeUnit.SECONDS)
        if (!done) {
            return Result(false, message = "渲染超时")
        }

        val bmp = bitmap ?: return Result(false, message = "渲染失败（可能是内存不足）")

        // 5. 写 PNG
        return try {
            val parent = outFile.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            bmp.recycle()
            Result(
                ok = true,
                outputPath = outFile.absolutePath,
                width = w,
                height = h,
                message = "已生成 $w × $h PNG"
            )
        } catch (e: Exception) {
            try { bmp.recycle() } catch (_: Exception) {}
            Result(false, message = "写入 PNG 失败：" + (e.message ?: "未知"))
        }
    }

    // ---------- 内部 ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(ctx: Context): WebView {
        return WebView(ctx).apply {
            settings.javaScriptEnabled = false
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            setBackgroundColor(Color.WHITE)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
    }

    /** 从 SVG 文本中解析 width / height（不依赖 XML 库，容错解析） */
    fun parseSize(svg: String): Pair<Float, Float>? {
        val head = svg.substring(0, minOf(1200, svg.length))
        val w = Regex("width\\s*=\\s*\"([0-9.]+)", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)?.toFloatOrNull()
        val h = Regex("height\\s*=\\s*\"([0-9.]+)", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)?.toFloatOrNull()
        if (w != null && h != null && w > 0 && h > 0) return w to h
        // 退化：从 viewBox 取
        val vb = Regex("viewBox\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)
        if (vb != null) {
            val p = vb.trim().split(Regex("[\\s,]+"))
            if (p.size == 4) {
                val vw = p[2].toFloatOrNull()
                val vh = p[3].toFloatOrNull()
                if (vw != null && vh != null && vw > 0 && vh > 0) return vw to vh
            }
        }
        return null
    }

    /**
     * 入注入缩放：把 svg 根节点的 width/height 乘以 k。
     * viewBox 保持不变，因此内容会等比放大。
     */
    private fun injectScale(svg: String, k: Float): String {
        if (k <= 1.001f) return svg
        val size = parseSize(svg) ?: return svg
        val nw = size.first * k
        val nh = size.second * k

        // 只替换首个匹配，手工拼接（避免 replaceFirst 不支持 lambda）
        var out = replaceFirstByRegex(
            svg,
            "(width\\s*=\\s*\")([0-9.]+)(\")",
            fmt(nw)
        )
        out = replaceFirstByRegex(
            out,
            "(height\\s*=\\s*\")([0-9.]+)(\")",
            fmt(nh)
        )
        return out
    }

    /** 用正则替换首个匹配：保留第 1、3 组，中间换成 value */
    private fun replaceFirstByRegex(input: String, pattern: String, value: String): String {
        val m = Regex(pattern, RegexOption.IGNORE_CASE).find(input) ?: return input
        val g = m.groupValues
        if (g.size < 4) return input
        return input.substring(0, m.range.first) +
                g[1] + value + g[3] +
                input.substring(m.range.last + 1)
    }

    private fun fmt(v: Float): String = String.format("%.1f", v)

    /** 包成 HTML，并保证 body 无边距 */
    private fun wrapHtml(svg: String, w: Int, h: Int): String {
        return """
        |<!DOCTYPE html>
        |<html><head>
        |<meta charset="utf-8">
        |<meta name="viewport" content="width=$w, height=$h, initial-scale=1.0">
        |<style>
        |  html,body{margin:0;padding:0;background:#FFFFFF;overflow:hidden;}
        |  svg{display:block;}
        |</style>
        |</head><body>$svg</body></html>
        """.trimMargin()
    }

    // ---------- 输出命名 ----------

    fun suggestOutput(ctx: Context, srcName: String): File {
        val base = if (srcName.endsWith(".so", true) || srcName.endsWith(".elf", true))
            srcName.substringBeforeLast('.') else srcName
        val dir = File("/storage/emulated/0/Download/csdemo_cfg")
        if (!dir.exists()) dir.mkdirs()
        var f = File(dir, base + "_cfg.png")
        var n = 1
        while (f.exists()) {
            f = File(dir, base + "_cfg_" + n + ".png")
            n++
        }
        return f
    }
}
