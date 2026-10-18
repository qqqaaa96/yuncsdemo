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
 * 规则（按需求）：
 *   · 必须 Root：没 root 不展示检测结果，只提示需要授权；
 *   · 有 root：进页自动检测，右上角可手动刷新。
 *
 * 三种结果：
 *   1) 检测到驱动（红）—— /proc 下 inte_* 节点存在
 *   2) 未检测到（绿）—— 三个节点都不存在
 *   3) 无法判断（灰）—— 有 root 但节点读不到（SELinux 拦截）
 */
@Composable
fun AntiCheatPage() {
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<AntiCheat.Report?>(null) }
    var loading by remember { mutableStateOf(false) }
    var rootReady by remember { mutableStateOf(RootState.useRoot()) }

    fun runDetect() {
        loading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { AntiCheat.detect() }
            report = r
            rootReady = r.hasRoot
            loading = false
        }
    }

    // 进页自动检测
    androidx.compose.runtime.LaunchedEffect(Unit) { runDetect() }

    Column(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // ---- 标题行 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "反作弊检测",
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = pal.ink,
                modifier = Modifier.weight(1f)
            )
            // 刷新
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

        // ---- 无 root：只提示，不展示检测结果 ----
        if (!rootReady) {
            CardBlock {
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
                    "本功能用于检测内核底层的反作弊驱动。\n" +
                            "必须拥有 Root 授权才能读取 /proc 下受保护的系统节点。\n" +
                            "请先切换到 Root 方案并授予权限，然后点击右上角刷新。",
                    fontSize = 12.sp, color = pal.inkSoft, lineHeight = 18.sp
                )
            }
            Spacer(Modifier.height(40.dp))
        } else if (report == null || loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 16.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在检测...", fontSize = 13.sp, color = pal.inkSoft)
            }
            Spacer(Modifier.height(40.dp))
        } else {
            val r = report!!

            // ---- 有驱动 ----
            if (r.anyPresent) {
            CardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已内置反作弊驱动",
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Bad,
                        modifier = Modifier.weight(1f)
                    )
                    // 状态徒标
                    Box(
                        Modifier
                            .background(Bad.copy(alpha = 0.12f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text("Live（活跃运行）", fontSize = 10.sp, color = Bad)
                    }
                }
                Spacer(Modifier.height(14.dp))

                val d = r.driver
                KVRow("驱动模块代号", d?.moduleName ?: "--")
                KVRow("驱动内存占用", d?.memoryBytes?.let { "$it 字节" } ?: "--")
                KVRow(
                    "sysfs 模块路径",
                    if (d?.sysfsRegistered == true) "${d.sysfsPath}（已注册）" else "未注册"
                )
                KVRow("内核上报特征", d?.modulesLine ?: "--", mono = true)
            }
            Spacer(Modifier.height(18.dp))
        }

        // ---- 底层保护节点 ----
        Text("底层保护节点（/proc）", fontSize = 12.sp, color = pal.inkSoft)
        Spacer(Modifier.height(8.dp))
        CardBlock {
            r.nodes.forEachIndexed { i, n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(7.dp).background(
                            when (n.exists) {
                                true -> Bad
                                false -> pal.inkFaint
                                else -> pal.inkFaint
                            }, CircleShape
                        )
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        n.path + ": " + n.detail,
                        fontSize = 13.sp,
                        color = when (n.exists) {
                            true -> pal.ink
                            false -> pal.inkSoft
                            else -> pal.inkSoft
                        },
                        lineHeight = 18.sp,
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

        // ---- 结论 ----
        when {
            r.anyPresent -> {
                Text(
                    "该设备当前运行着内核级反作弊驱动。\n" +
                            "相关节点由系统安全策略保护，普通应用无法读取。",
                    fontSize = 12.sp, color = Bad, lineHeight = 18.sp
                )
            }
            r.anyUnknown -> {
                Text(
                    "部分节点无法读取（可能被 SELinux 拦截），\n" +
                            "因此无法给出确定结论。\n" +
                            "可尝试在 Root 管理器里确认 su 是否确实以 uid=0 运行后重试。",
                    fontSize = 12.sp, color = pal.inkSoft, lineHeight = 18.sp
                )
            }
            else -> {
                CardBlock {
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
                        "已检查 /proc 下的 inte_ko、inte_status、inte_systbl，\n" +
                                "三个节点均不存在。",
                        fontSize = 12.sp, color = pal.inkSoft, lineHeight = 18.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

// ---------------- 小组件 ----------------

@Composable
private fun CardBlock(content: @Composable () -> Unit) {
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
private fun KVRow(label: String, value: String, mono: Boolean = false) {
    val pal = LocalPalette.current
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
