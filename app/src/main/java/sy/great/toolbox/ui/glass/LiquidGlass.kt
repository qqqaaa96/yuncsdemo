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
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/**
 * 液态玻璃（Liquid Glass）绘制魔改。
 *
 * 移植自 Kyant0/AndroidLiquidGlass（Apache 2.0），
 * 但去掉了对 JetBrains Compose Multiplatform 的依赖，
 * 全部改用 AndroidX Compose 的公开 API 实现。
 *
 * 工作方式：
 *   1. 把“背景”录到一个 GraphicsLayer（由调用方通过 GlassBackdrop 提供）
 *   2. 在卡片位置用 RuntimeShader 对背景做折射采样
 *   3. 把结果画到卡片上
 *
 * 降级：
 *   · Android 13 以下没有 RuntimeShader → 自动退化为普通磨砂
 */

/** 是否支持 RuntimeShader（Android 13+） */
val isRuntimeShaderSupported: Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * 背景采样层。
 *
 * 持有录制好的背景图层，供卡片采样。
 */
class GlassBackdrop internal constructor(
    val layer: GraphicsLayer,
) {
    /** 背景层在窗口中的位置，用于换算采样坐标 */
    internal var layerPosition: Offset = Offset.Zero
}

/** 折射参数 */
data class GlassLens(
    /** 折射高度（边缘变形区域的高度，px） */
    val refractionHeight: Float,
    /** 折射强度（偏移量，px） */
    val refractionAmount: Float,
    /** 圆角半径（px），用于 shader 的 cornerRadii */
    val cornerRadius: Float,
    /** 深度效果：中心的球面感 */
    val depthEffect: Boolean = false,
)

/**
 * 把当前内容录制为背景层。
 *
 * 用法：把需要被玻璃采样的背景内容（列表背景）包在 [glassBackdrop] 里。
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
        // 把内容录进 GraphicsLayer 供卡片采样。
        //
        // record 的 lambda 里只录制、不实际显示，
        // 所以录制完还要在外面 drawContent() 真正画一遍。
        // （这两个不是重复绘制：record 只写离屏图层）
        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w > 0 && h > 0) {
            try {
                backdrop.layer.record(size = IntSize(w, h)) {
                    this@draw.drawContent()
                }
            } catch (_: Throwable) {
                // 录制失败不影响正常显示
            }
        }
        drawContent()
    }
}

/**
 * 液态玻璃卡片背景。
 *
 * @param backdrop 背景采样层
 * @param shape    卡片形状（必须是 RoundedCornerShape，用于计算 cornerRadii）
 * @param lens     折射参数
 */
fun Modifier.glassLens(
    backdrop: GlassBackdrop?,
    shape: Shape,
    lens: GlassLens,
): Modifier = this then GlassLensElement(backdrop, shape, lens)

private class GlassLensElement(
    val backdrop: GlassBackdrop?,
    val shape: Shape,
    val lens: GlassLens,
) : ModifierNodeElement<GlassLensNode>() {

    override fun create() = GlassLensNode(backdrop, shape, lens)

    override fun update(node: GlassLensNode) {
        node.backdrop = backdrop
        node.shape = shape
        node.lens = lens
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
) : DrawModifierNode, Modifier.Node() {

    // 着色器只创建一次，缓存复用
    private var shader: RuntimeShader? = null
    private var shaderFailed = false

    override fun ContentDrawScope.draw() {
        val bd = backdrop

        // 无背景 / 系统不支持 / 曾经失败 → 直接画内容，绝不崩
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

        val radiusPx = lens.cornerRadius

        try {
            val rs = shader ?: RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER).also {
                shader = it
            }

            rs.setFloatUniform("size", w, h)
            rs.setFloatUniform("offset", 0f, 0f)
            rs.setFloatUniform("cornerRadii", radiusPx, radiusPx, radiusPx, radiusPx)
            rs.setFloatUniform("refractionHeight", lens.refractionHeight)
            rs.setFloatUniform("refractionAmount", -lens.refractionAmount)
            rs.setFloatUniform("depthEffect", if (lens.depthEffect) 1f else 0f)

            // 关键：把背景层画到一个临时 GraphicsLayer，
            // 给它加上 RuntimeShader RenderEffect，再把该层 draw 上来。
            //
            // 不能直接把 RenderEffect 丢给 native Canvas 画：
            // RenderEffect 需要生效在一个离屏图层上，
            // GraphicsLayer 的 renderEffect 属性正是干这个的。
            val renderLayer = renderLayerCache
                ?: androidx.compose.ui.graphics.layer.GraphicsLayer().also {
                    renderLayerCache = it
                }

            renderLayer.record(size = IntSize(w.toInt(), h.toInt())) {
                // 在图层内部，把背景层平移到卡片自身的坐标系原点
                translate(-bd.layerPosition.x, -bd.layerPosition.y) {
                    drawLayer(bd.layer)
                }
            }

            renderLayer.renderEffect = android.graphics.RenderEffect
                .createRuntimeShaderEffect(rs, "content")
                .asComposeRenderEffect()

            drawLayer(renderLayer)

            // 卡片文字等再画在上面
            drawContent()
        } catch (_: Throwable) {
            // 任何着色器 / GPU 兼容问题 → 永久降级，不允许崩
            shaderFailed = true
            shader = null
            renderLayerCache?.renderEffect = null
            drawContent()
        }
    }

    /** 复用的离屏图层，避免每帧新建 */
    private var renderLayerCache: GraphicsLayer? = null
}
