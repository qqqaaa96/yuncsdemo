package com.csdemo.ui

import android.graphics.Paint
import android.graphics.Typeface
import java.io.File
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.CfgBuilder
import com.csdemo.tools.CfgModel
import com.csdemo.tools.CfgRouter
import com.csdemo.tools.CfgSvgExporter
import com.csdemo.tools.ExportUtils
import com.csdemo.ui.theme.Accent
import com.csdemo.ui.theme.Bad
import com.csdemo.ui.theme.Good
import com.csdemo.ui.theme.Ink
import com.csdemo.ui.theme.InkFaint
import com.csdemo.ui.theme.InkSoft
import com.csdemo.ui.theme.Line
import com.csdemo.ui.theme.Paper
import com.csdemo.ui.theme.PaperSoft

/** 与 CfgLayout.Kind 对应（避免在 UI 层直接引用全路径） */
private typealias CfgLayoutKind = com.csdemo.tools.CfgLayout.Kind

/**
 * 左右内边距。
 *
 * ⚠ 必须与 CfgModel 内部的 PAD_X 保持一致（同为 16f），
 * 否则绘制起点会与测量起点错位，导致文字右边被裁断。
 */
private const val PAD_X = 16f

/** 节点与边的配色 */
private val BlockBorder = Color(0xFFD6D6D6)
private val SelBorder = Color(0xFF1B6EF3)
private val AddrColor = Color(0xFF8A8A8A)
private val MnemonicColor = Color(0xFF0B5FBF)
private val RegColor = Color(0xFF0F7B6C)
private val ImmColor = Color(0xFFB05A00)
private val TargetColor = Color(0xFF7A3EB1)
private val PunctColor = Color(0xFF666666)
private val BlockBg = Color(0xFFFFFFFF)
private val BlockBgSel = Color(0xFFF0F5FF)
private val TitleBg = Color(0xFFF6F6F6)

/**
 * 函数级控制流图（独立页面）。
 *
 * 绘制：Compose Canvas；文字：原生 Paint（保证小字号清晰）。
 * 交互：双指缩放、单指拖动平移、点击块高亮、双击复位。
 * LOD：缩小时隐藏指令细节，只保留块边框与地址。
 */
