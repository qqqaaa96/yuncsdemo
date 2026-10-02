package com.csdemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.CfgBuilder
import com.csdemo.ui.theme.Bad
import com.csdemo.ui.theme.Good
import com.csdemo.ui.theme.Ink
import com.csdemo.ui.theme.InkFaint
import com.csdemo.ui.theme.InkSoft
import com.csdemo.ui.theme.Line
import com.csdemo.ui.theme.Paper
import com.csdemo.ui.theme.PaperSoft
import com.csdemo.ui.theme.Warn

/**
 * IDA 风格的控制流图。
 *
 * 特点：
 *   • 可双指缩放、单指拖动平移
 *   • 方块节点显示完整反汇编
 *   • 绿色箭头 = 真分支（taken），虚线灰箭头 = 假分支/下落
 *   • 双击自适应屏幕
 */
@Composable
fun IdaCfgView(graph: CfgBuilder.Graph, modifier: Modifier = Modifier) {
    if (graph.blocks.isEmpty()) {
        Box(
            modifier.fillMaxWidth().height(200.dp)
                .background(PaperSoft, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("无可用基本块", fontSize = 12.sp, color = InkSoft)
        }
        return
    }

    val layout = remember(graph) { computeIdaLayout(graph) }

    // 视图变换状态
    var scale by remember(graph) { mutableStateOf(1f) }
    var offsetX by remember(graph) { mutableStateOf(0f) }
    var offsetY by remember(graph) { mutableStateOf(0f) }

    // 把图形平移到可见区域
    fun resetView() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    androidx.compose.runtime.LaunchedEffect(graph) { resetView() }

    Column(modifier.fillMaxWidth()) {
        // 工具栏
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                graph.blocks.size.toString() + " 块 · " + graph.edges.size + " 边",
                fontSize = 11.sp, color = InkSoft,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { scale = (scale * 1.3f).coerceAtMost(4f) }) {
                Text("放大", fontSize = 11.sp)
            }
            TextButton(onClick = { scale = (scale / 1.3f).coerceAtLeast(0.25f) }) {
                Text("缩小", fontSize = 11.sp)
            }
            TextButton(onClick = { resetView() }) {
                Text("复位", fontSize = 11.sp)
            }
        }

        // 图例
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            LegendDot(Good, "真分支")
            Spacer(Modifier.width(12.dp))
            LegendDot(InkFaint, "下落/假分支")
            Spacer(Modifier.width(12.dp))
            LegendDot(com.csdemo.ui.theme.Accent, "入口")
            Spacer(Modifier.width(12.dp))
            LegendDot(Bad, "出口")
        }

        Text(
            "双指缩放 · 单指拖动平移 · 双击复位",
            fontSize = 10.sp, color = InkFaint,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        // 画布
        Box(
            Modifier
                .fillMaxWidth()
                .height(520.dp)
                .background(Paper, RoundedCornerShape(10.dp))
                .border(1.dp, Line, RoundedCornerShape(10.dp))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    }
                    .pointerInput(graph) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.2f, 5f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
                    .pointerInput(graph, scale) {
                        detectTapGestures(
                            onDoubleTap = { resetView() }
                        )
                    }
            ) {
                // 先画边
                Canvas(Modifier.fillMaxSize()) {
                    drawIdaEdges(graph, layout)
                }
                // 再画节点（文字 + 边界），高度按指令数动态计算
                for (b in graph.blocks) {
                    val p = layout.pos[b.start] ?: continue
                    BlockNode(b, p.x, p.y, layout.nodeW, nodeHeight(b))
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(8.dp).background(color, androidx.compose.foundation.shape.CircleShape)
        )
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 10.sp, color = InkSoft)
    }
}

