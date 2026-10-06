# 项目交接文档 — 交给下一个 AI

> 生成时间：2026-10-02
> 原因：上一轮对话长度达到上限，需要交接

---

## 一、你在接手什么

一个 **Android 原生工具箱 App**（Kotlin + Jetpack Compose）。

**工程位置**：`/storage/emulated/0/MT2/mcp/csdemo/`

**当前状态**：源码完整，可编译（最后一次构建通过了 Kotlin 编译）。

**用户是谁**：中文用户，非专业开发者，用 MT 管理器和 AndroidIDE 在手机上开发和构建。**不会用电脑**，全部操作在手机上完成。

---

## 二、最重要的事：你的工具环境限制

你通过 **MT MCP** 操作文件。**必须知道这些限制**，否则会反复出错：

### 限制 1：只能写 Home 目录
```
Home = /storage/emulated/0/MT2/mcp/
```
- ✅ 可读写：`MT2/mcp/` 及其子目录
- ❌ 拒绝：其他所有路径（含 `/storage/emulated/0/` 根目录）
- 用户要的文件如果不在 Home 下，必须让用户自己移动

### 限制 2：目录创建的返回值**不可信**

**这是最坑的一点，务必记住。**

`mt_file_create_directory` 报告 `changed: true`，但**父目录不存在时实际没创建**。

同样，`mt_file_edit_text` 在**父目录不存在时也返回成功**，但文件没落盘。

**正确做法**：
```
1. 建目录（逐层）
2. 立刻用 mt_file_stat 验证目录存在
3. 再写文件
4. 再立刻用 mt_file_stat 验证文件存在
```

### 限制 3：没有编译能力

**你不能编译 Kotlin、不能跑 Gradle。**

- 改完代码后，**必须自己逐行复核语法**
- 用户会在 AndroidIDE 里构建，然后把报错贴回来
- **不要声称"已编译通过"**，你没这个能力

### 限制 4：不能执行 shell 命令

不能 `java -jar`，不能 `bash`，不能运行任何程序。

### 限制 5：MCP 会间歇性断开

报错形如 `Failed to connect to /127.0.0.1:8787`。

**处理**：直接重试同一个调用，通常第二次就好。

---

## 三、用户的核心诉求

### 诉求 1：ELF 控制流图必须真实

用户原话：
> "我要的不是检测控制流是否真实，而是保证他不犯错，要原本就真实！"

**含义**：
- ❌ 不要做"CFG 校验徽标"告诉用户"图可能不准"
- ✅ 要让 CFG **本来就正确**
- 上一轮我删掉了 `CfgValidator.kt`（那是个错误方向）

### 诉求 2：不要 AI 味

用户反复强调这一点。具体表现：
- ❌ 不用 emoji 堆砌
- ❌ 不写"赋能""生态""一站式"这类空话
- ❌ 不要在 UI 里讲设计哲学
- ✅ 白底黑字，一个蓝色强调
- ✅ 文案用数据说话（如 `SoC 骁龙 8 Gen 3`）
- ✅ 动效克制（按压回弹、错峰淡入）

### 诉求 3：功能要真实可用

**不要假实现**。读不到就显示读不到，失败就说失败原因。

---

## 四、项目结构

