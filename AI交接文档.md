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

`mt_file_write_bytes` 需要手写 UTF-8 十六进制，中文很容易编错。
**已实际发生**：写 Markdown 时 `**` 和 `|` 编码错误，表格全毁。
**写文本一律用 `mt_file_edit_text` + `write_target`。**

### 2.11 `replace_match` 的 `matchText` 必须和当前内容逐字一致

每次 `read_text` 拿到的内容才是真实的。凭记忆写锚点会报：

```
edits[0].matchText was not found in the current text
```

**原因常见三种**：
1. 空白/缩进不一致（Kotlin 代码里 4 空格）
2. 之前改过，内容已变
3. 中文标点/全角字符

### 2.12 同一个 edit 里多个 replace，后一个可能因前一个版本变化失败

工具返回 `FILE_TARGET_VERSION_MISMATCH` 时，**重新 read_text 拿新版本号再试**。
这是正常现象，不是错误。

### 2.13 工具会间歇性消失

出现过 `Tool mcp__MT__mt_file_search_text not found`。
原因：MCP 客户端重连中。**直接重试**，或者等几秒。

---

## 二之二、Kotlin / Compose 编译错误速查

下面全部是**实际踩过并修好**的错误。遇到相同报错直接查表。

| 错误 | 根因 | 修复 |
|---|---|---|
| `Unresolved reference 'offset'` | 缺 `import androidx.compose.foundation.layout.offset` | 补 import |
| `Unresolved reference 'size'` | 缺 `androidx.compose.foundation.layout.size` | 补 import |
| `Unresolved reference 'defaultMinSize'` | 缺 import | 补 import |
| `No parameter with name 'includeFontPadding'` | material3 的 `Text` 没这个参数 | 用 `lineHeight` 代替 |
| `TextStyle(...)` 报 `No value passed for 'spanStyle'` | 位置参数错 | 用具名参数 |
| `Argument type mismatch: 'Long' vs 'Int'` | `crc32()` 返 Long | 加 `.toInt()` |
| `Cannot infer type for type parameter 'T'` | 空 lambda 或 `mutableStateOf(null)` | 显式写 `<String>` / `EMPTY_ACTION` 常量 |
| `@Composable invocations can only happen from...` | 在非 Composable lambda 里调 Composable | 外面读好值传进去 |
| `Modifier 'private' is not applicable to 'local function'` | 通常是括号不配对 | 检查 1.7b |
| `Syntax error: Missing '}'` | 同上 | 同上 |
| `Syntax error: Unclosed comment` | 注释里有 `/*` | 改掉 |
| `Conflicting overloads` | 两个同名 private 顶层函数 | 改其中一个名字 |
| `Unresolved reference 'ptrace_'` 等 | 写了不存在的符号 | 检查 libc 真实符号名 |

---

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

## 三之四、SSC 脚本编译加密（最新、最复杂）

### 定位

Shell 混淆页有两个模式（底部液态玻璃底栏切换）：

- **ShellVMP**：纯混淆脚本（输出 `.sh`），原有逻辑未动
- **SSC**：把脚本编译成 ARM64 ELF，带加密、完整性自检、反调试

### 需要的环境

**用户自己上传 NDK 压缩包**（`.zip` / `.tgz` / `.tar.gz`），
自动解压到 `filesDir/ndk/`（App 私有目录，**不需要任何存储权限**）。

不支持 `.7z`（引入依赖太大）。

### 文件职责

| 文件 | 职责 |
|---|---|
| `NdkEnv.kt` | NDK 检测：递归找 clang、找 sysroot、找 linker64 |
| `ArchiveExtractor.kt` | **零依赖**解压器：zip（JDK）+ tgz/tar（手写 tar 解析） |
| `Rc4.kt` | RC4 加解密（Kotlin 侧） |
| `CryptoCSource.kt` | 产物需要的 C 代码：SHA-256、元数据格式、Poly1305 |
| `GzShell.kt` | gzexe 风格自解压包装器 |
| `SscCompiler.kt` 74KB | **主流程**：生成 wrapper.c / 编译 / 链接 / 追加元数据 |
| `ui/ShellObfuscateScreen.kt` | UI：两个 Tab + NDK 上传 + 进度弹窗 |

### 完整编译流程

```
1. 读脚本字节（readBytes，不是 readText）
2. RC4 加密 17 轮（必须是奇数轮）
3. 写 script.enc
4. 生成 wrapper.c：
   - 明文警告（8 条）
   - 240 条分散短句
   - 60+ 条假字符串
   - 运行时消息表（异或 0x5B）
   - 密钥碎片 g_ka/g_kb/g_kc/g_kd
   - SHA-256 + 元数据自检代码
   - main 混淆 trampoline + ssc_real_main
5. clang -c wrapper.c → wrapper.o
6. ld.lld → ELF
7. 追加 500~900KB 伪装代码填充区
8. 追加 128 字节元数据（含 SHA-256）
```

