# 项目交接文档 — 交给下一个 AI

> 生成时间：2026-10-18
> 原因：对话长度即将上限，需要交接
> **接手前请读完本文，尤其是「二」「三」「六」三节**

---

## 一、你在接手什么

一个 **Android 原生工具箱 App**（Kotlin + Jetpack Compose）。

| 项 | 值 |
|---|---|
| 工程位置 | `/storage/emulated/0/MT2/mcp/csdemo/` |
| 包名 | `sy.great.toolbox`（**已从 `com.csdemo` 迁完**） |
| 版本 | `1.0.1-beta` / versionCode `101` |
| 编译 | GitHub Actions（云端），**用户不在本地编译** |
| 用户 | 中文、非专业开发者，手机 + MT 管理器 + Actions |

**用户明确要求：回复要短、不要长篇大论、不要问太多问题、不要说教。**

---

## 二、工具环境限制（必须记住）

### 限制 1：只能读写 Home
```
Home = /storage/emulated/0/MT2/mcp/
```
不在 Home 下的文件，必须让用户自己移动。

### 限制 2：目录创建返回值不可信
`mt_file_create_directory` 报 `changed: true` 但父目录不存在时**实际没建**。
`mt_file_edit_text` 在父目录不存在时**也返回成功但没落盘**。

**正确做法**：建目录 → `mt_file_stat` 验证 → 写文件 → 再 `stat` 验证。

### 限制 3：没有编译能力
**不能编译、不能跑 Gradle。** 改完必须逐行复核语法。
**永远不要声称「已编译通过」。** 用户会推 Actions 并把报错贴回来。

### 限制 4：MCP 会间歇断开
报 `Failed to connect to /127.0.0.1:8787`，直接重试同一个调用即可。

### 限制 5：没有 shell
git 命令必须让用户自己跑。

---

## 三、禁止清单（用户明确拒绝）

1. **❌ 不要擅自动 UI** —— 用户原话：「改动我的 UI 是高危操作」。改 UI 前必须先问。
2. **❌ 不要做假实现** —— 读不到就说读不到。用户对「造假」极度反感。
3. **❌ 不要一次改几千行** —— 用户经历过多次「一次写太多导致全部编译失败」。一次 1~3 处。
4. **❌ 不要写 AI 味文案** —— 不要 emoji 堆砌、不要「赋能/生态」。
5. **❌ 不要装编译器**
6. **❌ 不要问太多问题** —— 但改 UI 必须先问。

---

## 四、工具链（已迁移，勿回退）

| 项 | 值 |
|---|---|
| AGP | 9.4.1 |
| Kotlin | 2.4.20（AGP 9 内置，不用 kotlin.android 插件） |
| Compose Compiler | `org.jetbrains.kotlin.plugin.compose` 2.4.20 |
| serialization | `org.jetbrains.kotlin.plugin.serialization` 2.4.20 |
| Compose BOM | 2026.09.00 |
| material3 | 1.5.0-alpha28 |
| **material-icons-extended** | **1.7.8（必须固定，BOM 2026 已移除）** |
| compileSdk / targetSdk | 37 |
| minSdk | **33**（miuix-blur 要求） |
| JDK | 21 |
| NDK | **27.0.12077973**（Radare2 JNI 用） |
| CMake | 3.22.1 |
| miuix | 0.9.4（ui / blur / icons / preference / nav） |

**`buildFeatures { aidl = true }`** —— Shizuku UserService 必需，不要关。

---

## 五、已完成的功能（不要重做）

### 5.1 主页（KernelSU 复刻）
`sy.great.toolbox.ksu` 包：`HomeMiuix.kt` / `HomeUiState.kt` / `Kernels.kt` / `WarningCard.kt` / `StatusTagMiuix.kt` / `BlurExt.kt`
- 三态状态卡：root（绿）/ ADB（紫）/ user（蓝），自动优先 root
- **双色卡已停用**，`DualStatusCard` 保留但标 `@Suppress("unused")`，不要重新启用

