package com.csdemo.ksu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 主页 UI：从 KernelSU manager 的 HomeMiuix.kt 完整复制。
 *
 * 仅有三处与 KernelSU 不同：
 *   · 判断依据从“ksuVersion != null”改为“root 已授权”（由外部传入 granted）
 *   · “工作中” 的文案在 strings.xml 里改成了 “授权 su 成功”
 *   · TopBar 不包含 RebootListPopup（本项目没有内核，无重启功能）
 *
 * 其余布局 / 尺寸 / 颜色 / 字号 / 间距与 KernelSU 完全一致。
 */
@Composable
fun HomePagerMiuix(
    state: HomeUiState,
    actions: HomeActions,
    bottomInnerPadding: Dp,
    /**
     * 运行身份（三态）：
     *   ROOT      → 绿色 “su 已授权[root]”
     *   ADB_SHELL → 紫色 “ADB 已授权[adbshell]”
     *   USER      → 蓝色 “基础模式运行中[user]”
     */
    runMode: com.csdemo.tools.AdbShell.Mode = com.csdemo.tools.AdbShell.Mode.USER,
    /** 本机是否具备 ADB（Shizuku）能力 */
    hasAdb: Boolean = false,
    /** 本机是否具备 root 能力 */
    hasRoot: Boolean = false,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(true)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    Scaffold(
        topBar = {
            TopBar(
                scrollBehavior = scrollBehavior,
                backdrop = backdrop,
                barColor = barColor,
            )
        },
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.showManagerPrBuildWarning) {
                            WarningCard(stringResource(id = R.string.home_pr_build_warning), level = WarningLevel.Notice)
                        } else if (state.showKernelPrBuildWarning) {
                            WarningCard(stringResource(id = R.string.home_pr_kernel_warning), level = WarningLevel.Notice)
                        }
                        if (state.showGkiWarning) {
                            WarningCard(stringResource(id = R.string.home_gki_warning), level = WarningLevel.Notice)
                        }
                        if (state.showRootWarning) {
                            WarningCard(stringResource(id = R.string.grant_root_failed))
                        }
                        StatusCard(
                            state = state,
                            actions = actions,
                            runMode = runMode,
                            hasAdb = hasAdb,
                            hasRoot = hasRoot,
                        )
                        InfoCard(
                            systemInfo = state.systemInfo,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SupportLinks(
                            onOpenUrl = actions.onOpenUrl,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(bottomInnerPadding))
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    scrollBehavior: ScrollBehavior,
    backdrop: LayerBackdrop?,
    barColor: Color,
) {
    BlurredBar(backdrop) {
        TopAppBar(
            color = barColor,
            title = stringResource(R.string.app_name),
            scrollBehavior = scrollBehavior
        )
    }
}

@Composable
private fun StatusCard(
    state: HomeUiState,
    actions: HomeActions,
    runMode: com.csdemo.tools.AdbShell.Mode,
    hasAdb: Boolean,
    hasRoot: Boolean,
) {
    // 双色卡片状态：
    //   0 = 双色（左紫 ADB/右绿 root）
    //   1 = 纯 ADB（紫）
    //   2 = 纯 root（绿）
    // 只有 adb 与 root 同时具备时才启用双色。
    val bothAvailable = hasAdb && hasRoot
    // 初始显示状态跟随全局 activeMode：
    //   全局是 ADB → 直接显示紫卡
    //   全局是 ROOT → 直接显示绿卡
    //   否则（USER / 不确定）→ 显示双色卡
    var dualMode by remember(bothAvailable) {
        mutableIntStateOf(
            when (com.csdemo.tools.RootState.activeMode.value) {
                com.csdemo.tools.AdbShell.Mode.ADB_SHELL -> 1
                com.csdemo.tools.AdbShell.Mode.ROOT -> 2
                else -> 0
            }
        )
    }
    // 快速连点计数（用于“连点 3 下回到双色”）
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

    fun onDualTap(target: Int) {
        // 单色态下连点 3 下 → 回到双色卡
        val now = System.currentTimeMillis()
        if (dualMode != 0) {
            if (now - lastTapAt < 600L) {
                tapCount += 1
            } else {
                tapCount = 1
            }
            lastTapAt = now
            if (tapCount >= 3) {
                tapCount = 0
                dualMode = 0
                // 回到双色卡时，默认以 root 为生效身份（手动可选）
                com.csdemo.tools.RootState.activeMode.value = com.csdemo.tools.AdbShell.Mode.ROOT
                return
            }
        }
        dualMode = target
        // 同步全局生效身份：切到哪边，全局就用哪边提权。
        // 这一步是修“切到 ADB 后 Root 检测仍报已授权”的关键。
        com.csdemo.tools.RootState.activeMode.value = when (target) {
            1 -> com.csdemo.tools.AdbShell.Mode.ADB_SHELL
            else -> com.csdemo.tools.AdbShell.Mode.ROOT
        }
    }

    Column {
        when {
            // 同时具备 adb + root：双色卡片（可切换）
            bothAvailable -> {
                DualStatusCard(
                    mode = dualMode,
                    onPickAdb = { onDualTap(1) },
                    onPickRoot = { onDualTap(2) },
                )
            }

            // 只有 ADB：紫色卡片
            hasAdb || runMode == com.csdemo.tools.AdbShell.Mode.ADB_SHELL -> {
                AdbStatusCard(onClick = { actions.onInstallClick() })
            }

            // 都没有：蓝色卡片
            runMode == com.csdemo.tools.AdbShell.Mode.USER -> {
                NoSuStatusCard(onClick = { actions.onInstallClick() })
            }

            state.ksuVersion != null -> {
                val workingState = buildString {
                    if (state.isSafeMode) {
                        append(" [${stringResource(id = R.string.safe_mode)}]")
                    }
                    if (state.isLateLoadMode) {
                        append(" [${stringResource(id = R.string.jailbreak_mode)}]")
                    }
                }
                val workingMode = when (state.lkmMode) {
                    null -> null
                    true -> "LKM"
                    else -> "GKI"
                }
                val workingText = "${stringResource(id = R.string.home_working)}$workingState"

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.defaultColors(
                            color = when {
                                isDynamicColor -> colorScheme.secondaryContainer
                                isSystemInDarkTheme() -> Color(0xFF1A3825)
                                else -> Color(0xFFDFFAE4)
                            }
                        ),
                        onClick = {
                            if (!state.isLateLoadMode) {
                                actions.onInstallClick()
                            }
                        },
                        showIndication = !state.isLateLoadMode,
                        pressFeedbackType = PressFeedbackType.Tilt
                    ) {
                        Box {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset(27.dp, 31.dp),
                                contentAlignment = Alignment.BottomEnd
                            ) {
                                Icon(
                                    modifier = Modifier.size(110.dp),
                                    imageVector = Icons.Rounded.CheckCircleOutline,
                                    tint = if (isDynamicColor) {
                                        colorScheme.primary.copy(alpha = 0.8f)
                                    } else {
                                        Color(0xFF36D167)
                                    },
                                    contentDescription = null
                                )
                            }
                            if (workingMode != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp, 10.dp),
                                    contentAlignment = Alignment.BottomStart,
                                ) {
                                    Text(
                                        text = workingMode,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp, 14.dp),
                                contentAlignment = Alignment.TopStart,
                            ) {
                                Column {
                                    Text(
                                        text = workingText,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(1.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = stringResource(
                                                R.string.home_working_version,
                                                "${state.ksuVersion}-${state.kernelUAPIVersion}"
                                            ),
                                            modifier = Modifier.weight(1f, fill = false),
                                            fontSize = 15.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            state.kernelVersion.isGKI() -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (!state.isLateLoadMode) {
                                actions.onInstallClick()
                            }
                        },
                        showIndication = !state.isLateLoadMode,
                        pressFeedbackType = PressFeedbackType.Tilt
                    ) {
                        BasicComponent(
                            // 本项目语义：未授权 root（而非 KernelSU 的“未安装”）
                            title = "未授权 Root",
                            summary = "su 存在，但未授予权限；请在弹出的授权框中允许",
                            startAction = {
                                Icon(
                                    Icons.Rounded.ErrorOutline,
                                    "未授权 Root",
                                    modifier = Modifier.padding(end = 6.dp),
                                    tint = colorScheme.onBackground,
                                )
                            },
                        )
                    }
                }
            }

            else -> {
                Card(
                    onClick = {
                        if (!state.isLateLoadMode) {
                            actions.onInstallClick()
                        }
                    },
                    showIndication = !state.isLateLoadMode,
                    pressFeedbackType = PressFeedbackType.Tilt
                ) {
                    BasicComponent(
                        title = stringResource(R.string.home_unsupported),
                        summary = stringResource(R.string.home_unsupported_reason),
                        startAction = {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                stringResource(R.string.home_unsupported),
                                modifier = Modifier.padding(end = 16.dp),
                                tint = colorScheme.onBackground,
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * 免 root（基础模式）卡片。
 *
 * 布局与 KernelSU 的绿色“工作中”卡片同构，颜色改为蓝色系，
 * 文字为 “NoSU 基础模式运行中[shell]”。
 */
@Composable
private fun NoSuStatusCard(
    onClick: () -> Unit = {},
) {
    // 与绿卡保持完全一致的外层结构：Row + IntrinsicSize.Min。
    // 绿卡就是包在这样一层 Row 里的，去掉这层会导致卡片测量方式不同，
    // Tilt 按压回弹的观感也会不一致。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = when {
                isDynamicColor -> colorScheme.secondaryContainer
                isSystemInDarkTheme() -> Color(0xFF102A43)
                else -> Color(0xFFDCEBFB)
            }
        ),
        // 与绿色卡片一致：可点击 + 按压回弹反馈
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box {
            // 右下角大终端图标（自绘，避免依赖图标库）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(27.dp, 31.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                ShellGlyph(
                    size = 110.dp,
                    color = if (isDynamicColor) {
                        colorScheme.primary.copy(alpha = 0.8f)
                    } else {
                        Color(0xFF3B82F6)
                    },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp, 14.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                Column {
                    Text(
                        text = "NoSU 基础模式运行中[shell]",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = "以普通应用身份运行，无特权",
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
    }
}

/**
 * ADB shell 卡片。
 *
 * 布局与绿色 / 蓝色卡片同构，颜色用紫色系，
 * 文字为 “ADB 已授权[adbshell]”。
 */
@Composable
private fun AdbStatusCard(
    onClick: () -> Unit = {},
) {
    // 与绿卡保持完全一致的外层结构：Row + IntrinsicSize.Min。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = when {
                isDynamicColor -> colorScheme.tertiaryContainer
                isSystemInDarkTheme() -> Color(0xFF2A1F45)
                else -> Color(0xFFEDE6FF)
            }
        ),
        // 与绿色卡片一致：可点击 + 按压回弹反馈
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(27.dp, 31.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                ShellGlyph(
                    size = 110.dp,
                    color = if (isDynamicColor) {
                        colorScheme.primary.copy(alpha = 0.8f)
                    } else {
                        Color(0xFF7C4DFF)
                    },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp, 14.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                Column {
                    Text(
                        text = "ADB 已授权[adbshell]",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = "通过 Shizuku 以 shell 身份运行",
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
    }
}

/**
 * 双能力状态卡片（adb + root 同时具备时使用）。
 *
 * mode 含义：
 *   0 —— 双色：左半紫（ADB）、右半绿（root），点哪边选哪边
 *   1 —— 纯 ADB：整卡紫色
 *   2 —— 纯 root：整卡绿色
 */
@Composable
private fun DualStatusCard(
    mode: Int,
    onPickAdb: () -> Unit,
    onPickRoot: () -> Unit,
) {
    // 尺寸与绿卡完全一致：外层 Row(IntrinsicSize.Min) + Card(fillMaxWidth)。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(
                color = when (mode) {
                    1 -> if (isSystemInDarkTheme()) Color(0xFF2A1F45) else Color(0xFFEDE6FF)
                    2 -> if (isSystemInDarkTheme()) Color(0xFF1A3825) else Color(0xFFDFFAE4)
                    else -> if (isSystemInDarkTheme()) Color(0xFF201C36) else Color(0xFFF1EEFF)
                }
            ),
            onClick = { },
            showIndication = false,
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box {
                // 双色态：左右各一块色块（左紫 ABD / 右绿 SU），只有它们可点
                if (mode == 0) {
                    Row(Modifier.matchParentSize()) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(if (isSystemInDarkTheme()) Color(0xFF2A1F45) else Color(0xFFEDE6FF))
                                .clickable { onPickAdb() }
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(if (isSystemInDarkTheme()) Color(0xFF1A3825) else Color(0xFFDFFAE4))
                                .clickable { onPickRoot() }
                        )
                    }
                }

                // 左上：标题（与绿卡相同的 22sp SemiBold + padding(16,14)）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp, 14.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Column {
                        Text(
                            text = when (mode) {
                                1 -> "ADB 已授权[adbshell]"
                                2 -> "su 已授权[root]"
                                else -> "SU / ADB 切换"
                            },
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = when (mode) {
                                1 -> "通过 Shizuku 以 shell 身份运行"
                                2 -> "以超级用户身份运行"
                                else -> "点左侧用 ADB，点右侧用 SU"
                            },
                            fontSize = 15.sp,
                        )
                    }
                }

                // 右下：图标，尺寸/位置与绿卡一致（offset(27,31)、110dp）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(27.dp, 31.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (mode == 0) {
                            ShellGlyph(size = 70.dp, color = Color(0xFF7C4DFF))
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                modifier = Modifier.size(70.dp),
                                imageVector = Icons.Rounded.CheckCircleOutline,
                                tint = Color(0xFF36D167),
                                contentDescription = null,
                            )
                        } else if (mode == 1) {
                            ShellGlyph(size = 110.dp, color = Color(0xFF7C4DFF))
                        } else {
                            Icon(
                                modifier = Modifier.size(110.dp),
                                imageVector = Icons.Rounded.CheckCircleOutline,
                                tint = Color(0xFF36D167),
                                contentDescription = null,
                            )
                        }
                    }
                }

                // 单色态：整卡可点，点一下切到另一种（连点 3 下回双色由上层计数）
                if (mode == 1) {
                    Box(Modifier.matchParentSize().clickable { onPickRoot() })
                } else if (mode == 2) {
                    Box(Modifier.matchParentSize().clickable { onPickAdb() })
                }
            }
        }
    }
}

