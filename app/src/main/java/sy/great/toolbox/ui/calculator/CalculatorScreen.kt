package sy.great.toolbox.ui.calculator

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.ui.PageHeader
import sy.great.toolbox.ui.pressable
import sy.great.toolbox.ui.theme.LocalPalette

/**
 * 计算器主壳（9 个子计算器，纯 Kotlin 实现）。
 *
 * 结构：返回栏 + 横向标签栏 + 当前子面板
 *
 * 每个子面板一个独立文件，互不干扰：
 *   BasicCalc / SciCalc / GraphCalc / EquationCalc / MatrixCalc
 *   StatCalc / BaseCalc / UnitCalc / GeometryCalc
 */
@Composable
fun CalculatorScreen(onBack: () -> Unit = {}) {
    val pal = LocalPalette.current

    var tab by remember { mutableStateOf(0) }

    val tabs = listOf(
        "普通" to 0,
        "科学" to 1,
        "函数图像" to 2,
        "方程" to 3,
        "矩阵" to 4,
        "统计" to 5,
        "进制" to 6,
        "单位" to 7,
        "几何" to 8,
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            PageHeader(title = "计算器", onBack = onBack)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            tabs.forEach { (name, idx) ->
                val on = tab == idx
                Box(
                    Modifier
                        .background(
                            if (on) pal.accent else pal.paperSoft,
                            RoundedCornerShape(15.dp)
                        )
                        .pressable(pressedScale = 0.95f) { tab = idx }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        name,
                        fontSize = 12.5.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) Color.White else pal.inkSoft,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> BasicCalc()
                1 -> SciCalc()
                2 -> GraphCalc()
                3 -> EquationCalc()
                4 -> MatrixCalc()
                5 -> StatCalc()
                6 -> BaseCalc()
                7 -> UnitCalc()
                else -> GeometryCalc()
            }
        }
    }
}

/** 可滚动内容容器 */
@Composable
internal fun CalcPage(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        content()
        Spacer(Modifier.height(28.dp))
    }
}

/** 输入区标题 */
@Composable
internal fun CalcLabel(text: String) {
    val pal = LocalPalette.current
    Text(
        text,
        fontSize = 11.5.sp,
        color = pal.inkSoft,
        modifier = Modifier.padding(bottom = 5.dp, top = 4.dp),
    )
}