### 5.2 液态玻璃底栏
`ui/liquid/`（Lens/CombinedBackdrop/Vibrancy/InnerShadow）+ `ui/bottombar/`
- 页面内容必须 `.layerBackdrop(backdrop)` 注册，底栏才能采样
- **关闭悬浮底栏时**：`AppShell.kt` 里有 `if (!floating)` 分支，画的是「贴底横条」（直角、全宽、实心、各项均分）
- ⚠️ **贴底横条里 `TabItem` 是逐个显式调用的**（为了 `Modifier.weight` 的作用域）
  **加 tab 时必须同步补一个 `TabItem`，否则关闭悬浮后会少一项。**

### 5.3 底部 5 个 tab（顺序不能乱）
```
0 主页 / 1 反作弊 / 2 常用功能 / 3 工具 / 4 设置
```
`MainTab` 枚举顺序 = `HorizontalPager` 的 `when(page)` 索引，**改一处必须改另一处**。

### 5.4 设置页 + 主题页
- `SettingsPage`：只有「检查更新 / 主题设置 / 关于」三个入口
  （底栏设置、界面缩放已合并进主题设置，不要重新拆开）
- `ThemeSettingsPage`：复刻 KernelSU 视觉，带顶部预览卡、每项左侧带图标
- ⚠️ `Scaffold` 必须传 `popupHost = { }`，否则点下拉闪退

### 5.5 统一返回键 `PageHeader`
`ui/PageHeader.kt`：蓝色 `〈`（Accent 色）+ 标题，点击区 44dp。
**所有子页都用它**（含 ELF 上传页、ELF 详情页、设置子页）。

### 5.6 刷新率悬浮窗（系统级，Scene 同款）
- `tools/FpsOverlayService.kt`：前台服务 + `TYPE_APPLICATION_OVERLAY`
- `tools/DisplayRateReader.kt`：读驱动实测节点
  ```
  /sys/class/drm/sde-crtc-0/measured_fps
  /sys/devices/platform/soc/ae00000.qcom,mdss_mdp/drm/card0/card0-sde-crtc-0/measured_fps
  /sys/class/graphics/fb0/measured_fps
  /sys/kernel/fpsgo/fstb/fpsgo_status  ← MTK，需按表头找 currentFPS 列
  service call SurfaceFlinger 1013   ← binder 兜底
  ```
- **纯 Shizuku（ADB）通道，不用 su**（su 每次起进程太慢，做不到毫秒级）
- 采样 16ms 起（自适应），值没变不 setText
- 交互：小形态只显示刷新率 / 点一下展开 / 拖动改位置

### 5.7 帧率录制
- **长按悬浮窗 2 秒** → 窗口变橙色（**仍显示帧率**，不改成「录制中」文字）+ Toast「帧率录制中」
- **点击一次** → 停止；**不足 10 秒** → Toast「还差 N 秒」
- 停止后 Toast「已停止录制，共 N 个采样点（已存入历史）」
- `tools/FpsRecorder.kt`：50ms 采样，采集项：FPS / 温度 / CPU频率 / CPU使用率 / 内存 / GPU频率
- **除刷新率外的指标合并成一条 shell 命令**（避免 50ms 内多次往返）

### 5.8 帧率历史记录
- `ui/FpsHistoryScreen.kt`：列表显示**结束时间（精确到秒）** + 时长 + 采样点数
- 点进去 → 复用 `FpsChartCard`（5 张卡片）
- **最多保留 100 条**，超出删最旧的
- 存储：`filesDir/fps_history/<结束时间戳>.txt`，纯文本格式（首行头部 + 每行一条采样）
- 入口在 `FpsOverlayScreen` 里（miuix `ArrowPreference` 横向行）

