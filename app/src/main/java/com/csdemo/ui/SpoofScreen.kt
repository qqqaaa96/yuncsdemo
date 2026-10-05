package com.csdemo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.DeviceSpoof
import com.csdemo.tools.SpoofData
import com.csdemo.ui.theme.Bad
import com.csdemo.ui.theme.Good
import com.csdemo.ui.theme.Ink
import com.csdemo.ui.theme.InkFaint
import com.csdemo.ui.theme.InkSoft
import com.csdemo.ui.theme.Line
import com.csdemo.ui.theme.LocalPalette
import com.csdemo.ui.theme.Paper
import com.csdemo.ui.theme.PaperSoft
import com.csdemo.ui.theme.Warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RealProps(
    val model: String,
    val brand: String,
    val manufacturer: String,
    val device: String,
    val product: String,
    val board: String,
    val platform: String,
    val hardware: String,
    val socModel: String,
    val socManufacturer: String
)

@Composable
fun SpoofScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var real by remember { mutableStateOf<RealProps?>(null) }
    var chip by remember { mutableStateOf<SpoofData.Chip?>(null) }
    var phone by remember { mutableStateOf<SpoofData.Phone?>(null) }
    var chipOpen by remember { mutableStateOf(false) }
    var phoneOpen by remember { mutableStateOf(false) }
    var customModel by remember { mutableStateOf("") }
    var customBrand by remember { mutableStateOf("") }
    var customSoc by remember { mutableStateOf("") }

    var applying by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DeviceSpoof.ApplyResult?>(null) }
    var restoreResult by remember { mutableStateOf<DeviceSpoof.ApplyResult?>(null) }
    var msg by remember { mutableStateOf("") }
    var hasResetprop by remember { mutableStateOf<Boolean?>(null) }
    var spoofed by remember { mutableStateOf(false) }
    val rootState = rememberRootState()

    fun load() {
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                RealProps(
                    DeviceSpoof.readProp("ro.product.model"),
                    DeviceSpoof.readProp("ro.product.brand"),
                    DeviceSpoof.readProp("ro.product.manufacturer"),
                    DeviceSpoof.readProp("ro.product.device"),
                    DeviceSpoof.readProp("ro.product.name"),
                    DeviceSpoof.readProp("ro.product.board"),
                    DeviceSpoof.readProp("ro.board.platform"),
                    DeviceSpoof.readProp("ro.hardware"),
                    DeviceSpoof.readProp("ro.soc.model"),
                    DeviceSpoof.readProp("ro.soc.manufacturer")
                )
            }
            real = data
            hasResetprop = withContext(Dispatchers.IO) { DeviceSpoof.hasResetprop() }
            spoofed = withContext(Dispatchers.IO) { DeviceSpoof.isSpoofed(ctx) }
        }
    }

    // 轻型刷新：应用/复原伪装之后同步“当前属性”卡。
    //
    // 与 load() 的区别：
    //   · 不重算 hasResetprop（写入方式不会因此改变）；
    //   · 不重算 isSpoofed（调用方刚已算过）。
    // 这两项都会起 extra 子进程（hasResetprop 最多 2 个 su，
    // isSpoofed 要逐条 readProp 再跑一遍），是主要的浪费。
    //
    // 说明：RealProps 的 10 个字段必须全部填满（结构决定），
    // 所以这里仍然有 10 次 readProp，这部分省不掉。
    // 收益主要是去掉 hasResetprop + isSpoofed 那一轮。
    // 目的：避免用户点完立刻退出时，重活正好撞上退出动画。
    fun refreshReal() {
        scope.launch {
            real = withContext(Dispatchers.IO) {
                RealProps(
                    DeviceSpoof.readProp("ro.product.model"),
                    DeviceSpoof.readProp("ro.product.brand"),
                    DeviceSpoof.readProp("ro.product.manufacturer"),
                    DeviceSpoof.readProp("ro.product.device"),
                    DeviceSpoof.readProp("ro.product.name"),
                    DeviceSpoof.readProp("ro.product.board"),
                    DeviceSpoof.readProp("ro.board.platform"),
                    DeviceSpoof.readProp("ro.hardware"),
                    DeviceSpoof.readProp("ro.soc.model"),
                    DeviceSpoof.readProp("ro.soc.manufacturer")
                )
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        PageHeader("设备伪装", onBack)
        Spacer(Modifier.height(4.dp))
        Text("改写系统属性，重启自动恢复", fontSize = 11.sp, color = LocalPalette.current.inkSoft)
        Spacer(Modifier.height(14.dp))
        RootBanner(rootState)

        // ---- 运行参数（折叠） ----
        val hrp = hasResetprop
        FoldCard(
            title = "运行参数",
            icon = "\u2261",
            subtitle = when (hrp) {
                null -> "检测中..."
                true -> "resetprop 可用"
                else -> "无 resetprop，仅能尝试 setprop"
            }
        ) {
            FieldLabel("写入方式", "resetprop 为内存级改写")
            Spacer(Modifier.height(8.dp))
            SegmentedControl(
                options = listOf("resetprop", "setprop"),
                selectedIndex = if (hrp == true) 0 else 1,
                onSelect = { }
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).background(
                        if (hrp == true) Good else Warn, CircleShape
                    )
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (hrp == true) "重启后属性自动恢复原值"
                    else "ro.* 属性可能无法改写，属正常限制",
                    fontSize = 12.sp, color = LocalPalette.current.inkSoft, lineHeight = 17.sp
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 电量伪装 ----
        FoldCard(
            title = "电量伪装",
            icon = "\u26A1",
            subtitle = "正常伪装 1~100 · 沙雕伪装 0~999999",
        ) {
            // 模式选择
            var battMode by remember { mutableStateOf(0) }
            val mode = if (battMode == 0) DeviceSpoof.BatteryMode.NORMAL else DeviceSpoof.BatteryMode.FUNNY
            SegmentedControl(
                options = listOf("正常伪装", "沙雕伪装"),
                selectedIndex = battMode,
                onSelect = { battMode = it }
            )
            Spacer(Modifier.height(12.dp))

            // 数值滑块
            var battValue by remember(battMode) {
                mutableStateOf(if (battMode == 0) 50 else 8888)
            }
            LabeledSlider(
                label = "伪装电量",
                valueText = if (battValue > 100) battValue.toString() + "%（将按系统上限 100 生效）"
                else battValue.toString() + "%",
                value = battValue.toFloat(),
                range = mode.min.toFloat()..mode.max.toFloat(),
                steps = if (battMode == 0) 100 else 20,
                onValueChange = { battValue = it.toInt() }
            )
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    text = "应用电量伪装",
                    onClick = {
                        applying = true
                        msg = ""
                        scope.launch {
                            // 关键：Shizuku 的 bindUserService 必须在主线程调用，
                            // 否则绑定失败，导致 ADB 路径不生效。
                            if (!com.csdemo.tools.RootState.useRoot() &&
                                com.csdemo.tools.AdbShell.granted()
                            ) {
                                try {
                                    com.csdemo.tools.AdbShell.ensureService(ctx.applicationContext)
                                } catch (_: Throwable) {
                                }
                            }
                            val r = withContext(Dispatchers.IO) {
                                DeviceSpoof.applyBattery(ctx, battValue, mode)
                            }
                            msg = "电量伪装（" + mode.label + "）：" + r.results.firstOrNull()?.actual +
                                    "\n" + r.method
                            android.widget.Toast.makeText(
                                ctx,
                                if (r.allOk) "电量伪装成功：" + battValue + "%（" + r.method + "）"
                                else "电量伪装失败：需 root 或 ADB（Shizuku）已授权",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            applying = false
                        }
                    }
                )
                PillButton(
                    text = "还原伪装",
                    filled = false,
                    onClick = {
                        restoring = true
                        msg = ""
                        scope.launch {
                            if (!com.csdemo.tools.RootState.useRoot() &&
                                com.csdemo.tools.AdbShell.granted()
                            ) {
                                try {
                                    com.csdemo.tools.AdbShell.ensureService(ctx.applicationContext)
                                } catch (_: Throwable) {
                                }
                            }
                            val r = withContext(Dispatchers.IO) {
                                DeviceSpoof.restoreBattery(ctx)
                            }
                            msg = r.method + "\n" + (r.results.firstOrNull()?.actual ?: "")
                            android.widget.Toast.makeText(
                                ctx,
                                if (r.allOk) "已还原电量伪装" else "还原失败：需 root 或 ADB（Shizuku）已授权",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            restoring = false
                        }
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "说明：系统实时电量由系统服务上报，改属性不能真正改状态栏；" +
                        "这里写入的是部分 ROM / 诊断工具会读的容量类属性，属尽力而为。",
                fontSize = 11.sp, color = LocalPalette.current.inkSoft, lineHeight = 17.sp
            )
        }
        Spacer(Modifier.height(12.dp))

        // ---- 当前值（折叠） ----
        FoldCard(title = "当前属性", icon = "\u2699", initiallyOpen = false) {
            val r = real
            if (r == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 14.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("读取中...", fontSize = 12.sp, color = LocalPalette.current.inkSoft)
                }
            } else {
                KV("model", r.model, mono = true)
                KV("brand", r.brand, mono = true)
                KV("device", r.device, mono = true)
                KV("board", r.board, mono = true)
                KV("platform", r.platform, mono = true)
                KV("hardware", r.hardware, mono = true)
                KV("ro.soc.model", r.socModel, mono = true)
                KV("manufacturer", r.socManufacturer, mono = true)
                if (spoofed) {
                    Spacer(Modifier.height(8.dp))
                    Tag("已伪装", Warn)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 选芯片 ----
        FoldCard(title = "选择芯片", icon = "\u25C8") {
            PickerRow(
                current = chip?.name ?: "未选择",
                open = chipOpen,
                onToggle = { chipOpen = !chipOpen; phoneOpen = false },
                options = SpoofData.CHIPS.map { it.name },
                subOf = { n ->
                    val c = SpoofData.chipByName(n)
                    if (c == null) "" else c.vendor + "   " + c.socModel
                },
                onPick = { n -> chip = SpoofData.chipByName(n); chipOpen = false }
            )
            val c = chip
            if (c != null) {
                Spacer(Modifier.height(10.dp))
                HLine()
                Spacer(Modifier.height(10.dp))
                KV("ro.soc.model", c.socModel, mono = true)
                KV("ro.soc.manufacturer", c.socManufacturer, mono = true)
                KV("ro.board.platform", c.platform, mono = true)
                KV("ro.hardware", c.hardware, mono = true)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 选机型 ----
        FoldCard(title = "选择机型", icon = "\u25A4") {
            PickerRow(
                current = phone?.name ?: "未选择",
                open = phoneOpen,
                onToggle = { phoneOpen = !phoneOpen; chipOpen = false },
                options = SpoofData.PHONES.map { it.name },
                subOf = { n ->
                    val p = SpoofData.PHONES.firstOrNull { it.name == n }
                    if (p == null) "" else p.brand + "   " + p.model
                },
                onPick = { n ->
                    val p = SpoofData.PHONES.firstOrNull { it.name == n }
                    phone = p
                    phoneOpen = false
                    if (p != null) SpoofData.chipByName(p.chipName)?.let { chip = it }
                }
            )
            val p = phone
            if (p != null) {
                Spacer(Modifier.height(10.dp))
                HLine()
                Spacer(Modifier.height(10.dp))
                KV("ro.product.model", p.model, mono = true)
                KV("ro.product.brand", p.brand, mono = true)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 自定义 ----
        FoldCard(title = "自定义", icon = "\u270E", initiallyOpen = false,
            subtitle = "数据表没有的型号，直接填") {
            OutlinedTextField(
                value = customModel,
                onValueChange = { customModel = it },
                label = { Text("ro.product.model", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = customBrand,
                onValueChange = { customBrand = it },
                label = { Text("ro.product.brand", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = customSoc,
                onValueChange = { customSoc = it },
                label = { Text("ro.soc.model", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(16.dp))

        // ---- 操作 ----
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                text = if (applying) "应用中" else "应用伪装",
                leading = if (applying) null else "\u25B6",
                enabled = !applying,
                onClick = {
                    val p = phone
                    val c = chip
                    val hasCustom = customModel.isNotBlank() || customBrand.isNotBlank() || customSoc.isNotBlank()
                    if (p == null && c == null && !hasCustom) {
                        msg = "请至少选择芯片、机型，或填自定义值"
                        return@PillButton
                    }
                    applying = true; msg = ""; result = null
                    scope.launch {
                        val props = LinkedHashMap<String, String>()
                        props.putAll(DeviceSpoof.buildProps(p, c))
                        if (customModel.isNotBlank()) props["ro.product.model"] = customModel.trim()
                        if (customBrand.isNotBlank()) {
                            props["ro.product.brand"] = customBrand.trim()
                            props["ro.product.manufacturer"] = customBrand.trim()
                        }
                        if (customSoc.isNotBlank()) props["ro.soc.model"] = customSoc.trim()
                        result = withContext(Dispatchers.IO) { DeviceSpoof.applyProps(ctx, props) }
                        spoofed = withContext(Dispatchers.IO) { DeviceSpoof.isSpoofed(ctx) }
                        applying = false
                        // 性能：不再全量 load()。
                        // load() 除了这 10 次 readProp，还会额外跑
                        // hasResetprop（最多 2 个 su）和 isSpoofed（逐条 readProp），
                        // 而这批值刚刚都已经算过了。
                        // 若用户点完立刻退出页面，这些子进程会正好撞上
                        // 退出动画，造成明显卡顿。改用轻型刷新。
                        refreshReal()
                    }
                },
                modifier = Modifier.weight(1f)
            )
            PillButton(
                text = if (restoring) "复原中" else "一键复原",
                filled = false,
                enabled = !restoring,
                onClick = {
                    restoring = true; msg = ""; restoreResult = null
                    scope.launch {
                        restoreResult = withContext(Dispatchers.IO) { DeviceSpoof.restore(ctx) }
                        spoofed = withContext(Dispatchers.IO) { DeviceSpoof.isSpoofed(ctx) }
                        restoring = false
                        // 同“应用伪装”：只做轻量刷新，不再全量 load()
                        refreshReal()
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }

        if (msg.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(msg, fontSize = 12.sp, color = Warn)
        }
        Spacer(Modifier.height(14.dp))

        // ---- 输出 ----
        OutputBar("写入结果") {
            val res = result
            if (res == null) {
                Text("尚未执行", fontSize = 12.sp, color = LocalPalette.current.inkFaint)
            } else {
                Text("方式：" + res.method + "　成功 " + res.okCount + "/" + res.results.size,
                    fontSize = 11.sp, color = LocalPalette.current.inkSoft)
                Spacer(Modifier.height(8.dp))
                res.results.forEach { pr ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(7.dp).background(
                                    if (pr.ok) Good else Bad, CircleShape
                                )
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(pr.key, fontSize = 12.sp, color = LocalPalette.current.ink,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f))
                            Text(if (pr.ok) "成功" else "失败", fontSize = 11.sp,
                                color = if (pr.ok) Good else Bad)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text("写入 " + pr.target, fontSize = 11.sp, color = LocalPalette.current.inkSoft,
                            modifier = Modifier.padding(start = 16.dp))
                        Text("读回 " + (if (pr.actual.isBlank()) "（空）" else pr.actual),
                            fontSize = 11.sp, color = if (pr.ok) Good else Bad,
                            modifier = Modifier.padding(start = 16.dp))
                        SpoofData.PROP_VISIBILITY[pr.key]?.let { vis ->
                            Text(vis, fontSize = 10.sp, color = LocalPalette.current.inkFaint,
                                modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                    HLine()
                }
                if (!res.allOk) {
                    Spacer(Modifier.height(8.dp))
                    Text("部分属性未生效。ro.* 需 resetprop，setprop 无法改写。",
                        fontSize = 11.sp, color = Warn, lineHeight = 16.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        OutputBar("复原结果") {
            val rr = restoreResult
            if (rr == null) {
                Text("尚未执行", fontSize = 12.sp, color = LocalPalette.current.inkFaint)
            } else if (rr.results.isEmpty()) {
                Text("没有可复原的快照", fontSize = 12.sp, color = LocalPalette.current.inkSoft)
            } else {
                Text("成功 " + rr.okCount + "/" + rr.results.size, fontSize = 11.sp, color = LocalPalette.current.inkSoft)
                Spacer(Modifier.height(8.dp))
                rr.results.forEach { pr ->
                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(if (pr.ok) Good else Bad, CircleShape))
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pr.key, fontSize = 12.sp, color = LocalPalette.current.ink,
                                fontFamily = FontFamily.Monospace)
                            Text("恢复为 " + pr.target + "　读回 " + pr.actual,
                                fontSize = 11.sp, color = LocalPalette.current.inkSoft)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        FoldCard(title = "原理与限制", icon = "\u2139", initiallyOpen = false) {
            listOf(
                "resetprop 是内存级改写，重启后自动恢复原值，不改动真实系统分区。",
                "ro.* 属性用 setprop 基本改不动，这是 Android 限制。",
                "设置里“处理器”读的是 ro.soc.model（Android 12+）。",
                "部分 ROM 的设置页面是硬编码文字，不读属性，任何工具都改不了。",
                "已运行的进程可能缓存了旧属性值，重启后完全生效。"
            ).forEach {
                Text("• " + it, fontSize = 11.sp, color = LocalPalette.current.inkSoft,
                    lineHeight = 17.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** 选择器行：圆形刷新 + 当前值 + 展开箭头 */
@Composable
private fun PickerRow(
    current: String,
    open: Boolean,
    onToggle: () -> Unit,
    options: List<String>,
    subOf: (String) -> String,
    onPick: (String) -> Unit
) {
    val pal = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(pal.paper, RoundedCornerShape(50))
            .border(1.dp, pal.line, RoundedCornerShape(50))
            .pressable(pressedScale = 0.985f, onClick = onToggle)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            current,
            fontSize = 14.sp,
            color = if (current == "未选择") pal.inkFaint else pal.ink,
            modifier = Modifier.weight(1f)
        )
        CircleButton(if (open) "\u02C4" else "\u25BE", onToggle)
    }

    AnimatedVisibility(
        visible = open,
        enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.NORMAL))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(pal.paper, RoundedCornerShape(14.dp))
                .border(1.dp, pal.line, RoundedCornerShape(14.dp))
        ) {
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(options, key = { it }) { opt ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .pressable(pressedScale = 0.99f) { onPick(opt) }
                            .padding(horizontal = 16.dp, vertical = 11.dp)
                    ) {
                        Text(opt, fontSize = 14.sp, color = pal.ink, fontWeight = FontWeight.Medium)
                        val sub = subOf(opt)
                        if (sub.isNotBlank()) {
                            Text(sub, fontSize = 11.sp, color = pal.inkSoft)
                        }
                    }
                    HLine()
                }
            }
        }
    }
}
