# 项目交接文档

> 最后更新：2026-11-21（完整重写）
> 当前版本：1.0.3-beta（versionCode 103），纯净模式（账号功能保留但入口隐藏）
> 接手前必读：零（用户习惯）、一（铁律）、2.0（MCP 工具限制）、3.12（文件全量清单）

---

## 零、30 秒速览

**Android 工具箱 App + PHP 账号服务器** 双端项目。

| 端 | 位置 | 技术 |
|---|---|---|
| App | `/storage/emulated/0/MT2/mcp/csdemo/` | Kotlin 2.4.20 + Compose BOM 2026.09.00 + miuix 0.9.4 |
| 服务器 | `/storage/emulated/0/MT2/mcp/登录系统服务器/` | PHP 8.5 + SQLite + frp 内网穿透 |
| 参考 | `/storage/emulated/0/MT2/mcp/KernelSU-main/` | UI / 弹窗 / 主页参考 |
| 参考 | `/storage/emulated/0/MT2/mcp/AndroidLiquidGlass-kmp/` | 液态玻璃 demo（**不要引它的库，见 3.11**） |
| 参考 | `/storage/emulated/0/MT2/mcp/html/数学教学/` | 函数图像计算器原始实现 |
| 参考 | `/storage/emulated/0/MT2/mcp/ssc-master/` | SSC 脚本包装器（**加壳参考，见 3.16**） |
| 参考 | `/storage/emulated/0/MT2/mcp/sdb-master/` | sdb 头文件（radare2 依赖） |
| 参考 | `/storage/emulated/0/MT2/mcp/radare2-master/` | radare2 源码（不含 sdb） |

**无本地编译能力** —— 改完必须让用户推 GitHub Actions 验证。
**PHP 可以本地跑** —— 用户在 Termux 用 `php -S`。

---

## 零之二、用户习惯与要求（极其重要，先读这节）

### 沟通方式

| 项 | 要求 |
|---|---|
| 语言 | **全程中文**，思考过程和输出都是 |
| 长度 | **短、直接**。不要长篇大论，不要重复用户说过的话 |
| 语气 | 不说教、不解释原理（除非被问）、不道歉过度 |
| token | **极其在意消耗**。不要贴大段代码回显，不要复述文件内容 |
| 报错 | 会自己贴到 `/storage/emulated/0/MT2/mcp/报错.txt`，**直接去读，不要让他贴** |
| 图片 | 尽量用文字代替截图；需要图片时他会发，不要主动要 |

### 用户会生气的情况（已发生过多次）

1. **说“可能”“大概”“应该”** —— 他要确定答案。做不到就直说做不到，并说明原因。
2. **自己发挥加功能** —— 只要他说的，不要“顺手优化”、不要加没要求的按钮/标签/预设。
3. **用 HTML / WebView 实现** —— 明确要求**纯 Kotlin**。理由：效率低。
4. **走他的服务器** —— 他的服务器很垃圾，能本地/直连就本地直连。
5. **精度不一致** —— 原话：「用计算器算 1+1 弹出 3，你觉得好受吗」。计算类功能必须准确。
6. **同一个 bug 修两次没好** —— 修之前先把根因读明白，不要猜。
7. **改完后反复报错** —— 会直接开骂。所以改前要想清楚依赖链，而不是试错。
8. **声称编译通过** —— 没有本地编译环境，**绝对不能说“已编译通过”**。

### 确认事项的做法

- 改动大（>3 文件 / >100 行）**先说方案 + 利弊**，让他选。
- 涉及到 UI、破坏性、删功能，**必须先问**。
- 不确定他的意思时，**问一句**，不要自己理解成别的。
- 他要“极高强度”时不要质疑可行性，直接做；但必须说清安全边界。

### 他对“安全”的真实理解

- **不能把 SHA-256 说成等同于 Ed25519** —— 两者解决的问题不同（完整性 vs 身份认证）
- **不能宣传本地自校验能抵抗主动攻击者**
- 他要的是“加大逆向成本”，不是“绝对不可破解”
- 他会自己判断风险，不需要 AI 反复劝阻

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

**已踩实例**：`CalcEngine.compileImplicit`、`CalcPlot.drawPolar`、`McpTools.parseLong`、
`SscCompiler.readLine`、`ElfScreen.requestAllFilesAccess`。

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

### 1.10 注释里不能出现 `*/` 或 `/*`

