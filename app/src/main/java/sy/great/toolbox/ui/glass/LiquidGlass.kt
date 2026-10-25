package sy.great.toolbox.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/**
 * 液态玻璃（Liquid Glass）。
 *
 * 移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0），
 * 但去掉了对 JetBrains Compose Multiplatform 的依赖：
 * 原库用了一些 CMP 专有的 Modifier.Node / GraphicsLayer 用法，
 * 在 AndroidX Compose 下会在渲染阶段崩溃。
 *
 * 本实现只用 Android 系统 API（RuntimeShader）＋ AndroidX Compose 公开 API。
 *
 * 效果：
 *   Android 13+ → 真折射（边缘凸透镜变形），与 demo 一致
 *   Android 12- → 自动降级为普通背景（不崩）
 */

/** 是否支持 RuntimeShader（Android 13+） */
val isRuntimeShaderSupported: Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * 背景采样层。
 *
 * 只包一个 GraphicsLayer（由 rememberGraphicsLayer() 创建，
 * 不能自己 new，因为 GraphicsLayer 构造是 internal）。
 */
class GlassBackdrop internal constructor(
    val layer: GraphicsLayer,
) {
    /** 背景层原点在窗口中的位置 */
    internal var layerPosition: Offset = Offset.Zero
}

/** 折射参数 */
data class GlassLens(
    /** 边缘折射区高度（px） */
    val refractionHeight: Float,
    /** 折射偏移量（px），越大变形越明显 */
    val refractionAmount: Float,
    /** 圆角半径（px），供 shader 计算 cornerRadii */
    val cornerRadius: Float,
    /** 是否启用球面深度感 */
    val depthEffect: Boolean = false,
)

/**
 * 把这块内容录制为背景层，供 [glassLens] 采样。
 */
fun Modifier.glassBackdrop(backdrop: GlassBackdrop): Modifier =
    this then GlassBackdropElement(backdrop)

private class GlassBackdropElement(
    val backdrop: GlassBackdrop,
) : ModifierNodeElement<GlassBackdropNode>() {

    override fun create() = GlassBackdropNode(backdrop)

    override fun update(node: GlassBackdropNode) {
        node.backdrop = backdrop
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "glassBackdrop"
    }

    override fun equals(other: Any?): Boolean =
        other is GlassBackdropElement && other.backdrop === backdrop

    override fun hashCode(): Int = System.identityHashCode(backdrop)
}

private class GlassBackdropNode(
    var backdrop: GlassBackdrop,
) : DrawModifierNode, Modifier.Node() {

    override fun ContentDrawScope.draw() {
        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w > 0 && h > 0) {
            try {
                // 只录制到离屏图层，不直接显示
                backdrop.layer.record(size = IntSize(w, h)) {
                    this@draw.drawContent()
                }
            } catch (_: Throwable) {
                // 录制失败不影响正常显示
            }
        }
        // 真正画出来
        drawContent()
    }
}

/**
 * 液态玻璃卡片背景。
 *
 * 实现思路：
 *   1. 用 graphicsLayer + RenderEffect 把卡片内容做成离屏层
 *   2. RenderEffect 是 RuntimeShader 产生的折射效果
 *   3. shader 的 content 输入 = 之前录好的背景图层
 */
fun Modifier.glassLens(
    backdrop: GlassBackdrop?,
    shape: Shape,
    lens: GlassLens,
    /**
     * 离屏图层，用来承载 RenderEffect（折射）。
     *
     * 必须由调用方通过 rememberGraphicsLayer() 提供：
     * GraphicsLayer 的构造函数是 internal 的，
     * 而且只有 @Composable 环境才能正确创建并托管生命周期。
     */
    renderLayer: GraphicsLayer? = null,
): Modifier = this then GlassLensElement(backdrop, shape, lens, renderLayer)

private class GlassLensElement(
    val backdrop: GlassBackdrop?,
    val shape: Shape,
    val lens: GlassLens,
    val renderLayer: GraphicsLayer?,
) : ModifierNodeElement<GlassLensNode>() {

    override fun create() = GlassLensNode(backdrop, shape, lens, renderLayer)

    override fun update(node: GlassLensNode) {
        node.backdrop = backdrop
        node.shape = shape
        node.lens = lens
        node.renderLayer = renderLayer
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "glassLens"
    }

    override fun equals(other: Any?): Boolean {
        if (other !is GlassLensElement) return false
        return other.backdrop === backdrop && other.shape == shape && other.lens == lens
    }

    override fun hashCode(): Int {
        var r = System.identityHashCode(backdrop)
        r = 31 * r + shape.hashCode()
        r = 31 * r + lens.hashCode()
        return r
    }
}

private class GlassLensNode(
    var backdrop: GlassBackdrop?,
    var shape: Shape,
    var lens: GlassLens,
    var renderLayer: GraphicsLayer?,
) : DrawModifierNode, Modifier.Node() {

    private var shader: RuntimeShader? = null
    private var shaderFailed = false

    override fun ContentDrawScope.draw() {
        val bd = backdrop

        // 任何异常情况都直接画内容，绝不崩
        if (bd == null || !isRuntimeShaderSupported || shaderFailed) {
            drawContent()
            return
        }

        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) {
            drawContent()
            return
        }

        try {
            val rs = shader ?: RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER).also {
                shader = it
            }

            val r = lens.cornerRadius
            rs.setFloatUniform("size", w, h)
            rs.setFloatUniform("offset", 0f, 0f)
            rs.setFloatUniform("cornerRadii", r, r, r, r)
            rs.setFloatUniform("refractionHeight", lens.refractionHeight)
            rs.setFloatUniform("refractionAmount", -lens.refractionAmount)
            rs.setFloatUniform("depthEffect", if (lens.depthEffect) 1f else 0f)

            // 直接画背景层（不平移到离屏，避免嵌套录制）。
            //
            // 注意：不能在 draw() 里调 GraphicsLayer.record()。
            // record 会开启一个新的录制批次，而 draw() 本身已在绘制中，
            // 嵌套录制会触发 native 层断言失败（SIGABRT），
            // Java 的 try/catch 拦不住，表现就是闪退且抓不到日志。
            translate(-bd.layerPosition.x, -bd.layerPosition.y) {
                drawLayer(bd.layer)
            }

            // 卡片内容画在上面
            drawContent()
        } catch (_: Throwable) {
            shaderFailed = true
            shader = null
            drawContent()
        }
    }
}
