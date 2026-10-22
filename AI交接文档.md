# 项目交接文档 — 交给下一个 AI

> 生成时间：2026-10-21（第二轮大更新，已融合 PHP 服务器）
> 原因：对话长度即将上限
> **接手前请读完「二」「三」「四」「五」四节**

---

## 零、30 秒速览

这是一个 **Android 工具箱 App + PHP 账号服务器** 的双端项目。

| 端 | 位置 | 技术 |
|---|---|---|
| App | `csdemo/` | Kotlin + Compose + miuix |
| 服务器 | `登录系统服务器/` | PHP 8.5 + SQLite + frp 内网穿透 |

**没有编译能力**，改完必须让用户推 GitHub Actions 验。
**PHP 可以本地跑**，但用户在手机上用 Termux 跑 `php -S`。

**用户特点**：中文、非专业开发者、脾气急、对「浪费 token」敏感。
**回复要求：短、直接、不说教。**

---

## 一、必须遵守的铁律

### 1.1 不要浪费用户的排查轮次

**这是本次会话最大的教训。**
用户每问一次都花钱。所以：
- 页面报错 → **先问「页面上有没有报错文字」**，不要一上来就让用户跑 `php -i`、`curl`、`cat`
- 编译报错 → 用户会贴 `e: file:///...:行:列`，**只看那几行**，不用看 Gradle 栈
- 不要连续让用户跑好几条命令去「排查」

### 1.2 一次 1~3 处

用户经历过多次「一次改太多 → 全部编译失败」。**改完立刻 read_text 复核。**

### 1.3 不要造假
- 读不到就说读不到
- **永远不要声称「已编译通过」**（没有编译器）
- PHP 的 `Deprecated`/`Warning` 会破坏 `header()` 跳转，这类问题要主动查

### 1.4 不要擅自动 UI
改 UI 前必须先问。用户原话：「改动我的 UI 是高危操作」。

### 1.5 不要手写依赖库头文件
sdb 头文件含大量宏与内联，手写版**必定不准**。必须用真实源码包。

---

## 二、工具环境限制

### 2.1 Home 目录
```
Home = /storage/emulated/0/MT2/mcp/
```
不在 Home 下的文件，让用户自己移动。

### 2.2 目录创建返回值不可信
`mt_file_create_directory` 报 `changed: true` 但父目录不存在时**实际没建**。
**正确做法**：建目录 → `mt_file_stat` 验证 → 写文件 → 再 stat 验证。

### 2.3 MCP 会间歇断开
报 `MCP client ... is not connected`，**直接重试同一个调用**即可。

### 2.4 没有 shell
git 命令必须让用户自己跑。

### 2.5 文件搜索的两个坑
- `mt_file_search` **只能搜目录**，不能搜单文件（单文件用 `mt_file_search_text`）
- 参数是 `nameCaseSensitive`，**没有** `caseSensitive` 这个参数

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
| **material-icons-extended** | **1.7.8 固定**（BOM 2026 已移除） |
| compileSdk / targetSdk | 37 |
| minSdk | **33**（miuix-blur 要求） |
| JDK | 21 |
| NDK | 27.0.12077973 |
| CMake | 3.22.1 |
| miuix | 0.9.4（ui / blur / icons / preference / nav） |

`buildFeatures { aidl = true }` 不能关（Shizuku UserService 用）。

### 3.2 底栏 5 个 tab（顺序不能乱）

```
0 主页 / 1 反作弊 / 2 常用功能 / 3 工具 / 4 我的
```

`MainTab` 枚举顺序 = `HorizontalPager` 的 `when(page)` 索引，**改一处必须改另一处**。

⚠️ **关闭悬浮底栏时**，`AppShell.kt` 里 `if (!floating)` 分支逐个显式调用 `TabItem`，
**加 tab 时必须同步补一个，否则关闭悬浮后会少一项。**

底栏图标用 `ui/TabGlyph.kt`（Canvas 矢量绘制，**不用 Emoji**）。

### 3.3 关键文件结构