@Composable
fun CfgGraphScreen(
    graph: CfgBuilder.Graph,
    funcName: String,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current

    // ---- 创建测量用 Paint（等宽字体，保证与绘制一致） ----
    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            textSize = 13f * ctx.resources.displayMetrics.density
        }
    }
    val addrPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            textSize = 11f * ctx.resources.displayMetrics.density
        }
    }
    val titlePaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 12f * ctx.resources.displayMetrics.density
        }
    }
    val codePaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            textSize = 13f * ctx.resources.displayMetrics.density
        }
    }

    // ---- 构建模型（只做一次） ----
    // 传统一传入 titlePaint，使测量与绘制共用同一套字体度量
    val model = remember(graph) {
        CfgModel.build(graph, textPaint, addrPaint, titlePaint, showBytes = false)
    }

    // ---- 视图变换 ----
    var scale by remember { mutableStateOf(1f) }
    var tx by remember { mutableStateOf(0f) }
    var ty by remember { mutableStateOf(0f) }
    var selected by remember { mutableStateOf(-1) }

    // ---- 导出状态 ----
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportMsg by remember { mutableStateOf("") }

    fun reset() {
        scale = 1f
        tx = 0f
        ty = 0f
    }

    LaunchedEffect(graph) { reset() }

    val density = ctx.resources.displayMetrics.density

    // 画布尺寸（px）
    val canvasW = model.layout.width
    val canvasH = model.layout.height

    Column(Modifier.fillMaxSize().background(Paper)) {
        // ---------- 顶栏 ----------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    funcName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Ink,
                    maxLines = 1
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        model.blocks.size.toString() + " 块 ・ " +
                                model.routes.size + " 边 ・ " +
                                model.layout.layerCount + " 层",
                        fontSize = 10.sp, color = InkSoft
                    )
                    // 截断时才提示（这是真的信息，不是"可能不准"）
                    if (graph.truncated) {
                        Spacer(Modifier.width(8.dp))
                        Tag("已截断", com.csdemo.ui.theme.Warn)
                    }
                }
            }
            // 导出 SVG（矢量，自适应宽高，可无限放大）
            TextButton(
                onClick = {
                    if (exporting) return@TextButton
                    exporting = true
                    exportMsg = ""
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            try {
                                val svg = CfgSvgExporter.build(model, funcName)
                                if (svg.isEmpty()) {
                                    ExportUtils.Result(false, message = "生成 SVG 失败")
                                } else {
                                    ExportUtils.exportText(
                                        ctx = ctx,
                                        fileName = funcName.replace(Regex("[\\\\/:*?\"<>|\\s+]"), "_") + "_cfg.svg",
                                        content = svg,
                                        mime = "image/svg+xml"
                                    )
                                }
                            } catch (e: Exception) {
                                ExportUtils.Result(false, message = e.message ?: "未知错误")
                            }
                        }
                        exportMsg = if (r.ok) {
                            "已导出 SVG\n" + r.displayPath
                        } else {
                            "导出失败：" + r.message
                        }
                        exporting = false
                    }
                }
            ) {
                Text(if (exporting) "导出中..." else "导出 SVG", fontSize = 12.sp)
            }
        }

        // ---------- 工具条 ----------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { scale = (scale * 1.25f).coerceAtMost(4f) }) {
                Text("放大", fontSize = 12.sp)
            }
            TextButton(onClick = { scale = (scale / 1.25f).coerceAtLeast(0.2f) }) {
                Text("缩小", fontSize = 12.sp)
            }
            TextButton(onClick = { reset() }) {
                Text("复位", fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                String.format("%.0f%%", scale * 100),
                fontSize = 11.sp, color = InkSoft
            )
        }

        // 图例
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            LegendItem(Good, "真分支 T")
            LegendItem(Bad, "假分支 F")
            LegendItem(Accent, "入口")
            LegendItem(InkFaint, "回边/其他")
        }

        Spacer(Modifier.height(4.dp))

        // ---------- 画布 ----------
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp)
                .padding(bottom = 8.dp)
                .background(PaperSoft, RoundedCornerShape(10.dp))
                .border(1.dp, Line, RoundedCornerShape(10.dp))
                .pointerInput(model) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // 缩放下限放宽到 0.05，可一直缩小
                        scale = (scale * zoom).coerceIn(0.05f, 6f)
                        tx += pan.x
                        ty += pan.y
                    }
                }
                .pointerInput(model, scale, tx, ty) {
                    detectTapGestures(
                        onTap = { off ->
                            // 命中最上层块（逆变换坐标）
                            val px = (off.x - tx) / scale
                            val py = (off.y - ty) / scale
                            var hit = -1
                            for (n in model.layout.nodes) {
                                if (px >= n.x && px <= n.x + n.width &&
                                    py >= n.y && py <= n.y + n.height
                                ) {
                                    hit = n.id
                                    break
                                }
                            }
                            selected = hit
                        },
                        onDoubleTap = { reset() }
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val nc = drawContext.canvas.nativeCanvas

                // ---------- 统一变换：框与文字一起缩放 ----------
                // 注意：整个绘制过程只允许一对 save/restore，
                // 否则每次子绘制后的 restore 会错位，导致 scale 累积。
                nc.save()
                nc.translate(tx, ty)
                nc.scale(scale, scale)

                // 字号跟随缩放，保证放大后文字不乱挤
                val baseDensity = ctx.resources.displayMetrics.density
                val ts = 13f * baseDensity
                val ads = 11f * baseDensity
                val tts = 12f * baseDensity

                titlePaint.textSize = tts
                addrPaint.textSize = ads
                codePaint.textSize = ts

                // LOD 分级
                val lod = scale
                val showFull = lod >= 0.60f
                val showMnemonicOnly = lod in 0.22f..0.60f

                // ---------- 0. 函数分区标题 ----------
                val ranges = model.layout.groupRanges
                if (ranges.isNotEmpty()) {
                    val secPaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                        textSize = 14f * baseDensity
                        color = android.graphics.Color.argb(255, 27, 110, 243)
                    }
                    val linePaint2 = android.graphics.Paint().apply {
                        isAntiAlias = true
                        strokeWidth = 1f / scale
                        color = android.graphics.Color.argb(255, 230, 230, 230)
                    }
                    val nameByGroup = HashMap<Int, String>()
                    for (b in model.blocks) {
                        if (!nameByGroup.containsKey(b.group)) nameByGroup[b.group] = b.label
                    }
                    for ((g, range) in ranges.toSortedMap()) {
                        val top = range.first
                        nc.drawLine(0f, top - 36f, model.layout.width, top - 36f, linePaint2)
                        nc.drawText(
                            "▸ " + (nameByGroup[g] ?: ("func_" + g)),
                            4f, top - 16f, secPaint
                        )
                    }
                }

                // ---------- 1. 先画边 ----------
                for (r in model.routes) {
                    val color = when {
                        r.isBack -> InkFaint
                        r.kind == CfgLayoutKind.TRUE -> Good
                        r.kind == CfgLayoutKind.FALSE -> Bad
                        r.kind == CfgLayoutKind.CALL -> Accent
                        else -> InkFaint
                    }
                    drawRouteNative(nc, r, color, r.isBack, scale)

                    // 边标签（T / F），仅在足够大时显示
                    if (lod > 0.35f && r.label.isNotEmpty()) {
                        drawEdgeLabelNative(nc, r, scale)
                    }
                }

                // ---------- 2. 再画节点 ----------
                for (b in model.blocks) {
                    val n = model.layout.nodes.firstOrNull { it.id == b.id } ?: continue
                    drawBlockNative(
                        nc, b, n, lod, b.id == selected,
                        titlePaint, addrPaint, codePaint,
                        showFull, showMnemonicOnly, scale,
                        model.metrics
                    )
                }

                nc.restore()
                // 与开头的 nc.save() 配对，恢复变换矩阵。
                // Compose 的 Canvas 每帧都会重新绘制，
                // 不需要再叠加 restoreToCount 之类的额外重置。
            }
        }

        // ---------- 导出结果提示 ----------
        if (exportMsg.isNotBlank()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .background(
                        if (exportMsg.startsWith("已导出")) Good.copy(alpha = 0.10f)
                        else Bad.copy(alpha = 0.10f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(10.dp)
            ) {
                Text(
                    exportMsg,
                    fontSize = 11.sp,
                    color = if (exportMsg.startsWith("已导出")) Good else Bad,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

// ============================================================
// 图例
// ============================================================

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(14.dp)
                .height(2.dp)
                .background(color, RoundedCornerShape(1.dp))
        )
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 10.sp, color = InkSoft)
    }
}

