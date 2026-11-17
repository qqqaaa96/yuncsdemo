# 项目交接文档

> 最后更新：**2026-11-17**
> 当前版本：**1.0.3-beta（versionCode 103）**
> 接手前**必读**：零（用户习惯）、一（铁律）、二（环境）、八（工作流）

---

## 零、30 秒速览

**Android 工具箱 App + PHP 账号服务器** 双端项目。

| 端 | 位置 | 技术 |
|---|---|---|
| App | `/storage/emulated/0/MT2/mcp/csdemo/` | Kotlin 2.4.20 + Compose BOM 2026.09.00 + miuix 0.9.4 |
| 服务器 | `/storage/emulated/0/MT2/mcp/登录系统服务器/` | PHP 8.5 + SQLite + frp 内网穿透 |
| 参考 | `/storage/emulated/0/MT2/mcp/KernelSU-main/` | UI / 弹窗 / 主页参考 |
| 参考 | `/storage/emulated/0/MT2/mcp/AndroidLiquidGlass-kmp/` | 液态玻璃 demo（**不要直接引它的库，见 3.10**） |
| 参考 | `/storage/emulated/0/MT2/mcp/html/数学教学/` | 函数图像计算器的原始实现（含 math.js 版） |
| 参考 | `/storage/emulated/0/MT2/mcp/ssc-master/` | SSC 脚本编译器（**已放弃，见 5.4**） |

**无本地编译能力** —— 改完必须让用户推 GitHub Actions 验证。
**PHP 可以本地跑** —— 用户在 Termux 用 `php -S`。

---

## 零之二、用户习惯与要求（极其重要）

### 沟通方式

| 项 | 要求 |
|---|---|
| 语言 | 中文 |
| 长度 | **短、直接**。不要长篇大论，不要重复我说过的话 |
| 语气 | 不说教、不解释原理（除非被问）、不道歉过度 |
| token | **极其在意消耗**。不要贴大段代码回显，不要复述文件内容 |
| 报错 | 会自己贴到 `/storage/emulated/0/MT2/mcp/报错.txt`，**直接去读，不要让他贴** |

### 用户会生气的情况（已发生过多次）

1. **说"可能""大概""应该"** —— 他要确定答案。做不到就直说做不到，并说明原因。
2. **自己发挥加功能** —— 只要他要的，不要"顺手优化"、不要加没要求的按钮/标签/预设。
3. **用 HTML / WebView 实现** —— 明确要求**纯 Kotlin**。理由：效率低。
4. **走他的服务器** —— 他的服务器很垃圾，能本地 / 直连就本地直连。
5. **精度不一致** —— 原话：「用计算器算 1+1 弹出 3，你觉得好受吗」。计算类功能必须准确。
6. **同一个 bug 修两次没好** —— 修之前先把根因读明白，不要猜。

### 确认事项的做法

- 改动大（>3 文件 / >100 行）**先说方案 + 利弊**，让他选。
- 涉及到 UI、破坏性、删功能，**必须先问**。
- 不确定他的意思时，**问一句**，不要自己理解成别的。

---

## 一、铁律（绝不能违反）

### 1.1 不浪费轮次

- 页面报错 → **先问「页面上有没有报错文字」**，不要让用户跑 `php -i` / `curl` / `cat`
- 编译报错 → 用户会贴 `e: file:///...:行:列`，**只看那几行**，不要全文分析
- **不要连续让用户跑多条命令「排查」**，一次最多 1~2 条
- 用户说「报错」时，**先读 `/storage/emulated/0/MT2/mcp/报错.txt`**，不要去猜

### 1.2 一次只改 1~3 处

用户经历过多次「一次改太多 → 全部编译失败」。
**改完立刻 `read_text` 复核。**

### 1.3 绝不造假

- 读不到就说读不到
- **永远不要声称「已编译通过」** —— 没有本地编译能力
- PHP 的 `Deprecated` / `Warning` 会破坏 `header()` 跳转，要主动检查

### 1.4 不擅自动 UI

改 UI 前**必须先问**。用户原话：「改动我的 UI 是高危操作」。

### 1.5 不手写依赖库头文件

sdb / radare2 头文件含大量宏与内联，手写版**必定不准**。必须用真实源码包。

### 1.6 改「同一变量多处引用」前必须先搜次数

反复踩坑：`badgeWidth` 出现 2 次只改 1 处 → 编译报错。
**先 search 查出现次数，全改完再搜一次验空。**

### 1.7 局部变量不能向前引用

Kotlin 局部变量 / 函数按声明顺序解析。
踩过 3 次：`showSwitch`、`msg`、`avatarVer`。
**在 Composable 里插逻辑前，先扫一遍所有 val/var 位置。**

### 1.7b 插入代码前必须确认锚点唯一（已踩 3 次）

`mt_file_edit_text` 用 `insert_before_match` 时，如果 **锚点文本也出现在你要写入的内容里**，
锚点会被拼接两次，导致：

```kotlin
// 实际写进去的（错误）
private fun parseLong(v: Any?): Long {    private fun parseLong(v: Any?): Long {
```

后果：括号计数全乱 → 后面所有声明变成“局部函数” → 一堆看似无关的报错
（`Modifier 'private' is not applicable to 'local function'`、
`Interface 'Tok' cannot be local`、`Missing return statement`…）。

**已踩实例**：`CalcEngine.compileImplicit`、`CalcPlot.drawPolar`、`McpTools.parseLong`。

**正确做法**（任选一）：
1. 锚点带前后文，确保全文件唯一（如带上上一行 `}` 或下一行内容）
2. 改用 `replace_match`，把原内容一起写在 `writeText` 末尾
3. 插入后 **立即 `read_text` 复查那一段**，确认没有重复

### 1.8 不用 `truncate_bytes` 删代码段

**`mt_file_truncate_bytes(size=0)` 会清空整个文件**，不是删某一段！
已因此把 `AppPages.kt`（50KB）整文件清空过一次。
删除大段代码请用 `replace_match` 精确匹配，或整文件 `write_target` 重写。

### 1.9 不用「对象.全限定名.函数()」写法

```kotlin
// ❌ 非法，会报 Unresolved reference 'top'/'androidx'/'sy'
Modifier.top.yukonga.miuix.kmp.blur.layerBackdrop(bd)
androidx.compose.material3.Switch(...)   // 独立调用可以，链式不行

// ✅ 正确：先 import，再简写
import top.yukonga.miuix.kmp.blur.layerBackdrop
Modifier.layerBackdrop(bd)
```

踩过 3 次：`layerBackdrop`、`FloatingBottomBarItem`、`rememberLayerBackdrop`。

---

## 二、工具环境限制

### 2.1 Home

```
/storage/emulated/0/MT2/mcp/
```

不在 Home 下的文件，让用户自己移动。

### 2.2 目录创建返回值不可信

`mt_file_create_directory` 报 `changed: true` 但父目录不存在时**实际没建**。
**建目录 → stat 验证 → 写文件 → 再 stat 验证。**

### 2.3 MCP 会间歇断开

报 `MCP client ... is not connected` 或 `Connect timeout`，**直接重试同一个调用**。

### 2.4 没有 shell

git 命令必须让用户自己跑。

### 2.5 文件搜索的坑

| 问题 | 说明 |
|---|---|
| `mt_file_search` 只能搜目录 | 单文件用 `mt_file_search_text` |
| 参数名是 `nameCaseSensitive` | **没有** `caseSensitive` |
| 搜 `.git` 有大量 `invalid_encoding` | 要限定 `path` |
| **搜 `/*` 搜不到** | `/` 被当路径分隔符处理，改用 `pattern="'"` 或搜具体文本 |
| 搜 `'` 单引号有时也失效 | 用 `mt_file_read_text` 全文读 |
| `contentPattern` 搜目录只返回文件名 | 不返回命中行，需要逐文件用 `mt_file_search_text` |

### 2.6 编辑后 targetVersion 立刻变

每次 `mt_file_edit_text` 成功后版本号都变。
**连续改同文件必须重新 `read_text` 拿新版本号。**

### 2.7 新建文件必须用 write_target

`targetVersion="missing"` 时必须 `mode="write_target"`。

### 2.8 写大文件分多次

一次 `write_target` 太大可能被截断。大文档建议先写主体，再用 `insert_after_match` 追加。

### 2.9 `edit_text` 的 `matchText` 要足够独特

短文本会报 `FILE_TEXT_MATCH_AMBIGUOUS`（多次出现）。
**用带上下文的 2~3 行做锚点。**

---

## 三、App 端（csdemo/）

### 3.1 工具链（勿回退）