```
csdemo/
├── settings.gradle.kts
├── build.gradle.kts          AGP 8.12.0 / Kotlin 1.9.24
├── gradle.properties         ★ 含 android.aapt2FromMavenOverride=/usr/bin/aapt2
├── local.properties          ★ 含 sdk.dir=/opt/android_sdk
├── gradle/wrapper/           ★ gradle-wrapper.jar 必须有，不能删
├── 预审计代码.txt             审计文档（含 ElfParser.kt + CfgBuilder.kt 源码）
└── app/
    ├── build.gradle.kts      compileSdk 35 / minSdk 26 / Compose BOM 2024.04.01
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/logo.png   应用图标源图（2048x2048）
        ├── res/
        │   ├── drawable/ic_launcher_background.xml
        │   ├── drawable/ic_launcher_foreground.xml
        │   ├── mipmap-anydpi-v26/ic_launcher.xml
        │   ├── mipmap-xxxhdpi/ic_launcher.png
        │   ├── mipmap-xxxhdpi/ic_launcher_native.png
        │   └── values/{strings,themes}.xml
        └── java/com/csdemo/
            ├── MainActivity.kt              路由 + 首启流程
            ├── tools/                       ★ 工具层
            │   ├── Shell.kt                 进程执行 + su 提权
            │   ├── RootCheck.kt             Root 检测（三级结论）
            │   ├── Net.kt                   ping/dns/tcp/http/arp
            │   ├── Device.kt                硬件信息
            │   ├── Apps.kt                  应用列表
            │   ├── Codec.kt                 编解码哈希
            │   ├── ElfParser.kt             ★ ELF 解析
            │   ├── CfgBuilder.kt            ★★ CFG 构建（最重要）
            │   ├── CfgLayout.kt             分层布局
            │   ├── CfgRouter.kt             边路由
            │   ├── CfgModel.kt              可绘制模型
            │   ├── CfgSvgExporter.kt        SVG 导出
            │   ├── Arm64Disasm.kt           ★★ ARM64 反汇编器
            │   ├── ElfLoader.kt             SO 文件定位
            │   ├── ElfPatcher.kt            字符串原地修改
            │   ├── FileImporter.kt          文件导入
            │   ├── ExportUtils.kt           导出（MediaStore）
            │   ├── RootTune.kt              Root 调优
            │   ├── DeviceSpoof.kt           设备伪装
            │   ├── SpoofData.kt             芯片/机型数据表
            │   ├── Selinux.kt               SELinux
            │   ├── Haptics.kt               震动
            │   ├── Plan.kt                  方案选择
            │   ├── CardKey.kt               卡密（旧）
            │   ├── MailAuth.kt              邮箱验证
            │   ├── SmtpMailer.kt            SMTP 客户端
            │   ├── Codes.kt                 验证码生成
            │   ├── PseudoCode.kt            旧伪 C（已被 decompiler 取代）
            │   └── decompiler/              ★ 新反编译架构
            │       ├── DecompilerIR.kt      IR 数据结构
            │       ├── IrTranslator.kt      寄存器状态模拟
            │       ├── CfgIr.kt             IR 级基本块 + CFG
            │       ├── CCodeGen.kt          支配关系 + 结构还原
            │       └── DecompilerEngine.kt  顶层串联
            └── ui/
                ├── Home.kt                  首页
                ├── Widgets.kt               通用组件
                ├── Motion.kt                动效
                ├── Kit.kt                   另一套组件
                ├── Theme/Color.kt           配色
                ├── DeviceScreens.kt         设备 + Root 页
                ├── NetScreens.kt            网络工具页
                ├── AppsScreens.kt           应用列表 + 编解码页
                ├── CfgGraphScreen.kt        ★★ CFG 图形页面
                ├── CfgView.kt               旧的简易 CFG 视图（已弃用）
                ├── ElfScreen.kt             ★★ ELF 详情页（含控制流/伪C tab）
                ├── TuneScreens.kt           Root 调优页
                ├── SpoofScreen.kt           设备伪装页
                ├── SelinuxScreen.kt         SELinux 页
                ├── OnboardScreens.kt        隐私政策 + 方案选择
                ├── CardKeyScreen.kt         验证码输入页
                └── MailVerifyScreen.kt      邮箱验证页
```

---

## 五、当前必须优先解决的问题

### P0-1：ELF CFG 真实性

**已完成**（上一轮）：
- ✅ 线性扫描 → 可达性工作队列（`Work` 队列，ret/b 不推进）
- ✅ 切块加地址连续性检查
- ✅ `makeBlock` 的 successors 不再自算（只用 edges）
- ✅ `buildForFunc` 不再回退到 entry 段（拿错段会导致解码错位）
- ✅ 边去重从 hash 改为字符串键
- ✅ NORETURN 识别（abort/exit/__stack_chk_fail 等）