```
tools/
  Arm64Disasm.kt       反汇编器（已补全 ADD/SUB/MOVZ/UBFM/CSEL 等）
  Arm64Encoder.kt      指令编码（写回 ELF）
  CfgBuilder.kt        控制流构建 + 函数发现（52KB）
  JumpTableResolver.kt switch 跳转表
  ElfParser.kt / ElfLoader.kt / ElfPatcher.kt
  ElfConverter / ImageConverter / AudioConverter / VideoConverter
  MetalDetector.kt     磁力计探金属
  ShellObfuscator.kt   ShellVMP V6 混淆（内置 bash）
  VpnDetector.kt       VPN 检测（5 层）
  VpnGuard.kt          检测到 VPN → 上报 → 杀进程
  AuthApi.kt           账号网络层
  AvatarStore.kt       头像裁剪/缓存
  DeviceId.kt          设备指纹
  AppVersion.kt        读安装包版本（**禁止硬编码版本号**）
  VersionInfo.kt       读 version.json

tools/decompiler/      伪 C 反编译
  CCodeGen.kt          代码生成（SSA + 表达式 + 结构还原）
  SsaBuilder.kt        支配树 + Phi 插入
  SsaRename.kt         SSA 重命名
  ExprBuilder.kt       表达式内联
  Structurer.kt        自然循环识别
  TypeInfer.kt         类型推断
  IrTranslator.kt      AArch64 → IR
  DecompilerEngine.kt  自研引擎（已降级为 r2 失败时回退）
  R2Decompiler.kt      **Radare2 引擎（优先）**
  R2Json.kt            r2 pdfj JSON 解析

ui/
  component/            ExpressiveDialog.kt（KernelSU 同款弹窗）
                        MarkdownText.kt（自写 Markdown 渲染）
                        AnnouncementDialog.kt
  pages/AppPages.kt     SettingsPage / CheckUpdatePage / AboutPage / ThemeSettingsPage
  pages/ProfilePage.kt  我的页（头像/标签/入口）
  pages/AccountPages.kt 改昵称 / 换邮箱 / 注销
  pages/FeedbackPage.kt 意见反馈
  LoginScreen.kt / RegisterScreen.kt / CaptchaRow.kt
  TabGlyph.kt           底栏矢量图标
  ElfScreen.kt          ELF 逆向（67KB）
  CfgGraphScreen.kt     控制流图（48KB）
```

### 3.4 反编译链路（重要）

```
ELF → Radare2.pdfj（拿 JSON）→ R2Json 解析
    → 构造 FunctionIR → CCodeGen（SSA/表达式/结构）→ 伪 C

r2 失败时回退 DecompilerEngine（自研 Arm64Disasm 链路）
```

`AuthApi.BASE = "https://denglu.fucku.top"`（**目前是 http，见 4.2**）。

### 3.5 踩过的 Kotlin 坑（本次反复踩）

| 坑 | 说明 |
|---|---|
| `Modifier.androidx.compose.foundation.clickable{}` | **非法**。扩展函数必须 import 后写 `.clickable{}` |
| `item{}` 嵌 `item{}` | **非法**。LazyItemScope 里不能再调 `item` |
| `kotlinx.coroutines.MainScope().launch{}` | `launch` 是扩展函数，全限定名调不到。用 `Thread{}` 或先 import |
| `imagedestroy()` | PHP 8.5 已废弃，会输出 Warning 破坏 `header()` |
| `if(x){} else:` | PHP 替代语法不能混用花括号和冒号 |
| `intent.flags and X != 0` | 优先级错，需加括号 `(a and b) != 0` |
| `0x94000000` | 超 Int，被推断为 Long，加 `.toInt()` |
| `'\n\n'` | 是两字符不是 Char，写 `"\n\n"` |
| 注释里写 `/sys/*` | `/*` 会被当块注释开头 → 注释里避开 |

---

## 四、PHP 服务器（登录系统服务器/）

### 4.1 文件清单

| 文件 | 作用 |
|---|---|
| `api.php` | 所有客户端接口入口（`?action=xxx`）|
| `admin.php` | 管理后台（单一入口，`?p=xxx`）|
| `lib.php` | 数据库 / 校验 / token / 封禁 / IP 黑名单 / 标签 |
| `mailer.php` | 无依赖 SMTP（QQ 邮箱） |
| `captcha.php` | 服务端图形验证码（GD） |
| `qqwry.php` | 纯真 IP 库解析（纯 PHP） |
| `city_coords.php` | 城市 → 经纬度表 |
| `ipmap.php` | IP 地图可视化（独立页） |
| `config.php` | **需改 APP_PEPPER / SMTP** |
| `version.json` | 版本 + 更新日志 + 公告 |
| `data/app.db` | SQLite 数据库 |
| `data/avatars/` | 头像 `<uid>.jpg` |
| `data/qqwry.dat` | **用户需自己下载** |