| 项 | 值 |
|---|---|
| AGP | 9.4.1 |
| Kotlin | 2.4.20（AGP 9 内置，**不要**加 kotlin.android 插件） |
| Compose Compiler | `org.jetbrains.kotlin.plugin.compose` 2.4.20 |
| Compose BOM | 2026.09.00 |
| material3 | 1.5.0-alpha28 |
| **material-icons-extended** | **1.7.8 固定**（BOM 已移除，不固定会 Unresolved） |
| compileSdk / targetSdk | 37 |
| minSdk | **33**（miuix-blur 0.9.4 要求） |
| JDK | 21 |
| NDK | 27.0.12077973 |
| CMake | 3.22.1 |
| miuix | 0.9.4（ui / blur / icons / preference / nav） |
| activity-compose | **1.13.0**（必须，1.9.3 会崩） |
| lifecycle | **2.11.0** |
| navigationevent-compose | **1.1.2**（miuix WindowDialog 必需） |
| zxing core | 3.5.3（二维码/条码） |

`buildFeatures { aidl = true }` 不能关（Shizuku UserService 用）。

### 3.2 底栏 5 个 tab（顺序不能乱）

```
0 主页 / 1 反作弊 / 2 常用功能 / 3 工具 / 4 我的
```

`MainTab` 枚举在 `ui/AppShell.kt`，顺序 = `HorizontalPager` 的 `when(page)` 索引，**改一处必须改另一处**。

⚠️ **关闭悬浮底栏时**，`AppShell.kt` 里 `if (!floating)` 分支逐个显式调用 `TabItem`，**加 tab 必须同步补一个**。

底栏图标用 `ui/TabGlyph.kt`（Canvas 矢量绘制，**不用 Emoji**）。
已有图标 kind：`home` / `anticheat` / `features` / `tools` / `profile` / `mail` / `announce`。
传其他值会走 `else -> drawCircle` 兜底（画个圆点）。

### 3.3 页面缓存（PageCache）

`tools/PageCache.kt` —— 内存级缓存，保持滚动位置 + 避免重复刷新。

```kotlin
PageCache.get<T>("mail")          // 读缓存
PageCache.put("mail", items)      // 写缓存
PageCache.isFresh("mail", 30_000) // 30 秒内算新鲜
PageCache.scroll / saveScroll     // 列表位置
PageCache.clear()                 // 退出登录时调
```

已接入：`MailScreen`、`RankingScreen`。

### 3.4 实时同步（SyncCenter）

`tools/SyncCenter.kt` —— 单接口轮询，每 20 秒。

```
GET api.php?action=sync
→ { annRev, ann, verRev, ver, tagRev, tag, nameRev, name, pointsRev, stat }
```

⏸ **重要：不靠 rev 判断更新**（曾因 rev 返回空串导致签到按钮恒灰）。
现在每次 sync 直接覆盖 `stat` / `tag` / `name`，字段不存在才保留旧值。

暴露的 State：
```kotlin
SyncCenter.currentAnnouncement   // 公告
SyncCenter.currentTag            // 称号（含 fxColors 流光）
SyncCenter.currentNameStyle      // 名字样式 + username/uid0/email 实际值
SyncCenter.currentStat           // 积分/经验/等级/签到
SyncCenter.latestVersion         // 版本
SyncCenter.applyStat() / applyTag()   // 签到后立即更新
SyncCenter.lastAnnouncement      // 最近一次公告（关闭后仍保留，供顶栏图标用）
SyncCenter.reopenAnnouncement()  // 重新弹出公告
```

### 3.5 设置存储（AppSettings）

`tools/AppSettings.kt` —— SharedPreferences + Compose State。

```kotlin
AppSettings.themeMode          // System / Light / Dark
AppSettings.floatingBottomBar  // 底栏是否悬浮
AppSettings.glassBottomBar     // 底栏是否开启玻璃效果
AppSettings.enableBlur         // 全局模糊开关
AppSettings.navigationBadge
AppSettings.pageScale          // 0.8 ~ 1.1
```

启动时 `MainActivity.onCreate` 里调 `AppSettings.load(this)`。

### 3.6 邮件主题（MailTheme）

`tools/MailTheme.kt` —— 邮件卡片的液态玻璃可调参数（最新加的）。

```kotlin
MailTheme.current = Params(
    refractionHeightDp = 25.92f,   // 边缘折射高度
    refractionAmountDp = 56.16f,   // 折射强度
    chromaticAberration = 0.24f,   // 色散 0~1
    blurDp = 4.41f,                // 高斯模糊
    cornerRadiusDp = 33.6f,        // 卡片圆角
)

MailTheme.setRefractionHeight(v) / setRefractionAmount(v) /
setChromaticAberration(v) / setBlur(v) / setCornerRadius(v)
MailTheme.reset()   // 恢复默认
```

**UI 在主题设置页**（`AppPages.kt` → `ThemeSettingsPage`）：「邮件主题」区块 + 5 个滑块 + 恢复默认。
滑块用 `MailParamSlider`（ArrowPreference + Slider，**拖动松手才生效**）。

启动时 `MainActivity` 调 `MailTheme.load(this)`。

### 3.7 邮件列表（MailScreen）

`ui/MailScreen.kt` —— 液态玻璃卡片列表。

**结构（与 demo 一致，关键）**：
```
Box {
    Box(Modifier.fillMaxSize().layerBackdrop(listBackdrop)
        .background(pal.paper)) { MailLineArt(...) }   ← 背景层（采样源）
    LazyColumn(contentPadding=16dp, spacedBy(16dp)) {  ← 内容层（兄弟）
        GlassMailCard(backdrop = listBackdrop, ...)
    }
}
```

⚠️ **背景与列表必须是兄弟节点**。写成 `Box.layerBackdrop { LazyColumn { 卡片 } }`
会导致卡片自己被包在采样层内，**玻璃采样不到下层画面**。

邮件图标：`MailLineArt` —— 纯线条大圆角信封，蓝色 `#3B82F6`，居中上移 88dp。
它放在采样层内部，作为玻璃折射的参照物。

### 3.8 邮件卡片（GlassMailCard）

`ui/component/GlassMailCard.kt`

```kotlin
Modifier.drawBackdrop(
    backdrop = backdrop,
    shape = { RoundedCornerShape(cornerDp) },
    effects = {
        vibrancy()
        blur(params.blurDp.dp.toPx())
        lens(
            refractionHeight = params.refractionHeightDp.dp.toPx(),
            refractionAmount = params.refractionAmountDp.dp.toPx(),
            chromaticAberration = params.chromaticAberration,
        )
    },
    onDrawSurface = { if (levelTint != Transparent) drawRect(levelTint) },
)
```

**等级配色**（按 `mail.level`）：

| level | 颜色 | 透明度 |
|---|---|---|
| `normal` 普通 | 不加底色 | — |
| `important` 须知 | `#FFC107` | 5% |
| `warning` 重要 | `#E53935` | 5% |

颜色极淡，只到「一眼能分出类别」，不破坏玻璃通透感。

### 3.9 下拉刷新（微信风格）

在 `MailScreen.kt` 的 `else ->` 分支内：

- `nestedScroll` + `dragPull`（普通 state，**同步更新**）+ `animPull`（Animatable 回弹）
- 顶部圆环：拖动时角度 = `下拉距离 / 阈值 × 360°`，刷新时 800ms 循环旋转
- 阈值 72dp，上限 140dp，阻尼动态衰减

⚠️ **拖动期间绝不能 `scope.launch { snapTo() }`** —— 手势与状态不同步，表现为不跟手/容易过头。

### 3.10 液态玻璃（重要历史坑）

项目自己有一份实现：`ui/liquid/`，基于 **miuix-blur**：

| 文件 | 作用 |
|---|---|
| `Lens.kt` | `BackdropEffectScope.lens()` 折射效果 |
| `Vibrancy.kt` | `BackdropEffectScope.vibrancy()` 色彩提亮 |
| `CombinedBackdrop.kt` | 两个 Backdrop 合并 |
| `InnerShadow.kt` | 内阴影 |

**正确用法（与底栏 FloatingBottomBar 同一套）**：
```kotlin
val backdrop = rememberLayerBackdrop {
    drawRect(pal.paper)   // ← 必须先画一层底！
    drawContent()
}

Modifier.layerBackdrop(backdrop)                    // 背景层：注册为采样源
Modifier.drawBackdrop(backdrop, shape, effects)     // 卡片：从 backdrop 取图
```

⚠️ **`rememberLayerBackdrop()` 空的会退化成灰黑**：
不加 `drawRect` 就采样不到东西，底栏 / 卡片都变成灰黑实心。

#### ❌ 已废弃的方案（不要再尝试）