### 关键技术点（每一条都粘着血）

#### A. 必须用 linker64 拉起 clang

```kotlin
// App 私有目录是 noexec，直接执行 clang 会 Permission denied
val cmd = listOf(linker64, clangPath) + args
```

#### B. 必须 `-fintegrated-cc1`

```kotlin
// 否则 clang 会 fork 自己（clang -cc1），报：
//   error: expected absolute path: "-cc1"
args.add("-fintegrated-cc1")
```

#### C. 必须分两步：先 -c，再单独 ld.lld

```
// 一步到底会让 clang 在链接阶段 fork ld，同样失败
// 且 ld.lld 不支持 --discard-section（GNU ld 的参数）
```

#### D. 不能 include 系统头文件

用户上传的 NDK 可能头文件残缺（实际遇到 `asm/types.h` 缺失）。
**所有 libc 函数自己 extern 声明**，类型用 `u64` / `s64` / `pid_t_`（避开 clang 内建的 `size_t`）。

#### E. 必须自己写 `_start`

```c
__attribute__((naked)) int main(void) { /* 混淆 trampoline */ }
__attribute__((naked)) void _start(void) {
    __asm__("ldr x0, [sp]\n"      // argc
            "add x1, sp, #8\n"    // argv
            "bl  main\n"
            "mov x8, #93\n"       // __NR_exit_group
            "svc #0\n");
}
```

链接时 `-e _start`。**直接把 main 当入口会因 argc/argv 未初始化而段错误。**

#### F. RC4 多轮必须整份处理

```c
// ❌ 错误：按 4KB 分块，每块单独跑 17 轮
// → 只有第一块解对，后面全是乱码（实际发生过）

// ✅ 正确：整份跑完 17 轮，再分块写管道
for (round = 0; round < 17; round++)
    ssc_rc4(buf, use_len, keybuf);
```

#### G. RC4 轮数必须是奇数

偶数轮会相互抵消（RC4 是异或流密码）：
- 16 轮 = 8 次来回 = **明文**
- 17 轮 = 正常加密

代码里已加自检：密文等于明文就报错。

#### H. 管道传脚本必须关写端

```c
// 子进程：必须关 pfd[1]，否则 sh 读 stdin 永远等不到 EOF
// → 死锁（已实际发生：进程卡死必须 Ctrl+C）
dup2(pfd[0], 0);
close(pfd[0]);
close(pfd[1]);
args[1] = "-s";   // sh -s，从 stdin 读脚本
execv(args[0], args);
```

#### I. clang 必须用 `-O1` + `-fno-builtin`

**`-O2` 会把加密逻辑常量折叠**：
- `g_key_enc[i] ^ 0x3C` → 明文密钥直接写进 `.rodata`
- `'0' + (n%10)` → 256 字节查表
- `g_order_enc` → 顺序数组

#### J. volatile 挡不住数据段明文

`static volatile const unsigned char arr[]` 仍然会把**初始值**写进 `.rodata`。
要彻底消除，必须**运行时派生**（如轮次表由密钥碎片现场算）。

#### K. `-O2` 会把加密逻辑常量折叠

实测问题：

| 现象 | 原因 |
|---|---|
| `.rodata` 里搜到 `7ecf656575b260a3` | `g_key_enc[i] ^ 0x3C` 被编译期算成明文 |
| `.rodata` 里有一大段 `0123456789:;<=>?abcdefg...` | `'0' + (n%10)` 被优化成 256 字节查表 |
| `g_order_enc` 变成顺序数组 | `arr[i] ^ 0x5A` 被折叠 |

**必须用 `-O1 -fno-builtin`。**

#### L. `ld.lld` 不支持 `--discard-section`

```
ld.lld: error: unknown argument '--discard-section=.comment'
```

那是 **GNU ld** 的参数。`ld.lld` 只能用 `-s` 剥符号。
`.comment` 段靠 clang 的 `-fno-ident` 阻止生成。

#### M. `__attribute__((used))` 挡不住 `--gc-sections`

链接时 `--gc-sections` 会删掉**未被代码引用的数据段**。
`used` 属性只约束编译器，不约束链接器。

**现象**：C 里写了 10 条明文字符串，`strings` 里一条看不到。

**解法**：必须真的**逐字节读取**（累加进 volatile 全局），只取指针不够。

#### N. `strings` 默认不显示中文

`strings` 只识别 ASCII 可打印字符（`0x20-0x7E`）。
中文 UTF-8 是 `0xE6 0x96 0x87` 这类高位字节，**默认被当二进制跳过**。

需要 `strings -e S`，或改用 MT 管理器 / hex 编辑器。

**所以警告文本必须同时给英文版**，保证任何工具都能看到。