### 5.9 反作弊检测（第 2 个 tab）
- `tools/AntiCheat.kt`：检查 `/proc/inte_ko`、`/proc/inte_status`、`/proc/inte_systbl`
- **必须 root**，没 root 只显示「需要 Root 权限」，不展示结果
- 有 root 自动检测，右上角可手动刷新
- 驱动信息**全部真实读取**（`/proc/modules` + `/sys/module`），**不允许写死**

### 5.10 设备伪装 / 电量伪装
- `DeviceSpoof.applyBattery` 用 `dumpsys battery set level N`
- **level 只接受 1~100**，>100 要裁切并如实告知
- 执行后用 `dumpsys battery` 回读验证

---

## 六、🔴 当前正在进行：接入 Radare2（未完成）

### 6.1 为什么要接

用户反馈「控制流准确性不行、伪 C 是狗屎」。

**根因分析（已确认）：**
- 现有 `CfgBuilder.kt` 是「递归下降 + 模式匹配」
- **间接跳转 `br xN` 直接中断**，不解析跳转表 → switch 的块全丢
- 没有 SSA / 类型推断 → 伪 C 只是「带变量名的汇编」

**解决方案：用 Radare2 的 `libr_anal.so` / `libr_core.so` 做分析。**

### 6.2 已完成的部分

**① 从 `Android Reverse Ide_20.apk` 提取了 27 个 `.so` 到 `app/src/main/jniLibs/arm64-v8a/`**
```
libr2.so  libr_core.so  libr_anal.so  libr_arch.so(12MB)  libr_asm.so
libr_bin.so  libr_util.so  libr_io.so  libr_cons.so  libr_config.so
libr_flag.so  libr_reg.so  libr_syscall.so  libr_search.so  libr_debug.so
libr_esil.so  libr_lang.so  libr_magic.so  libr_muta.so  libr_socket.so
libr_fs.so  libr_bp.so  libr_egg.so  libr_main.so
libc++_shared.so  libcore_ghidra.so(8.7MB，Ghidra反编译器)
libandroid-reverse-native.so
```
**⚠️ 这几个 `.so` 打包进 APK 后编译已通过（Build #84 前后）。**

**② 头文件已全部就位**
```
cpp/include/           ← radare2-master/libr/include 的全部 .h（约 130 个）
cpp/include/r_util/    ← 69 个
cpp/include/r_anal/    ← 2 个（hint.h, op.h）
cpp/include/r_muta/    ← 4 个
cpp/include/sdb/       ← sdb-master/include/sdb 的 23 个（真的，非手写）
```
**⚠️ sdb 是 radare2 的 git submodule，radare2-master.zip 里不含它。**
**已从 `sdb-master/include/sdb/` 拷入（用户提供的源码包）。**

**③ 写了 JNI 桥**
- `cpp/CMakeLists.txt`：编译 `radare_bridge.c`，链接 23 个 `libr_*.so`
- `cpp/radare_bridge.c`：JNI 实现（`nativeVersion` / `nativeOpen` / `nativeAnalyze` / `nativeCfgJson` / `nativePseudoC` / `nativeDisasm` / `nativeClose`）
- `tools/RadareBridge.kt`：Kotlin 调用层

**④ 手写的 `r_userconf.h` / `r_version.h`**（替换 autotools 生成的版本）

### 6.3 ❌ 当前卡在这里

**最后一次编译（Build #92）报错：**
```
r_util/r_str_constpool.h:5:10: fatal error: 'sdb/ht.h' file not found
```

**原因（务必理解，否则会白花很多时间）：**

`sdb` 是 Radare2 的 **git submodule**，`radare2-master.zip` 里**不含它的头文件**。
`subprojects/sdb/` 下只有 `sdb.mk` / `sdb.wrap`（下载配置），**没有 `.h`**。