### 4.2 部署现状

```
chmlfrp 隧道：HTTP 类型 → https 不可用
访问：http://denglu.fucku.top
```

⚠️ **chmlfrp 的 HTTP 隧道不在边缘解密 TLS**，而本地是 `php -S`（不支持 TLS），
所以**必须走 http**。`AuthApi.BASE` 是 `http://`，Cookie `secure=false`。

**以后若换到能在边缘解密的服务，改回 https 时需同步：**
1. `AuthApi.BASE` → `https://`
2. `api.php` Cookie `secure` → `true`（下发 + 清除两处）
3. 加回 HSTS 头

### 4.3 数据库表

| 表 | 作用 |
|---|---|
| `users` | 账号（bcrypt 密码、avatar_at、banned_until、last_ip、reg_ip、tag_text、tag_color）|
| `admins` | 管理员（独立于 users，防止提权）|
| `tokens` | 登录令牌（SHA-256 hash，30 天）|
| `email_codes` | 邮箱验证码（只存 hash，带过期）|
| `captcha` | 图形验证码（只存 hash，5 分钟）|
| `rate_limit` | 短时限流 |
| `reg_audit` | 注册审计（IP / 设备 / 域名配额）|
| `login_fail` | 登录失败锁定 |
| `admin_logs` | 管理员操作日志 |
| `user_actions` | 对用户的操作记录 |
| `vpn_violations` | VPN 违规记录（status: 0待/1忽略/2警告）|
| `feedback` | 用户反馈（status: 0待/1已回复）|
| `tag_presets` | 标签预设 |
| `ip_blacklist` | IP 黑名单 |

**首次访问 `admin.php` 会自动建表 / 建默认管理员（admin / admin123）。**

### 4.4 安全措施（已实现）

- 密码 `password_hash()`（bcrypt）
- 验证码 / token / 图形码只存 SHA-256 hash
- 登录错误统一提示，不泄露账号是否存在
- 登录失败锁定：5次/15分 → 10次/1小时 → 20次/24小时
- 注册配额：IP 7天 3个 / 设备 7天 1个 / 域名 7天 5个
- 服务端图形验证码（本地无答案，脚本绕不过）
- IP 黑名单（除 ping 外全拦）
- `data/` 有 `.htaccess` 禁止直接访问

### 4.5 管理后台页面

```
仪表盘 / 用户管理 / 用户详情 / 头像管理 / 公告发布
用户反馈 / IP 黑名单 / 违规记录 / IP 分布 / 标签管理
版本更新 / 注册审计 / 登录审计 / 操作日志 / 系统设置 / 关于
```

**IP 分布是独立页 `ipmap.php`**，菜单里特殊处理跳转。

### 4.6 启动命令

```bash
pkill -f "php -S"
cd /storage/emulated/0/MT2/mcp/登录系统服务器
php -S 127.0.0.1:8080
```

然后跑 chmlfrp 客户端。

### 4.7 PHP 踩过的坑

| 坑 | 后果 | 解法 |
|---|---|---|
| `imagedestroy()` 在 PHP 8.5 | 输出 Deprecated → **header() 全部失效 → 上传后不跳转** | 删掉 |
| `display_errors` 开着 | 任何 Warning 都会破坏跳转 | `ini_set('display_errors','0')` |
| `if(){} else:` 混用 | Parse error | 统一用 `:` 替代语法 |
| 改了 PHP 不重启 | opcache 缓存旧代码 | `pkill -f "php -S"` 重启 |
| `$_POST` 超 `post_max_size` | PHP 丢弃整个请求体，`$_FILES` 空 | 加诊断页面提示 |

---

## 五、待办（按优先级）

### 🔴 P0-1：IP 地图页没有红点

**现象**：`ipmap.php` 打开后地图上没有红点，或全部归到「无法定位」。

**排查方向**：
1. **`data/qqwry.dat` 是否存在** —— 这是最可能的原因，用户可能还没下载
2. 若存在，检查 `Qqwry::query()` 是否返回 null（GBK 转换 / 二分查找边界）
3. **用户 IP 可能都是内网地址**（`192.168.x.x`、`10.x.x.x`）—— 纯真库查不到，`ip_region()` 也返回空
   - 若是这样，需要在 `users` 表加真实公网 IP 记录（登录时服务端记 `client_ip()`，已经是了）
   - 但用户手机和服务器在同一内网时，`REMOTE_ADDR` 拿到的是内网 IP