#### O. META_CHECK 在 MAIN_BODY 之前会缺声明

拼接顺序是：

```
1. CryptoCSource.SHA256
2. CryptoCSource.META_CHECK   ← 用了 open/read/close/lseek
3. MAIN_BODY                  ← 声明在这里
```

META_CHECK 看不到后面的声明 → C99 报一堆 `undeclared function 'open'`，
之后 MAIN_BODY 又声明一次 → `conflicting types for 'read'`。

**解法**：IO 声明写进 `CryptoCSource` 里，让它自包含。
同时 MAIN_BODY 里删掉重复声明。

#### P. `ssc_msg` 返回静态缓冲区，不能同时持有两个

```c
static char *ssc_msg(int idx) { static char buf[80]; ... return buf; }
```

同一条语句里调两次（如 `ssc_fail` 里取 msg + 取 "(exit:" 前缀），
第二次会覆盖第一次的结果。
**需要同时用两个时必须先拷贝。**

#### Q. 不能用 `size_t` / `ssize_t` 做 typedef

```c
typedef unsigned long size_t;   // ❌ clang 报 redefinition
```

clang 内建了 `size_t`。必须用自定义名：`u64` / `s64` / `pid_t_`。

#### R. `ptrace` 不能改名

```c
extern long ptrace_(...);              // ❌ undefined symbol: ptrace_
extern long ptrace_(...) __asm__("ptrace");  // ⚠️ 部分 clang 版本不生效
```

**直接写 `extern long ptrace(...)` 就行**（没 include 头文件，不会冲突）。

#### S. 明文警告必须直接在二进制里插入，不能靠 C 字符串

C 里的字符串会被编译器**集中放到 `.rodata` 尾部**，形成一大块连续文本，
不是用户要的「随机分散」。

**正确做法**：编译完 ELF 后，由 Kotlin 侧直接向填充区的随机位置覆写字节。

---

### 当前的防护层

| 层 | 手段 |
|---|---|
| 脚密文 | RC4 17 轮，密文与明文完全不同 |
| 密钥 | 4 碎片异或合成（`g_ka ^ g_kb ^ g_kc ^ g_kd`） |
| 轮次表 | 运行时由密钥碎片派生，磁盘无表 |
| 字符串 | 11 条真字符串异或 0x5B，运行时解到栈上 |
| 诱饵 | 60+ 条假字符串（frida/xposed/magisk 等） |
| 明文声明 | 8 条警告 + 240 条「文件被保护！」（故意可见） |
| 填充 | 500~900KB 伪装 ARM64 代码（假函数/假跳转/假字符串） |
| 符号表 | `-s` 已剔除 |
| 编译器指纹 | `-fno-ident` |
| 完整性 | SHA-256 + 128B 元数据（追加在文件末尾） |
| 反调试 | `/proc/self/status` 的 `TracerPid` 优先，ptrace 傅底 |
| 脚本传输 | pipe + dup2 + `sh -s`，零落盘 |
| 内存清理 | 写一块清一块，密钥用完清零 |
| 函数头 | `main` 是 naked trampoline，真逻辑在 `ssc_real_main` |

### 元数据格式（128 字节，追加在文件末尾）

```
0..  3   version = 2
4.. 35   payload_digest  （载荷密文 SHA-256）
36.. 51  payload_tag     （Poly1305 占位）
52.. 83  self_digest     （ELF 自身 SHA-256，排除本区）
84.. 87  payload_len     （小端 32 位）
88..119  reserved        （Ed25519 预留，全 0）
120..123 meta_crc        （本区 CRC32，覆盖 0..119）
124..127 padding         （随机）
```

**哈希覆盖范围：`[0, fileSize - 128)`**。不能把元数据算进去，否则自指死循环。

### 退出码

| 码 | 含义 | 颜色 |
|---|---|---|
| 2 | 检测到调试器 | 红 |
| 3 | 密钥区被篡改 | 黄 |
| 4 | 内存分配失败 | 品红 |
| 5 | 管道创建失败 | 青 |
| 6 | 解释器启动失败 | 青 |
| 7 | ELF 完整性校验失败 | 品红 |

### 已知限制（不要宣传成“强防篡改”）

- SHA-256 自检能拦「随手改文件」，**拦不住有能力重算摘要的攻击者**
- 密钥虽然分 4 片，但**运行时必须在内存里合成**，可被 hook 抓取
- `ptrace` 反调试可被 patch 掉
- 要真正抵抗主动攻击，必须有**可信私钥 + 可信验证路径**，属于发布侧基础设施

---

## 三之五、完整文件清单（每个文件干什么）

> 下面每一行都是**实际存在的文件**。接手时先读这张表，再打开具体文件。
> 标注 ❌ 的是死代码/已废弃，不要往里加功能。

### 入口