// ============================================================
// 导出到公共存储（无需权限，MT 管理器可直接看到）
// ============================================================

/**
 * 把 SVG 写入公共目录，优先尝试多个候选路径。
 *
 * 候选顺序：
 *   1. /storage/emulated/0/Download/csdemo      标准下载目录
 *   2. /storage/emulated/0/Documents/csdemo     文档目录
 *   3. /storage/emulated/0/csdemo_cfg           根目录下的自建目录
 *   4. 应用外部私有目录（兵底，可能不易访问）
 */
private fun writeSvgToPublic(content: String, funcName: String): String {
    val base = funcName.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
        .let { if (it.length > 60) it.take(60) else it }

    val candidates = listOf(
        File("/storage/emulated/0/Download/csdemo_cfg"),
        File("/storage/emulated/0/Documents/csdemo_cfg"),
        File("/storage/emulated/0/csdemo_cfg"),
        File("/sdcard/Download/csdemo_cfg")
    )

    for (dir in candidates) {
        try {
            if (!dir.exists() && !dir.mkdirs()) continue
            if (!dir.canWrite()) continue

            var out = File(dir, base + "_cfg.svg")
            var i = 1
            while (out.exists()) {
                out = File(dir, base + "_cfg_" + i + ".svg")
                i++
            }
            java.io.FileOutputStream(out).use {
                it.write(content.toByteArray(Charsets.UTF_8))
            }
            if (out.exists() && out.length() > 0) return out.absolutePath
        } catch (e: Exception) {
            // 尝试下一个候选
        }
    }
    return ""
}

// ============================================================
// 原生绘制（框与文字同一坐标系，缩放完全同步）
// ============================================================

/** 路径画笔缓存 */
private val routePaint = android.graphics.Paint().apply {
    isAntiAlias = true
    style = android.graphics.Paint.Style.STROKE
    strokeCap = android.graphics.Paint.Cap.ROUND
    strokeJoin = android.graphics.Paint.Join.ROUND
}