而 r2 的头文件**大量 `#include <sdb/...>`**：
```
r_bin.h      → sdb/ht_pp.h, sdb/ht_up.h
r_cons.h     → sdb/sdb.h,  sdb/ht_up.h
r_anal.h     → sdb/set.h
r_esil.h     → sdb/ht_uu.h, sdb/ht_up.h
r_agraph.h   → sdb/ht_uu.h
r_lib.h      → sdb/ht_pp.h
r_cmd.h      → sdb/ht_pp.h
r_event.h    → sdb/ht_up.h
r_str_constpool.h → sdb/ht.h
```

**这就是为什么“逐个补 sdb 头文件”会像挤牙膏一样永远补不完。**

**已处理**：用户提供了 `sdb-master` 源码包，其 `include/sdb/` 下的 **23 个头文件已全部拷入**
`cpp/include/sdb/`（已核实：`sdb.h` 为真实文件 18651 字节，非手写）。
它们覆盖了：`sdb.h` / `ht.h` / `ht_inc.h` / `ht_pp.h` / `ht_pu.h` / `ht_su.h` /
`ht_up.h` / `ht_uu.h` / `set.h` / `types.h` / `buffer.h` / `cdb.h` / `cdb_make.h` /
`config.h` / `cwisstable.h` / `dict.h` / `heap.h` / `ls.h` / `rangstr.h` /
`asserts.h` / `gcc_stdatomic.h` / `msvc_stdatomic.h` / `version.h`。

**⚠️ 还没推送验证过这一步。**

**⚠️ 已否决的方案：dlsym**
有人试过改用 `dlopen` + `dlsym` 绕开所有头文件（能编译过），
**但用户明确否决，不要再用**。原因：失去编译期类型检查，参数写错会运行时崩。

**⚠️ 另一个已验证的事：不要用自己手写的“最小 sdb.h”**
早期手写过假的 `sdb.h` / `ht_*.h`，由于 sdb 头文件含大量宏与内联函数，
手写版本一定不准，**已全部换成 sdb-master 的真实文件**。

### 6.4 下一个 AI 要做的事（按顺序）

**Step 1：先推一次，看能不能过 `sdb/ht.h`**
```bash
cd /storage/emulated/0/MT2/mcp/csdemo && git add . && git commit -m "真实 sdb 头文件" && git push origin master:main
```
- 如果报**新的头文件缺失** → 继续从 `radare2-master/libr/include/` 或 `sdb-master/include/sdb/` 拷
- 如果报**`undefined symbol: r_xxx`** → 说明 `CMakeLists.txt` 的 `R2_LIBS` 缺库，从 APK 里再提（**注意：临时 workspace 已关闭，需重新 `mt_apk_open`**）
- 如果**编译通过** → 进入 Step 2

**Step 2：验证库真的能加载**
在 `FpsOverlayScreen` 或新页面加一个按钮，调 `RadareBridge.version()`，看是否返回版本号。
**如果返回 `ERR: ...`，说明 `dlopen`/链接有问题。**

**Step 3：接线到 ELF 控制流**
把 `CfgBuilder.analyzeFunctions` 换成 `RadareBridge.cfgJson(addr)`，解析返回的 JSON。

### 6.5 ⚠️ 已知风险

1. **`libandroid-reverse-native.so` 只导出了 13 个 JNI 函数**（全是 Java decompiler 相关），**没有 r2 的 JNI 入口**。所以必须自己写桥。
2. **`r_core_version()` 不在公开头文件里** → 已改用命令 `?V`。
3. **`pdg`（Ghidra 伪 C）可能不可用** —— `libcore_ghidra.so` 的 JNI 接口未知，只能先试 `pdc`（r2 自带伪 C）。
4. **`APP 体积 +25MB`。**
5. **`sdb` 头文件版本必须与 `libr_util.so` 内的 sdb 一致**，否则可能运行异常。

---

## 七、待办列表