1. **`io.github.kyant0:backdrop:2.0.1`（KMP 库）**
   - 它是 **JetBrains Compose Multiplatform** 产物，与 AndroidX Compose 在
     `GraphicsLayer` / `Modifier.Node` 底层二进制不兼容
   - 表现：**编译能过、运行必崩、全局异常处理器抳不到**
   - 崩溃堆栈：`prepareTreeImpl ↔ prepareListAndChildren` 递归 160+ 层

2. **自己写 `ui/glass/LiquidGlass.kt` + AGSL shader**
   - 用了 `GraphicsLayer.record() { drawContent() }`，这是**嵌套录制**
   - AndroidX Compose 不允许在 `draw()` 里开新的录制批次 → native 断言失败（SIGABRT）
   - 已**完全删除**，不要重建

3. **`lens()` 的 shape 类型限制**
   - `Lens.kt` 里 `cornerRadii` 只认 `RoundedRectangularShape` 或 `CornerBasedShape`
   - 其他类型会 **同步抛 `UnsupportedOperationException`**，直接崩闪退
   - 用 `RoundedCornerShape`（CornerBasedShape 子类）最稳

### 3.11 完整文件结构（全量清单）

> 下面每一行都是**实际存在的文件**。接手时先读这张表，再打开具体文件。
> 标注 ❌ 的是死代码/已废弃，不要往里加功能。

#### 入口

| 文件 | 职责 | 危险点 |
|---|---|---|
| `MainActivity.kt` | 唯一 Activity。初始化顺序 Plan→CardKey→MailAuth→AppSettings→MailTheme→VisitTracker；**字符串路由表**（route → 屏幕）；history 返回栈；全局崩溃日志 | 少一个初始化 = 功能静默失效；路由漏分支会掉到 `else -> AppShell` |

#### `ksu/` — KernelSU 风主页

| 文件 | 职责 |
|---|---|
| `HomeMiuix.kt` 53KB | 主页本体（ServerLinkItem / InfoCard / TopBar） |
| `HomeUiState.kt` | 主页 UI 状态 |
| `StatusTagMiuix.kt` / `WarningCard.kt` / `WarningLevel.kt` | 状态标签 / 警告卡 |
| `Kernels.kt` / `LatestVersionInfo.kt` | 内核版本信息 |
| `BlurExt.kt` | miuix blur 扩展 |
| `effect/` | 背景动态效果（BgEffectBackground/Config/Modifier/Painter、OS3BgFrag、ColorBlendToken、DeviceType） |

#### `tools/` — 全部业务逻辑

**账号与网络**

| 文件 | 职责 |
|---|---|
| `AuthApi.kt` | 账号网络层。**BASE = `http://denglu.fucku.top`**；含内置开发者账号 `qfmicd/123456`（本地拦截，不联网） |
| `AccountStore.kt` | 已登录账号历史（最多 5 个） |
| `MailApi.kt` | 站内信（含奖励领取） |
| `RankApi.kt` | 排行榜 / 签到 / 隐私 + **测速排行榜** |
| `SyncCenter.kt` | 20 秒轮询同步（公告/版本/称号/名字/积分） |
| `VersionInfo.kt` | 拉 version.json |
| `PingMonitor.kt` | 300ms 探 ping，主页「与服务器建立连接」 |
| `VisitTracker.kt` | 启动上报访问（不登录也报） |
| `Net.kt` | 网络诊断（ping/dns/端口/http/ARP/局域网扫描） |
| `Updater.kt` | App 内下载 APK（现已改为跳浏览器，降为傅底） |
| `AvatarStore.kt` | 头像压缩 / 本地缓存 / 下载 |
| `DeviceId.kt` | 设备指纹（注册配额用） |
| `SmtpMailer.kt` / `MailAuth.kt` | 旧客户端 SMTP（已被服务端取代，仅保留卡密验证流程） |

**认证与状态**

| 文件 | 职责 |
|---|---|
| `Plan.kt` | 隐私同意 + 基础/Root 方案 |
| `CardKey.kt` | 6 位卡密（默认 `123456`，每次冷启动重输） |
| `AppSettings.kt` | 主题/底栏/玻璃/模糊/缩放 |
| `MailTheme.kt` | 邮件玻璃 5 参数 |
| `PageCache.kt` | 内存页面缓存（列表 + 滚动位置） |
| `RootState.kt` | root/adb/user 三态 + `mainTab` |
| `AppVersion.kt` | 从安装包读版本号 |

**Root / Shell**

| 文件 | 职责 |
|---|---|
| `Shell.kt` | 命令执行 / `runRoot`（20s 超时） |
| `AdbShell.kt` | Shizuku |
| `UserService.kt` | Shizuku UserService（独立进程 `:adbshell`） |
| `RootCheck.kt` | root 检测 |
| `Selinu.kt` → `Selinux.kt` | SELinux 状态/切换 |
| `RootTune.kt` | 调频 / 调度 / 温控 / 线程优化 |
| `Device.kt` | 设备信息 |
| `Apps.kt` | 应用列表 |
| `IntegrityCheck.kt` | 完整性校验 |

**安全与检测**

| 文件 | 职责 |
|---|---|
| `VpnDetector.kt` | VPN 检测（5 层：Capabilities/接口/代理/路由/包名） |
| `VpnGuard.kt` | 检测到 VPN → 上报 → 弹 Toast → 杀进程 |
| `AntiCheat.kt` | 反作弊驱动检测（/proc/inte_*） |
| `DeviceSpoof.kt` / `SpoofData.kt` | 设备属性伪装（resetprop）+ 机型/芯片数据表 |

**帧率**

| 文件 | 职责 |
|---|---|
| `DisplayRateReader.kt` | **屏幕刷新率**（照搬 Scene FpsUtils：4 节点探测 + SF1013 兜底 + 250ms 节流）。仅 ADB/Root 可用 |
| `FpsReader.kt` | **真实帧率**（SurfaceFlinger --latency，取帧间隔中位数） |
| `FpsOverlayService.kt` | 前台服务 + 悬浮窗（双行：帧率 + 刷新率） |
| `FpsRecorder.kt` | 帧率历史采样 |
| `FpsChart.kt`(ui) / `FpsHistoryScreen.kt`(ui) | 图表 / 历史页 |
| `DeviceMetricsReader.kt` | 温度/CPU/内存采集 |
| `DisplayRateReader.kt` | （同上） |

**测速**

| 文件 | 职责 |
|---|---|
| `SpeedTest.kt` | 测速核心。源：Cloudflare/CacheFly/Hetzner + 国内镜像；**不封顶**（多轮 Range）；上传/下载/延迟/抖动 |
| `SpeedHistory.kt` | 本地测速历史 + 统计（峰值/谷值/平均/曲线） |

**计算器（新建）**

| 文件 | 职责 |
|---|---|
| `CalcEngine.kt` 49KB | **表达式引擎**：归一化 → 词法 → 递归下降 → AST → 求值。含 `classify`（识别显函数/极坐标/隐函数/参数方程）、`compileToLambda`（预编译，快 25 倍）、`asPoly`、`findRoots`、`autoRange` |
| `CalcPlot.kt` | **绘图**：坐标轴（X/Y 独立刻度密度）、drawFunction（断点检测）、drawPolar、drawParam、**drawImplicit（marching squares，零分配）** |
| `CalcFormat.kt` | 数字格式化 |

**格式转换**

| 文件 | 职责 |
|---|---|
| `ImageConverter.kt` | 图片格式转换（JPEG/PNG/WebP） |
| `AudioConverter.kt` | 音频 → WAV（MediaCodec） |
| `VideoConverter.kt` | 视频重封装 / 抽音轨 / GIF（**不重编码**） |
| `Codec.kt` / `Codes.kt` | 编码 / 哈希 |
| `CodeMaker.kt` | 二维码/条形码（ZXing） |
| `SvgToPng.kt` | SVG 转 PNG |
| `MetalDetector.kt` | 金属探测（磁力计 + 震动反馈引擎） |
| `NfcHelper.kt` | NFC 读写核心 |

**逆向**