/** 自绘终端图标（圆角矩形 + 提示符），用于免 root / ADB 卡片 */
@Composable
private fun ShellGlyph(size: Dp, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = s * 0.07f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        // 圆角外框
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(s * 0.08f, s * 0.16f),
            size = androidx.compose.ui.geometry.Size(s * 0.84f, s * 0.68f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.14f),
            style = stroke,
        )
        // 提示符 >_
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(s * 0.28f, s * 0.40f)
            lineTo(s * 0.42f, s * 0.50f)
            lineTo(s * 0.28f, s * 0.60f)
        }
        drawPath(path, color = color, style = stroke)
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(s * 0.50f, s * 0.60f),
            end = androidx.compose.ui.geometry.Offset(s * 0.70f, s * 0.60f),
            strokeWidth = s * 0.07f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

@Composable
private fun SupportLinks(
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val learnMoreUrl = stringResource(R.string.home_learn_kernelsu_url)

    Card(modifier = modifier) {
        ArrowPreference(
            title = stringResource(R.string.home_support_title),
            summary = stringResource(R.string.home_support_content),
            startAction = {
                Icon(
                    imageVector = Icons.Filled.VolunteerActivism,
                    contentDescription = stringResource(R.string.home_support_title),
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = { onOpenUrl("https://patreon.com/weishu") },
        )
        ArrowPreference(
            title = stringResource(R.string.home_learn_kernelsu),
            summary = stringResource(R.string.home_click_to_learn_kernelsu),
            startAction = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = stringResource(R.string.home_learn_kernelsu),
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = { onOpenUrl(learnMoreUrl) },
        )
    }
}

@Composable
private fun InfoCard(
    systemInfo: SystemInfo,
    modifier: Modifier = Modifier,
) {
    @Composable
    fun InfoText(
        icon: ImageVector,
        title: String,
        content: String,
        bottomPadding: Dp = 24.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(24.dp),
                tint = colorScheme.onSurface,
            )
            Column {
                Text(
                    text = title,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = content,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }

    val selinuxDisplay = when (systemInfo.selinuxStatus) {
        "Enforcing" -> stringResource(R.string.selinux_status_enforcing)
        "Permissive" -> stringResource(R.string.selinux_status_permissive)
        "Disabled" -> stringResource(R.string.selinux_status_disabled)
        else -> stringResource(R.string.selinux_status_unknown)
    }
    val seccompDisplay = when (systemInfo.seccompStatus) {
        -1 -> stringResource(R.string.seccomp_status_not_supported)
        0 -> stringResource(R.string.seccomp_status_disabled)
        1 -> stringResource(R.string.seccomp_status_strict)
        2 -> stringResource(R.string.seccomp_status_filter)
        else -> stringResource(R.string.seccomp_status_unknown)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoText(
                    icon = Icons.Filled.Tag,
                    title = stringResource(R.string.home_manager_version),
                    content = systemInfo.managerVersion,
                )
                InfoText(
                    icon = Icons.Filled.DeveloperBoard,
                    title = stringResource(R.string.home_kernel),
                    content = systemInfo.kernelVersion,
                )
                InfoText(
                    icon = Icons.Filled.Smartphone,
                    title = stringResource(R.string.home_device_model),
                    content = systemInfo.deviceModel,
                )
                InfoText(
                    icon = Icons.Filled.Fingerprint,
                    title = stringResource(R.string.home_fingerprint),
                    content = systemInfo.fingerprint,
                    bottomPadding = 0.dp
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoText(
                    icon = Icons.Filled.Security,
                    title = stringResource(R.string.home_selinux_status),
                    content = selinuxDisplay,
                )
                InfoText(
                    icon = Icons.Filled.FilterList,
                    title = stringResource(R.string.home_seccomp_status),
                    content = seccompDisplay,
                    bottomPadding = 0.dp
                )
            }
        }
    }
}
