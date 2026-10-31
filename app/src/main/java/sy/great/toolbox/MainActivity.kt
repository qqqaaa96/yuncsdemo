package sy.great.toolbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import sy.great.toolbox.tools.Plan
import sy.great.toolbox.ui.Motion
import sy.great.toolbox.ui.AppsScreen
import sy.great.toolbox.ui.CardKeyScreen
import sy.great.toolbox.ui.CodecScreen
import sy.great.toolbox.ui.ConvertScreen
import sy.great.toolbox.ui.CpuFreqScreen
import sy.great.toolbox.ui.DeviceScreen
import sy.great.toolbox.ui.DnsScreen
import sy.great.toolbox.ui.HomeScreen
import sy.great.toolbox.ui.HttpScreen
import sy.great.toolbox.ui.LanScreen
import sy.great.toolbox.ui.MetalDetectorScreen
import sy.great.toolbox.ui.ShellObfuscateScreen
import sy.great.toolbox.ui.MailVerifyScreen
import sy.great.toolbox.ui.LoginScreen
import sy.great.toolbox.ui.RegisterScreen
import sy.great.toolbox.ui.RegisterVerifyScreen
import sy.great.toolbox.tools.AuthApi
import sy.great.toolbox.ui.PingScreen
import sy.great.toolbox.ui.PlanScreen
import sy.great.toolbox.ui.PortScanScreen
import sy.great.toolbox.ui.PrivacyScreen
import sy.great.toolbox.ui.RootScreen
import sy.great.toolbox.ui.ElfScreen
import sy.great.toolbox.ui.AntiCheatBlockScreen
import sy.great.toolbox.ui.FpsHistoryScreen
import sy.great.toolbox.ui.FpsOverlayScreen
import sy.great.toolbox.ui.AppShell
import sy.great.toolbox.tools.AppSettings
import sy.great.toolbox.ui.pages.AboutPage
import sy.great.toolbox.ui.pages.CheckUpdatePage
import sy.great.toolbox.ui.pages.ThemeSettingsPage
import sy.great.toolbox.ui.pages.ChangeUsernamePage
import sy.great.toolbox.ui.pages.ChangeEmailPage
import sy.great.toolbox.ui.pages.DeleteAccountPage
import sy.great.toolbox.ui.pages.FeedbackPage
import sy.great.toolbox.ui.SchedScreen
import sy.great.toolbox.ui.SelinuxScreen
import sy.great.toolbox.ui.SpoofScreen
import sy.great.toolbox.ui.SpeedTestScreen
import sy.great.toolbox.ui.ThermalScreen
import sy.great.toolbox.ui.ThreadOptScreen
import sy.great.toolbox.ui.theme.CsdemoTheme