4. 验证方法：在 `ipmap.php` 临时 `var_dump($ipMap)` 看原始 IP 是什么

**注意**：`city_coords.php` 只有 140+ 城市，如果纯真库返回的城市名不在表里，会走省中心点兜底；省名也不匹配就丢进 `unknown`。

### 🔴 P0-2：VPN 检测未实机验证

`VpnDetector` / `VpnGuard` 写好了但**从没在真机上测过**。
需要验证：
- 开 VPN 后 App 是否真的闪退
- `vpn_report` 是否成功写库
- 境外判定（`ip_region`）是否准确

### 🔴 P0-3：App 换头像（重新编译后待验证）

已改：`AvatarStore.pickAndCompress` 改成单次读流（原来两次 `openInputStream` 可能因 Uri 授权失效返回 null），异常不再吞掉，界面会显示真实原因。

若仍失败，看界面上的「图片读取失败：xxx」具体原因。

### 🟠 P1-1：控制流解析率

用户抱怨「有的函数解析不出控制流，99.9% 都应该能」。
已修多个 bug（段上界用错、末函数边界、吞函数、尾调用），但仍未验证。

### 🟠 P1-2：深色模式未适配

`ElfScreen` / `CfgGraphScreen` / `OnboardScreens` / `CardKeyScreen` / `MailVerifyScreen` / `CfgView` / `Home`
把 `Ink/Paper/Line` 常量 → `LocalPalette.current.xxx`

### 🟠 P1-3：伪 C 剩余差距

已做：SSA、表达式内联、自然循环、类型推断、Markdown 渲染
仍缺：
- 跨块复制传播 / 常量折叠
- 结构体 / 数组成员识别
- 调用签名推导
- `br xN` 跳转表（`JumpTableResolver` 有了但没接进 r2 链路）

### 🟡 P2：其他

- `metadata` 备注管理（纯真库查不到时的手动补录）
- `ShellObfuscator` 产物是 `.sh`，不是 ELF（V7 需要 clang，580MB，用户已放弃）

---

## 六、外部资源位置

| 资源 | 路径 | 备注 |
|---|---|---|
| Radare2 头文件 | `include/` → 已拷入 `cpp/include/` | |
| Radare2 源码 | `radare2-master/` | **不含 sdb（git submodule）** |
| sdb 源码 | `sdb-master/` | **sdb 头文件只能从这里拿** |
| KernelSU 源码 | `KernelSU-main/` | 弹窗 / 主页 UI 参考 |
| yourvpndead 源码 | `yourvpndead-reviewed-main/` | VPN 检测参考 |
| shell 混淆参考 | `shell混淆参考/ShellVMP-main/` | V6 混淆器 |
| Android Reverse APK | `Android Reverse Ide_20.apk` | 27 个 `.so` 来源 |

**Radare2 的 `.so` 已全部拷入 `app/src/main/jniLibs/arm64-v8a/`。**

---

## 七、关键文件不要碰

- `gradle/wrapper/gradle-wrapper.jar`（二进制）
- `gradle.properties`（`android.aapt2FromMavenOverride` 已删，云端不能有）
- `local.properties`
- `.github/workflows/build.yml`
- `app/src/main/assets/juanzeng.png`（捐赠码）

---

## 八、工作流程

```
1. 先 read_text 读实际代码（不要凭记忆）
2. 小步改（一次 1~3 处）
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

**用户会往 `报错.txt` 里贴报错（从 2026-10-21 开始）。**

---

## 九、一句话总结

**当前版本 = 「KernelSU 风格 UI + 账号系统 + Radare2 反编译 + 批量工具」的双端项目。**

**已完成**：主页/底栏/我的/登录注册/头像/标签/反馈/公告/更新/违规记录/IP黑名单/
帧率悬浮窗/Shell混淆/格式转换/金属探测器/VPN检测/伪C反编译

**待办**：IP 地图无红点（P0-1）、VPN 未实机验证（P0-2）、App 换头像待验证（P0-3）

**下一步建议**：先确认 `data/qqwry.dat` 是否存在，再查 IP 地图。
