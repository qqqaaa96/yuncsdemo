package com.csdemo.tools

import java.io.File
import java.io.FileOutputStream

/**
 * 把控制流图导出为 SVG。
 *
 * 输出为纯静态矢量图：节点矩形、指令文本、正交折线与箭头。
 * 不包含交互逻辑，但可以在浏览器或矢量软件里无限放大。
 */
object CfgSvgExporter {

    /** 配色（与界面保持一致） */
    private const val C_BG = "#FFFFFF"
    private const val C_BLOCK_BG = "#FFFFFF"
    private const val C_BLOCK_SEL = "#F0F5FF"
    private const val C_TITLE_BG = "#F6F6F6"
    private const val C_BORDER = "#D6D6D6"
    private const val C_ENTRY = "#1B6EF3"
    private const val C_EXIT = "#CC2222"
    private const val C_ADDR = "#8A8A8A"
    private const val C_MNEMONIC = "#0B5FBF"
    private const val C_REG = "#0F7B6C"
    private const val C_IMM = "#B05A00"
    private const val C_TARGET = "#7A3EB1"
    private const val C_PUNCT = "#666666"
    private const val C_TRUE = "#17803D"
    private const val C_FALSE = "#CC2222"
    private const val C_CALL = "#1B6EF3"
    private const val C_FAINT = "#9A9A9A"

    private const val FONT = "ui-monospace,Menlo,Consolas,monospace"
    private const val FS_TITLE = 12f
    private const val FS_ADDR = 11f
    private const val FS_CODE = 13f
    private const val LINE_H = 21f

