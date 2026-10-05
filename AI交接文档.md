# 项目交接文档 — 交给下一个 AI

> 生成时间：2026-10-05
> 原因：对话长度即将上限，需要交接
> 接手前请先读完本文，尤其是「二、工具环境限制」和「三、禁止清单」

---

## 一、你在接手什么

一个 **Android 原生工具箱 App**（Kotlin + Jetpack Compose）。

**工程位置**：`/storage/emulated/0/MT2/mcp/csdemo/`

**当前状态**：源码完整，构建配置已迁到 KernelSU 同款工具链，能编译（最后一次构建到 Kotlin 编译阶段）。

**用户是谁**：中文用户，非专业开发者，用 MT 管理器 + AndroidIDE 在手机上开发。**全部操作在手机完成**。用 **GitHub Actions 云端编译**（不是本地 IDE 编译）。

---

## 二、工具环境限制（必须记住，否则反复出错）

你通过 **MT MCP** 操作文件。

### 限制 1：只能读写 Home 目录
```
Home = /storage/emulated/0/MT2/mcp/
```
- 可读写：Home 及其子目录
- 其他路径默认拒绝
- 不在 Home 下的文件，必须让用户自己移动

### 限制 2：没有编译能力
**绝对不能编译 Kotlin、不能跑 Gradle、不能执行任何命令。**
- 改完代码后必须**自己逐行复核语法**
- **不要声称「已编译通过」** —— 你没有这个能力
- 用户会推送 GitHub → Actions 编译 → 把报错贴回来

### 限制 3：MCP 会间歇性断开
报错形如 `Failed to connect to /127.0.0.1:8787` 或 `MCP client ... is not connected`。
**处理**：直接重试同一个调用，通常第 2~3 次就好。

### 限制 4：目录创建返回值不可信
`mt_file_create_directory` 报 `changed: true` 但父目录不存在时**实际没建**。
`mt_file_edit_text` 在父目录不存在时**也返回成功但文件没落盘**。
**正确做法**：建目录 → `mt_file_stat` 验证 → 再写文件 → 再 `stat` 验证。

### 限制 5：写入大文件用 append
写 `预审计代码.txt` 这种大文件时，用 `mt_file_append_text` 分段追加，避免一次超长失败。

---

## 三、禁止清单（用户明确拒绝的方向，不要再做）

1. **❌ 不要擅自动 UI** —— 用户原话：「改动我的 UI 是高危操作」。
   - 改 UI 前**必须先问**，或者用户明确要求才动。
   - 用户很满意现有视觉，不要「优化」它。
2. **❌ 不要把静态色改成动态而破坏现有页面** —— 老页面（Home.kt 等）的 UI 已弃用，只保证能编译即可。
3. **❌ 不要做假实现** —— 读不到就说读不到，失败就说失败原因。
4. **❌ 不要写 AI 味文案** —— 不用 emoji 堆砌、不写「赋能/生态/一站式」这类空话。
   - 白底黑字，一个蓝色强调（Accent = 0xFF1B6EF3）
   - 文案用数据说话，动效克制
5. **❌ 不要一次改几千行** —— 用户经历过多次「一次写太多导致全部编译失败」。
6. **❌ 不要装编译器** 

---

## 四、工具链现状（已迁移，勿回退）

| 项 | 值 | 文件 |
|---|---|---|
| AGP | 9.4.1 | `build.gradle.kts`(root) |
| Kotlin | 2.4.20（AGP 9 内置，不再用 kotlin.android 插件） | 同上 |
| Compose Compiler | `org.jetbrains.kotlin.plugin.compose` 2.4.20 | 同上 |
| serialization | `org.jetbrains.kotlin.plugin.serialization` 2.4.20 | 同上 |
| Compose BOM | 2026.09.00 | `app/build.gradle.kts` |
| material3 | 1.5.0-alpha28 | 同上 |
| compileSdk/targetSdk | 37 | 同上 |
| minSdk | **33**（miuix-blur 要求） | 同上 |
| JDK | 21 | 同上 |
| 云端 | GitHub Actions（JDK21 + Gradle 9.7.1） | `.github/workflows/build.yml` |

**关键依赖**：
```
miuix-ui-android / miuix-blur-android / miuix-icons-android
miuix-preference-android / miuix-nav-android  : 0.9.4
material-icons-extended : 1.7.8  （必须固定版本，BOM 2026 已移除该库）
dev.rikka.shizuku:api / provider : 13.1.5
kotlinx-serialization-core : 1.7.3
```

**`buildFeatures { aidl = true }`** —— Shizuku UserService 的 AIDL 必需，不要关。

---

