package sy.great.toolbox.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sy.great.toolbox.tools.FpsRecorder
import sy.great.toolbox.ui.theme.LocalPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 帧率历史记录。
 *
 * 两级：
 *   1) 列表页：每条记录一行（miuix ArrowPreference，与设置页同款），
 *      显示结束时间（精确到秒）+ 时长 + 采样点数。
 *   2) 详情页：点某一条后，用与“录制结果”同款的 5 张卡片展示该次数据。
 *
 * 数据来自 FpsRecorder 的历史文件（filesDir/fps_history）。
 */
@Composable
fun FpsHistoryScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<FpsRecorder.HistoryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // 当前打开的那一条（null = 在列表页）
    var opened by remember { mutableStateOf<FpsRecorder.HistoryItem?>(null) }
    var detail by remember { mutableStateOf<List<FpsRecorder.Sample>>(emptyList()) }

    /** 重读列表 */
    fun reload() {
        scope.launch {
            loading = true
            val list = withContext(Dispatchers.IO) {
                FpsRecorder.listHistory(ctx)
            }
            items = list
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    // ---------- 详情页 ----------
    val cur = opened
    if (cur != null) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            PageHeader("录制详情", onBack = { opened = null })
            Spacer(Modifier.height(6.dp))
            Text(
                "结束时间 " + fmtTime(cur.endedAtMs) + "　时长 " + fmtDur(cur.durMs),
                fontSize = 11.sp, color = pal.inkSoft,
            )
            Spacer(Modifier.height(14.dp))

            if (detail.isEmpty()) {
                Text("该记录没有可显示的数据。", fontSize = 12.sp, color = pal.inkSoft)
            } else {
                // 与“刷新率悬浮窗”页同款的 5 张卡片
                FpsChartCard(samples = detail, title = "历史录制结果")
            }

            Spacer(Modifier.height(40.dp))
        }
        return
    }

    // ---------- 列表页 ----------
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        PageHeader("帧率记录", onBack)
        Spacer(Modifier.height(6.dp))
        Text(
            "每次录制结束后自动保存，最多保留 100 条。",
            fontSize = 11.sp, color = pal.inkSoft,
        )
        Spacer(Modifier.height(14.dp))

        if (loading) {
            Text("读取中...", fontSize = 12.sp, color = pal.inkSoft)
        } else if (items.isEmpty()) {
            Text(
                "还没有历史记录。\n长按悬浮窗 2 秒开始录制，停止后就会出现在这里。",
                fontSize = 12.sp, color = pal.inkSoft, lineHeight = 17.sp,
            )
        } else {
            // 与设置页同款：miuix 的横向可点击行
            top.yukonga.miuix.kmp.basic.Card(
                modifier = Modifier.fillMaxWidth(),
                content = {
                    items.forEach { it0 ->
                        top.yukonga.miuix.kmp.preference.ArrowPreference(
                            title = fmtTime(it0.endedAtMs),
                            summary = "时长 " + fmtDur(it0.durMs) + "　" + it0.count + " 个采样点",
                            onClick = {
                                opened = it0
                                detail = emptyList()
                                scope.launch {
                                    detail = withContext(Dispatchers.IO) {
                                        FpsRecorder.loadHistory(ctx, it0.id)
                                    }
                                }
                            },
                        )
                    }
                }
            )
        }

        Spacer(Modifier.height(40.dp))
    }
}

// ---------------- 格式化 ----------------

/** 结束时间：精确到秒 */
private fun fmtTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

private fun fmtDur(ms: Long): String {
    val totalSec = (ms / 1000L).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) m.toString() + "分" + s + "秒" else s.toString() + " 秒"
}