// 应用版本：不再手写。
//
// 之前这里硬编码 "1.0.1-beta"，与 build.gradle.kts 的 versionName 脱钩，
// 升版本后界面还显示旧号。现在统一从安装包读取。
private fun appVersion(ctx: android.content.Context): String {
    val n = sy.great.toolbox.tools.AppVersion.name(ctx)
    return if (n.isBlank()) "未知" else n
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Plan.load(this)
        sy.great.toolbox.tools.CardKey.load(this)
        sy.great.toolbox.tools.MailAuth.load(this)
        AppSettings.load(this)
        sy.great.toolbox.tools.MailTheme.load(this)

        // 访问上报：每个进程生命周期只发一次，后台线程执行，失败静默。
        // 不需要登录，未登录用户也会记录。
        sy.great.toolbox.tools.VisitTracker.reportOnce(this)

        // 全局崩溃日志：把未捕获异常写到 App 私有目录，
        // 路径 /sdcard/Android/data/sy.great.toolbox/files/csdemo_crash.log
        // 方便直接用 MT 管理器打开看，不用装 logcat 工具。
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                val f = java.io.File(dir, "csdemo_crash.log")
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                f.appendText(
                    "\n===== " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date()) + " [UNCAUGHT] =====\n" + sw.toString() + "\n"
                )
            } catch (_: Throwable) {
            }
            prevHandler?.uncaughtException(thread, throwable)
        }

        setContent {
            val dark = when (AppSettings.themeMode.value) {
                AppSettings.ThemeMode.Dark -> true
                AppSettings.ThemeMode.Light -> false
                AppSettings.ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            // 界面缩放：整体缩放 density，让所有 dp 尺寸跟着变。
            //
            // 关键：必须 remember，只在 pageScale 真正变化时重建 Density 对象。
            // 若每次重组都新建 Density 实例，LocalDensity 会被视为“变了”，
            // 导致全树（含 miuix blur / backdrop）反复重新测量而崩溃。
            val base = androidx.compose.ui.platform.LocalDensity.current
            val scale = AppSettings.pageScale.value
            val scaled = androidx.compose.runtime.remember(base, scale) {
                androidx.compose.ui.unit.Density(
                    density = base.density * scale,
                    fontScale = base.fontScale
                )
            }
            CsdemoTheme(darkTheme = dark) {
                // miuix 主题：一级设置，覆盖整个应用（包括 AppShell、所有子页面）。
                // 参照 KernelSU 的 MiuixKernelSUTheme：必须传 isDark，
                // 仅传 ColorSchemeMode 不够，miuix 不会知道当前是深色。
                val miuixController = androidx.compose.runtime.remember(dark) {
                    top.yukonga.miuix.kmp.theme.ThemeController(
                        if (dark) top.yukonga.miuix.kmp.theme.ColorSchemeMode.Dark
                        else top.yukonga.miuix.kmp.theme.ColorSchemeMode.Light,
                        isDark = dark,
                    )
                }
                top.yukonga.miuix.kmp.theme.MiuixTheme(controller = miuixController) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalDensity provides scaled
                    ) {
                        Surface(
                            Modifier.fillMaxSize(),
                            color = if (dark) Color(0xFF121212) else Color.White
                        ) {
                            AppRoot()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var route by remember { mutableStateOf("") }

    // VPN 检测：启动时查一次 + 持续轮询（每 2 秒）+ 网络变化回调
    //
    // 之前只查一次，导致「先开 App 再开 VPN」完全检测不到。
    // startWatching 内部两种机制互为兜底，检测到就上报 + 杀进程。
    sy.great.toolbox.tools.VpnGuard.startWatching(ctx)

    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sy.great.toolbox.tools.VpnGuard.checkAndBlock(ctx)
        }
    }

    // 首启流程决定初始路由
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (route.isEmpty()) {
            // 一级：账号登录（新增）
            //   已有本地 token → 继续后面的原有流程
            //   没有 token        → 先去登录页
            if (!AuthApi.hasToken(ctx)) {
                route = "login"
                return@LaunchedEffect
            }

            // 二级至五级：原有流程，完全保留
            route = if (!sy.great.toolbox.tools.MailAuth.verified.value) {
                "mailverify"
            } else {
                when {
                    !Plan.agreed.value -> "privacy"
                    Plan.plan.value == Plan.NONE -> "plan"
                    else -> "home"
                }
            }
        }
    }

    // 登录成功后继续原有流程
    fun afterLogin() {
        route = if (!sy.great.toolbox.tools.MailAuth.verified.value) {
            "mailverify"
        } else {
            when {
                !Plan.agreed.value -> "privacy"
                Plan.plan.value == Plan.NONE -> "plan"
                else -> "home"
            }
        }
    }

    // NFC 写入内容（从写入编辑页传给靠卡页）
    var nfcWritePayload by remember {
        mutableStateOf<sy.great.toolbox.ui.WritePayload?>(null)
    }

    // 注册流程临时参数（新页面内部使用）
    var regName by remember { mutableStateOf("") }
    var regMail by remember { mutableStateOf("") }
    var regPwd by remember { mutableStateOf("") }

    // ============================================================
    // 公告：由 SyncCenter 统一推送
    //
    // 旧实现的两个 bug：
    //   1. annChecked 只查一次，之后永不重查
    //   2. 公告 id 变了但 App 不重启就不会重新检查
    //
    // 现在改为：SyncCenter 每 20 秒拉一次 sync 接口，
    // 发现 annRev 变化且没看过就弹。同时首次启动立即查一次。
    // ============================================================
    val announcement = sy.great.toolbox.tools.SyncCenter.currentAnnouncement

    // 登录后立即同步一次（拿到称号等）
    androidx.compose.runtime.LaunchedEffect(route) {
        if (route == "home") {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    sy.great.toolbox.tools.SyncCenter.syncNow(ctx)
                } catch (_: Throwable) {
                }
            }
        }
    }

    // 前台轮询：每 20 秒同步一次（应用在后也不会被回收）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(20_000L)
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    sy.great.toolbox.tools.SyncCenter.syncNow(ctx)
                }
            } catch (_: Throwable) {
            }
        }
    }

    // ---- 路由历史栈 ----
    // 侧滑/返回键不再总是回主页，而是回“上一个页面”。
    val history = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    fun navTo(target: String) {
        if (target != route) {
            // 防止历史无限增长
            if (history.size > 32) history.removeAt(0)
            history.add(route)
            route = target
        }
    }
    fun popBack() {
        if (history.isNotEmpty()) {
            route = history.removeAt(history.size - 1)
        } else {
            route = "home"
        }
    }

    val isHome = route == "home"
    // 隐私、方案、卡密、邮箱页不允许返回键跳过
    val canBack = route.isNotEmpty() && !isHome &&
            route != "privacy" && route != "plan" &&
            route != "cardkey" && route != "mailverify" &&
            route != "login" && route != "register" && route != "register_verify"
    BackHandler(enabled = canBack) {
        popBack()
    }

    // 状态栏高度。
    //
    // 注意：WindowInsets.statusBars 与 asPaddingValues() 都是 composable 扩展，
    // 只能在 composable 上下文直接调用，不能放进 remember{} 的 lambda。
    // 它们的开销很小（Compose 内部已缓存），无需自己 remember。
    val statusTop = androidx.compose.foundation.layout.WindowInsets.statusBars
        .asPaddingValues().calculateTopPadding()
    val isHomeRoute = route == "home"

    // 自带顶栏（miuix TopAppBar）的路由集合。
    //
    // 这些页面的 TopAppBar 内部已经处理了状态栏，
    // 外层再叠一层 statusTop 就会出现“双倍顶部空白”（白边）。
    //
    // 注意：只能在 AnimatedContent 内容里用 current（新旧页各自判断），
    // 不能在外部用 route，否则进出动画期间新旧两页会拿到同一个值。
    fun hasOwnTopBar(r: String): Boolean =
        r == "home" || r.isEmpty() ||
                r == "set_theme" || r == "set_bottombar" || r == "set_scale" ||
                r == "set_update" || r == "set_about"

    Box(
        Modifier
            .fillMaxSize()
            .background(if (AppSettings.themeMode.value == AppSettings.ThemeMode.Dark) Color(0xFF121212) else Color.White)
    ) {
        // 页面切换动画：新页淡入 + 右侧滑入 + 轻微放大；旧页淡出 + 缩小。
        // 每个功能的进入/退出都走这套过渡，节奏统一。
        androidx.compose.animation.AnimatedContent(
            targetState = route,
            // 关键（性能）：
            // 1) 不在 AnimatedContent 上做带条件变化的 padding。
            //    否则路由一变，padding 变 → 整棵子树（新旧两页）重新测量，动画首帧必卡。
            //    改为把状态栏高度放到内容里（见下方 Box）。
            // 2) sizeTransform = null：完全关闭“容器尺寸动画”。
            //    AnimatedContent 默认会为新旧两页不同尺寸做插值动画，
            //    这会导致每一帧都重新布局与重绘，是切页卡顿的主因。
            transitionSpec = {
                // 关键（性能）：用 .using(SizeTransform(clip = false)) 关闭“容器尺寸动画”。
                // AnimatedContent 默认会为新旧两页不同尺寸做插值，
                // 导致动画每一帧都要重新测量与布局两棵子树 —— 这就是“进出页面卡一下”的根因。
                // SizeTransform(clip = false) 关闭尺寸变化动画，只保留内容的淡入/位移。
                (
                    androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(Motion.FAST)
                    ) + androidx.compose.animation.slideInHorizontally(
                        animationSpec = androidx.compose.animation.core.tween(Motion.NORMAL),
                        initialOffsetX = { full -> full / 10 }
                    )
                )
                    .togetherWith(
                        androidx.compose.animation.fadeOut(
                            animationSpec = androidx.compose.animation.core.tween(Motion.FAST)
                        )
                    )
                    .using(androidx.compose.animation.SizeTransform(clip = false))
            },
            // 说明：本版本 Compose 的 AnimatedContent 没有 sizeTransform 参数，
            // 无法在这里直接关闭“容器尺寸动画”。
            // 已通过“去掉外层条件 padding + 子页包一层固定尺寸 Box”
            // 来避免容器尺寸变化，达到同样目的。
            label = "route"
        ) { current ->
            // 每个页面包一层：只做静态 padding，不参与尺寸动画。
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxSize()
                    // 自带顶栏的页面（含主页）不再叠加状态栏 padding，
                    // 否则会出现双倍顶部空白（白边）。
                    .padding(top = if (hasOwnTopBar(current)) 0.dp else statusTop)
            ) {
            when (current) {
            "" -> {}

            // ---- 新增：账号登录 / 注册 ----
            "login" -> LoginScreen(
                onLoggedIn = { afterLogin() },
                onGoRegister = { route = "register" }
            )
            "register" -> RegisterScreen(
                onCodeSent = { n, e, p ->
                    regName = n
                    regMail = e
                    regPwd = p
                    route = "register_verify"
                },
                onBackToLogin = { route = "login" }
            )
            "register_verify" -> RegisterVerifyScreen(
                username = regName,
                email = regMail,
                password = regPwd,
                onRegistered = { afterLogin() },
                onBack = { route = "register" }
            )

            // ---- 原有流程（完全保留，未改动）----
            "mailverify" -> MailVerifyScreen(onSent = {
                // 邮件发送成功后进入验证码输入页
                route = "cardkey"
            })
            "cardkey" -> CardKeyScreen(onPassed = {
                // 验证码通过后，再按首启流程决定去向
                route = when {
                    !Plan.agreed.value -> "privacy"
                    Plan.plan.value == Plan.NONE -> "plan"
                    else -> "home"
                }
            })
            "privacy" -> PrivacyScreen(
                onAgree = {
                    Plan.setAgreed(ctx, true)
                    route = if (Plan.plan.value == Plan.NONE) "plan" else "home"
                },
                onExit = {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            )
            "plan" -> PlanScreen(onPicked = { route = "home" })
            "home" -> AppShell(
                onOpen = { navTo(it) },
                onSettingsAction = { action -> navTo("set_" + action) },
                selectedTab = sy.great.toolbox.tools.RootState.mainTab.value,
                onTabChange = { sy.great.toolbox.tools.RootState.mainTab.value = it },
                onLogout = {
                    // 先本地清 token 并跳回登录页（不等网络，避免卡住）
                    AuthApi.clearToken(ctx)
                    sy.great.toolbox.tools.PageCache.clear()
                    route = "login"
                    // 服务端 logout 丢到后台线程，失败也不影响体验。
                    // 不用协程：这里是普通 lambda，没有 CoroutineScope。
                    Thread {
                        try { AuthApi.logout(ctx) } catch (_: Throwable) {}
                    }.start()
                },
                onChangeUsername = { navTo("acc_username") },
                onChangeEmail = { navTo("acc_email") },
                onDeleteAccount = { navTo("acc_delete") },
                onFeedback = { navTo("feedback") },
                onOpenMail = { navTo("mail") },
                onOpenRanking = { navTo("ranking") },
                onOpenAnnouncement = {
                    // 重新弹出公告弹窗。
                    //
                    // 弹窗本身常驻在组合树里，靠 SyncCenter.currentAnnouncement 控制显隐：
                    // 把之前关掉的公告放回去，show 状态会随 ann.id 重建为 true。
                    sy.great.toolbox.tools.SyncCenter.reopenAnnouncement()
                },
                onPrivacy = { navTo("privacy_settings") },
            )

            // ---- 邮箱（站内信）----
            "mail" -> sy.great.toolbox.ui.MailScreen(onBack = { popBack() })

            // ---- 排行榜 ----
            "ranking" -> sy.great.toolbox.ui.RankingScreen(onBack = { popBack() })

            // ---- 隐私设置 ----
            "privacy_settings" -> sy.great.toolbox.ui.pages.PrivacySettingsPage(onBack = { popBack() })

            // ---- NFC ----
            "nfc" -> {
                val supported = sy.great.toolbox.tools.NfcHelper.isSupported(ctx)
                val enabled = sy.great.toolbox.tools.NfcHelper.isEnabled(ctx)
                sy.great.toolbox.ui.NfcHomeScreen(
                    onBack = { popBack() },
                    nfcSupported = supported,
                    nfcEnabled = enabled,
                    onOpenRead = { navTo("nfc_read") },
                    onOpenWrite = { navTo("nfc_write") },
                    onOpenInfo = { navTo("nfc_read") },
                    onOpenCode = { navTo("code") },
                )
            }

            "nfc_read" -> sy.great.toolbox.ui.NfcReadScreen(onBack = { popBack() })

            "nfc_write" -> sy.great.toolbox.ui.NfcWriteScreen(
                onBack = { popBack() },
                onRequestWrite = { payload ->
                    nfcWritePayload = payload
                    navTo("nfc_write_tag")
                },
            )

            // 写入阶段：靠卡执行
            "nfc_write_tag" -> sy.great.toolbox.ui.NfcReadScreen(
                onBack = { popBack() },
                writePayload = nfcWritePayload,
            )

            // ---- 二维码 / 条形码 ----
            "code" -> sy.great.toolbox.ui.CodeScreen(onBack = { popBack() })

            // ---- 账号子页 ----
            "feedback" -> FeedbackPage(onBack = { popBack() })
            "acc_username" -> ChangeUsernamePage(
                currentName = AuthApi.username(ctx),
                onDone = { popBack() },
                onBack = { popBack() }
            )
            "acc_email" -> ChangeEmailPage(
                currentEmail = AuthApi.email(ctx),
                onDone = { popBack() },
                onBack = { popBack() }
            )
            "acc_delete" -> DeleteAccountPage(
                username = AuthApi.username(ctx),
                onDeleted = {
                    AuthApi.clearToken(ctx)
                    sy.great.toolbox.tools.PageCache.clear()
                    route = "login"
                },
                onBack = { popBack() }
            )
            "set_update" -> CheckUpdatePage(currentVersion = appVersion(ctx), onBack = { popBack() })
            // 主题设置、底栏设置、界面缩放均在主题页内（与 KernelSU 一致）
            "set_theme", "set_bottombar", "set_scale" -> ThemeSettingsPage(onBack = { popBack() })
            "set_about" -> AboutPage(version = appVersion(ctx), onBack = { popBack() })
            // 常用功能 / 工具里点进去的页面：顶部自带“‹ 标题”返回键
            "device" -> DeviceScreen(onBack = { popBack() })
            // 刷新率悬浮窗（系统级，Scene 同款交互）
            "fps" -> FpsOverlayScreen(
                onBack = { popBack() },
                onOpenHistory = { navTo("fps_history") },
            )
            // 帧率历史记录（从悬浮窗设置页进入）
            "fps_history" -> FpsHistoryScreen(onBack = { popBack() })
            // 反作弊阻断页（从反作弊检测页底部按钮进入）
            "anti_block" -> AntiCheatBlockScreen(onBack = { popBack() })
            "root" -> RootScreen(onBack = { popBack() })
            "apps" -> AppsScreen(onBack = { popBack() })
            "ping" -> PingScreen(onBack = { popBack() })
            "dns" -> DnsScreen(onBack = { popBack() })
            "port" -> PortScanScreen(onBack = { popBack() })
            "http" -> HttpScreen(onBack = { popBack() })
            "lan" -> LanScreen(onBack = { popBack() })
            "codec" -> CodecScreen(onBack = { popBack() })
            "cpu" -> CpuFreqScreen(onBack = { popBack() })
            "sched" -> SchedScreen(onBack = { popBack() })
            "thermal" -> ThermalScreen(onBack = { popBack() })
            "thread" -> ThreadOptScreen(onBack = { popBack() })
            "spoof" -> SpoofScreen(onBack = { popBack() })
            "selinux" -> SelinuxScreen(onBack = { popBack() })
            // ELF 逆向：上传页顶部自带蓝色“〈”返回栏，
            // 必须把 popBack 传进去，否则默认空实现会让返回键点了没反应。
            "elf" -> ElfScreen(onBack = { popBack() })
            // 格式转换：图片 / 音频 / 视频
            "convert" -> ConvertScreen(onBack = { popBack() })
            // 金属探测器：磁力计 + 震动 + 提示音
            "metal" -> MetalDetectorScreen(onBack = { popBack() })
            // 网络测速：下载 / 上传 / 延迟
            "speed" -> SpeedTestScreen(onBack = { popBack() })
            // Shell 脚本极端混淆（内置 bash，无需 root）
            "shellobf" -> ShellObfuscateScreen(onBack = { popBack() })
            else -> AppShell(
                onOpen = { navTo(it) },
                onSettingsAction = { action -> navTo("set_" + action) },
                selectedTab = sy.great.toolbox.tools.RootState.mainTab.value,
                onTabChange = { sy.great.toolbox.tools.RootState.mainTab.value = it },
                onLogout = {
                    AuthApi.clearToken(ctx)
                    sy.great.toolbox.tools.PageCache.clear()
                    route = "login"
                    Thread {
                        try { AuthApi.logout(ctx) } catch (_: Throwable) {}
                    }.start()
                },
                onOpenMail = { navTo("mail") },
            )
            }
            }
        }

        // 已删除右上角那个浮空的“返回”文字按钮：
        // 每个子页现在都有自己的蓝色“〈”返回键，
        // 这里再浮一个既重复又逆宾。
        // 系统返回手势仍由上面的 BackHandler 处理。

        // ---- 公告弹窗（KernelSU 同款 OverlayDialog）----
        //
        // 常驻在组合树里，靠 show 控制显隐。
        // 不能写成 if (ann != null) { AnnouncementDialog(...) }，
        // OverlayDialog 内部要建 Window，随重组频繁创建销毁会崩溃。
        val ann = announcement
        if (ann != null) {
            // AnnouncementDialog 需要 MutableState<Boolean>，
            // 这里用 remember(ann.id) 包一层：换新公告会自动重建弹窗。
            val showState = androidx.compose.runtime.remember(ann.id) {
                androidx.compose.runtime.mutableStateOf(true)
            }
            sy.great.toolbox.ui.component.AnnouncementDialog(
                show = showState,
                announcement = ann,
                onDismiss = {
                    sy.great.toolbox.ui.component.AnnouncementStore.markSeen(ctx, ann)
                    sy.great.toolbox.tools.SyncCenter.dismissAnnouncement()
                },
            )
        }
    }
}