| 文件 | 职责 |
|---|---|
| `ElfParser.kt` | 自研 ELF 解析（头/节/符号/程序头） |
| `ElfLoader.kt` | 从 APK/系统/用户路径找 .so |
| `ElfPatcher.kt` | **只允许等长或更短**的字符串替换 |
| `Arm64Disasm.kt` 35KB / `Arm64Encoder.kt` | 反汇编 / 编码 |
| `CfgBuilder.kt` 57KB | **可达性扫描**建 CFG（不支持 br Xn / NORETURN / tail call） |
| `JumpTableResolver.kt` | 跳转表解析 |
| `CfgLayout.kt` / `CfgModel.kt` / `CfgRouter.kt` / `CfgSvgExporter.kt` | 图布局 / 模型 / 路由 / SVG 导出 |
| `PseudoCode.kt` 31KB | 最早的老伪 C（注释包裹型，❌ 已被取代但未删） |
| `RadareBridge.kt` | JNI 桥（`libandroid-reverse-native` + `libradare_bridge`） |
| `decompiler/R2Decompiler.kt` | **主用引擎**：r2 pdfj → IR → C |
| `decompiler/DecompilerEngine.kt` | 旧引擎（自研链路，fallback） |
| `decompiler/CCodeGen.kt` / `IrTranslator.kt` / `SsaBuilder.kt` / `SsaRename.kt` / `ExprBuilder.kt` / `Structurer.kt` / `TypeInfer.kt` / `CfgIr.kt` / `DecompilerIR.kt` / `R2Json.kt` | 反编译各阶段 |
| `ShellObfuscator.kt` | ShellVMP V6（用 linker64 加载避开 noexec） |
| `PartitionBackup.kt` | 分区备份（⚠ **核心已完成但缺 UI**） |

**其它**

| 文件 | 职责 |
|---|---|
| `ExportUtils.kt` | 导出文件 |
| `FileImporter.kt` | 文件导入（SAF） |
| `Haptics.kt` | 震动反馈 |

#### `ui/` — 全部界面

**主壳与公共件**

| 文件 | 职责 |
|---|---|
| `AppShell.kt` | 主壳，5 tab + 两种底栏 + backdrop 采样层。**改 tab 要同步 3 处** |
| `Home.kt` | `FEATURES` 功能入口表（分组：检测/网络/工具/Root/逆向） |
| `Motion.kt` | 动效参数（FAST 120/NORMAL 220/SLOW 380/STAGGER 45）+ `pressable` + `staggerIn` |
| `Kit.kt` | 通用组件（FoldCard/RowItem/HLine/Spinner） |
| `Widgets.kt` | 零散小组件 |
| `PageHeader.kt` | 蓝色〈返回 + 标题 |
| `TabGlyph.kt` | 底栏矢量图标（home/anticheat/features/tools/profile/mail/announce） |
| `CardGlyph.kt` | 卡片矢量图标（battery/settings/chip/phone/edit/info/list） |
| `FeatureIcons.kt` | 功能图标 |
| `theme/Color.kt` | 固定色常量 + `Palette` + `LocalPalette` |
| `theme/Theme.kt` | `CsdemoTheme` + `isAppInDark()` |
| `theme/Type.kt` | 字体 |

**液态玻璃**

| 文件 | 职责 |
|---|---|
| `liquid/Lens.kt` | 折射 shader（**只认 CornerBasedShape**） |
| `liquid/Vibrancy.kt` | 色彩提亮 |
| `liquid/CombinedBackdrop.kt` | 合并两个 backdrop |
| `liquid/InnerShadow.kt` | 内阴影 |
| `bottombar/FloatingBottomBar.kt` | 浮动底栏（含玻璃） |
| `bottombar/DampedDragAnimation.kt` | 阻尼拖拽动画 |
| `bottombar/DragGestureInspector.kt` / `InteractiveHighlight.kt` | 手势检查 / 高亮 |

**计算器（新建）**

| 文件 | 职责 |
|---|---|
| `calculator/CalculatorScreen.kt` | 主壳：9 个标签 + 子面板容器 |
| `calculator/CalcWidgets.kt` | 显示屏 / 按键 / 网格 / 输出框 / 按钮 |
| `calculator/BasicCalc.kt` | 普通 + 科学计算器 |
| `calculator/GraphCalc.kt` | **函数图像**：输入框 + 画布 + 参数滑块（自动识别类型/参数） |
| `calculator/LiquidGlassSlider.kt` | 液态玻璃滑块（miuix-blur 实现） |
| `calculator/OtherCalcs.kt` | 方程 / 矩阵 / 统计 / 进制 / 单位 / 几何 |

**各功能页**

| 文件 | 职责 |
|---|---|
| `LoginScreen.kt` / `RegisterScreen.kt` | 登录 / 注册 |
| `MailVerifyScreen.kt` | 邮箱验证 |
| `CardKeyScreen.kt` | 卡密验证 |
| `OnboardScreens.kt` | 隐私 / 方案选择 |
| `MailScreen.kt` 58KB | 邮箱（玻璃卡片 + 下拉刷新 + 详情） |
| `RankingScreen.kt` | 积分排行榜 |
| `SpeedTestScreen.kt` 31KB | 测速页（指针仪表盘 + 历史曲线） |
| `SpeedRankScreen.kt` | 测速排行榜（单行布局：头像/名字/网络/上下行/延迟/消耗） |
| `SpoofScreen.kt` 25KB | 设备伪装 |
| `FpsOverlayScreen.kt` 22KB | 帧率悬浮窗设置 |
| `FpsHistoryScreen.kt` / `FpsChart.kt` | 帧率历史 / 图表 |
| `ElfScreen.kt` 69KB | ELF 逆向主界面 |
| `CfgGraphScreen.kt` 48KB | 控制流图（已加 clip，拖动不溢出） |
| `CfgView.kt` | CFG 视图辅助 |
| `AntiCheatPage.kt` / `AntiCheatBlockScreen.kt` | 反作弊 |
| `CodeScreen.kt` | 码生成 |
| `NfcHomeScreen.kt` / `NfcReadScreen.kt` / `NfcWriteScreen.kt` | NFC |
| `MetalDetectorScreen.kt` | 金属探测 |
| `ConvertScreen.kt` | 格式转换 |
| `DeviceScreens.kt` | 设备信息 |
| `AppsScreens.kt` | 应用列表 |
| `NetScreens.kt` | 网络工具（ping/dns/端口/http/局域网） |
| `TuneScreens.kt` | Root 调节（CPU/调度/温控/线程） |
| `SelinuxScreen.kt` | SELinux |
| `ShellObfuscateScreen.kt` | Shell 混淆 |
| `CaptchaRow.kt` | 图形验证码输入行 |
| `nav/AppNav.kt` | miuix NavKey（❌ **未使用**，实际走字符串路由） |