- 注释里写 `/sys/*` → `/*` 被当块注释开头 → **Unclosed comment**
- 注释里写 `prebuilt/*/bin` → `*/` 提前**结束**了注释 → 后面代码全变语法错误

**已踩**：`NdkEnv.kt` 里写 `toolchains/llvm/prebuilt/*&#47;bin/clang`，
导致 10+ 个看似无关的 Unresolved reference。

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
断开时工具列表会暂时只剩部分工具（如只有 `mt_file_read_text`），重试即可恢复。

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
| `mt_file_search_text` 必须传 `encoding` | 即使是空字符串也必须给 |

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

### 2.10 写文件用 `mt_file_edit_text`，不要用 `mt_file_write_bytes`

`mt_file_write_bytes` 需要手写 UTF-8 十六进制，中文很容易编错（实际发生过 `**` 变成裸字符）。
**写文本一律用 `mt_file_edit_text` + `write_target`。**

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
**ABI 只编 arm64-v8a**（预编译的 libr_*.so 只有这个）。

### 3.2 底栏 5 个 tab（顺序不能乱）

```
0 主页 / 1 反作弊 / 2 常用功能 / 3 工具 / 4 设置
```

`MainTab` 枚举在 `ui/AppShell.kt`，顺序 = `HorizontalPager` 的 `when(page)` 索引，**改一处必须改另一处**。

⚠️ **关闭悬浮底栏时**，`AppShell.kt` 里 `if (!floating)` 分支逐个显式调用 `TabItem`，**加 tab 必须同步补一个**。

底栏图标用 `ui/TabGlyph.kt`（Canvas 矢量绘制，**不用 Emoji**）。
已有图标 kind：`home` / `anticheat` / `features` / `tools` / `profile` / `mail` / `announce`。
传其他值会走 `else -> drawCircle` 傅底（画个圆点）。

### 3.3 账号功能总开关

`tools/AccountFeature.kt`：

```kotlin
const val ENABLED = false   // 纯净模式（当前）
```

**改成 true 即可恢复全部账号功能**（代码全部保留，只是入口隐藏）。

关闭时自动隐藏：首启登录页、主页邮件/排行榜/服务器连接、启动上报、
SyncCenter 轮询、ProfilePage 账号区、检查更新、隐私设置。

### 3.4 页面缓存（PageCache）

`tools/PageCache.kt` —— 内存级缓存，保持滚动位置 + 避免重复刷新。

```kotlin
PageCache.get<T>("mail")          // 读缓存
PageCache.put("mail", items)      // 写缓存
PageCache.isFresh("mail", 30_000) // 30 秒内算新鲜
PageCache.scroll / saveScroll     // 列表位置
PageCache.clear()                 // 退出登录时调
```

已接入：`MailScreen`、`RankingScreen`。

### 3.5 设置存储

`tools/AppSettings.kt`（SharedPreferences + Compose State）：

```kotlin
AppSettings.themeMode          // System / Light / Dark
AppSettings.floatingBottomBar  // 底栏是否悬浮
AppSettings.glassBottomBar     // 底栏是否开启玻璃效果
AppSettings.enableBlur         // 全局模糊开关
AppSettings.navigationBadge
AppSettings.pageScale          // 0.8 ~ 1.1
```

`tools/MailTheme.kt`（邮件玻璃 5 参数）：

```kotlin
MailTheme.current = Params(
    refractionHeightDp = 25.92f,   // 边缘折射高度
    refractionAmountDp = 56.16f,   // 折射强度
    chromaticAberration = 0.24f,   // 色散
    blurDp = 4.41f,                // 高斯模糊
    cornerRadiusDp = 33.6f,        // 卡片圆角
)
```

⚠️ 主题设置页里的「邮件主题」滑块已在纯净模式删除，但 `MailTheme.kt` 和玻璃效果仍在。

---

## 三之二、MCP 模块（把 App 变成 AI 可调用的 ELF 分析工具）

### 架构（重中之）

**server 必须由 `McpService`（前台 Service）持有，不能在 Composable 里 `remember`。**

以前在页面里 `remember { McpServer(...) }`，一离开页面就 stop，客户端必断。
现在：切界面 / 退页面不断连，`START_STICKY` 被杀自启。

### 文件职责