| 文件 | 职责 | 危险点 |
|---|---|---|
| `MainActivity.kt` | 唯一 Activity。初始化顺序 Plan→CardKey→MailAuth→AppSettings→MailTheme→VisitTracker；**字符串路由表**（route → 屏幕）；history 返回栈；全局崩溃日志 | 少一个初始化 = 功能静默失效；路由漏分支会掉到 `else -> AppShell` |

### `tools/` — 全部业务逻辑

#### 账号与网络

| 文件 | 职责 |
|---|---|
| `AccountFeature.kt` | **账号功能总开关**。`ENABLED = false` 即纯净模式 |
| `AuthApi.kt` | 账号网络层。BASE = `http://denglu.fucku.top`；含内置开发者账号 `qfmicd/123456`（本地拦截） |
| `AccountStore.kt` | 已登录账号历史（最多 5 个） |
| `MailApi.kt` | 站内信（含奖励领取） |
| `RankApi.kt` | 排行榜 / 签到 / 隐私 + **测速排行榜** |
| `SyncCenter.kt` | 20 秒轮询同步（公告/版本/称号/名字/积分） |
| `VersionInfo.kt` | 拉 version.json |
| `PingMonitor.kt` | 300ms 探 ping，主页「与服务器建立连接」 |
| `VisitTracker.kt` | 启动上报访问 |
| `Net.kt` | 网络诊断（ping/dns/端口/http/ARP/局域网扫描） |
| `Updater.kt` | App 内下载 APK（已降为傅底，实际跳浏览器） |
| `AvatarStore.kt` | 头像压缩 / 本地缓存 / 下载 |
| `DeviceId.kt` | 设备指纹（注册配额用） |
| `SmtpMailer.kt` / `MailAuth.kt` | 旧客户端 SMTP（已被服务端取代，仅保留卡密验证） |

#### 认证与状态

| 文件 | 职责 |
|---|---|
| `Plan.kt` | 隐私同意 + 基础/Root 方案 |
| `CardKey.kt` | 6 位卡密（默认 `123456`，每次冷启动重输） |
| `AppSettings.kt` | 主题/底栏/玻璃/模糊/缩放 |
| `MailTheme.kt` | 邮件玻璃 5 参数 |
| `PageCache.kt` | 内存页面缓存（列表 + 滚动位置） |
| `RootState.kt` | root/adb/user 三态 + `mainTab` |
| `AppVersion.kt` | 从安装包读版本号 |

#### Root / Shell

| 文件 | 职责 |
|---|---|
| `Shell.kt` | 命令执行 / `runRoot`（20s 超时） |
| `AdbShell.kt` | Shizuku |
| `UserService.kt` | Shizuku UserService（独立进程 `:adbshell`） |
| `RootCheck.kt` | root 检测 |
| `Selinux.kt` | SELinux 状态/切换 |
| `RootTune.kt` | 调频 / 调度 / 温控 / 线程优化 |
| `Device.kt` | 设备信息 |
| `Apps.kt` | 应用列表 |
| `IntegrityCheck.kt` | 完整性校验 |

#### 安全与检测

| 文件 | 职责 |
|---|---|
| `VpnDetector.kt` | VPN 检测（5 层：Capabilities/接口/代理/路由/包名） |
| `VpnGuard.kt` | 检测到 VPN → 上报 → 弹 Toast → 杀进程 |
| `AntiCheat.kt` | 反作弊驱动检测（/proc/inte_*） |
| `DeviceSpoof.kt` / `SpoofData.kt` | 设备属性伪装（resetprop）+ 机型/芯片数据表 |

#### 帧率

| 文件 | 职责 |
|---|---|
| `DisplayRateReader.kt` | **屏幕刷新率**（4 节点探测 + SF1013 傅底 + 250ms 节流）。仅 ADB/Root |
| `FpsReader.kt` | **真实帧率**（SurfaceFlinger --latency，取中位数） |
| `FpsOverlayService.kt` | 前台服务 + 悬浮窗（双行：帧率 + 刷新率） |
| `FpsRecorder.kt` | 帧率历史采样 |
| `DeviceMetricsReader.kt` | 温度/CPU/内存采集 |

#### 测速

| 文件 | 职责 |
|---|---|
| `SpeedTest.kt` | 测速核心。源：Cloudflare/CacheFly/Hetzner + 国内镜像；**不封顶** |
| `SpeedHistory.kt` | 本地测速历史 + 统计（峰值/谷值/平均/曲线） |

#### 计算器（复杂模块）

| 文件 | 职责 |
|---|---|
| `CalcEngine.kt` 59KB | **表达式引擎**：归一化 → 词法 → 递归下降 → AST → 求值。含 `classify`（显函数/极坐标/隐函数/参数方程）、`compileToLambda`（预编译）、`asPoly`、`findRoots`、`autoRange` |
| `CalcPlot.kt` 29KB | **绘图**：坐标轴（X/Y 独立刻度）、drawFunction（断点检测）、drawPolar、drawParam、**drawImplicit（marching squares）** |
| `CalcFormat.kt` | 数字格式化 |