## 五、当前已完成的功能（不要重做）

### 5.1 主页 = KernelSU 主页 UI（完整复制）
- `com.csdemo.ksu` 包下：`HomeMiuix.kt` / `HomeUiState.kt` / `Kernels.kt` / `WarningCard.kt` / `StatusTagMiuix.kt` / `WarningLevel.kt` / `LatestVersionInfo.kt` / `BlurExt.kt`
- 三态状态卡（自动优先 root）：
  - 有 root → 绿色「su 已授权[root]」
  - 无 root 有 ADB → 紫色「ADB 已授权[adbshell]」
  - 都没有 → 蓝色「基础模式运行中[user]」
- 绿/紫/蓝卡布局同构：`Row(IntrinsicSize.Min)` + `Card(fillMaxWidth)` + `PressFeedbackType.Tilt`
- **注意**：双色卡（adb+root 同时可选）已**停用**，`DualStatusCard` 保留但标了 `@Suppress("unused")`，不要重新启用（用户明确不要）。

### 5.2 主页吸顶效果
`LazyColumn` 必须带 `nestedScroll(scrollBehavior.nestedScrollConnection)`，否则 `TopAppBar` 收不到滚动，标题不会「左上角 → 上滑吸顶居中」。

### 5.3 液态玻璃底栏（完整复制 KernelSU）
`ui/liquid/`（Lens/CombinedBackdrop/Vibrancy/InnerShadow）+ `ui/bottombar/`（FloatingBottomBar/DampedDragAnimation/InteractiveHighlight/DragGestureInspector）

**关键**：页面内容必须 `.layerBackdrop(backdrop)` 注册进背景层，底栏才能采样到内容、显示折射。

### 5.4 设置页
- `SettingsPage` / `ThemeSettingsPage` / `CheckUpdatePage` / `AboutPage`（在 `ui/pages/AppPages.kt`）
- 用 miuix 的 `Scaffold` + `TopAppBar` + `Card` + `ArrowPreference` / `SwitchPreference` / `OverlayDropdownPreference`
- **`Scaffold` 必须传 `popupHost = { }`**，否则点击下拉会闪退
- 界面缩放：用 `ArrowPreference` + `Slider`（0.8~1.1），真实生效（`MainActivity` 的 `LocalDensity`）

### 5.5 关于页
- `AboutPage`：Logo + 应用名 + 版本 + 信息/设备/许可卡
- **背景 = KernelSU 动态色块**（`com.csdemo.ksu.effect` 全包 6 文件）
- 用 `rememberLayerBackdrop()` + `BgEffectBackground(bgModifier = Modifier.layerBackdrop(backdrop))` + `GlassCard`（`textureBlur` 采样）→ 半透明玻璃 + 内容随背景流动

### 5.6 深色模式
- **`isAppInDark()`**（`ui/theme/Theme.kt`）—— 读 `AppSettings.themeMode`，**不是** `isSystemInDarkTheme()`
- 主页状态卡、关于页背景都必须用 `isAppInDark()`，否则应用内选深色而系统浅色时不生效
- `AppShell` 的 `MiuixTheme` 要传 `ThemeController(mode, isDark = dark)`
- 已改深色的文件：`Kit.kt` / `Widgets.kt` / `DeviceScreens` / `NetScreens` / `AppsScreens` / `TuneScreens` / `SelinuxScreen` / `SpoofScreen` / `AppShell` 等
- **未改深色的文件（待办）**：`ElfScreen.kt` / `CfgGraphScreen.kt` / `OnboardScreens.kt` / `CardKeyScreen.kt` / `MailVerifyScreen.kt` / `CfgView.kt` / `Home.kt`

### 5.7 ADB shell 模式（Shizuku UserService）
- `tools/AdbShell.kt` + `tools/UserService.kt` + `app/src/main/aidl/com/csdemo/IUserService.aidl`
- `AndroidManifest.xml` 里有 `rikka.shizuku.ShizukuProvider` + `<service android:name="com.csdemo.tools.UserService" android:process=":adbshell" android:exported="true"/>`
- **注意**：`Shizuku.bindUserService` **必须在主线程**调用（否则绑定失败）。`SpoofScreen` 里是先主线程 `ensureService` 再进 IO。
- **`Shizuku.newProcess` 是 private，不能用**。

### 5.8 电量伪装
- `DeviceSpoof.applyBattery` / `restoreBattery` —— 用 **`dumpsys battery set level N`** / **`dumpsys battery reset`**
- **`level` 只接受 1~100**，>100 要裁切并如实告知（不要假装成功）
- 执行后会用 `dumpsys battery` 回读验证（`readBatteryLevel`）
- root 或 ADB(Shizuku) 都可执行；成功/失败弹 Toast

