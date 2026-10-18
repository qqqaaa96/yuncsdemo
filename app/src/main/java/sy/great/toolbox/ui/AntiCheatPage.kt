package sy.great.toolbox.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sy.great.toolbox.tools.AntiCheat
import sy.great.toolbox.tools.RootState
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.LocalPalette

/**
 * 反作弊检测页。
 *
 * 规则：
 *   · 必须 Root：没 root 只提示需要授权，不展示检测结果；
 *   · 有 root：进页自动检测，右上角可手动刷新。
 *
 * 三种结果：
 *   1) 检测到驱动（红）
 *   2) 未检测到（绿）
 *   3) 无法判断（灰，如 SELinux 拦截）
 */
@Composable
fun AntiCheatPage() {
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<AntiCheat.Report?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun runDetect() {
        loading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { AntiCheat.detect() }
            report = r
            loading = false
        }
    }

    LaunchedEffect(Unit) { runDetect() }

    Column(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 标题 + 刷新
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "反作弊检测",
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = pal.ink,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .size(38.dp)
                    .background(pal.paperSoft, CircleShape)
                    .pressable(pressedScale = 0.92f) { if (!loading) runDetect() },
                contentAlignment = Alignment.Center
            ) {
                Text("\u21BB", fontSize = 17.sp, color = Accent)
            }
        }
        Spacer(Modifier.height(16.dp))

        val r = report

        // 分支一：未就绪
        if (r == null || loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 16.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在检测...", fontSize = 13.sp, color = pal.inkSoft)
            }
            Spacer(Modifier.height(40.dp))
        } else if (!r.hasRoot) {
            // 分支二：无 Root
            NoRootBlock(pal)
            Spacer(Modifier.height(40.dp))
        } else {
            // 分支三：有 Root，展示结果
            RootResultBlock(r, pal)
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ---------------- 无 Root ----------------

@Composable
private fun NoRootBlock(pal: sy.great.toolbox.ui.theme.Palette) {
    BlockCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(pal.inkFaint, CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(
                "需要 Root 权限",
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = pal.ink
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "本功能需要读取 /proc 下受系统保护的反作弊节点，\n" +
                    "必须拥有 Root 授权。\n" +
                    "请先切换到 Root 方案并授予权限，然后点右上角刷新。",
            fontSize = 12.sp, color = pal.inkSoft, lineHeight = 18.sp
        )
    }
}

// ---------------- 有 Root：展示结果 ----------------

@Composable
private fun RootResultBlock(r: AntiCheat.Report, pal: sy.great.toolbox.ui.theme.Palette) {
    // 1) 检测到驱动：红色卡片
    if (r.anyPresent) {
        DriverCard(r, pal)
        Spacer(Modifier.height(18.dp))
    }

    // 2) 底层保护节点
    Text("底层保护节点（/proc）", fontSize = 12.sp, color = pal.inkSoft)
    Spacer(Modifier.height(8.dp))
    BlockCard {
        r.nodes.forEachIndexed { i, n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dot = when (n.exists) {
                    true -> Bad
                    false -> pal.inkFaint
                    null -> pal.inkFaint
                }
                Box(Modifier.size(7.dp).background(dot, CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(
                    n.path + ": " + n.detail,
                    fontSize = 13.sp,
                    color = if (n.exists == true) pal.ink else pal.inkSoft,
                    lineHeight = 18.sp
                )
            }
            if (i != r.nodes.lastIndex) {
                Spacer(Modifier.height(10.dp))
                HLine()
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    Spacer(Modifier.height(18.dp))

    // 3) 结论
    if (r.anyPresent) {
        Text(
            "该设备当前运行着内核级反作弊驱动。\n" +
                    "相关节点由系统安全策略保护，普通应用无法读取。",
            fontSize = 12.sp, color = Bad, lineHeight = 18.sp
        )
    } else if (r.anyUnknown) {
        Text(
            "部分节点无法读取（可能被 SELinux 拦截），\n" +
                    "因此无法给出确定结论。可检查 su 是否以 uid=0 运行后重试。",
            fontSize = 12.sp, color = pal.inkSoft, lineHeight = 18.sp
        )
    } else {
        BlockCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(Good, CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(
                    "设备未与反作弊系统链接",
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Good
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "已检查 inte_ko、inte_status、inte_systbl，三个节点均不存在。",
                fontSize = 12.sp, color = pal.inkSoft, lineHeight = 18.sp
            )
        }
    }
}

// ---------------- 驱动卡片 ----------------

@Composable
private fun DriverCard(r: AntiCheat.Report, pal: sy.great.toolbox.ui.theme.Palette) {
    val d = r.driver
    BlockCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已内置反作弊驱动",
                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Bad,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .background(Bad.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text("Live（活跃运行）", fontSize = 10.sp, color = Bad)
            }
        }
        Spacer(Modifier.height(14.dp))

        InfoRow("驱动模块代号", d?.moduleName ?: "--", pal)
        InfoRow("驱动内存占用", d?.memoryBytes?.let { it.toString() + " 字节" } ?: "--", pal)
        InfoRow(
            "sysfs 模块路径",
            if (d?.sysfsRegistered == true) (d.sysfsPath ?: "") + "（已注册）" else "未注册",
            pal
        )
        InfoRow("内核上报特征", d?.modulesLine ?: "--", pal, mono = true)
    }
}

// ---------------- 小组件 ----------------

@Composable
private fun BlockCard(content: @Composable () -> Unit) {
    val pal = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        content()
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    pal: sy.great.toolbox.ui.theme.Palette,
    mono: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, fontSize = 12.sp, color = pal.inkSoft, modifier = Modifier.width(110.dp))
        Text(
            value,
            fontSize = 12.sp,
            color = pal.ink,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