**未完成**（需要你继续）：
- ❌ **间接跳转 `br Xn` 未解析** —— 目前当出口，会丢失 switch 结构
- ❌ **Jump Table 未实现** —— 需要回溯 ADRP+ADD+LDR+BR
- ❌ **函数发现不是真正的递归下降** —— 目前是「符号表 + e_entry + 段起点」
- ❌ **Tail Call 未识别** —— `b target_function` 被当普通跳转
- ❌ **分支条件语义不完整** —— 只有 T/F，没有保存 Z/N/C/V 谓词

### P0-2：Arm64Disasm 需要核查

**上一个审计报告说**：因为没拿到 `Arm64Disasm.kt` 源码，**无法认证解码正确性**。

**需要核查**：
- 各分支指令的 `target` 计算是否正确
- 五个标志位 `isBranch` / `isCondBranch` / `isCall` / `isReturn` / `isIndirect` 是否互斥且完整
- `decode` 失败时返回什么

**如果解码错，CFG 必错。** 这是前置条件。

### P1：伪 C 质量

当前 `tools/decompiler/` 是**第一阶段的实现**：
- ✅ IR 数据结构
- ✅ 寄存器状态模拟（`mov w8,w0` + `add w8,w8,w1` → `v0=arg0; v1=v0+arg1`）
- ✅ 支配关系 + if/while 还原
- ❌ **SSA 未实现**（无 Phi 节点）
- ❌ **数据流分析未实现**
- ❌ **类型恢复未实现**（用户想要 `int32_t` 这种）

### P2：布局性能

- `CfgLayout.orderWithinLayers()` 用 `nodes.firstOrNull { it.id == ... }` → **O(N²)**
- 建议改 `Map<Int, Node>`

---

## 六、用户明确拒绝的方向（不要再做）

1. **❌ 不要做 CFG 校验徽标** —— 用户认为这是"让体验感大打折扣"
2. **❌ 不要做假的反编译器** —— 用户要真类型，做不到就直说
3. **❌ 不要写 AI 味文案** —— 不用 emoji 堆砌、不写空话
4. **❌ 不要一次写几千行** —— 用户经历过多次"一次写太多导致全部编译失败"

---

## 七、工作方式建议

### 每轮改动的正确流程

```
1. 先 read_text 读实际代码（不要凭记忆）
2. 小步改（一次改 1~3 处）
3. 改完立即 read_text 复核
4. 确认花括号配对、导入齐全
5. 把关键片段追加到「预审计代码.txt」
6. 让用户构建
7. 用户贴回报错 → 修
```

### 常见错误（我已经踩过的坑）

| 错误 | 后果 | 正确做法 |
|---|---|---|
| `const val` 写在 `data class` 里 | 编译失败 | 只能顶层/object/companion |
| `typealias` 写在 `object` 里 | 编译失败 | 只能顶层 |
| `"$d"` 当字面量 | 编译失败（被当插值） | 用 `'$'` 单字符 |
| 删代码时只删中间，留了括号 | 大量语法错误 | 连带闭合符号一起匹配 |
| 加导入时没检查重名 | `Overload resolution ambiguity` | 先 read_text 确认 |
| 局部函数互相调用 | 顺序问题 | 改成类方法或 lambda |
| lambda 里 `return` | 需要 `return@label` | 改用类方法 |
| `Regex.replaceFirst(input){lambda}` | 不存在这个重载 | 手工拼接 |
| 插入代码时定义了重名字段 | 重复定义 | 先搜一遍 |

### 关键文件不要碰

- `gradle/wrapper/gradle-wrapper.jar`（二进制，删了不能构建）
- `gradle.properties`（含 `aapt2FromMavenOverride`，AndroidIDE 必需）
- `local.properties`（含 `sdk.dir=/opt/android_sdk`）