/** 单个方块节点：显示完整反汇编 */
@Composable
private fun BlockNode(b: CfgBuilder.Block, x: Float, y: Float, w: Float, h: Float) {
    val borderColor = when {
        b.isEntry -> com.csdemo.ui.theme.Accent
        b.isExit -> Bad
        else -> Line
    }
    Column(
        Modifier
            .padding(start = x.dp, top = y.dp)
            .width(w.dp)
            .height(h.dp)
            .background(Paper)
            .border(if (b.isEntry || b.isExit) 2.dp else 1.dp, borderColor, RoundedCornerShape(4.dp))
            .padding(5.dp)
    ) {
        // 节点地址标签
        Text(
            "loc_" + java.lang.Long.toHexString(b.start).uppercase(),
            fontSize = 8.sp,
            color = if (b.isEntry) com.csdemo.ui.theme.Accent else InkFaint,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(3.dp))
        // 完整指令
        Column {
            b.insns.forEach { insn ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        javafix(insn.mnemonic),
                        fontSize = 7.sp,
                        color = when {
                            insn.isCall -> Good
                            insn.isBranch -> com.csdemo.ui.theme.Accent
                            insn.isReturn -> Bad
                            else -> Ink
                        },
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(38.dp),
                        maxLines = 1
                    )
                    Text(
                        insn.text.removePrefix(insn.mnemonic).trim(),
                        fontSize = 7.sp,
                        color = Ink,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private fun javafix(s: String): String = s.uppercase()

/** 绘制边（IDA 风格：直角转折 + 箭头） */
private fun DrawScope.drawIdaEdges(graph: CfgBuilder.Graph, layout: IdaLayout) {
    for (e in graph.edges) {
        val from = layout.pos[e.from] ?: continue
        val to = layout.pos[e.to] ?: continue

        val color = when (e.kind) {
            CfgBuilder.EdgeKind.TAKEN -> Good          // 真分支：绿
            CfgBuilder.EdgeKind.CALL -> com.csdemo.ui.theme.Accent
            CfgBuilder.EdgeKind.RETURN -> Bad
            else -> InkFaint                            // 下落：灰
        }
        val dashed = e.kind != CfgBuilder.EdgeKind.TAKEN

        // 起点：本块底部中点；终点：目标块顶部中点（用各自实际高度）
        val fromH = layout.heights[e.from] ?: layout.nodeH
        val sx = from.x + layout.nodeW / 2f
        val sy = from.y + fromH
        val ex = to.x + layout.nodeW / 2f
        val ey = to.y

        // 路径：起点 → 向下 → 水平 → 向下 → 终点
        val midY = sy + (ey - sy) / 2f
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(sx, midY)
            lineTo(ex, midY)
            lineTo(ex, ey)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 1.5f,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5f, 5f)) else null
            )
        )

        // 箭头（三角形）
        val arrowSize = 5f
        val arrow = Path().apply {
            moveTo(ex, ey)
            lineTo(ex - arrowSize, ey - arrowSize)
            lineTo(ex + arrowSize, ey - arrowSize)
            close()
        }
        drawPath(arrow, color)
    }
}

/** 布局结果 */
/** 图上的一个坐标点 */
data class OffsetF(val x: Float, val y: Float)

private data class IdaLayout(
    val pos: Map<Long, OffsetF>,
    val heights: Map<Long, Float>,
    val width: Int,
    val height: Int,
    val nodeW: Float,
    val nodeH: Float
)

/**
 * IDA 风格布局：
 * 按可达深度分层，同层水平排列；
 * 节点高度根据指令条数动态计算。
 */
private fun computeIdaLayout(graph: CfgBuilder.Graph): IdaLayout {
    val nodeW = 200f
    val gapX = 40f
    val gapY = 50f

    // BFS 分层
    val depth = HashMap<Long, Int>()
    val queue = ArrayDeque<Long>()
    queue.add(graph.entry)
    depth[graph.entry] = 0
    var guard = 0
    while (queue.isNotEmpty() && guard < 8000) {
        guard++
        val cur = queue.removeFirst()
        val d = depth[cur] ?: 0
        for (e in graph.edges) {
            if (e.from == cur && !depth.containsKey(e.to)) {
                depth[e.to] = d + 1
                queue.add(e.to)
            }
        }
    }
    var maxD = depth.values.maxOrNull() ?: 0
    for (b in graph.blocks) {
        if (!depth.containsKey(b.start)) {
            maxD++
            depth[b.start] = maxD
        }
    }

    // 分层分组
    val layers = HashMap<Int, MutableList<Long>>()
    for (b in graph.blocks) {
        val d = depth[b.start] ?: 0
        layers.getOrPut(d) { ArrayList() }.add(b.start)
    }

    // 每层 Y 位置（基于每层最大高度）
    val blockMap = graph.blocks.associateBy { it.start }
    val layerY = HashMap<Int, Float>()
    var curY = 20f
    val maxDepth = layers.keys.maxOrNull() ?: 0
    for (d in 0..maxDepth) {
        layerY[d] = curY
        val maxH = layers[d]?.mapNotNull { addr ->
            blockMap[addr]?.let { nodeHeight(it) }
        }?.maxOrNull() ?: 60f
        curY += maxH + gapY
    }

    val pos = HashMap<Long, OffsetF>()
    for ((d, list) in layers) {
        val sorted = list.sorted()
        val y = layerY[d] ?: 0f
        sorted.forEachIndexed { idx, addr ->
            val x = 20f + idx * (nodeW + gapX)
            pos[addr] = OffsetF(x, y)
        }
    }

    // 记录每个节点的实际高度，供画边使用
    val heights = HashMap<Long, Float>()
    for (b in graph.blocks) {
        heights[b.start] = nodeHeight(b)
    }

    val maxX = layers.values.maxOfOrNull { it.size } ?: 1
    return IdaLayout(
        pos = pos,
        heights = heights,
        width = (20f + maxX * (nodeW + gapX)).toInt(),
        height = (curY + 20f).toInt(),
        nodeW = nodeW,
        nodeH = 60f
    )
}

/** 节点高度：8px 标题 + 每条约 11px，至少 50px */
private fun nodeHeight(b: CfgBuilder.Block): Float {
    val lines = b.insns.size.coerceAtLeast(1)
    return (20f + lines * 11f).coerceAtLeast(50f)
}