    /**
     * 生成 SVG 文本。
     *
     * @param fileName 标题中显示的文件名
     */
    fun build(model: CfgModel.Graph, fileName: String, showBytes: Boolean = false): String {
        // ---------- 1. 精确计算内容边界 ----------
        // 关键：不能只用 layout.width/height，回边与侧边通道会超出节点范围
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        fun acc(x: Float, y: Float) {
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }

        for (n in model.layout.nodes) {
            acc(n.x, n.y)
            acc(n.x + n.width, n.y + n.height)
        }
        for (r in model.routes) {
            for (p in r.points) acc(p.x, p.y)
        }

        if (minX == Float.MAX_VALUE) {
            minX = 0f; minY = 0f; maxX = 100f; maxY = 100f
        }

        // 预留边距（含顶部说明区、底部箭头与标签）
        val padL = 30f
        val padR = 40f
        val padT = 46f
        val padB = 40f

        // ------------------------------------------------------------
        // viewBox 直接用「内容真实边界（外扩边距）」，不做 translate。
        //
        // 这是 SVG 最标准的做法：viewBox 可以是负坐标，
        // 无论内容位于第一象限、第四象限还是包含负数，
        // 都会被完整框住，不会出现“只显示左上角 1/4”的情况。
        // ------------------------------------------------------------
        val vbX = minX - padL
        val vbY = minY - padT
        val contentW = (maxX - minX) + padL + padR
        val contentH = (maxY - minY) + padT + padB

        // 宽高用整数，避免部分查看器对小数尺寸处理不一致
        val outW = kotlin.math.ceil(contentW).toInt().coerceAtLeast(1)
        val outH = kotlin.math.ceil(contentH).toInt().coerceAtLeast(1)

        val sb = StringBuilder(1 shl 16)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
            .append("width=\"").append(outW).append("\" ")
            .append("height=\"").append(outH).append("\" ")
            .append("viewBox=\"").append(fmt(vbX)).append(' ').append(fmt(vbY))
            .append(' ').append(outW).append(' ').append(outH).append("\" ")
            .append("preserveAspectRatio=\"xMidYMid meet\">\n")

        // 背景铺满整个可视区域
        sb.append("<rect x=\"").append(fmt(vbX)).append("\" y=\"").append(fmt(vbY))
            .append("\" width=\"").append(outW).append("\" height=\"").append(outH)
            .append("\" fill=\"").append(C_BG).append("\"/>\n")

        // 顶部说明（放在 viewBox 左上角附近）
        sb.append("<text x=\"").append(fmt(vbX + 12f)).append("\" y=\"")
            .append(fmt(vbY + 22f))
            .append("\" font-family=\"").append(FONT)
            .append("\" font-size=\"13\" fill=\"#111111\">")
            .append(esc(fileName)).append(" · 控制流图  块数 ").append(model.blocks.size)
            .append("  边数 ").append(model.routes.size).append("</text>\n")

        // 内容直接使用原始坐标，不做整体平移
        sb.append("<g>\n")

        // ---------- 1. 先画边（在节点下方） ----------
        for (r in model.routes) {
            val color = when {
                r.isBack -> C_FAINT
                r.kind == CfgLayout.Kind.TRUE -> C_TRUE
                r.kind == CfgLayout.Kind.FALSE -> C_FALSE
                r.kind == CfgLayout.Kind.CALL -> C_CALL
                else -> C_FAINT
            }
            val pts = r.points
            if (pts.size < 2) continue

            val d = StringBuilder()
            d.append('M').append(fmt(pts[0].x)).append(',').append(fmt(pts[0].y))
            for (i in 1 until pts.size) {
                d.append('L').append(fmt(pts[i].x)).append(',').append(fmt(pts[i].y))
            }
            sb.append("<path d=\"").append(d).append("\" fill=\"none\" stroke=\"").append(color)
                .append("\" stroke-width=\"1.6\"")
            if (r.isBack) sb.append(" stroke-dasharray=\"7 6\"")
            sb.append("/>\n")

            // 箭头
            val a = pts[pts.size - 2]
            val b = pts[pts.size - 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            if (len > 1f) {
                val ux = dx / len
                val uy = dy / len
                val asz = 8f
                val tipX = b.x - ux
                val tipY = b.y - uy
                val lx = tipX - ux * asz - uy * asz * 0.55f
                val ly = tipY - uy * asz + ux * asz * 0.55f
                val rx = tipX - ux * asz + uy * asz * 0.55f
                val ry = tipY - uy * asz - ux * asz * 0.55f
                sb.append("<path d=\"M").append(fmt(tipX)).append(',').append(fmt(tipY))
                    .append('L').append(fmt(lx)).append(',').append(fmt(ly))
                    .append('L').append(fmt(rx)).append(',').append(fmt(ry))
                    .append("Z\" fill=\"").append(color).append("\"/>\n")
            }

            // T / F 标签
            if (r.label.isNotEmpty()) {
                val p = r.labelAt
                val isT = r.label == "T"
                val lc = if (isT) C_TRUE else C_FALSE
                val box = 16f
                sb.append("<rect x=\"").append(fmt(p.x - box / 2f))
                    .append("\" y=\"").append(fmt(p.y - box / 2f))
                    .append("\" width=\"").append(fmt(box))
                    .append("\" height=\"").append(fmt(box))
                    .append("\" rx=\"4\" fill=\"").append(lc).append("\" fill-opacity=\"0.14\"/>\n")
                // 用几何形状表示：T = 横+竖，F = 横+短斜
                sb.append("<path d=\"M").append(fmt(p.x - 4f)).append(',').append(fmt(p.y - 3f))
                    .append('L').append(fmt(p.x + 4f)).append(',').append(fmt(p.y - 3f))
                if (isT) {
                    sb.append('M').append(fmt(p.x)).append(',').append(fmt(p.y - 3f))
                        .append('L').append(fmt(p.x)).append(',').append(fmt(p.y + 4f))
                } else {
                    sb.append('M').append(fmt(p.x - 3f)).append(',').append(fmt(p.y - 3f))
                        .append('L').append(fmt(p.x - 1f)).append(',').append(fmt(p.y + 4f))
                }
                sb.append("\" fill=\"none\" stroke=\"").append(lc)
                    .append("\" stroke-width=\"1.6\" stroke-linecap=\"round\"/>\n")
            }
        }

        // ---------- 1.5 函数分区标题 ----------
        val ranges = model.layout.groupRanges
        if (ranges.isNotEmpty()) {
            // 每个分区画一条分隔线与函数名
            val nameByGroup = HashMap<Int, String>()
            for (b in model.blocks) {
                if (!nameByGroup.containsKey(b.group)) nameByGroup[b.group] = b.label
            }
            for ((g, range) in ranges.toSortedMap()) {
                val top = range.first
                val bottom = range.second
                // 分隔线（横跨整个内容宽度）
                sb.append("<line x1=\"").append(fmt(minX)).append("\" y1=\"")
                    .append(fmt(top - 34f))
                    .append("\" x2=\"").append(fmt(maxX))
                    .append("\" y2=\"").append(fmt(top - 34f))
                    .append("\" stroke=\"#E6E6E6\" stroke-width=\"1\"/>\n")
                // 函数名
                val fn = esc(nameByGroup[g] ?: ("func_" + g))
                sb.append("<text x=\"").append(fmt(minX)).append("\" y=\"").append(fmt(top - 16f))
                    .append("\" font-family=\"").append(FONT)
                    .append("\" font-size=\"13\" font-weight=\"bold\" fill=\"#1B6EF3\">")
                    .append("▸ ").append(fn).append("</text>\n")
            }
        }

        // ---------- 2. 再画节点 ----------
        for (blk in model.blocks) {
            val n = model.layout.nodes.firstOrNull { it.id == blk.id } ?: continue

            val border = when {
                blk.isEntry -> C_ENTRY
                blk.isExit -> C_EXIT
                else -> C_BORDER
            }
            val bw = if (blk.isEntry || blk.isExit) 2.2f else 1.2f

            // 块体
            sb.append("<rect x=\"").append(fmt(n.x)).append("\" y=\"").append(fmt(n.y))
                .append("\" width=\"").append(fmt(n.width))
                .append("\" height=\"").append(fmt(n.height))
                .append("\" rx=\"5\" fill=\"").append(C_BLOCK_BG).append("\"/>\n")

            // 标题栏
            sb.append("<path d=\"M").append(fmt(n.x + 5f)).append(',').append(fmt(n.y))
                .append('L').append(fmt(n.x + n.width - 5f)).append(',').append(fmt(n.y))
                .append("Q").append(fmt(n.x + n.width)).append(',').append(fmt(n.y))
                .append(' ').append(fmt(n.x + n.width)).append(',').append(fmt(n.y + 5f))
                .append('L').append(fmt(n.x + n.width)).append(',').append(fmt(n.y + 24f))
                .append('L').append(fmt(n.x)).append(',').append(fmt(n.y + 24f))
                .append('L').append(fmt(n.x)).append(',').append(fmt(n.y + 5f))
                .append("Q").append(fmt(n.x)).append(',').append(fmt(n.y))
                .append(' ').append(fmt(n.x + 5f)).append(',').append(fmt(n.y))
                .append("Z\" fill=\"").append(C_TITLE_BG).append("\"/>\n")

            // 边框
            sb.append("<rect x=\"").append(fmt(n.x)).append("\" y=\"").append(fmt(n.y))
                .append("\" width=\"").append(fmt(n.width))
                .append("\" height=\"").append(fmt(n.height))
                .append("\" rx=\"5\" fill=\"none\" stroke=\"").append(border)
                .append("\" stroke-width=\"").append(fmt(bw)).append("\"/>\n")

            // 标题
            val title = esc(if (blk.label.length > 32) blk.label.take(32) else blk.label)
            sb.append("<text x=\"").append(fmt(n.x + 12f))
                .append("\" y=\"").append(fmt(n.y + 17f))
                .append("\" font-family=\"").append(FONT)
                .append("\" font-size=\"").append(fmt(FS_TITLE))
                .append("\" font-weight=\"bold\" fill=\"#3C3C3C\">")
                .append(title).append("</text>\n")

            // 入口 / 出口标记
            if (blk.isEntry || blk.isExit) {
                val tag = if (blk.isEntry) "入口" else "出口"
                val tc = if (blk.isEntry) C_ENTRY else C_EXIT
                sb.append("<text x=\"").append(fmt(n.x + n.width - 44f))
                    .append("\" y=\"").append(fmt(n.y + 17f))
                    .append("\" font-family=\"").append(FONT)
                    .append("\" font-size=\"").append(fmt(FS_TITLE))
                    .append("\" font-weight=\"bold\" fill=\"").append(tc).append("\">")
                    .append(tag).append("</text>\n")
            }

            // 指令行
            var lineY = n.y + 24f + 16f
            for (ln in blk.lines) {
                // 地址
                sb.append("<text x=\"").append(fmt(n.x + 12f))
                    .append("\" y=\"").append(fmt(lineY))
                    .append("\" font-family=\"").append(FONT)
                    .append("\" font-size=\"").append(fmt(FS_ADDR))
                    .append("\" fill=\"").append(C_ADDR).append("\">")
                    .append(java.lang.Long.toHexString(ln.addr)).append("</text>\n")

                // 指令片段
                var x = n.x + 106f
                for (p in ln.pieces) {
                    val color = pieceColor(p.kind, ln)
                    val txt = esc(p.text)
                    // 近似宽度估算（等宽字体）
                    val approx = p.text.length * FS_CODE * 0.60f
                    if (x + approx > n.x + n.width - 8f) break
                    sb.append("<text x=\"").append(fmt(x))
                        .append("\" y=\"").append(fmt(lineY))
                        .append("\" font-family=\"").append(FONT)
                        .append("\" font-size=\"").append(fmt(FS_CODE))
                        .append("\" fill=\"").append(color).append("\">")
                        .append(txt).append("</text>\n")
                    x += approx
                }

                lineY += LINE_H
                if (lineY > n.y + n.height - 8f) break
            }
        }

        sb.append("</g>\n")
        sb.append("</svg>\n")
        return sb.toString()
    }

    /** 写入文件 */
    fun write(model: CfgModel.Graph, fileName: String, out: File): Boolean {
        return try {
            val parent = out.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            FileOutputStream(out).use { fos ->
                fos.write(build(model, fileName).toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 建议输出路径（存到应用外部目录，便于取出） */
    fun suggestOutput(ctx: android.content.Context, srcName: String): File {
        val base = if (srcName.endsWith(".so", true) || srcName.endsWith(".elf", true))
            srcName.substringBeforeLast('.') else srcName
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "cfg")
        if (!dir.exists()) dir.mkdirs()
        var f = File(dir, base + "_cfg.svg")
        var n = 1
        while (f.exists()) {
            f = File(dir, base + "_cfg_" + n + ".svg")
            n++
        }
        return f
    }

    // ---------- 工具 ----------

    /**
     * 数值格式化。
     *
     * 注意：
     *   · 部分坐标会接近 0 且为负（如 -0.04），直接格式化成 "-0.0"，
     *     某些 SVG 查看器会把它当作非法数值。这里统一归零。
     *   · 去掉无意义的小数尾 .0，减小文件体积。
     */
    private fun fmt(v: Float): String {
        var x = v
        if (kotlin.math.abs(x) < 0.05f) x = 0f
        val s = String.format(java.util.Locale.US, "%.1f", x)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun pieceColor(kind: CfgModel.PieceKind, ln: CfgModel.Line): String = when (kind) {
        CfgModel.PieceKind.MNEMONIC -> when {
            ln.isCall -> C_TRUE
            ln.isBranch -> C_ENTRY
            ln.isReturn -> C_EXIT
            else -> C_MNEMONIC
        }
        CfgModel.PieceKind.REG -> C_REG
        CfgModel.PieceKind.IMM -> C_IMM
        CfgModel.PieceKind.TARGET -> C_TARGET
        CfgModel.PieceKind.COMMENT -> C_ADDR
        else -> C_PUNCT
    }
}