**component/**

| 文件 | 职责 |
|---|---|
| `GlassMailCard.kt` | 邮件玻璃卡片 |
| `TagBadge.kt` | 称号胶囊 + `FxText` 流光 |
| `CheckinCard.kt` | 签到卡 |
| `ConfirmDialogMiuix.kt` | **弹窗外壳（左边 dismissText / 右边 confirmText）** |
| `AnnouncementDialog.kt` | 公告弹窗（基于上面） |
| `UpdateDialog.kt` | 更新弹窗 |
| `ExpressiveDialog.kt` | 另一个弹窗 |
| `MarkdownWebView.kt` / `MarkdownText.kt` | Markdown 渲染 |
| `NoticeCard.kt` | ColorOS 风通知卡片 |

**pages/**

| 文件 | 职责 |
|---|---|
| `AppPages.kt` 49KB | HomePage / FeaturesPage / ToolsPage / SettingsPage / **ThemeSettingsPage**（含邮件主题滑块）/ CheckUpdatePage / AboutPage |
| `ProfilePage.kt` 30KB | 我的页 |
| `AccountPages.kt` | 改昵称 / 换邮箱 / 注销 |
| `FeedbackPage.kt` | 反馈 |
| `PrivacySettingsPage.kt` | 隐私设置 |

#### tools/mcp/ — MCP 服务器（把 App 变成 AI 可调用工具）

| 文件 | 职责 |
|---|---|
| `McpTool.kt` | 工具定义数据类 + **路径沙箱**（拒绝 `..` 越权）+ Schema DSL |
| `McpServer.kt` | HTTP 服务器 + JSON-RPC 2.0（纯 Socket，无第三方库）。双端口监听 |
| `McpTools.kt` | 12 个 ELF 工具实现（见下表） |
| `ui/McpAnalyzeScreen.kt` | MCP 页面（开关/地址/令牌/文件列表/工具清单/统计） |

**端点**：
```
本机   http://127.0.0.1:8989/mcp?token=<token>
局域网 http://<手机IP>:8787/mcp?token=<token>
工作目录 /storage/emulated/0/ELF分析处/mcp/
```

**认证三种都认**（因为不同客户端能力不同）：
1. `Authorization: Bearer <token>`
2. `X-Mcp-Token: <token>`
3. URL 参数 `?token=<token>` ← **最关键**，很多 SDK 只能配 URL

token 存 `SharedPreferences("csdemo_mcp")`，**只生成一次**，页面可重置。

**工具清单（12 个）**：

| 工具 | 作用 |
|---|---|
| `mcp_list_files` | 列目录 ELF + 大小 + SHA256 |
| `mcp_read_bytes` | 读任意偏移 hex |
| `mcp_hash` | MD5 / SHA-1 / SHA-256 |
| `elf_header` | 架构/位数/入口/段数 |
| `elf_sections` | 节区表 |
| `elf_segments` | 程序头 + 权限标志 |
| `elf_symbols` | 符号表（可筛函数） |
| `elf_strings` | 提取字符串（可筛内容） |
| `disasm_range` | 按地址反汇编 |
| `disasm_function` | 按函数名反汇编 |
| `cfg_function` | 控制流图 |
| `decompile_file` | 伪 C（Radare2） |

**没有迁移的**（原 SOMCP 项目有，但本项目缺依赖，**故意不注册**）：
rizin 系 / LIEF 系 / unidbg 动态调试 / JNI / 脱壳 / `mt_apk_*` 桥接。
不注册的原因：注册了却返回“不支持”会让 AI 反复试错。

**改 MCP 时的铁律**：
- 所有文件路径必须走 `McpToolContext.resolve()`，不要直接用 `File(path)`
- 新增工具会自动出现在 `tools/list`，无需额外注册
- 返回的 JSON 字段名固定，不要随意改（AI 靠它解析）

#### assets/

| 路径 | 说明 |
|---|---|
| `juanzeng.png` | 捐赠码（**不要动**） |#### assets/

| 路径 | 说明 |
|---|---|
| `juanzeng.png` | 捐赠码（**不要动**） |
| `logo.png` | App logo |
| `shbin/` | ShellVMP 运行环境（13 ELF + 13 库，**不要动**） |
| `shellvmp/` | 同上 |
| `webview/` | Markdown 样式表 |

```
java/sy/great/toolbox/
├── MainActivity.kt           路由 + 主题 + 全局初始化
├── ksu/                      KernelSU 同款主页
│   ├── HomeMiuix.kt          主页（ServerLinkItem / InfoCard / TopBar）
│   ├── HomeUiState.kt
│   ├── StatusTagMiuix.kt
│   └── effect/               背景效果
├── tools/
│   ├── AuthApi.kt            账号网络层，BASE = http://denglu.fucku.top
│   ├── AccountStore.kt       已登录账号历史（最多 5 个）
│   ├── MailApi.kt            站内信（含奖励领取）
│   ├── RankApi.kt            排行榜 / 签到 / 隐私
│   ├── SyncCenter.kt         实时同步中心
│   ├── PageCache.kt          页面缓存
│   ├── AppSettings.kt        主题/底栏/缩放设置
│   ├── MailTheme.kt          ✅ 新增：邮件玻璃参数
│   ├── PingMonitor.kt        300ms 探延迟（主页「与服务器建立连接」）
│   ├── VisitTracker.kt       App 启动上报访问
│   ├── Updater.kt            App 内下载 APK + 安装（现已降为傅底）
│   ├── CodeMaker.kt          二维码/条码生成（ZXing）
│   ├── NfcHelper.kt          NFC 读写核心
│   ├── PartitionBackup.kt    分区备份（⚠ 仍缺 UI）
│   ├── Shell.kt              命令执行 / runRoot
│   ├── AdbShell.kt           Shizuku
│   ├── RootState.kt          root/adb/user 三态
│   ├── VpnDetector.kt        VPN 检测（5 层）
│   ├── VpnGuard.kt           检测到 VPN → 上报 → 杀进程
│   ├── DeviceSpoof.kt        设备属性伪装（含电量伪装）
│   ├── SpoofData.kt          机型/芯片数据表
│   ├── ElfParser.kt / ElfLoader.kt / ElfPatcher.kt
│   ├── CfgBuilder.kt         控制流构建（56KB）
│   ├── Arm64Disasm.kt / Arm64Encoder.kt
│   ├── JumpTableResolver.kt
│   ├── ShellObfuscator.kt    ShellVMP V6（产物 .sh）
│   └── decompiler/           伪 C 反编译
│       ├── R2Decompiler.kt   Radare2 引擎（优先）
│       ├── R2Json.kt         r2 pdfj JSON 解析
│       ├── DecompilerEngine.kt
│       ├── CCodeGen.kt       SSA + 表达式 + 结构还原
│       └── SsaBuilder.kt / SsaRename.kt / ExprBuilder.kt /
│           Structurer.kt / TypeInfer.kt / IrTranslator.kt
├── ui/
│   ├── AppShell.kt           主壳，5 个 tab，MainTab 枚举在这里
│   ├── Home.kt               FEATURES 列表（功能入口定义）
│   ├── liquid/               液态玻璃效果实现（见 3.10）
│   ├── CodeScreen.kt         码生成页
│   ├── NfcHomeScreen.kt      NFC 主页
│   ├── NfcReadScreen.kt      NFC 读/写共用页（含 WritePayload）
│   ├── NfcWriteScreen.kt     NFC 写入表单
│   ├── MailScreen.kt         邮箱（液态玻璃 + 下拉刷新）
│   ├── RankingScreen.kt      排行榜
│   ├── SpoofScreen.kt        设备伪装（含电量伪装 UI）
│   ├── ShellObfuscateScreen.kt  Shell 混淆
│   ├── TabGlyph.kt           底栏矢量图标
│   ├── CardGlyph.kt          卡片矢量图标
│   ├── ElfScreen.kt          ELF 逆向（69KB）
│   ├── CfgGraphScreen.kt     控制流图（48KB）
│   ├── component/
│   │   ├── GlassMailCard.kt  ✅ 邮件卡片玻璃
│   │   ├── NoticeCard.kt     旧邮件卡片（已不用）
│   │   ├── TagBadge.kt       称号胶囊 + FxText 流光
│   │   ├── NoticeCard.kt     ColorOS 通知栏卡片
│   │   ├── CheckinCard.kt    签到卡
│   │   ├── ConfirmDialogMiuix.kt  弹窗外壳
│   │   ├── MarkdownWebView.kt
│   │   └── AnnouncementDialog.kt
│   └── pages/
│       ├── AppPages.kt       HomePage / SettingsPage / CheckUpdatePage /
│       │                     AboutPage / ThemeSettingsPage
│       ├── ProfilePage.kt    我的页
│       ├── AccountPages.kt   改昵称 / 换邮箱 / 注销
│       ├── FeedbackPage.kt
│       └── PrivacySettingsPage.kt
└── shellvmp/                 ❌ 不存在（SSC 计划已废弃，见 5.4）
```

### 3.12 miuix 弹窗的正确写法

**`WindowDialog` 依赖 `LocalNavigationEventDispatcherOwner`。**

缺依赖会报：
```
IllegalStateException: No NavigationEventDispatcher was provided
  via LocalNavigationEventDispatcherOwner
    at miuix.kmp.window.WindowDialogKt.WindowDialog
```

**必须同时有：**
```kotlin
implementation("androidx.activity:activity-compose:1.13.0")   // 1.9.3 不够
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
```

**弹窗必须放在稳定组合位置**（LazyColumn 的 item 外），否则 item 回收时窗口异常闪退。

### 3.13 Canvas / DrawScope 里不能读 @Composable

```kotlin
// ❌ 错误：Canvas 的 lambda 是 DrawScope.() -> Unit，不是 @Composable
Canvas(...) { drawRect(color = colorScheme.onBackground) }

// ✅ 正确：先读出来
val c = colorScheme.onBackground
Canvas(...) { drawRect(color = c) }
```

同类错误已踩 2 次。

### 3.14 踩过的 Kotlin 坑（完整表）

| 坑 | 说明 |
|---|---|
| `Modifier.androidx.compose.foundation.clickable{}` | **非法**。扩展函数必须 import 后写 `.clickable{}` |
| `item{}` 嵌 `item{}` | **非法**。LazyItemScope 里不能再调 `item` |
| `kotlinx.coroutines.MainScope().launch{}` | `launch` 是扩展函数，全限定名调不到 |
| `intent.flags and X != 0` | 优先级错，需 `(a and b) != 0` |
| `0x94000000` | 超 Int，被推断为 Long，加 `.toInt()` |
| `'\n\n'` | 是两字符不是 Char，写 `"\n\n"` |
| `sb.append('#\n')` | 同上，单引号里只能是单字符 |
| 注释里写 `/sys/*` | `/*` 会被当块注释开头 → **Unclosed comment** |
| `.background(if(a) X else when{...})` | 嵌套后推断成 `Any`。**必须写 `val c: Color = ...`** |
| `padding(top = -30.dp)` | **负数 padding 直接抛异常**，用 `Box` 叠放代替 |
| `height(thresholdPx)` | `height()` 只收 `Dp`，px 要 `.toPx()` 转 Dp |
| 两个同名 private 顶层函数 | 同包内调用会 `Overload resolution ambiguity`，两个都要改名 |
| `onHorizontalDrag` 里 `scope.launch { snapTo }` | 每个拖拽事件一个新协程 → 卡顿 |
| `onSizeChanged { w = it.width }` + 读 `w` | 重组循环。改用固定参考宽度 |
| `printStackTrace` 到 `/sdcard` | 无存储权限，要写 `getExternalFilesDir(null)` |
| `level.entries` | Kotlin 1.9+ 支持，2.4.20 没问题 |
| `@Composable` 写在 lambda 分支里 | 会报 `@Composable invocations can only happen from...` |

### 3.15 死亡回退：加 import 清单

新建 Compose 文件时先列一遍用到的扩展函数，每个都要有对应 import。
已踩漏：`clickable`、`launch`、`asImageBitmap`、`pressable`、
`layerBackdrop`、`rememberLayerBackdrop`、`FloatingBottomBarItem`、`offset`、
`onSizeChanged`、`asPaddingValues`、`WindowInsets`、`navigationBars`、`glassLens`。

---

## 四、PHP 服务器（登录系统服务器/）

### 4.1 文件清单（当前实际）

| 文件 | 作用 |
|---|---|
| `api.php` | **所有客户端接口**（`?action=xxx`） |
| `admin.php` | 管理后台单一入口（`?p=xxx`） |
| `admin_mail.php` | 邮件下发（独立页） |
| `admin_mail_admin.php` | **邮件管理**（列表/详情/单删/全删，可选扣回奖励） |
| `admin_ranking.php` | 排行榜（**已废弃**，改自动计算） |
| `admin_ipmap.php` | 访问分布（独立页） |
| `lib.php` | 数据库 / 校验 / token / 封禁 / 积分经验 / 隐私 |
| `mailbox.php` | 站内信（含奖励领取） |
| `mailer.php` | 无依赖 SMTP（QQ 邮箱） |
| `captcha.php` | 服务端图形验证码 |
| `config.php` | **需改 APP_PEPPER / SMTP** |
| `version.json` | 版本 + 更新日志 + 公告 |
| `data/app.db` | SQLite 数据库 |
| `data/avatars/` | 头像 `<uid>.jpg` |
| `data/apk/app.apk` | 旧的本机安装包（**现已不用**） |
| `ipstat.php` | 访问统计 / IP 库（**IP 地图已删，文件保留**） |
| `ipstat_test.php` | ipstat 自检 |
| `assign_uid.php` | UID 批量分配脚本 |
| `mail_test.php` / `mail_diag.php` | 邮件自检 |
| `set_pwd.php` | **一次性改密码脚本**（改 `$table/$who/$newPwd` 后跑 `php set_pwd.php`） |
| `mail_recall_do.php` | 邮件撤回临时脚本（已有后台页面，可删） |
| `install.sh` | 安装脚本 |
| `README.md` | 部署说明 |

### 4.2 部署现状

```
chmlfrp 隧道，HTTPS 已开通
访问：https://denglu.fucku.top
```

⚠️ `AuthApi.BASE` 实际写的是 **`http://denglu.fucku.top`**（http），
但服务端 `req_is_https()` 会识别 `X-Forwarded-Proto`，Cookie secure 动态判断。

### 4.3 启动命令（必须带上传参数）

```bash
pkill -f "php -S"
cd /storage/emulated/0/MT2/mcp/登录系统服务器
php -d upload_max_filesize=512M -d post_max_size=520M \
    -d memory_limit=512M -d max_execution_time=300 \
    -S 127.0.0.1:8080
```

⚠️ **上传限制必须带**。默认 2M，APK 传不上。
`post_max_size` 必须 **大于** `upload_max_filesize`。

### 4.4 数据库表

| 表 | 作用 |
|---|---|
| `users` | 账号。关键列见下 |
| `admins` | 管理员（独立于 users） |
| `tokens` | 登录令牌（SHA-256 hash，30 天） |
| `checkins` | 签到流水（`user_id+day` 唯一索引防重复） |
| `email_codes` | 邮箱验证码 |
| `captcha` | 图形验证码 |
| `rate_limit` | 短时限流 |
| `reg_audit` | 注册审计 |
| `login_fail` | 登录失败锁定 |
| `admin_logs` | 管理员操作日志 |
| `user_actions` | 对用户的操作记录 |
| `vpn_violations` | VPN 违规 |
| `feedback` | 用户反馈 |
| `tag_presets` | 标签预设 |
| `ip_blacklist` | IP 黑名单 |
| `mail` / `mail_target` / `mail_outbox` | 站内信 |
| `visits` / `ip_geo` | 访问记录（`ip_geo` 已废） |

**`users` 表关键列**：
```
uid            5 位对外编号
points         积分
exp            经验
checkin_streak 连续签到天数
checkin_total  累计签到天数
last_checkin_at
privacy        隐私设置（JSON）
tag_text / tag_color / tag_fx        称号
name_mode      username / uid / email
name_color / name_fx                 主名字样式
uid_color / uid_fx                   ⚠ 若已加则为 UID 行独立样式
mail_color / mail_fx                 ⚠ 若已加则为邮箱行独立样式
banned_until
avatar_at
```

**首次访问 `admin.php` 自动建表 + 默认管理员 `admin/admin123`。**

### 4.5 安全措施

- 密码 `password_hash()`（bcrypt）
- 验证码 / token / 图形码只存 SHA-256 hash
- 登录失败锁定：5次/15分 → 10次/1小时 → 20次/24小时
- 注册配额：IP 7天3个 / 设备 7天1个 / 域名 7天5个
- 服务端图形验证码（本地无答案）
- IP 黑名单（除 ping 外全拦）
- `data/` 有 `.htaccess` 禁止直接访问
- **用户名支持中文/符号**（2~20 字符，禁 `\ / : * ? " < > |` 和控制字符）

### 4.6 管理后台页面

```
仪表盘 / 用户管理 / 用户详情 / 头像管理 / 公告发布
用户反馈 / 在线用户 / IP 黑名单 / 违规记录 / 邮件下发
邮件管理 / 排行榜 / 访问分布 / 标签管理 / 版本更新
注册审计 / 登录审计 / 操作日志 / 系统设置 / 关于
```

### 4.7 API 接口清单（`api.php?action=xxx`）

| action | 说明 | 需登录 |
|---|---|---|
| `ping` | 健康检查 + 服务器出口 IP 归属地 | ✗ |
| `visit` | 访问上报 | ✗ |
| `captcha` | 图形验证码（二进制 PNG + X-Captcha-Id 头） | ✗ |
| `captcha_json` | 图形验证码（JSON，推荐） | ✗ |
| `send_code` | 发邮箱验证码 | ✗ |
| `register` | 注册 | ✗ |
| `login` | 登录 | ✗ |
| `logout` | 登出 | ✓ |
| `me` | 当前用户信息 | ✓ |
| `change_password` | 改密码 | ✓ |
| `reset_send` / `reset_password` | 重置密码 | ✗ |
| `checkin` | 签到（1~300 经验） | ✓ |
| `privacy_save` | 隐私设置 | ✓ |
| `ranking` | 排行榜（`?by=points\|exp\|streak`） | ✓ |
| `sync` | 实时同步（公告/版本/称号/名字/积分） | ✓ |
| `mail_list` / `mail_unread` / `mail_read` / `mail_read_all` /
  `mail_delete` / `mail_claim` | 站内信 | ✓ |
| `upload_avatar` / `avatar` | 头像 | ✓ |
| `download_apk` | APK 下载（支持 Range 续传） | ✗ |
| `vpn_report` | VPN 上报 | ✓ |

### 4.8 ⚠ `ok()` 是平铺的，不包 data

```php
function ok(array $extra) { json_out(array_merge(['ok'=>true], $extra)); }
// 返回：{"ok":true, "items":[...], "unread":3}
// 不是：{"ok":true, "data":{...}}
```

客户端解析必须兼容两种：`(r.optJSONObject("data") ?: r)`。
**已踩坑**：`RankApi` 只认 `data.items` → 报「返回格式异常」。

### 4.9 签到 / 经验规则

```
经验：random_int(1, 300)        完全均匀，无权重
积分：= 经验基础值
连续加成：只加经验，+2%/天，封顶 +50%
等级：Level n 需要 100*(n-1)^2 经验
```

### 4.10 PHP 踩过的坑

| 坑 | 后果 | 解法 |
|---|---|---|
| `imagedestroy()` PHP 8.5 | Deprecated → **header() 全失效** | 删掉 |
| `curl_close()` PHP 8.5 | 同上 | 删掉（对象析构自动关）|
| `display_errors` 开着 | 任何 Warning 破坏跳转 | `ini_set('display_errors','0')` |
| `if(){} else:` 混用 | Parse error | 统一用 `:` |
| 改了 PHP 不重启 | opcache 缓存旧代码 | `pkill -f "php -S"` |
| `$_POST` 超 `post_max_size` | PHP **静默丢弃整个请求体** | 加诊断页面提示 |
| `declare(strict_types)` 重复 | Fatal error | 一个文件只能一次 |
| `ALTER TABLE` 用 try/catch 吞异常 | **列静默缺失，保存无效** | 先 `PRAGMA table_info` 查存在再 ALTER |
| `admin_log()` 定义在 `admin.php` | 独立页调用报 undefined | 自己写一份局部 log 函数 |

### 4.11 上传大文件（APK）的完整参数

PHP 上传有三层限制，**必须同时够大**：

| 层 | 参数 | 推荐值 |
|---|---|---|
| 1 | `upload_max_filesize` | 512M |
| 2 | `post_max_size` | 520M（必须大于 1）|
| 3 | 代码内校验 | 512MB |

任意一层不足都会失败；**第 1 层不足时 PHP 静默丢包，不报错**。

当前状态：**版本更新已改为填下载链接**（`downloadUrl`），不再上传 APK。

### 4.12 版本/公告数据（`version.json`）

```json
{
  "versionCode": 102,
  "versionName": "1.0.2-beta",
  "downloadUrl": "https://github.com/qqqaaa96/yuncsdemo/releases/latest",
  "forceUpdate": false,
  "publishedAt": "2026-10-20",
  "changelog": ["..."],
  "announcement": {
    "enabled": true,
    "id": "20261021-051436",
    "title": "账号系统上线",
    "content": "...",
    "showOnce": true
  }
}
```

⚠️ `downloadUrl` 现在是 **GitHub Releases 直链**，App 点击更新会**跳浏览器**
（不再 App 内下载安装）。

---

## 五、待办 / 未完成

### 🔴 P0-1：MCP 功能待实测

`tools/mcp/` + `ui/McpAnalyzeScreen.kt` 是**最新加的**，**未经过真实客户端验证**。

已验证：编译通过、服务能启动、JSON-RPC 格式正确。
待验证：
- 真实 MCP 客户端（Claude Desktop / Cursor / io.modelcontextprotocol SDK）能否握手
- `initialize` → `tools/list` → `tools/call` 完整链路
- 各工具在大文件上的耗时与内存

已踩过的坑：
- `invalid token`：原来 token 每次随机 → 改为 SharedPreferences 持久化 + URL 参数认证
- 前向引用：`regenerateToken()` 必须写在 `server`/`running`/`msg` 之后
- **`insert_before_match` 锚点重复反弹**：插入 `parseLong` / `compileImplicit` / `drawPolar` 时锚点被拼了两次，导致级联语法错误（已发生 **3 次**）

### 🔴 P0-2：函数图像计算器仍在调整

`calculator/GraphCalc.kt` + `tools/CalcEngine.kt` + `tools/CalcPlot.kt` 是**最新、最不稳定**的代码。
已知待处理：

1. **隐函数性能**：已做 AST 预编译（快 25 倍）+ marching squares 零分配，
   但 `(x^2+y^2-1)^3-x^2*y^3=0` 这类仍可能拖动手势卡。
2. **函数库不完整**：现在是手写 35 个（`sin/cos/tan/sec/csc/cot/asin/acos/atan/sinh/cosh/tanh/ln/log/log2/exp/sqrt/cbrt/abs/sign/floor/ceil/round/trunc/frac` 等）。
   用户要求"支持所有函数"——**纯 Kotlin 达不到 math.js 的量**，遇到不支持的要及时补。
3. **待验证的输入**（用户反馈过的）：
   - `x^3+y^3=3*x*y` → y 不应被识别为参数
   - `r^2=cos(2*theta)` → 应画极坐标
   - `y=e^(-0.2*x)*sin(5*x)` → e 必须是常数 2.718
   - `x=(a+b)*cos(t)-b*cos((a+b)/b*t),y=...` → 参数方程

**修改 CalcEngine 时的铁律**：
- 变量名必须走 `isVarName()`，不要直接用 `a..z` 判断
- 新增变量/常数一律加进 `MULTI_PARAMS` / `CONSTS`，`KNOWN` 自动派生
- 坐标轴（x/y/theta/t）**绝不能**进 `params`，否则会冒出错误滑块
- `classify()` 的枚举加了成员，所有 `when(Kind)` 都要补分支

### 🟠 P1-1：分区备份 UI

`tools/PartitionBackup.kt` 核心已完成，**缺 UI**。

已实现：
- 4 种 by-name 路径探测 + `/proc/partitions` 傅底
- `blockdev --getsize64` / `/sys/class/block/*/size` 补大小
- 按名分类：基带 / 字库 / 系统 / 其他
- dd 备份 + 大小校验 + 失败自动清文件
- manifest.txt 生成

输出：`/storage/emulated/0/字库基带备份/标签_分区名.img`

**用户要求「按多系统工具箱的方式」做 UI，但未提供截图。需先要截图。**

### 🟠 P1-2：控制流解析率

用户抱怨「有的函数解析不出控制流，99.9% 都应该能」。已修多 bug 但未验证。

### 🟠 P1-3：深色模式未适配

`ElfScreen` / `CfgGraphScreen` / `OnboardScreens` / `CardKeyScreen` /
`MailVerifyScreen` / `CfgView` / `Home`
把 `Ink/Paper/Line` 常量 → `LocalPalette.current.xxx`

### 🟠 P1-4：伪 C 剩余差距

已做：SSA、表达式内联、自然循环、类型推断、Markdown 渲染
仍缺：跨块复制传播 / 常量折叠 / 结构体数组成员识别 / 调用签名推导 /
`br xN` 跳转表接入 r2 链路

### 🟡 P2

- `admin_ranking.php` 已废弃（排行榜改自动算），菜单项可删
- `ipstat.php` / `visits` / `ip_geo` 已无 UI 使用，可清理
- `mail_recall_do.php` / `mail_recall_check.php` 临时脚本可删
- 根目录 5 个 0 字节垃圾文件：`A` / `Configure` / `Get` / `Kotlin` / `Run`
- `app/build.gradle.kts` 里有一段过时注释提到 `ui/glass/LiquidGlass.kt`（已删），可清理

### ❌ 5.4 SSC 脚本编译（已放弃）

**目标**：把脚本编译成 ELF 可执行文件。

**尝试过程**：
1. 引用 `io.github.kyant0:backdrop` KMP 库 → 运行时崩（见 3.10）
2. 自写 AGSL shader → RenderNode 递归崩
3. JNI 内嵌 libclang-cpp + libLLVM（198MB）→
   - GitHub 单文件限 100MB，`libLLVM.so` 127MB 推不上去
   - `llvm-strip --strip-debug` **无效**（无调试符号，128M 不变）
   - 缺 Android NDK sysroot，即使编出 ELF 也链不上 bionic
   - **已彻底放弃并清理**（删 3043 个头文件 + 5 个 SO）

**如果还要重做**，必读：
- `/storage/emulated/0/MT2/mcp/ssc嵌入方案.md`（专家级实施方案，很详细）
- 参考源码：`/storage/emulated/0/MT2/mcp/ssc-master/`
- **必读结论**：
  - SSC **不是编译器**，它生成 C++ 包装源码再调 g++，产物是「脚本启动器 ELF」
  - Android 10+ **禁止从 App 私有目录 execve**，路线 A 不可行
  - 路线 B（进程内 LLVM）需 Android sysroot + 完整 CRT + 头文件，工作量极大
  - GitHub 单文件 100MB 限，必须用 git-lfs 或运行时下载

---

## 六、外部资源位置

| 资源 | 路径 | 备注 |
|---|---|---|
| Radare2 头文件 | `csdemo/app/src/main/cpp/include/` | 从 `radare2-master/libr/include` 拷入 |
| Radare2 源码 | `/storage/emulated/0/MT2/mcp/radare2-master/` | **不含 sdb（git submodule）** |
| sdb 源码 | `/storage/emulated/0/MT2/mcp/sdb-master/` | **sdb 头文件只能从这里拿** |
| KernelSU 源码 | `/storage/emulated/0/MT2/mcp/KernelSU-main/` | 弹窗 / 主页 UI 参考 |
| yourvpndead 源码 | `/storage/emulated/0/MT2/mcp/yourvpndead-reviewed-main/` | VPN 检测参考 |
| AndroidLiquidGlass | `/storage/emulated/0/MT2/mcp/AndroidLiquidGlass-kmp/` | 液态玻璃 demo（**只参考，不引库**）|
| SSC 源码 | `/storage/emulated/0/MT2/mcp/ssc-master/` | 脚本编译器（已放弃）|
| shell 混淆参考 | `/storage/emulated/0/MT2/mcp/shell混淆参考/ShellVMP-main/` | V6 混淆器 |
| NFC Tools PRO | `/storage/emulated/0/MT2/mcp/NFC Tools PRO_9.4.apk` | NFC 参考（1987 类）|
| Kernel Flasher | `Kernel Flasher_1.6.0.apk` | 刷写参考（已放弃）|

Radare2 的 `.so` 已全部拷入 `app/src/main/jniLibs/arm64-v8a/`。

---

## 七、关键文件不要碰

- `gradle/wrapper/gradle-wrapper.jar`（二进制）
- `gradle.properties`（`android.aapt2FromMavenOverride` 已删，云端不能有）
- `local.properties`
- `.github/workflows/build.yml`（除 CI 环境问题）
- `app/src/main/assets/juanzeng.png`（捐赠码）
- `app/src/main/assets/shbin/`（ShellVMP 运行环境，13 个 ELF + 13 个库）

---

## 八、工作流程

```
1. 先 read_text 读实际代码（不要凭记忆）
2. 小步改（一次 1~3 处）
3. 改完立即 read_text 复核
4. 检查：花括号配对、import 齐全、类型显式、注释配对
5. 让用户推送 GitHub
6. 用户贴回 e: 开头的报错（带文件名+行号）
7. 修复，重复
```

**用户推代码：**
```bash
cd /storage/emulated/0/MT2/mcp/csdemo && git add . && git commit -m "说明" && git push origin master:main
```
**注意：本地分支 `master`，远程 `main`，必须写 `master:main`。**
**大改后远程可能领先，需要 `git push --force origin master:main`。**

**用户会往 `/storage/emulated/0/MT2/mcp/报错.txt` 里贴报错**（注意：不在 csdemo 目录下，在父目录）。

### CI 注意

`ubuntu-latest` 不预装 NDK 27.0.12077973，workflow 已加显式安装：
```yaml
- name: Install NDK 27.0.12077973
  run: |
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;27.0.12077973"
```

### Git 回退

用户会直接说「回退 `xxxxxxx`」，执行：
```bash
git reset --hard xxxxxxx
```
**注意：这也会丢弃未提交改动。回退前先确认用户意。**

---

## 九、一句话总结

**当前版本 = 「KernelSU 风格 UI + 账号系统 + 站内信 + 排行榜/签到/经验 + 称号与名字流光
+ 液态玻璃邮件 + 指针式测速 + 9 合 1 计算器 + Radare2 反编译 + 批量工具」的双端项目。**

**已完成**：

- **账号**：登录/注册/邮箱验证/内置开发者账号/头像/切换账号/注销
- **主页**：KernelSU 风 UI/5 tab 底栏/双击切换/液态玻璃
- **社交**：站内信(玻璃卡片+下拉刷新)/积分排行榜/称号流光/隐私设置
- **测速**：指针仪表盘/下载上传延迟抖动/不封顶/历史曲线/测速排行榜
- **计算器**：普通/科学/函数图像/方程/矩阵/统计/进制/单位/几何（9 个）
- **帧率**：Scene 同款刷新率 + SurfaceFlinger 真实帧率双行悬浮窗
- **逆向**：ELF 解析/反汇编/CFG/伪 C（Radare2 主用）/Shell 混淆
- **工具**：格式转换/金属探测/NFC/码生成/VPN 检测/设备伪装/反作弊
- **MCP**：把 App 变成 MCP 服务器，12 个 ELF 工具可被外部 AI 调用

**待办**：

- **P0-1**：MCP 功能待真实客户端实测（见五.🔴）
- **P0-2**：函数图像计算器仍在调整（见五.🔴）
- **P1**：分区备份 UI、深色模式适配（`ElfScreen`/`CfgGraphScreen`/`OnboardScreens` 等）、伪 C 剩余差距
- **P2**：清理死代码（`PseudoCode.kt`、`nav/AppNav.kt`、`NoticeCard.kt`、`admin_ranking.php`）

**已放弃**：SSC 脚本编译 ELF（见 5.4）

---

## 九之二、函数图像计算器技术细节

这是目前**最复杂、最易改坏**的模块，单独说明。

### 数据流

```
用户输入
  ↓ normalizeInput()     全角/中文括号/×÷−/上标/θπ√ → 半角
  ↓ classify()           识别 EXPLICIT / POLAR / IMPLICIT / PARAMETRIC
  ↓ detectVar(src, kind) 词法扫描找变量，按 kind 分离「坐标轴」与「参数」
  ↓ buildAst()           递归下降 → Node 语法树
  ↓ compileToLambda()    AST → 嵌套闭包（一次性）
  ↓ CalcPlot.drawXxx()   绘制