/** 填充画笔 */
private val fillPaint = android.graphics.Paint().apply {
    isAntiAlias = true
    style = android.graphics.Paint.Style.FILL
}

/** 基础线宽（会随 scale 调整） */
private fun strokeW(scale: Float): Float = (1.6f / scale).coerceIn(0.5f, 3f)

/** 绘制一条正交折线边（原生） */
private fun drawRouteNative(
    nc: android.graphics.Canvas,
    r: CfgRouter.Route,
    color: Color,
    dashed: Boolean,
    scale: Float
) {
    val pts = r.points
    if (pts.size < 2) return

    val path = android.graphics.Path()
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)

    routePaint.color = color.toArgbNative()
    routePaint.strokeWidth = strokeW(scale)
    routePaint.pathEffect = if (dashed)
        android.graphics.DashPathEffect(floatArrayOf(7f, 6f), 0f) else null
    nc.drawPath(path, routePaint)

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
        val tipX = b.x - ux * 1f
        val tipY = b.y - uy * 1f
        val leftX = tipX - ux * asz - uy * asz * 0.55f
        val leftY = tipY - uy * asz + ux * asz * 0.55f
        val rightX = tipX - ux * asz + uy * asz * 0.55f
        val rightY = tipY - uy * asz - ux * asz * 0.55f
        val tri = android.graphics.Path()
        tri.moveTo(tipX, tipY)
        tri.lineTo(leftX, leftY)
        tri.lineTo(rightX, rightY)
        tri.close()
        fillPaint.color = color.toArgbNative()
        nc.drawPath(tri, fillPaint)
    }
}

/** 绘制 T / F 标签（原生） */
private fun drawEdgeLabelNative(
    nc: android.graphics.Canvas,
    r: CfgRouter.Route,
    scale: Float
) {
    val p = r.labelAt
    val isT = r.label == "T"
    val color = if (isT) Good else Bad
    val box = 16f

    fillPaint.color = color.copy(alpha = 0.16f).toArgbNative()
    val rect = android.graphics.RectF(p.x - box / 2f, p.y - box / 2f, p.x + box / 2f, p.y + box / 2f)
    nc.drawRoundRect(rect, 4f, 4f, fillPaint)

    routePaint.color = color.toArgbNative()
    routePaint.strokeWidth = strokeW(scale)
    routePaint.pathEffect = null
    val cx = p.x
    val cy = p.y
    // T：横 + 竖；F：横 + 短斜
    nc.drawLine(cx - 4f, cy - 3f, cx + 4f, cy - 3f, routePaint)
    if (isT) {
        nc.drawLine(cx, cy - 3f, cx, cy + 4f, routePaint)
    } else {
        nc.drawLine(cx - 3f, cy - 3f, cx - 1f, cy + 4f, routePaint)
    }
}