### 5.9 页面返回键
- `ui/PageHeader.kt`：`PageHeader(title, onBack)` = 绘制箭头（`←`）+ 标题，箭头用 Canvas 画（**横线要长**，`leftX = 0.08f, rightX = 0.95f`）
- **只给「常用功能」和「工具」里的子页**用（15 个页面）
- `MainActivity` 传 `onBack = { popBack() }`

### 5.10 其它
- 邮箱验证旁路：`MailAuth.load()` 里检测 `/storage/emulated/0/admin` 存在则跳过验证
- root 检测节流：`RootState.detectOnEnter()` 30 秒内不重复检测
- `AdminShell`… 见上

---

## 六、待办 / 未解决问题（重点）

### 🔴 P0：进入/退出子页面卡顿（用户反复反馈，**未解决**）

**用户描述**：
> 「切换 tab 页面倒没问题，就是里面所有进入页面的控件，进入页面/退出页面都会卡一下，然后不流畅」
> 「大概 45~60fps，退出的时候要卡一下再退出」
> 「每个可以进入页面的都是这样，我就要平滑的进出入效果」

**已做但无效的尝试**：
- ❌ `AnimatedContent` 去掉 `scaleIn/scaleOut`
- ❌ 去掉容器条件 padding
- ❌ `statusTop` 用 remember（后又改回直接调用 —— 因为 `WindowInsets.statusBars` 和 `asPaddingValues()` 都是 `@Composable` 扩展，不能放 `remember{}` 里）
- ❌ 尝试 `sizeTransform = null` —— **本版本 Compose 没有这个参数**，编译报错
- ✅ `AppsScreen` 扫描改异步（有效，但没解决整体卡顿）
- ✅ `CpuFreqScreen`/`SchedScreen` 读 cores 改异步（同上）

**已确认的根因分析**：
- `AnimatedContent` 是**布局阶段动画**（每帧测量+布局+绘制新旧两页）→ 高刷下必掉帧
- KernelSU 流畅是因为它的**页面内容极轻**（About = 一张图 + 链接），不是因为它用了 `NavDisplay`
- 但用户坚持要「照搬 KernelSU 的思路」

**已铺好但未接完的 B 方案（换 NavDisplay）**：
- ✅ `build.gradle.kts`(root) 已加 `org.jetbrains.kotlin.plugin.serialization` 2.4.20
- ✅ `app/build.gradle.kts` 已加 serialization 插件 + `kotlinx-serialization-core:1.7.3`
- ✅ `ui/nav/AppNav.kt` 已建：`AppRoute`（21 个 data object，实现 `NavKey`）+ `routeFromId()` + `AppNavigator` + `rememberAppNavigator` + `LocalAppNavigator`
- ❌ **`MainActivity` 的 `AppRoot()` 还没改成 `NavDisplay`** ← 下一步在这里

**KernelSU 的 NavDisplay 用法（参考）**：
```kotlin
// ui/navigation3/Navigator.kt（KernelSU）
@Composable
fun rememberNavigator(startRoute: Route): Navigator {
    val backStack = rememberNavBackStack<Route>(startRoute)
    return remember(backStack) { Navigator(backStack) }
}

// MainActivity（KernelSU）
NavDisplay(
    backStack = navigator.backStack,
    effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
    onBack = { navigator.pop() },
) {
    entry<Route.Main>(swipeDismiss = swipeDismiss) { mainScreenEntry() }
    entry<Route.About>(swipeDismiss = swipeDismiss) { AboutScreen() }
    // ...
}
```
import：`top.yukonga.miuix.kmp.nav.core.NavDisplay` / `NavDisplayEffects` / `rememberNavSystemCornerRadius` / `NavBackStack` / `NavKey` / `rememberNavBackStack`

**⚠️ 重要提醒**：`NavDisplay` 内部**也可能是布局阶段转场**，换过去**不一定能解决**。下一个 AI 要先判断：
- 如果用户接受，**优先做「页面轻量化」**（参考下面的 P1），比换导航更可能有效
- 如果用户坚持换，就接完 B

**「页面轻量化」建议（更可能有效）**：
1. **`ElfScreen`** 的 `CfgBuilder.discoverFunctions(elf)` 在 `remember(elf){}` 里**同步跑** → 改成 `LaunchedEffect` 异步
2. **`AppShell` 的 `beyondViewportPageCount = tabs.size - 1`** → 改小（如 0 或 1），别让 4 页同时组合/绘制
3. 检查所有子页的 `LaunchedEffect` 是否在动画期间抢主线程

