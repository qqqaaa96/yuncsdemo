package sy.great.toolbox.ksu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.R
import sy.great.toolbox.ui.pressable
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
    runMode: sy.great.toolbox.tools.AdbShell.Mode = sy.great.toolbox.tools.AdbShell.Mode.USER,
    /** 本机是否具备 ADB（Shizuku）能力 */
    hasAdb: Boolean = false,
    /** 本机是否具备 root 能力 */
    hasRoot: Boolean = false,
    /** 未读邮件数（0 不显示角标） */
    unreadMail: Int = 0,
    /** 点击右上角邮箱图标 */
    onOpenMail: () -> Unit = {},
    /** 当前是否有公告（有才在顶栏显示公告图标） */
    hasAnnouncement: Boolean = false,
    /** 点击顶栏公告图标：重新弹出公告弹窗 */
    onOpenAnnouncement: () -> Unit = {},
    /** 是否有新版本（决定顶部黄色卡片是否出现） */
    hasUpdate: Boolean = false,
    /** 新版本号，如 "1.0.3" */
    newVersionName: String = "",
    /** 更新日志 */
    newChangelog: List<String> = emptyList(),
    /** 点击「更新」，参数是下载地址 */
    onUpdate: (String) -> Unit = {},
    /** 新版本下载地址 */
    newDownloadUrl: String = "",
    /** 是否正在下载更新包 */
    downloading: Boolean = false,
    /** 下载进度 0..1；-1 表示未知总长 */
    downloadProgress: Float = 0f,
    /** 下载状态文字（进度或错误信息） */
    downloadMessage: String = "",
    /** 打开排行榜 */
    onOpenRanking: () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(true)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    // 更新弹窗的显隐状态。
    //
    // 提升到页面顶层（而不是藏在 UpdateCard 里），
    // 是因为 OverlayDialog 不能在 LazyColumn 的 item 作用域里构造。
    // 用 mutableStateOf 的 State 对象本身（不是解包的 by 代理），
    // 因为 KernelSU 的 ConfirmDialogMiuix 需要 MutableState<Boolean> 来监听显隐。
    val showUpdateDialogState = remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopBar(
                scrollBehavior = scrollBehavior,
                backdrop = backdrop,
                barColor = barColor,
                unreadMail = unreadMail,
                onOpenMail = onOpenMail,
                hasAnnouncement = hasAnnouncement,
                onOpenAnnouncement = onOpenAnnouncement,
            )
        },
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    // 关键：把列表的滚动事件接到 TopAppBar 的 scrollBehavior，
                    // 才会有“左上角标题 → 上滑吸顶居中”的效果。
                    // 缺了这一行，TopAppBar 收不到滚动，标题就不会动。
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
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
                        // 新版本提示：黄色卡片。
                        //
                        // 注意：这里只画卡片，弹窗在 Scaffold 外层。
                        // 不能在 item {} 里构造 OverlayDialog：
                        // LazyColumn 会回收 item 的组合，而 OverlayDialog
                        // 会建 Android Window，被回收时窗口还没 attach 就抛异常（闪退）。
                        if (hasUpdate) {
                            UpdateCard(
                                versionName = newVersionName,
                                onClick = { showUpdateDialogState.value = true },
                            )
                        }
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
                            onOpenRanking = onOpenRanking,
                            modifier = Modifier.fillMaxWidth(),
                        )


                        Spacer(Modifier.height(bottomInnerPadding))
                    }
                }
            }
        }
    }

    // 更新弹窗：放在 Scaffold 外层、页面顶层。
    //
    // OverlayDialog 会创建一个独立的 Android Window，
    // 必须处于稳定的组合位置。放进 LazyColumn 的 item {} 里
    // 会因 item 被回收而窗口异常，表现为点击卡片直接闪退。
    if (hasUpdate) {
        sy.great.toolbox.ui.component.UpdateDialog(
            show = showUpdateDialogState,
            versionName = newVersionName,
            changelog = newChangelog,
            downloading = downloading,
            downloadProgress = downloadProgress,
            downloadMessage = downloadMessage,
            onConfirm = {
                if (newDownloadUrl.isNotBlank()) onUpdate(newDownloadUrl)
            },
            onDismiss = { },
        )
    }
}