### 🔴 P0
1. **把 Radare2 接通**（见第六节）
2. **修复「有的函数解析不出控制流」**
   - 用户反馈：「99.9% 都应该能解析出，这绝对是 bug」
   - 排查方向：
     - `CfgBuilder.discoverFunctions()` 是否漏了「无符号表」的情况
     - `analyzeFunctions()` 的过滤条件是否太严（单块且无边 / 总指令 ≤2 / 块数≥3但零分支）
     - `buildFromAddress()` 的 `nextFuncAddr` 边界是否过早截断
     - 段末尾的最后一个函数是否被丢掉

### 🟠 P1
3. **深色未适配的页面**：`ElfScreen` / `CfgGraphScreen` / `OnboardScreens` / `CardKeyScreen` / `MailVerifyScreen` / `CfgView` / `Home`
   （把 `Ink/Paper/Line` 常量 → `LocalPalette.current.xxx`）
4. **ELF 逆向的遗留**：间接跳转 `br Xn` 解析 / Jump Table / 递归函数发现 / Tail Call / SSA / 类型恢复

### 🟡 P2
5. 控制流图里点某行 → 弹窗（分析伪C / 跳转到此 / 返回）—— **已做，但需验证**
6. 行编辑 + 格式检查（`checkAsmSyntax`）—— **已做，需验证**
7. `Arm64Encoder.kt` 指令写回 ELF —— **已做，需验证**

---

## 八、常见编译错误（踩过的坑）

| 错误 | 原因 | 正确做法 |
|---|---|---|
| `Unresolved reference 'CheckCircleOutline'` | BOM 2026 移除了 material-icons-extended | 固定 `1.7.8` |
| `Cannot access 'newProcess'... private` | Shizuku 13.x 的 `newProcess` 是 private | 用 `bindUserService` + AIDL |
| `@Composable invocations can only happen from...` | `@Composable` 扩展放进了 `remember{}` | `WindowInsets.statusBars` 要直接调 |
| `No parameter with name 'sizeTransform'` | 本版本无此参数 | 用 `.using(SizeTransform(clip = false))` |
| 点击下拉闪退 | `Scaffold` 缺 `popupHost` | `Scaffold(popupHost = { })` |
| `Unresolved reference 'RectangleShape'` | 它在 `ui.graphics`，不在 `foundation.shape` | `import androidx.compose.ui.graphics.RectangleShape` |
| `Unsupported escape sequence` | Kotlin 里 `\ `（反斜杠+空格）非法 | 用 `\$` 转义 `$`，不用 `\$` |
| `Syntax error: Unclosed comment` | 注释里写了 `/sys/.../*` 的 `/*` | 注释里不要出现 `/*` |
| `Unresolved reference 'z'` | 三引号字符串里 `$z` 仍会插值 | 改成普通字符串 + `\$z` |
| `cannot be called with an implicit receiver` | `Modifier.weight` 在非 `RowScope` lambda 里 | 用 `RowScope` 扩展函数 |
| `add_library cannot create imported target` | CMake `R2_LIBS` 里有重复项 | 去重 |
| `Argument type mismatch: '<anonymous>'` | `object : Runnable{}` 里的 `this` 不是 Service | 用 `this@ServiceName` |

---

## 九、工作流程

```
1. 先 read_text 读实际代码（不要凭记忆）
2. ���步改（一次 1~3 处）
3. 改完立即 read_text 复核
4. 检查：花括号配对、import 齐全、类型匹配
5. 让用户推送 GitHub
6. 用户贴回 e: 开头的报错（带文件名+行号）
7. 修复，重复
```

**用户推代码用：**
```bash
cd /storage/emulated/0/MT2/mcp/csdemo && git add . && git commit -m "说明" && git push origin master:main
```
**注意：本地分支是 `master`，远程是 `main`，必须写 `master:main`。**

---

## 十、关键文件不要碰

- `gradle/wrapper/gradle-wrapper.jar`（二进制，删了不能构建）
- `gradle.properties`（`android.aapt2FromMavenOverride` 已删，云端不能有这个）
- `local.properties`
- `.github/workflows/build.yml`
- `app/src/main/assets/juanzeng.png`（捐赠码）