#### SSC / 加壳（见 3.14）

| 文件 | 职责 |
|---|---|
| `SscCompiler.kt` 74KB | **主入口**：compile() → 加密 → 生成 wrapper.c → clang → ld.lld → 追填 → 追元数据 |
| `NdkEnv.kt` | NDK 检测：递归找 clang / sysroot / linker64 |
| `ArchiveExtractor.kt` | 零依赖解压：zip + tar/tgz（含符号链接处理、防路径穿越） |
| `Rc4.kt` | RC4 加解密 |
| `CryptoCSource.kt` | 产物需要的 C 代码（SHA-256 / 元数据自检 / Poly1305） |
| `GzShell.kt` | gzexe 风格自解压包装 |
| `ChaCha20.kt` | ChaCha20（备用，当前未接入产物） |
| `Ed25519.kt` | Ed25519 签名（Kotlin 侧，备用） |

#### 格式转换

| 文件 | 职责 |
|---|---|
| `ImageConverter.kt` | 图片格式转换（JPEG/PNG/WebP） |
| `AudioConverter.kt` | 音频 → WAV（MediaCodec） |
| `VideoConverter.kt` | 视频重封装 / 抽音轨 / GIF（**不重编码**） |
| `Codec.kt` / `Codes.kt` | 编码 / 哈希 |
| `CodeMaker.kt` | 二维码/条形码（ZXing） |
| `SvgToPng.kt` | SVG 转 PNG |
| `MetalDetector.kt` | 金属探测（磁力计 + 震动反馈） |
| `NfcHelper.kt` | NFC 读写核心 |

#### 逆向

| 文件 | 职责 |
|---|---|
| `ElfParser.kt` | 自研 ELF 解析（头/节/符号/程序头） |
| `ElfLoader.kt` | 从 APK/系统/用户路径找 .so |
| `ElfPatcher.kt` | **只允许等长或更短**的字符串替换 |
| `Arm64Disasm.kt` 40KB | 手写解码器（现为 Capstone 的回退路径） |
| `Arm64Encoder.kt` | ARM64 指令编码 |
| `CapstoneBridge.kt` | Capstone JNI 封装 |
| `CapstoneDecoder.kt` | Capstone 适配层（缓存 + 转 Insn） |
| `CfgBuilder.kt` 57KB | **可达性扫描**建 CFG。**必须传 funcSize**，否则越界 |
| `JumpTableResolver.kt` | 跳转表解析 |
| `CfgLayout.kt` / `CfgModel.kt` / `CfgRouter.kt` / `CfgSvgExporter.kt` | 图布局 / 模型 / 路由 / SVG 导出 |
| `PseudoCode.kt` 31KB | ❌ 最早的老伪 C（已被取代但未删） |
| `RadareBridge.kt` | JNI 桥（`libandroid-reverse-native` + `libradare_bridge`） |
| `decompiler/R2Decompiler.kt` | **主用引擎**：r2 pdfj → IR → C |
| `decompiler/DecompilerEngine.kt` | 旧引擎（自研链路，fallback） |
| `decompiler/CCodeGen.kt` / `IrTranslator.kt` / `SsaBuilder.kt` / `SsaRename.kt` / `ExprBuilder.kt` / `Structurer.kt` / `TypeInfer.kt` / `CfgIr.kt` / `DecompilerIR.kt` / `R2Json.kt` | 反编译各阶段 |
| `ShellObfuscator.kt` | ShellVMP V6（用 linker64 加载避开 noexec） |
| `PartitionBackup.kt` | 分区备份（⚠ **核心已完成但缺 UI**） |

#### MCP

| 文件 | 职责 |
|---|---|
| `mcp/McpTool.kt` | 工具定义 + 路径沙箱 + Schema DSL |
| `mcp/McpServer.kt` | HTTP + JSON-RPC 2.0 服务器 |
| `mcp/McpTools.kt` | 13 个工具实现 |
| `mcp/McpService.kt` | 前台 Service + WakeLock + 通知 |
| `mcp/McpCallLog.kt` | 调用日志（内存） |

#### 其它

| 文件 | 职责 |
|---|---|
| `ExportUtils.kt` | 导出文件 |
| `FileImporter.kt` | 文件导入（SAF）+ 复制到 MCP 目录 |
| `Haptics.kt` | 震动反馈 |

---

## 三之六、完整文件清单（续）— `ui/` 全部界面

### 主壳与公共件