@Composable
private fun TopBar(
    scrollBehavior: ScrollBehavior,
    backdrop: LayerBackdrop?,
    barColor: Color,
    unreadMail: Int,
    onOpenMail: () -> Unit,
    hasAnnouncement: Boolean,
    onOpenAnnouncement: () -> Unit,
) {
    BlurredBar(backdrop) {
        TopAppBar(
            color = barColor,
            title = stringResource(R.string.app_name),
            scrollBehavior = scrollBehavior,
            actions = {
                // 公告图标在邮箱左边。
                // 没有公告时不显示，避免点了没反应。
                if (hasAnnouncement) {
                    GlyphActionButton(
                        glyph = "announce",
                        badge = 0,
                        onClick = onOpenAnnouncement,
                    )
                }
                MailActionButton(unread = unreadMail, onClick = onOpenMail)
            },
        )
    }
}

/**
 * 新版本提示卡片。
 *
 * 完全照 KernelSU 的 UpdateCard：
 *   · 黄色 WarningCard（level = Notice）
 *   · 文案「有新版本可用: x.x.x」
 *   · 点击弹 UpdateDialog 看更新日志
 *   · 有下载地址 → 弹窗内点「更新」跳转
 *
 * 为什么用 WarningCard 而不是自己画：
 *   它的容器色 / 文字色 / 按压反馈都由 miuix 主题提供，
 *   深色模式下自动适配，且与传统其他警告卡视觉一致。
 */
/**
 * 新版本提示卡片。
 *
 * 只负责显示，点击后把事件上报给页面（页面再弹 UpdateDialog）。
 *
 * 为什么不在这里直接弹窗：
 *   它被调用于 LazyColumn 的 item {} 内部，
 *   而 OverlayDialog 会创建独立 Android Window，
 *   item 被回收时窗口还未 attach 就销毁 → 闪退。
 *   KernelSU 也是同样拆开：卡片在列表里，弹窗在页面顶层。
 */
@Composable
private fun UpdateCard(
    versionName: String,
    onClick: () -> Unit,
) {
    WarningCard(
        message = if (versionName.isNotBlank())
            "有新版本可用：$versionName"
        else
            "有新版本可用",
        level = WarningLevel.Notice,
        onClick = onClick,
    )
}

/**
 * 顶栏右上角的邮箱按钮。
 *
 * 图标用 Canvas 矢量绘制（与底栏 TabGlyph 同一套线条参数），
 * 未读数 > 0 时右上角叠一个小红点（超过 99 显示 99+）。
 *
 * 为什么不用 material-icons 的 Email 图标：
 *   项目里其他图标都是手绘矢量（TabGlyph / FeatureIcons），
 *   混用会风格不一致；且 material-icons-extended 已被 BOM 移除（见交接文档）。
 */
@Composable
private fun MailActionButton(unread: Int, onClick: () -> Unit) {
    GlyphActionButton(glyph = "mail", badge = unread, onClick = onClick)
}

/**
 * 顶栏通用图标按钮。
 *
 * 图标用 Canvas 矢量绘制（与底栏 TabGlyph 同一套线条参数），
 * badge > 0 时右上角叠一个小红点（超过 99 显示 99+）。
 *
 * 为什么不用 material-icons：
 *   项目里其他图标都是手绘矢量（TabGlyph / FeatureIcons），
 *   混用会风格不一致；且 material-icons-extended 已被 BOM 移除（见交接文档）。
 */