### 🔴 P1：其它未完成

1. **深色未适配的页面**：`ElfScreen` / `CfgGraphScreen` / `OnboardScreens` / `CardKeyScreen` / `MailVerifyScreen`（把 `Ink/Paper/Line` → `LocalPalette.current.xxx`）
2. **ELF 逆向的 P0 遗留**（交接文档旧版有详述，未做）：
   - 间接跳转 `br Xn` 目标解析 / Jump Table（`ADRP+ADD+LDR+BR`）
   - 真正的递归函数发现
   - Tail Call 识别
   - 分支条件语义（保留 Z/N/C/V 谓词）
   - SSA / 类型恢复（伪 C）
3. **`ui/PageHeader.kt` 的 `PageHeader`/`BackArrow`**：目前只在 15 个页面用；`ElfScreen`/`CfgGraphScreen` 的旧「← 返回」已被删除（改为系统手势），**若用户要，需重新加回**

---

## 七、已知 Bug 清单

### 已修复（不要重复修）
- ✅ `pressable` 的开关 bug（`Motion.pressable` 里 `rememberUpdatedState`）—— 解决「开了关不掉/关了开不了」
- ✅ 界面缩放闪退（`LocalDensity` 反复重建 → `remember(base, scale)`）
- ✅ 点击「界面缩放」闪退（`Scaffold` 缺 `popupHost`）
- ✅ 点击「关于」闪退（`painterResource` 不能读 `<inset>` 包裹的 drawable → 改 `R.mipmap.ic_launcher_native`）
- ✅ 深色没适配（`isSystemInDarkTheme` → `isAppInDark`）
- ✅ `WindowInsets.statusBars` 放进 `remember{}` 导致编译错（它是 `@Composable` 扩展）
- ✅ `AnimatedContent` 没有 `sizeTransform` 参数（不要再用）
- ✅ 双权限切换后 Root 检测串味（已改为自动优先 root；`RootCheck.scan()` 有 `RootState.useAdb()` 门控）

### 待查
- 🔴 **进出子页面卡顿**（见 P0）

---

## 八、常见编译错误（踩过的坑）

| 错误 | 原因 | 正确做法 |
|---|---|---|
| `Unresolved reference 'CheckCircleOutline'` | BOM 2026 已移除 `material-icons-extended` | 固定版本 `1.7.8` |
| `Cannot access 'newProcess'... private` | Shizuku 13.x 的 `newProcess` 是 private | 用 `bindUserService` + AIDL |
| `@Composable invocations can only happen from...` | 把 `@Composable` 扩展放进了 `remember{}` lambda | `WindowInsets.statusBars` / `asPaddingValues()` 要直接在 composable 里调 |
| `No parameter with name 'sizeTransform'` | 本版本 Compose 无此参数 | 不要用 |
| `Unresolved reference 'preference'` | 没加 `miuix-preference-android` | 已在 `app/build.gradle.kts` 加了 |
| 点击下拉闪退 | `Scaffold` 缺 `popupHost` | `Scaffold(popupHost = { })` |

---

## 九、工作流程建议

```
1. 先 read_text 读实际代码（不要凭记忆）
2. 小步改（一次改 1~3 处）
3. 改完立即 read_text 复核
4. 确认花括号配对、导入齐全
5. 让用户推送 GitHub 编译
6. 用户贴回报错 → 修
```

**用户脾气直接**（会用「垃圾」「狗屎」这类词）—— **不要辩解，直接改**。
**不要问太多问题** —— 用户说过「你想咋来咋来，不要问我」。但**改 UI 必须先问**。
**回复要短** —— 用户不喜欢长篇大论。

---

## 十、关键文件不要碰

- `gradle/wrapper/gradle-wrapper.jar`（二进制，删了不能构建）
- `gradle.properties`（注意：`android.aapt2FromMavenOverride` 已删，云端编译不能有这个）
- `local.properties`
- `.github/workflows/build.yml`
- `app/src/main/assets/juanzeng.png`（捐赠码）

---

## 十一、一句话总结现状

**当前版本 = 「KernelSU 风格 UI + AArch64 ELF 静态分析 + Shizuku ADB」的工具箱 App。**

- 主页 / 底栏 / 设置 / 关于 = KernelSU 复刻（液态玻璃、动态背景、深色）
- 三态身份：root（绿）/ ADB（紫）/ user（蓝），自动优先 root
- 电量伪装：`dumpsys battery`（root 或 ADB 均可）
- **未解决**：进入/退出子页面卡顿（P0）
- **未做**：间接跳转解析、Jump Table、递归函数发现、SSA/类型恢复