| 文件 | 职责 |
|---|---|
| `McpTool.kt` | 工具数据类 + `McpToolContext`（路径沙箱）+ `Schema` DSL |
| `McpServer.kt` | 纯 Socket HTTP + JSON-RPC 2.0。双端口、token 认证、Session ID |
| `McpTools.kt` | 13 个工具实现 |
| `McpService.kt` | 前台 Service，持 server + WakeLock + 通知栏 |
| `McpCallLog.kt` | 调用日志（内存 200 条） |
| `ui/McpAnalyzeScreen.kt` | 页面：主页 / 调用日志 两个 Tab |

### 端点与配置

```
本机   http://127.0.0.1:8903/mcp      （默认端口，可自定义 1024~65535）
局域网 http://<手机IP>:8787/mcp      （可在页面关闭）
工作目录 /storage/emulated/0/ELF分析处/mcp/
```

**默认不需要 token**（用户明确要求“填地址就能用”）。
页面可开「令牌认证」，开启后 URL 带 `?token=`。

### 13 个工具

本机/局域网 / `mcp_list_files` / `mcp_read_bytes` / `mcp_hash` / `mcp_write_file` /
`elf_header` / `elf_sections` / `elf_segments` / `elf_symbols` / `elf_strings` /
`disasm_range` / `disasm_function` / `cfg_function` / `decompile_file`

### 已修复的历史 bug（不要再踩）

1. **通知回 `id:null`** → 客户端报 `Invalid RequestId type: null`
   → 通知必须返回 `null`，HTTP 回 202 空 body
2. **缺 `Mcp-Session-Id`** → 新版客户端握手后就断
3. **`Schema.obj()` 的 `required` 永远空** → AI 不知道哪些必填
4. **`mcp_read_bytes` 全文件 `readBytes()`** → 大 `.so` 直接 OOM
   → 改用 `RandomAccessFile` 只读指定段
5. **沙箱前缀漏洞** → `/a/bc` 能绕过 `/a/b` → 必须用 `base + 分隔符` 判断
6. **`getRuntime().exec` 在私有目录 noexec** → 必须用 `linker64` 拉起
7. **通知不显示** → Manifest 缺 `POST_NOTIFICATIONS`（Android 13+ 必须运行时申请）
8. **CFG 不按函数边界扫** → 传 `funcSize`，否则会把后面函数也扫进来
9. **没 `successors`** → 必须从 `edges` 回填

### 修改 MCP 时的铁律

- 路径必须走 `McpToolContext.resolve()`，不要直接 `File(path)`
- 新增工具会自动出现在 `tools/list`，无需额外注册
- 返回的 JSON 字段名固定，**不要随意改**（AI 靠它解析）
- 非 ARM64 文件调反汇编/CFG 必须**明确报错**，不能返回 `.word` 垃圾

---

## 三之三、Capstone 反汇编引擎

### 为什么引入

手写 `Arm64Disasm.kt` 有实际解码错误：`MOVZ` / `NOP` / `ORR` 别名全错过。
Capstone 是工业级引擎，已接入。

### 接入方式

`app/src/main/cpp/CMakeLists.txt` 里 `FetchContent` 拉 **capstone `next` 分支**，
只开 ARM64 + X86，其余全关（控体积）。

```cmake
FetchContent_Declare(capstone
    GIT_REPOSITORY https://github.com/capstone-engine/capstone.git
    GIT_TAG        next
)
```

拉取失败时会编译 `capstone_stub.c`（空实现），Kotlin 侧 `CapstoneBridge.version()`
返回 null，`Arm64Disasm.decode()` 自动回退手写解码器。**App 不会白屏。**

### 文件职责

| 文件 | 职责 |
|---|---|
| `cpp/capstone_bridge.c` | JNI：一次调用反汇编整段，返回 `long[]` + `String[]` |
| `cpp/capstone_stub.c` | 拉取失败时的空实现 |
| `tools/CapstoneBridge.kt` | Kotlin 封装，加载 .so + 转 Insn |
| `tools/CapstoneDecoder.kt` | 适配层，按地址缓存解码结果 |

### 注意

**Capstone 6.0（next 分支）ARM64 解码有 bug**：
- `svc #0`（`0xD4000001`）解成 `b.ne`
- `brk #0`（`0xD4200000`）解成 `b.eq`
- `w8` 解成 `wsp`

不影响产物执行，但影响 ELF 逆向页的可读性。
**如需修正，把 `GIT_TAG` 改成 `5.0.1`（稳定版）。**

---

