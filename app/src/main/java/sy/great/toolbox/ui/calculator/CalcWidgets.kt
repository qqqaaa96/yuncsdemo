package sy.great.toolbox.ui.calculator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.ui.pressable
import sy.great.toolbox.ui.theme.LocalPalette

/**
 * 计算器按键的视觉类型。
 */
enum class KeyKind {
    /** 数字 */
    NUM,
    /** 运算符（+ − × ÷） */
    OP,
    /** 功能键（sin / AC / 括号等） */
    FN,
    /** 等号 */
    EQ,
    /** 删除 */
    DEL,
}

/** 显示屏：上方表达式 + 下方结果 */
@Composable
internal fun CalcDisplay(expr: String, result: String, isError: Boolean = false) {
    val pal = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(14.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = expr.ifBlank { " " },
            fontSize = 13.sp,
            color = pal.inkSoft,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = result,
            fontSize = if (isError) 17.sp else 30.sp,
            fontWeight = FontWeight.Bold,
            color = if (isError) pal.inkSoft else pal.ink,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 单个按键 */
@Composable
internal fun CalcKey(
    label: String,
    kind: KeyKind = KeyKind.NUM,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val pal = LocalPalette.current
    val bg = when (kind) {
        KeyKind.EQ -> pal.accent
        else -> pal.paperSoft
    }
    val fg = when (kind) {
        KeyKind.EQ -> Color.White
        KeyKind.OP -> pal.accent
        KeyKind.FN -> pal.accent
        KeyKind.DEL -> pal.inkSoft
        KeyKind.NUM -> pal.ink
    }
    val fs = when (kind) {
        KeyKind.FN -> 13.sp
        KeyKind.DEL -> 17.sp
        else -> 18.sp
    }
    Box(
        modifier
            .height(54.dp)
            .background(bg, RoundedCornerShape(12.dp))
            .pressable(pressedScale = 0.94f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = fs,
            fontWeight = if (kind == KeyKind.OP || kind == KeyKind.EQ || kind == KeyKind.FN)
                FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
        )
    }
}

/**
 * 按键网格。
 *
 * rows 为每行按键，每行元素均匀分配宽度。
 */
@Composable
internal fun KeyGrid(
    rows: List<List<Triple<String, KeyKind, () -> Unit>>>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                row.forEach { (label, kind, action) ->
                    CalcKey(
                        label = label,
                        kind = kind,
                        modifier = Modifier.weight(1f),
                        onClick = action,
                    )
                }
            }
        }
    }
}

/** 结果输出框 */
@Composable
internal fun CalcOutput(text: String, isError: Boolean = false) {
    val pal = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(12.dp))
            .padding(13.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = if (isError) pal.inkSoft else pal.ink,
            fontFamily = FontFamily.Monospace,
            lineHeight = 21.sp,
        )
    }
}

/** 主操作按钮 */
@Composable
internal fun CalcButton(
    text: String,
    primary: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val pal = LocalPalette.current
    Box(
        modifier
            .background(
                if (primary) pal.accent else pal.paperSoft,
                RoundedCornerShape(12.dp)
            )
            .pressable(pressedScale = 0.95f, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) Color.White else pal.ink,
        )
    }
}