---

## 八、用户环境（重要）

| 项 | 值 |
|---|---|
| 开发方式 | 手机 + MT 管理器 + AndroidIDE |
| 构建 | AndroidIDE 里点 Build |
| Gradle | 8.13（wrapper 已配好） |
| AGP | 8.12.0 |
| Kotlin | 1.9.24 |
| Compose BOM | 2024.04.01 |
| compileSdk/targetSdk | 35 |
| minSdk | 26 |
| JDK | AndroidIDE 内置（可能不确定版本） |

**用户是中文使用者，回复请用中文。**

---

## 九、下一步建议

按优先级，我建议下一个 AI 做：

### 第一步：核查 Arm64Disasm.kt（P0-2）

这是 CFG 正确性的前置条件。**逐条验证分支指令的 target 与标志位。**

### 第二步：实现 Jump Table / 间接跳转（P0-1）

这是"图缺结构"的最大原因。典型模式：
```asm
adrp x8, table
add  x8, x8, :lo12:table
ldr  w9, [x8, x0, lsl #2]
add  x8, x8, w9, sxtw #2
br   x8
```

### 第三步：真正的递归函数发现

```
queue = [entry]
while queue:
    analyze function
    for bl/b target:
        if target 不在已知函数列表:
            作为新函数加入 queue
```

### 第四步：SSA / 类型恢复（伪 C 提升）

---

## 十、和用户沟通的注意事项

1. **不要过度承诺** —— 做不到就说做不到，用户经历过多次"AI 说能做但做不出来"
2. **不要假装已编译** —— 你没编译能力
3. **用户脾气直接** —— 会用"垃圾""狗屎"这类词，**不要辩解，直接改**
4. **不要问太多问题** —— 用户说过"你想咋来咋来，不要问我"
5. **回复要短** —— 用户不喜欢长篇大论的解释

---

## 十一、参考：用户写过的原始需求

### ELF 逆向部分
> "可以生成真实控制流图，跟 ide 里面的一样，反伪 c"
> "不要固定分析某一个块，把它加进去上面的节区，符号，字符串等等"
> "点击直接绘制，不用选择某个块，比如 0x5be946 直接分析整个 so/ELF"

### 控制流图部分
> "可拖拽可点击，缩放后文字依然清晰"
> "正交折线，条件跳转有 True/False 标签，循环回边绕行"
> "优先放到最前面，把 0x0 这种放到最后面，避免影响分析"

### 伪 C 部分
> "就要反编译后的伪 c"
> "B"（选择了需要类型推导的方案）

### 导出部分
> "把导出 SVG 给我改成先导出 SVG，然后在 svg 转 png，自适应长宽高"
> "可以直接写啊"（要求直接写到指定目录）

---

## 十二、已知的外部资源

用户在 `/storage/emulated/0/MT2/mcp/360加固+VPM混淆/` 下有一套 **dpt 工具链**：
```
360加固+VPM混淆/dpt/
├── dpt.jar                  反编译/加固工具（Java，手机跑不了）
├── dpt-exclude-classes-template.rules
└── shell-files/
    ├── dex/classes.dex      壳 dex
    ├── dex/junkcode.dex
    └── libs/{arm,arm64,x86,x86_64}/libjiagu_vip.so
```

**这些和当前的 ELF 分析功能无关**，是之前的加固需求留下的。

---

## 十三、一句话总结现状

**当前版本 = 「AArch64 ELF 可达性扫描 CFG 分析器」**

- 可达性扫描已实现（不再有假块）
- NORETURN 已识别
- 间接跳转、Jump Table、递归函数发现**未实现**
- 伪 C 是第一阶段（IR + 基本结构还原，无类型）

**不要对外声称"完整真实 ELF CFG"，因为间接跳转和函数发现还不完整。**
没有让你改动的地方不要改