@Composable
private fun GlyphActionButton(glyph: String, badge: Int, onClick: () -> Unit) {
    val pal = sy.great.toolbox.ui.theme.LocalPalette.current

    Box(
        modifier = Modifier
            .size(44.dp)
            .pressable(
                pressedScale = 0.90f,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            sy.great.toolbox.ui.TabGlyph(
                kind = glyph,
                color = pal.ink,
                size = 21.dp,
            )
        }

        if (badge > 0) {
            val label = if (badge > 99) "99+" else badge.toString()
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 5.dp, end = 4.dp)
                    .height(15.dp)
                    .widthIn(min = 15.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(pal.accent)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    color = Color.White,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: HomeUiState,
    actions: HomeActions,
    runMode: sy.great.toolbox.tools.AdbShell.Mode,
    hasAdb: Boolean,
    hasRoot: Boolean,
) {
    // 身份优先级：root > adb > user（自动选择，无需手动切换）。
    // 若具备 root，则始终使用 root（即 runMode 为 ROOT）。
    val effectiveMode = when {
        hasRoot || runMode == sy.great.toolbox.tools.AdbShell.Mode.ROOT -> sy.great.toolbox.tools.AdbShell.Mode.ROOT
        hasAdb || runMode == sy.great.toolbox.tools.AdbShell.Mode.ADB_SHELL -> sy.great.toolbox.tools.AdbShell.Mode.ADB_SHELL
        else -> sy.great.toolbox.tools.AdbShell.Mode.USER
    }

    Column {
        when {
            // 1) 有 root → 绿色卡片（最高优先；也走 KernelSU 原有绿卡布局）
            effectiveMode == sy.great.toolbox.tools.AdbShell.Mode.ROOT -> {
                GreenStatusCard(state = state, actions = actions)
            }

            // 2) 无 root 但有 adb → 紫色卡片
            effectiveMode == sy.great.toolbox.tools.AdbShell.Mode.ADB_SHELL -> {
                AdbStatusCard(onClick = { actions.onInstallClick() })
            }

            // 3) 都没有 → 蓝色卡片
            else -> {
                NoSuStatusCard(onClick = { actions.onInstallClick() })
            }
        }
    }
}

/**
 * 绿卡（su 已授权）。
 *
 * 把 KernelSU 原有的绿色卡片分支抽成独立函数，便于上面按优先级调用。
 */
@Composable
private fun GreenStatusCard(
    state: HomeUiState,
    actions: HomeActions,
) {
    Column {
        when {
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
                                sy.great.toolbox.ui.theme.isAppInDark() -> Color(0xFF1A3825)
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
                sy.great.toolbox.ui.theme.isAppInDark() -> Color(0xFF102A43)
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
                sy.great.toolbox.ui.theme.isAppInDark() -> Color(0xFF2A1F45)
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
 * 双能力状态卡片（当前未启用）。
 *
 * 已改为“自动优先 root”策略（见 StatusCard），
 * 此组件保留以备后续需要“手动切换身份”时启用。
 */
@Suppress("unused")
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
                    1 -> if (sy.great.toolbox.ui.theme.isAppInDark()) Color(0xFF2A1F45) else Color(0xFFEDE6FF)
                    2 -> if (sy.great.toolbox.ui.theme.isAppInDark()) Color(0xFF1A3825) else Color(0xFFDFFAE4)
                    else -> if (sy.great.toolbox.ui.theme.isAppInDark()) Color(0xFF201C36) else Color(0xFFF1EEFF)
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
                                .background(if (sy.great.toolbox.ui.theme.isAppInDark()) Color(0xFF2A1F45) else Color(0xFFEDE6FF))
                                .clickable { onPickAdb() }
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(if (sy.great.toolbox.ui.theme.isAppInDark()) Color(0xFF1A3825) else Color(0xFFDFFAE4))
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
    onOpenRanking: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Canvas 的 lambda 是 DrawScope.() -> Unit，不是 @Composable，
    // 所以颜色必须先在外面读好再传进去。
    val iconColor = colorScheme.onBackground

    Card(modifier = modifier) {
        ArrowPreference(
            title = "关注开发者",
            summary = "点击跳转作者主页",
            startAction = {
                Icon(
                    imageVector = Icons.Filled.VolunteerActivism,
                    contentDescription = "关注开发者",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = {
                onOpenUrl(
                    "https://estplcb.m.chenzhongtech.com/fw/user/a54752575" +
                            "?cc=share_wxms&kpf=ANDROID_PHONE&fid=2398774475" +
                            "&shareMode=app&shareMethod=picture&appType=21" +
                            "&kpn=KUAISHOU&subBiz=QR_CODE_PROFILE" +
                            "&shareId=19136039742335&shareToken=Ygg7vigF1" +
                            "&shareObjectId=2398774475"
                )
            },
        )

        // 支持开发：点击弹出捐赠码图片
        var showDonate by remember { mutableStateOf(false) }
        ArrowPreference(
            title = "支持开发",
            summary = "点击查看捐赠码",
            startAction = {
                Icon(
                    imageVector = Icons.Filled.VolunteerActivism,
                    contentDescription = "支持开发",
                    modifier = Modifier.padding(end = 6.dp),
                    tint = colorScheme.onBackground,
                )
            },
            onClick = { showDonate = true },
        )

        // 排行榜：放在「支持开发」下面
        ArrowPreference(
            title = "排行榜",
            summary = "积分榜 · 连续签到榜",
            startAction = {
                androidx.compose.foundation.Canvas(
                    Modifier
                        .padding(end = 8.dp)
                        .size(20.dp)
                ) {
                    val s = size.minDimension
                    val w = s * 0.09f
                    val st = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = w,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    )
                    val p = s * 0.16f
                    val l = p
                    val r = s - p
                    val b = s - p
                    val cw = r - l
                    val ch = b - p

                    // 三根高低不一的柱子
                    val barW = cw * 0.20f
                    val hs = floatArrayOf(0.40f, 0.72f, 0.55f)
                    hs.forEachIndexed { i, hh ->
                        val x = l + cw * (0.06f + i * 0.32f)
                        drawRoundRect(
                            color = iconColor,
                            topLeft = androidx.compose.ui.geometry.Offset(x, b - ch * hh),
                            size = androidx.compose.ui.geometry.Size(barW, ch * hh),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.3f),
                            style = st,
                        )
                    }

                    // 基线
                    drawLine(
                        color = iconColor,
                        start = androidx.compose.ui.geometry.Offset(l, b),
                        end = androidx.compose.ui.geometry.Offset(r, b),
                        strokeWidth = w,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            },
            onClick = onOpenRanking,
        )

        if (showDonate) {
            DonateDialog(onDismiss = { showDonate = false })
        }
    }
}

/**
 * 捐赠弹窗：显示 assets 里的捐赠码图片。
 *
 * 图片用 BitmapFactory 从 assets 直接解码（不依赖任何图片库），
 * 点击遮罩或“关闭”按钮退出。
 */
@Composable
private fun DonateDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bmp = androidx.compose.runtime.remember {
        runCatching {
            context.assets.open("juanzeng.png").use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surface, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "支持开发",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "捐赠码",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = "捐赠码图片加载失败",
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(14.dp))
            top.yukonga.miuix.kmp.basic.TextButton(
                text = "关闭",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 「与服务器建立连接」一行。
 *
 * 布局与 InfoText 完全一致（图标 + 标题 + 小字），保证排版不出跳。
 *
 * 颜色规则（分段着色，不是整行变色）：
 *
 *   · 「延迟:」「归属地:」这两个标签 → 默认色（与上面标题一致）
 *   · 延迟数字（如 150ms）    → 按延迟变色
 *       - < 100ms  绿
 *       - < 200ms  黄
 *       - ≥ 200ms  红
 *   · 归属地国家              → 始终绿
 *   · [连接中...]             → 始终绿
 *   · 建连失败                → 整行红（含错误详情）
 *
 * 图标颜色跟随主状态（延迟色 / 连接中绿 / 失败红）。
 */
@Composable
private fun ServerLinkItem() {
    val pal = sy.great.toolbox.ui.theme.LocalPalette.current

    // 启动轮询（重复调用无副作用）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        sy.great.toolbox.tools.PingMonitor.start()
    }

    val st = sy.great.toolbox.tools.PingMonitor.state

    // 绿 / 黄 / 红
    val green = Color(0xFF2EA043)
    val yellow = Color(0xFFD29922)
    val red = Color(0xFFD1242F)

    // 「延迟:」「服务器归属地:」这些固定文字用默认色（与上面标题一致）
    val labelColor = colorScheme.onSurfaceVariantSummary

    var glyphColor = green
    var subtitle = androidx.compose.ui.text.AnnotatedString("")

    when (st) {
        is sy.great.toolbox.tools.PingMonitor.State.Connecting -> {
            // 延迟: --ms 服务器归属地: 未知 [连接中...]
            //        ↑                          ↑
            //      「--ms」绿              「未知」和「[连接中...]」绿
            glyphColor = green
            subtitle = androidx.compose.ui.text.buildAnnotatedString {
                fun part(s: String, c: Color) {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = c))
                    append(s)
                    pop()
                }
                part("延迟: ", labelColor)
                part("--ms", green)
                part(" 服务器归属地: ", labelColor)
                part("未知", green)
                part(" ", labelColor)
                part("[连接中...]", green)
            }
        }

        is sy.great.toolbox.tools.PingMonitor.State.Online -> {
            // 延迟: 150ms 服务器归属地: 中国
            //        ↑                   ↑
            //    按延迟变色            绿色
            val latColor = when {
                st.latencyMs < 100 -> green
                st.latencyMs < 200 -> yellow
                else -> red
            }
            glyphColor = latColor
            val region = st.region.ifBlank { "未知" }

            subtitle = androidx.compose.ui.text.buildAnnotatedString {
                fun part(s: String, c: Color) {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = c))
                    append(s)
                    pop()
                }
                part("延迟: ", labelColor)
                part("${st.latencyMs}ms", latColor)
                part(" 服务器归属地: ", labelColor)
                part(region, green)
            }
        }

        is sy.great.toolbox.tools.PingMonitor.State.Failed -> {
            // 整行红色：建连失败是异常状态，细节全部标红更好定位
            glyphColor = red
            subtitle = androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = red))
                append("[建立连接失败] 错误详情: ${st.detail}")
                pop()
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标：手绘“服务器 + 信号波”矢量
        ServerGlyph(
            color = glyphColor,
            size = 24.dp,
            modifier = Modifier.padding(end = 12.dp),
        )

        Column {
            Text(
                text = "与服务器建立连接",
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = labelColor,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 2,
            )
        }
    }
}

/**
 * 服务器图标（Canvas 矢量）。
 *
 * 构图：左边一个小圆点 + 两道短弧（信号），右边上下两个机架。
 *
 * 关键：信号弧的半径必须严格小于机架左边界的剩余空间，
 * 否则会和机架重叠（旧版就是 h*0.38 的弧占到了 w*0.30 开外的位置）。
 * 这里把弧限制在 [0.04, 0.24] 这一带，机架从 0.34 开始，保证留白。
 */
@Composable
private fun ServerGlyph(
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.075f

        // ---- 右侧：两个服务器机架 ----
        val boxLeft = w * 0.34f
        val boxRight = w * 0.94f
        val boxW = boxRight - boxLeft
        val boxH = h * 0.24f

        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(boxLeft, h * 0.23f),
            size = androidx.compose.ui.geometry.Size(boxW, boxH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f, w * 0.05f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(boxLeft, h * 0.55f),
            size = androidx.compose.ui.geometry.Size(boxW, boxH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f, w * 0.05f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )

        // 机架内的指示灯（靠左，小圆点）
        val dotR = w * 0.032f
        drawCircle(color, dotR, androidx.compose.ui.geometry.Offset(boxLeft + boxW * 0.16f, h * 0.35f))
        drawCircle(color, dotR, androidx.compose.ui.geometry.Offset(boxLeft + boxW * 0.16f, h * 0.67f))

        // ---- 左侧：信号弧 ----
        //
        // 圆心贴左边缘，半径最大 0.22w，不会被机架挡住。
        val cx = w * 0.10f
        val cy = h * 0.50f
        val radii = listOf(w * 0.09f, w * 0.18f)

        radii.forEachIndexed { i, r ->
            drawArc(
                color = color.copy(alpha = 0.55f + i * 0.35f),
                startAngle = -48f,
                sweepAngle = 96f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(cx - r, cy - r),
                size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                ),
            )
        }

        // 圆心小点
        drawCircle(color, w * 0.030f, androidx.compose.ui.geometry.Offset(cx, cy))
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
                ServerLinkItem()
                InfoText(
                    icon = Icons.Filled.Tag,
                    title = "应用版本",
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