/** 绘制基本块（原生，与文字同一坐标系） */
private fun drawBlockNative(
    nc: android.graphics.Canvas,
    block: CfgModel.Block,
    n: com.csdemo.tools.CfgLayout.Node,
    lod: Float,
    isSelected: Boolean,
    titlePaint: Paint,
    addrPaint: Paint,
    codePaint: Paint,
    showFull: Boolean,
    showMnemonicOnly: Boolean,
    scale: Float,
    metrics: CfgModel.TextMetrics
) {
    val borderColor = when {
        isSelected -> SelBorder
        block.isEntry -> Accent
        block.isExit -> Bad
        else -> BlockBorder
    }
    val borderW = if (isSelected || block.isEntry || block.isExit) 2.4f else 1.2f

    // 块体
    fillPaint.color = (if (isSelected) BlockBgSel else BlockBg).toArgbNative()
    val body = android.graphics.RectF(n.x, n.y, n.x + n.width, n.y + n.height)
    nc.drawRoundRect(body, 5f, 5f, fillPaint)

    // 标题栏（高度取自统一度量）
    fillPaint.color = TitleBg.toArgbNative()
    val head = android.graphics.RectF(n.x, n.y, n.x + n.width, n.y + metrics.titleHeight)
    nc.drawRoundRect(head, 5f, 5f, fillPaint)

    // 边框
    routePaint.color = borderColor.toArgbNative()
    routePaint.strokeWidth = borderW / scale
    routePaint.pathEffect = null
    nc.drawRoundRect(body, 5f, 5f, routePaint)

    // 裁剪到块内
    nc.save()
    nc.clipRect(n.x + 1f, n.y + 1f, n.x + n.width - 1f, n.y + n.height - 1f)

    // 标题（基线 = 块顶 + 标题栏高 - 底部留白）
    titlePaint.color = android.graphics.Color.argb(255, 60, 60, 60)
    val titleText = if (block.label.length > 32) block.label.take(32) else block.label
    nc.drawText(titleText, n.x + PAD_X, n.y + metrics.titleHeight - 8f, titlePaint)

    // 入口 / 出口标签
    if (block.isEntry || block.isExit) {
        val tag = if (block.isEntry) "入口" else "出口"
        val tw = titlePaint.measureText(tag)
        titlePaint.color = if (block.isEntry)
            android.graphics.Color.argb(255, 27, 110, 243)
        else
            android.graphics.Color.argb(255, 204, 34, 34)
        nc.drawText(tag, n.x + n.width - tw - 12f, n.y + metrics.titleHeight - 8f, titlePaint)
    }

    // 行高与首行 baseline 均取自统一度量，与 CfgModel 测量完全一致
    val lineH = metrics.lineHeight
    val firstBaseline = n.y + metrics.baselineOffset

    // 太小：只显示地址范围
    if (!showFull && !showMnemonicOnly) {
        addrPaint.color = android.graphics.Color.argb(255, 138, 138, 138)
        val first = block.lines.firstOrNull()
        val last = block.lines.lastOrNull()
        if (first != null && last != null) {
            val txt = "0x" + java.lang.Long.toHexString(first.addr) + " - 0x" +
                    java.lang.Long.toHexString(last.addr)
            nc.drawText(txt, n.x + 12f, firstBaseline, addrPaint)
        }
        nc.restore()
        return
    }

    // 逐行绘制：baseline 由统一度量推导，行距使用真实字体高度
    block.lines.forEachIndexed { idx, ln ->
        val y = firstBaseline + idx * lineH

        // 超出块底就不画（防溢出）
        if (y > n.y + n.height - 2f) return@forEachIndexed

        // 地址列：起点 PAD_X（与测量时一致）
        addrPaint.color = android.graphics.Color.argb(255, 138, 138, 138)
        nc.drawText(
            "0x" + java.lang.Long.toHexString(ln.addr),
            n.x + PAD_X, y + metrics.addrBaselineDelta, addrPaint
        )

        // 指令起点 = 地址列起点 + 地址列宽（必须与 CfgModel 测量公式一致）
        var x = n.x + PAD_X + metrics.addressWidth

        if (showMnemonicOnly) {
            val mnemonic = ln.pieces.firstOrNull()?.text ?: ""
            codePaint.color = lineColor(ln)
            nc.drawText(mnemonic, x, y, codePaint)
        } else {
            for (piece in ln.pieces) {
                codePaint.color = pieceColor(piece.kind, ln)
                nc.drawText(piece.text, x, y, codePaint)
                x += codePaint.measureText(piece.text)
                if (x > n.x + n.width - 10f) break
            }
        }
    }

    nc.restore()
}

/** Compose Color → 原生 int */
private fun Color.toArgbNative(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    )

/** 指令行颜色 */
private fun lineColor(ln: CfgModel.Line): Int = when {
    ln.isCall -> android.graphics.Color.argb(255, 23, 128, 61)
    ln.isBranch -> android.graphics.Color.argb(255, 27, 110, 243)
    ln.isReturn -> android.graphics.Color.argb(255, 204, 34, 34)
    else -> android.graphics.Color.argb(255, 17, 17, 17)
}

/** 片段颜色 */
private fun pieceColor(kind: CfgModel.PieceKind, ln: CfgModel.Line): Int = when (kind) {
    CfgModel.PieceKind.MNEMONIC -> lineColor(ln)
    CfgModel.PieceKind.REG -> android.graphics.Color.argb(255, 15, 123, 108)
    CfgModel.PieceKind.IMM -> android.graphics.Color.argb(255, 176, 90, 0)
    CfgModel.PieceKind.TARGET -> android.graphics.Color.argb(255, 122, 62, 177)
    CfgModel.PieceKind.COMMENT -> android.graphics.Color.argb(255, 138, 138, 138)
    else -> android.graphics.Color.argb(255, 102, 102, 102)
}