```

### classify 识别规则

| 输入形态 | Kind |
|---|---|
| `y=...` / `f(x)=...` | EXPLICIT |
| 同时有 `x=` 和 `y=` | PARAMETRIC |
| `r=` / `r^2=` / `r^n=` | POLAR |
| 只含 `theta` 无等号 | POLAR |
| 其余含等号 | IMPLICIT |
| 无等号且无 theta | EXPLICIT |

### 变量归属（最容易错）

| Kind | 坐标轴（**不是参数**） | 其余才是参数 |
|---|---|---|
| EXPLICIT | x | a/b/k… |
| POLAR | theta | a/b/k… |
| IMPLICIT | **x、y** | a/b/k… |
| PARAMETRIC | t、x、y | a/b… |

`detectVar` 用**词法扫描**（逐标识符判断），不是字符串 replace。
旧实现用 `replace("theta"," ")` 会把 `eta` 幽灵带出来——这个坑已经踩过。

### 性能关键（已做）

| 优化 | 效果 |
|---|---|
| `compileToLambda` 预编译 | 单次求值 1.5μs → 60ns |
| marching squares 零分配 | 去掉每格的 Pair / 局部函数 |
| X/Y 独立算刻度间隔 | 避免扁平视窗下 x 轴挤爆 |
| `key(paramValues)` 重建 Canvas | 拖滑块实时刷新 |

**仍未解决**：隐函数（cellPx=3 → 11 万格）拖动时依旧有压力。
若还要优化：`drawImplicit` 里 `setTop` 仍是局部函数，可内联。

### 已知局限（数学，不是 bug）

- `r^2=cos(2θ)` 只画 `r=+√(...)` 的正半支（负半支需另画）
- `y=e^(-0.2x)` 在 x<−10 时值很大，超视窗会被裁
- 隐函数的孤立点/极细区域可能因网格跨过而丢失

---

## 十、接手检查清单

新 AI 接手后，按顺序做这几件事：

1. **读零之二**（用户习惯）—— 不读会惹怒用户
2. **读一**（铁律）—— 都是踩过的坑
3. **读 3.11**（完整文件结构）—— 知道改哪个文件
4. **确认 Git 状态**：用户本地 `master` → 远程 `main`
5. **无法本地编译** —— 改完让用户推 CI，报错在 `报错.txt`
6. **改前先 `read_text`** —— 不要凭记忆写代码

### 改代码前必查

```
□ 这个符号/函数是否已在 CalcEngine 的 FN/CONSTS/MULTI_PARAMS 里？
□ 改了 Kind 枚举？→ 搜所有 when(Kind) 补分支
□ 改了 MainTab？→ 同步 AppShell 的 when(page) + 非悬浮底栏 TabItem
□ 改了引用变量？→ 先搜出现次数，全改完再搜一次验空
□ 插入代码？→ 锚点必须唯一，否则会重复拼接（已踩 3 次，见 1.7b）
□ 用了新的 Modifier 扩展？→ 补 import
□ 改了 MCP 工具？→ 路径必须走 McpToolContext.resolve()
```

### MCP 功能调试速查

| 现象 | 排查 |
|---|---|
| `invalid token` | 客户端 URL 要带 `?token=`，或加 `Authorization: Bearer` 头 |
| 连不上 | 先确认页面显示「服务运行中」；本机用 `adb forward tcp:8989 tcp:8989` |
| `tools/list` 为空 | 看应用内「可用工具」数量，应为 12 |
| 工具报“文件不存在” | 文件要放在 `/storage/emulated/0/ELF分析处/mcp/` |
| 端口占用 | 退出页面会自动 stop；若异常，重启 App |