---

## 十一、外部资源位置

| 资源 | 路径 | 备注 |
|---|---|---|
| Radare2 头文件 | `/storage/emulated/0/MT2/mcp/include/` | = `radare2-master/libr/include`，已拷入 `cpp/include` |
| Radare2 源码 | `/storage/emulated/0/MT2/mcp/radare2-master/` | **不含 sdb（git submodule）** |
| sdb 源码（含头文件）| `/storage/emulated/0/MT2/mcp/sdb-master/` | **sdb 头文件只能从这里拿** |
| Android Reverse APK | `/storage/emulated/0/MT2/mcp/Android Reverse Ide_20.apk` | 27 个 `.so` 的来源 |
| Android Reverse 文档 | `/storage/emulated/0/MT2/mcp/androidReverse-master/` | **只有 README+截图，无源码** |

**`androidReverse-master` 只是个占位仓库。它真正的能力（CFG / 伪 C）全部来自内嵌的 Radare2（README 写明 "Powered by an embedded Radare2 engine via JNI"），所以不能从它里“拄逻辑”。**

---

## 十二、本次会话踩过的坑（交给下一个人避坑）

### 12.1 不要一次改太多

本次会话里有多次“一次改 5~10 处 → 一堆编译错 → 花很久排查”。
**正确做法：一次 1~3 处，改完立刻复核。**

### 12.2 改 Compose 页面时，每段逻辑抽成独立函数

反复因为**嵌套太深、数错花括号**导致 `Expecting '}'` 或
`Modifier 'private' is not applicable to 'local function'`。

**正确做法**：`Column { if(...) A() else B() }`，每个分支都是独立的 `@Composable`，
不要在同一个函数里嵌套 5 层 `if/else + Card + Row + Column`。

### 12.3 加新代码必查 import

多次报 `Unresolved reference 'fillMaxWidth'` / `'RectangleShape'` / `'Row'`。
**正确做法：写完后把新用到的每个符号在 import 区核一遍。**

### 12.4 特殊场景的 Kotlin 陷阱（本次反复踩）

| 陷阱 | 说明 |
|---|---|
| `object : Runnable {}` 里的 `this` | 指向匿名对象，不是 Service → 用 `this@Service` |
| `forEachIndexed` 里调 `Modifier.weight` | `this` 不再是 `RowScope` → 用 `RowScope` 扩展函数 |
| `0x94000000` | 超 Int 范围，被推断为 `Long` → 加 `.toInt()` |
| `'\n\n'` | 是两个字符，不是 Char → 写 `"\n\n"` |
| 字符串里写 `\ ` | 非法转义 → 用 `\$` 转义 `$` |
| 注释里写 `/sys/*` | `/*` 被当块注释开头 → 注释里避开 `/*` |

### 12.5 不要“自己造”依赖库的头文件

sdb 头文件含大量宏与内联函数，手写的“最小版”**必定不准**。
**正确做法：找真实源码包（`sdb-master` 就在 Home 下）。**

### 12.6 文件会被外部改动的假象

多次报 `FILE_CHANGED_DURING_READ`。**不是别人在改**，而是：
- MCP 同一轮里连续编辑，哈希每次都变
- 或 MT 管理器/同步工具在碰文件
**处理：重新 read_text 用新 targetVersion 重试。**

---

## 十三、一句话总结

**当前版本 = 「KernelSU 风格 UI + Radare2 接入中 + Shizuku ADB」的工具箱 App。**

- 主页 / 底栏 / 设置 / 关于 / 刷新率悬浮窗 / 帧率录制 / 反作弊检测 = **已完成**
- **Radare2 接入 = 头文件已备齐（含真实 sdb），但还没编译验证过**
- **控制流解析率 bug = 未修复**

**下一步：先推一次验证 sdb 头文件，再修控制流解析率 bug。**