| 文件 | 职责 |
|---|---|
| `AppShell.kt` | 主壳，5 tab + 两种底栏 + backdrop 采样层。**改 tab 要同步 3 处** |
| `Home.kt` | `FEATURES` 功能入口表（分组：检测/网络/工具/Root/逆向） |
| `Motion.kt` | 动效参数（FAST 120/NORMAL 220/SLOW 380/STAGGER 45）+ `pressable` + `staggerIn` |
| `Kit.kt` | 通用组件（FoldCard/RowItem/HLine/Spinner） |
| `Widgets.kt` | 零散小组件 |
| `PageHeader.kt` | 蓝色〈返回 + 标题 |
| `TabGlyph.kt` | 底栏矢量图标 |
| `CardGlyph.kt` | 卡片矢量图标 |
| `FeatureIcons.kt` | 功能图标 |
| `theme/Color.kt` | 固定色常量 + `Palette` + `LocalPalette` |
| `theme/Theme.kt` | `CsdemoTheme` + `isAppInDark()` |
| `theme/Type.kt` | 字体 |

### 液态玻璃

| 文件 | 职责 |
|---|---|
| `liquid/Lens.kt` | 折射 shader（**只认 CornerBasedShape**） |
| `liquid/Vibrancy.kt` | 色彩提亮 |
| `liquid/CombinedBackdrop.kt` | 合并两个 backdrop |
| `liquid/InnerShadow.kt` | 内阴影 |
| `bottombar/FloatingBottomBar.kt` | 浮动底栏（含玻璃） |
| `bottombar/DampedDragAnimation.kt` | 阻尼拖拽动画 |
| `bottombar/DragGestureInspector.kt` / `InteractiveHighlight.kt` | 手势 / 高亮 |
| `glass/` | ❌ 已废弃（AGSL shader 方案，已删逻辑） |

### 计算器

| 文件 | 职责 |
|---|---|
| `calculator/CalculatorScreen.kt` | 主壳：9 个标签 + 子面板容器 |
| `calculator/CalcWidgets.kt` | 显示屏 / 按键 / 网格 / 输出框 |
| `calculator/BasicCalc.kt` | 普通 + 科学计算器 |
| `calculator/GraphCalc.kt` | **函数图像**：输入框 + 画布 + 参数滑块 |
| `calculator/LiquidGlassSlider.kt` | 液态玻璃滑块 |
| `calculator/OtherCalcs.kt` 30KB | 方程 / 矩阵 / 统计 / 进制 / 单位 / 几何 |

### 各功能页

| 文件 | 职责 |
|---|---|
| `LoginScreen.kt` / `RegisterScreen.kt` | 登录 / 注册（纯净模式入口已隐藏，代码保留） |
| `MailVerifyScreen.kt` | 邮箱验证（同上） |
| `CardKeyScreen.kt` | 卡密验证 |
| `OnboardScreens.kt` | 隐私 / 方案选择 |
| `MailScreen.kt` 58KB | 邮箱（玻璃卡片 + 下拉刷新 + 详情） |
| `RankingScreen.kt` | 积分排行榜 |
| `SpeedTestScreen.kt` 31KB | 测速页（指针仪表盘 + 历史曲线） |
| `SpeedRankScreen.kt` | 测速排行榜 |
| `SpoofScreen.kt` 25KB | 设备伪装 |
| `FpsOverlayScreen.kt` 22KB | 帧率悬浮窗设置 |
| `FpsHistoryScreen.kt` / `FpsChart.kt` | 帧率历史 / 图表 |
| `ElfScreen.kt` 71KB | ELF 逆向主界面（8 个 tab，含 MCP 分析） |
| `CfgGraphScreen.kt` 48KB | 控制流图 |
| `CfgView.kt` | CFG 视图辅助 |
| `AntiCheatPage.kt` / `AntiCheatBlockScreen.kt` | 反作弊 |
| `CodeScreen.kt` | 码生成 |
| `NfcHomeScreen.kt` / `NfcReadScreen.kt` / `NfcWriteScreen.kt` | NFC |
| `MetalDetectorScreen.kt` | 金属探测 |
| `ConvertScreen.kt` | 格式转换 |
| `DeviceScreens.kt` | 设备信息 |
| `AppsScreens.kt` | 应用列表 |
| `NetScreens.kt` | 网络工具 |
| `TuneScreens.kt` | Root 调节 |
| `SelinuxScreen.kt` | SELinux |
| `ShellObfuscateScreen.kt` 44KB | **Shell 混淆**（ShellVMP + SSC 两个 Tab） |
| `McpAnalyzeScreen.kt` 38KB | **MCP 页**（主页 + 调用日志） |
| `CaptchaRow.kt` | 图形验证码输入行 |
| `nav/AppNav.kt` | ❌ miuix NavKey（未使用，实际走字符串路由） |

### `ui/component/`