// ============================================================
// 旧版 Compose 绘制（保留以避免未使用报错开关，实际不再调用）
// ============================================================

@Suppress("unused")
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRouteLegacy(
    r: CfgRouter.Route,
    color: Color,
    dashed: Boolean,
    lod: Float
) {
    val pts = r.points
    if (pts.size < 2) return

    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) {
            lineTo(pts[i].x, pts[i].y)
        }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = 1.6f,
            cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(7f, 6f)) else null
        )
    )

    // 箭头（指向最后一段的方向）
    val a = pts[pts.size - 2]
    val bPt = pts[pts.size - 1]
    val dx = bPt.x - a.x
    val dy = bPt.y - a.y
    val len = kotlin.math.sqrt(dx * dx + dy * dy)
    if (len > 1f) {
        val ux = dx / len
        val uy = dy / len
        val asz = 7f
        val tipX = bPt.x - ux * 2f
        val tipY = bPt.y - uy * 2f
        val leftX = tipX - ux * asz - uy * asz * 0.6f
        val leftY = tipY - uy * asz + ux * asz * 0.6f
        val rightX = tipX - ux * asz + uy * asz * 0.6f
        val rightY = tipY - uy * asz - ux * asz * 0.6f
        val tri = Path().apply {
            moveTo(tipX, tipY)
            lineTo(leftX, leftY)
            lineTo(rightX, rightY)
            close()
        }
        drawPath(tri, color)
    }
}

/** 绘制边标签 T / F（色块，文字由外部 TextMeasurer 绘制时替换） */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEdgeLabel(
    r: CfgRouter.Route,
    lod: Float
) {
    val p = r.labelAt
    val isT = r.label == "T"
    val color = if (isT) Good else Bad
    val boxSize = 15f

    drawRoundRect(
        color = color.copy(alpha = 0.14f),
        topLeft = Offset(p.x - boxSize / 2f, p.y - boxSize / 2f),
        size = Size(boxSize, boxSize),
        cornerRadius = CornerRadius(4f)
    )
    // 对角短线标记（无文字版本，用几何形状区分 T / F）
    val cx = p.x
    val cy = p.y
    if (isT) {
        // T：横线 + 竖线
        drawLine(color, Offset(cx - 4f, cy - 3f), Offset(cx + 4f, cy - 3f), strokeWidth = 1.6f)
        drawLine(color, Offset(cx, cy - 3f), Offset(cx, cy + 4f), strokeWidth = 1.6f)
    } else {
        // F：横线 + 斜线
        drawLine(color, Offset(cx - 4f, cy - 3f), Offset(cx + 4f, cy - 3f), strokeWidth = 1.6f)
        drawLine(color, Offset(cx - 3f, cy - 3f), Offset(cx - 2f, cy + 4f), strokeWidth = 1.6f)
    }
}