| 文件 | 职责 |
|---|---|
| `GlassMailCard.kt` | 邮件玻璃卡片 |
| `TagBadge.kt` | 称号胶囊 + `FxText` 流光 |
| `CheckinCard.kt` | 签到卡 |
| `ConfirmDialogMiuix.kt` | **弹窗外壳**（左边 dismissText / 右边 confirmText） |
| `AnnouncementDialog.kt` | 公告弹窗（基于上面） |
| `UpdateDialog.kt` | 更新弹窗 |
| `ExpressiveDialog.kt` | 另一个弹窗 |
| `MarkdownWebView.kt` / `MarkdownText.kt` | Markdown 渲染 |
| `NoticeCard.kt` | ColorOS 风通知卡片 |

### `ui/pages/`

| 文件 | 职责 |
|---|---|
| `AppPages.kt` 46KB | HomePage / FeaturesPage / ToolsPage / **ThemeSettingsPage** / CheckUpdatePage / AboutPage |
| `ProfilePage.kt` 32KB | 设置页（纯净模式直接渲染主题设置 + 关于） |
| `AccountPages.kt` | 改昵称 / 换邮箱 / 注销 |
| `FeedbackPage.kt` | 反馈 |
| `PrivacySettingsPage.kt` | 隐私设置 |

---

## 四、PHP 服务器（登录系统服务器/）

### 4.1 文件清单

| 文件 | 作用 |
|---|---|
| `api.php` | **所有客户端接口**（`?action=xxx`） |
| `admin.php` | 管理后台单一入口（`?p=xxx`） |
| `admin_mail.php` / `admin_mail_admin.php` | 邮件下发 / 邮件管理 |
| `admin_ipmap.php` | 访问分布 |
| `lib.php` | 数据库 / 校验 / token / 封禁 / 积分经验 |
| `mailbox.php` | 站内信（含奖励领取） |
| `mailer.php` | 无依赖 SMTP（QQ 邮箱） |
| `captcha.php` | 服务端图形验证码 |
| `config.php` | **需改 APP_PEPPER / SMTP** |
| `version.json` | 版本 + 更新日志 + 公告 |
| `data/app.db` | SQLite 数据库 |

### 4.2 启动命令（必须带上传参数）

```bash
pkill -f "php -S"
cd /storage/emulated/0/MT2/mcp/登录系统服务器
php -d upload_max_filesize=512M -d post_max_size=520M \
    -d memory_limit=512M -d max_execution_time=300 \
    -S 127.0.0.1:8080
```

### 4.3 重要坑

| 坑 | 后果 | 解法 |
|---|---|---|
| `imagedestroy()` PHP 8.5 | Deprecated → **header() 全失效** | 删掉 |
| `curl_close()` PHP 8.5 | 同上 | 删掉 |
| `display_errors` 开着 | 任何 Warning 破坏跳转 | `ini_set('display_errors','0')` |
| `ALTER TABLE` 用 try/catch 吞异常 | **列静默缺失，保存无效** | 先 `PRAGMA table_info` 查 |
| `ok()` 是平铺的，不包 data | 客户端解析失败 | 客户端用 `(r.optJSONObject("data") ?: r)` |

---

## 五、待办 / 未完成

### 🔴 P0-1：SSC 加壳待验证

链路已打通（能编译、能链接、能出 ELF），但：

- 流式解密刚修（RC4 多轮必须整份），**未验证大脚本**
- 元数据自检在真机上是否生效、`/proc/self/exe` 是否可读，**待实测**
- 500~900KB 填充对启动速度的影响，**未测**

### 🔴 P0-2：MCP 待真实客户端实测

已验证：服务能启动、JSON-RPC 格式对、Capstone 能加载、13 个工具可用。
待验证：Claude Desktop / Cursor 完整握手链路。

### 🟠 P1-1：分区备份 UI

`PartitionBackup.kt` 核心完成，**缺 UI**。
用户要求「按多系统工具箱的方式」做，**需先要截图**。

### 🟠 P1-2：控制流解析率

用户抱怨「有的函数解析不出控制流」。已修多 bug 但未验证。

### 🟠 P1-3：深色模式未适配

`ElfScreen` / `CfgGraphScreen` / `OnboardScreens` / `CardKeyScreen` /
`MailVerifyScreen` / `CfgView` / `Home`
把 `Ink/Paper/Line` 常量 → `LocalPalette.current.xxx`

### 🟠 P1-4：伪 C 剩余差距

已做：SSA、表达式内联、自然循环、类型推断、Markdown 渲染
仍缺：跨块复制传播 / 常量折叠 / 结构体数组成员识别 / 调用签名推导 /
`br xN` 跳转表接入 r2 链路

### 🟡 P2

- `admin_ranking.php` 已废弃（排行榜改自动算）
- `PseudoCode.kt` / `nav/AppNav.kt` / `NoticeCard.kt` 死代码可清
- 根目录 5 个 0 字节垃圾文件：`A` / `Configure` / `Get` / `Kotlin` / `Run`

### ❌ 已放弃：SSC 脚本编译原生 ELF（旧方案）

当年试图用 JNI 内嵌 libclang-cpp + libLLVM（198MB）：
- GitHub 单文件限 100MB，`libLLVM.so` 127MB 推不上去
- `llvm-strip --debug` 无效
- 缺 Android NDK sysroot
- **已彻底放弃并清理**

**新的 SSC 方案是「用用户上传的 NDK 编译」**，走 clang 子进程，不再内嵌 LLVM。

---

## 六、工作流程

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

⚠️ **本地分支 `master`，远程 `main`，必须写 `master:main`。**
⚠️ **如果 git 报 `dubious ownership`，先跑：**
```bash
git config --global --add safe.directory /storage/emulated/0/MT2/mcp/csdemo
```

**报错文件位置**：`/storage/emulated/0/MT2/mcp/报错.txt`（注意不在 csdemo 目录下，在父目录）。

### CI 注意

`ubuntu-latest` 不预装 NDK 27.0.12077973，workflow 已加显式安装。
Capstone 通过 `FetchContent` 拉源码，**CI 需要联网**，首次构建多 1~2 分钟。

---

## 七、关键文件不要碰

- `gradle/wrapper/gradle-wrapper.jar`（二进制）
- `gradle.properties`（`android.aapt2FromMavenOverride` 已删，云端不能有）
- `local.properties`
- `.github/workflows/build.yml`（除 CI 环境问题）
- `app/src/main/assets/juanzeng.png`（捐赠码）
- `app/src/main/assets/shbin/`（ShellVMP 运行环境，13 个 ELF + 13 个库）
- `app/src/main/jniLibs/arm64-v8a/libr_*.so`（radare2 预编译库）

---

## 八、接手检查清单

新 AI 接手后，按顺序做这几件事：

1. **读零之二**（用户习惯）—— 不读会惹怒用户
2. **读一**（铁律）—— 都是踩过的坑
3. **读 3.12 / 3.13**（文件清单）—— 知道改哪个文件
4. **读 3.14**（SSC）—— 最新、最复杂的模块
5. **确认 Git 状态**：本地 `master` → 远程 `main`
6. **无法本地编译** —— 改完让用户推 CI，报错在 `报错.txt`
7. **改前先 `read_text`** —— 不要凭记忆写代码

### 改代码前必查

```
□ 这个符号/函数是否已在 CalcEngine 的 FN/CONSTS/MULTI_PARAMS 里？
□ 改了 Kind 枚举？→ 搜所有 when(Kind) 补分支
□ 改了 MainTab？→ 同步 AppShell 的 when(page) + 非悬浮底栏 TabItem
□ 改了引用变量？→ 先搜出现次数，全改完再搜一次验空
□ 插入代码？→ 锚点必须唯一，否则会重复拼接（已踩 5 次，见 1.7b）
□ 用了新的 Modifier 扩展？→ 补 import
□ 改了 MCP 工具？→ 路径必须走 McpToolContext.resolve()
□ 改了 SSC 的 C 代码？→ 不能 include 头文件，类型用 u64/s64
```

---

## 九、一句话总结

**当前版本 = 「KernelSU 风 UI + 纯净模式 + 站内信/排行榜/签到 + 液态玻璃邮件
+ 指针式测速 + 9 合一计算器 + Radare2/Capstone 反编译 + SSC 脚本加壳 + MCP 服务器」的双端项目。**

**已完成**：

- **账号**：登录/注册/邮箱验证/头像/切换账号/注销（纯净模式隐藏入口）
- **主页**：KernelSU 风 UI/5 tab 底栏/双击切换/液态玻璃
- **社交**：站内信(玻璃卡片+下拉刷新)/积分排行榜/称号流光/隐私设置
- **测速**：指针仪表盘/下载上传延迟抖动/不封顶/历史曲线/测速排行榜
- **计算器**：普通/科学/函数图像/方程/矩阵/统计/进制/单位/几何（9 个）
- **帧率**：Scene 同款刷新率 + SurfaceFlinger 真实帧率双行悬浮窗
- **逆向**：ELF 解析/反汇编(Capstone)/CFG/伪 C(Radare2)/Shell 混淆/SSC 加壳
- **工具**：格式转换/金属探测/NFC/码生成/VPN 检测/设备伪装/反作弊
- **MCP**：把 App 变成 MCP 服务器（前台 Service 常驻），13 个 ELF 工具

**待办**：

- **P0**：SSC 加壳实测（大脚本 / 自检 / 启动速度）
- **P0**：MCP 真实客户端实测
- **P1**：分区备份 UI、深色模式适配、伪 C 剩余差距
- **P2**：清理死代码

---