/**
 * 绘制基本块。
 *
 * LOD 分级：
 *   lod >= 0.75：完整指令行
 *   0.45 - 0.75：仅助记符
 *   < 0.45：仅标题与地址
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBlock(
    block: CfgModel.Block,
    n: com.csdemo.tools.CfgLayout.Node,
    lod: Float,
    isSelected: Boolean,
    titlePaint: Paint,
    addrPaint: Paint,
    codePaint: Paint
) {
    val borderColor = when {
        isSelected -> SelBorder
        block.isEntry -> Accent
        block.isExit -> Bad
        else -> BlockBorder
    }
    val borderWidth = if (isSelected || block.isEntry || block.isExit) 2.2f else 1.2f
    val bg = if (isSelected) BlockBgSel else BlockBg

    // 块体
    drawRoundRect(
        color = bg,
        topLeft = Offset(n.x, n.y),
        size = Size(n.width, n.height),
        cornerRadius = CornerRadius(5f)
    )
    // 标题栏底
    drawRoundRect(
        color = TitleBg,
        topLeft = Offset(n.x, n.y),
        size = Size(n.width, 22f),
        cornerRadius = CornerRadius(5f)
    )
    // 边框
    drawRoundRect(
        color = borderColor,
        topLeft = Offset(n.x, n.y),
        size = Size(n.width, n.height),
        cornerRadius = CornerRadius(5f),
        style = Stroke(width = borderWidth)
    )

    // ---------- 文字（裁剪到块内，防止溢出相邻块） ----------
    val nc = drawContext.canvas.nativeCanvas
    nc.save()
    nc.clipRect(n.x + 1f, n.y + 1f, n.x + n.width - 1f, n.y + n.height - 1f)

    // 标题（函数名）
    titlePaint.color = android.graphics.Color.argb(255, 60, 60, 60)
    val titleText = if (block.label.length > 30) block.label.take(30) else block.label
    nc.drawText(titleText, n.x + 12f, n.y + 16f, titlePaint)

    // 入口 / 出口标记
    if (block.isEntry || block.isExit) {
        val tag = if (block.isEntry) "入口" else "出口"
        val tw = titlePaint.measureText(tag)
        titlePaint.color = if (block.isEntry)
            android.graphics.Color.argb(255, 27, 110, 243)
        else
            android.graphics.Color.argb(255, 204, 34, 34)
        nc.drawText(tag, n.x + n.width - tw - 12f, n.y + 15f, titlePaint)
    }

    // 指令行
    val showFull = lod >= 0.75f
    val showMnemonicOnly = lod >= 0.45f && lod < 0.75f

    var lineY = n.y + 22f + 14f
    if (!showMnemonicOnly && lod < 0.45f) {
        // 太小：只显示地址列概览
        addrPaint.color = android.graphics.Color.argb(255, 138, 138, 138)
        val first = block.lines.firstOrNull()
        val last = block.lines.lastOrNull()
        if (first != null && last != null) {
            val txt = "0x" + java.lang.Long.toHexString(first.addr) + " - 0x" +
                    java.lang.Long.toHexString(last.addr)
            nc.drawText(txt, n.x + 12f, lineY, addrPaint)
        }
        nc.restore()
        return
    }

    for (ln in block.lines) {
        // 地址列
        addrPaint.color = android.graphics.Color.argb(255, 138, 138, 138)
        val addrTxt = "0x" + java.lang.Long.toHexString(ln.addr)
        nc.drawText(addrTxt, n.x + 12f, lineY, addrPaint)

        // 地址列宽度固定，保证各块纵向对齐
        var x = n.x + 106f

        if (showMnemonicOnly) {
            // 只画助记符
            val mnemonic = ln.pieces.firstOrNull()?.text ?: ""
            codePaint.color = if (ln.isCall)
                android.graphics.Color.argb(255, 23, 128, 61)
            else if (ln.isBranch)
                android.graphics.Color.argb(255, 27, 110, 243)
            else if (ln.isReturn)
                android.graphics.Color.argb(255, 204, 34, 34)
            else
                android.graphics.Color.argb(255, 17, 17, 17)
            nc.drawText(mnemonic, x, lineY, codePaint)
        } else {
            // 完整行，逐片段着色
            for (p in ln.pieces) {
                codePaint.color = when (p.kind) {
                    CfgModel.PieceKind.MNEMONIC ->
                        if (ln.isCall) android.graphics.Color.argb(255, 23, 128, 61)
                        else if (ln.isBranch) android.graphics.Color.argb(255, 27, 110, 243)
                        else if (ln.isReturn) android.graphics.Color.argb(255, 204, 34, 34)
                        else android.graphics.Color.argb(255, 11, 95, 191)
                    CfgModel.PieceKind.REG ->
                        android.graphics.Color.argb(255, 15, 123, 108)
                    CfgModel.PieceKind.IMM ->
                        android.graphics.Color.argb(255, 176, 90, 0)
                    CfgModel.PieceKind.TARGET ->
                        android.graphics.Color.argb(255, 122, 62, 177)
                    CfgModel.PieceKind.COMMENT ->
                        android.graphics.Color.argb(255, 138, 138, 138)
                    else ->
                        android.graphics.Color.argb(255, 102, 102, 102)
                }
                nc.drawText(p.text, x, lineY, codePaint)
                x += codePaint.measureText(p.text)
                // 超出块宽就不再画
                if (x > n.x + n.width - 8f) break
            }
        }

        lineY += 20f
        if (lineY > n.y + n.height - 6f) break
    }

    nc.restore()
}

