# 项目交接文档

> 生成：2026-10-24
> 接手前必读「一」「二」「三」「四」「五」

---

## 零、30 秒速览

**Android 工具箱 App + PHP 账号服务器** 双端项目。

| 端 | 位置 | 技术 |
|---|---|---|
| App | `csdemo/` | Kotlin + Compose + miuix |
| 服务器 | `登录系统服务器/` | PHP 8.5 + SQLite + frp |

**无编译能力**，改完必须用户推 GitHub Actions 验。
**PHP 可本地跑**，用户在 Termux 用 `php -S`。

**用户特点**：中文、非专业开发者、脾气急、敏感 token 浪费。
**回复：短、直接、不说教。**

### 当前构建状态（最重要）

**最后一次构建 #170 失败**，错误：

```
Syntax error: Unclosed comment.
```

某个 `.kt` 文件有未闭合的块注释 `/*`。

历史同类坑：**注释里写 `/sys/*` 会被当块注释开头**。

**接手第一件事**：对本次新增文件核对 `/*` 与 `*/` 数量是否相等：
- `NfcHelper.kt`
- `NfcHomeScreen.kt` / `NfcReadScreen.kt` / `NfcWriteScreen.kt`
- `CodeScreen.kt` / `CodeMaker.kt`
- `PartitionBackup.kt`

---

## 一、铁律（不能违反）

### 1.1 不浪费排查轮次
- 页面报错 → **先问「页面上有没有报错文字」**，不要一上来就让跑 `php -i` / `curl` / `cat`
- 编译报错 → 用户会贴 `e: file:///...:行:列`，**只看那几行**
- 不要连续让用户跑好几条命令「排查」

### 1.2 一次 1~3 处
用户经历过多次「一次改太多 → 全部编译失败」。**改完立刻 read_text 复核。**

### 1.3 不造假
- 读不到就说读不到
- **永远不要声称「已编译通过」**
- PHP 的 `Deprecated`/`Warning` 会破坏 `header()` 跳转，要主动查

### 1.4 不擅自动 UI
改 UI 前必须先问。原话：「改动我的 UI 是高危操作」。

### 1.5 不手写依赖库头文件
sdb 头文件含大量宏与内联，手写版**必定不准**。必须用真实源码包。

### 1.6 改「同一变量多处引用」必须先搜次数
反复踩坑：`badgeWidth` 出现 2 次，只改 1 处 → 编译报错。
**先 search 查出现次数，全改完再搜一次验空。**

### 1.7 局部变量不能向前引用
Kotlin 局部变量/函数按声明顺序解析。
踩过 3 次：`showSwitch`、`msg`、`avatarVer`。
**在 Composable 里插逻辑前，先扫一遍所有 val/var 位置。**

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
报 `MCP client ... is not connected`，**直接重试同一个调用**。

### 2.4 没有 shell
git 命令必须让用户自己跑。

### 2.5 文件搜索的坑
- `mt_file_search` **只能搜目录**，不能搜单文件（单文件用 `mt_file_search_text`）
- 参数是 `nameCaseSensitive`，**没有** `caseSensitive`
- 搜 `.git` 会有大量 `invalid_encoding`，要限定 `path`

### 2.6 编辑后 targetVersion 立刻变
每次 `mt_file_edit_text` 成功后版本号都变。
**连续改同文件必须重新 read_text 拿新版本号。**

### 2.7 新建文件必须用 write_target
`targetVersion="missing"` 时必须 `mode="write_target"`。

### 2.8 写 MD 用 write_target，不要清空重写
大文档一次写不完会被截断，分多次 `insert_after_match` 追加。

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
| **material-icons-extended** | **1.7.8 固定** |
| compileSdk / targetSdk | 37 |
| minSdk | **33** |
| JDK | 21 |
| NDK | 27.0.12077973 |
| CMake | 3.22.1 |
| miuix | 0.9.4（ui / blur / icons / preference / nav） |
| activity-compose | **1.13.0**（必须） |
| lifecycle | **2.11.0** |
| navigationevent-compose | **1.1.2**（miuix WindowDialog 必需） |
| zxing core | 3.5.3（二维码/条码） |

`buildFeatures { aidl = true }` 不能关（Shizuku UserService 用）。

### 3.2 底栏 5 个 tab（顺序不能乱）

```
0 主页 / 1 反作弊 / 2 常用功能 / 3 工具 / 4 我的
```

`MainTab` 枚举顺序 = `HorizontalPager` 的 `when(page)` 索引，**改一处必须改另一处**。

⚠️ **关闭悬浮底栏时**，`AppShell.kt` 里 `if (!floating)` 分支逐个显式调用 `TabItem`，**加 tab 必须同步补一个**。

底栏图标用 `ui/TabGlyph.kt`（Canvas 矢量绘制，**不用 Emoji**）。

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
```

### 3.5 关键文件结构

```
tools/
  AuthApi.kt          账号网络层，BASE = https://denglu.fucku.top
  AccountStore.kt     已登录账号历史（免密切换用，最多 5 个）
  MailApi.kt          站内信（含奖励领取）
  RankApi.kt          排行榜 / 签到 / 隐私
  SyncCenter.kt       实时同步中心
  PageCache.kt        页面缓存
  VisitTracker.kt     App 启动上报访问
  Updater.kt          App 内下载 APK + 安装
  CodeMaker.kt        二维码/条码生成（ZXing）
  NfcHelper.kt        NFC 读写核心
  PartitionBackup.kt  分区备份（⚠ 待接入 UI）
  Shell.kt            命令执行 / runRoot
  AdbShell.kt         Shizuku
  RootState.kt        root/adb/user 三态
  VpnDetector.kt      VPN 检测（5 层）
  VpnGuard.kt         检测到 VPN → 上报 → 杀进程（每 2 秒轮询 + 网络回调）
  DeviceSpoof.kt / SpoofData.kt   设备伪装
  ElfParser.kt / ElfLoader.kt / ElfPatcher.kt
  CfgBuilder.kt       控制流构建（56KB）
  Arm64Disasm.kt      反汇编
  Arm64Encoder.kt     指令编码
  JumpTableResolver.kt
  ShellObfuscator.kt  ShellVMP V6

tools/decompiler/     伪 C 反编译
  R2Decompiler.kt     Radare2 引擎（优先）
  R2Json.kt           r2 pdfj JSON 解析
  DecompilerEngine.kt 自研引擎（备用）
  CCodeGen.kt         SSA + 表达式 + 结构还原
  SsaBuilder.kt / SsaRename.kt / ExprBuilder.kt
  Structurer.kt / TypeInfer.kt / IrTranslator.kt

ui/
  AppShell.kt            主壳，5 个 tab
  MainTab 枚举在 AppShell.kt
  CodeScreen.kt          码生成页
  NfcHomeScreen.kt       NFC 主页  ⚠ 本次新增
  NfcReadScreen.kt       NFC 读卡  ⚠ 本次新增
  NfcWriteScreen.kt      NFC 写卡  ⚠ 本次新增
  MailScreen.kt          邮箱
  RankingScreen.kt       排行榜
  TabGlyph.kt            底栏矢量图标
  CardGlyph.kt           卡片矢量图标（battery/settings/chip/phone/edit/info/list）
  ElfScreen.kt           ELF 逆向（69KB）
  CfgGraphScreen.kt      控制流图（48KB）
  component/
    TagBadge.kt          称号胶囊 + FxText（普通文字流光）
    NoticeCard.kt        ColorOS 通知栏卡片（左滑操作）
    CheckinCard.kt       签到卡（已移到「我的」页）
    UpdateDialog.kt      更新弹窗（简单包装）
    ConfirmDialogMiuix.kt 弹窗外壳（WindowDialog + Layout 双子节）
    MarkdownWebView.kt   CommonMark + WebView
    AnnouncementDialog.kt / UpdatesDialog 等
  pages/
    AppPages.kt          HomePage / SettingsPage / CheckUpdatePage / AboutPage
    ProfilePage.kt       我的页（签到卡 + 账号 + 隐私 + 切换账号）
    PrivacySettingsPage.kt
    AccountPages.kt      改昵称 / 换邮箱 / 注销
    FeedbackPage.kt
```

### 3.6 ⚠️ miuix 弹窗的正确写法

**`WindowDialog` 依赖 `LocalNavigationEventDispatcherOwner`。**

如果缺依赖，会报：
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

### 3.7 Canvas / DrawScope 里不能读 @Composable

```kotlin
// 错误：Canvas 的 lambda 是 DrawScope.() -> Unit，不是 @Composable
Canvas(...) { drawRect(color = colorScheme.onBackground) }

// 正确：先读出来
val c = colorScheme.onBackground
Canvas(...) { drawRect(color = c) }
```

同类错误已踩 2 次。

### 3.8 踩过的 Kotlin 坑

| 坑 | 说明 |
|---|---|
| `Modifier.androidx.compose.foundation.clickable{}` | **非法**。扩展函数必须 import 后写 `.clickable{}` |
| `item{}` 嵌 `item{}` | **非法**。LazyItemScope 里不能再调 `item` |
| `kotlinx.coroutines.MainScope().launch{}` | `launch` 是扩展函数，全限定名调不到 |
| `intent.flags and X != 0` | 优先级错，需 `(a and b) != 0` |
| `0x94000000` | 超 Int，被推断为 Long，加 `.toInt()` |
| `'\n\n'` | 是两字符不是 Char，写 `"\n\n"` |
| 注释里写 `/sys/*` | `/*` 会被当块注释开头 → **Unclosed comment** |
| `.background(if(a) X else when{...})` | 嵌套后推断成 `Any`。**必须写 `val c: Color = ...`** |
| `onHorizontalDrag` 里 `scope.launch { snapTo }` | 每个拖拽事件一个新协程 → 卡顿。改成普通 state 同步更新 |
| `onSizeChanged { w = it.width }` + 读 `w` 拼 Brush | 重组循环。改用固定参考宽度 |
| `printStackTrace` 到 `/sdcard` | 无存储权限，要写 `getExternalFilesDir(null)` |

### 3.9 死亡回退：加 import 清单

新建 Compose 文件时先列一遍用到的扩展函数，每个都要有对应 import。
已踩：`clickable`、`launch`、`asImageBitmap`、`pressable`。

---

## 四、PHP 服务器（登录系统服务器/）

### 4.1 文件清单

| 文件 | 作用 |
|---|---|
| `api.php` | 所有客户端接口（`?action=xxx`）|
| `admin.php` | 管理后台（单一入口，`?p=xxx`）|
| `admin_mail.php` | 邮件下发（独立页）|
| `admin_ranking.php` | 排行榜（**已废弃**，改自动计算）|
| `admin_ipmap.php` | 访问分布（独立页）|
| `lib.php` | 数据库 / 校验 / token / 封禁 / 积分经验 / 隐私 |
| `mailbox.php` | 站内信（含奖励领取）|
| `mailer.php` | 无依赖 SMTP（QQ 邮箱）|
| `captcha.php` | 服务端图形验证码|
| `config.php` | **需改 APP_PEPPER / SMTP** |
| `version.json` | 版本 + 更新日志 + 公告 |
| `data/app.db` | SQLite 数据库|
| `data/avatars/` | 头像 `<uid>.jpg` |
| `data/apk/app.apk` | 上传的安装包|
| `data/qqwry.dat` | **已删**（IP 地图功能移除）|
| `ipstat.php` | 访问统计/IP 库（**IP 地图已删，文件保留但未用**）|
| `assign_uid.php` | UID 批量分配脚本|
| `mail_test.php` / `mail_diag.php` | 邮件自检 |

### 4.2 部署现状

```
chmlfrp 隧道，HTTPS 已开通（用户确认跑通）
访问：https://denglu.fucku.top
```

`AuthApi.BASE = "https://denglu.fucku.top"`，Cookie `secure` 动态判断。
IP 地图时曾改回 http，**现在是 https**。

### 4.3 数据库表

| 表 | 作用 |
|---|---|
| `users` | 账号。关键列：`uid`(5位对外编号) / `points` / `exp` / `checkin_streak` / `checkin_total` / `last_checkin_at` / `privacy` / `tag_text` / `tag_color` / `tag_fx` / `name_mode` / `name_color` / `name_fx` |
| `admins` | 管理员（独立于 users）|
| `tokens` | 登录令牌（SHA-256 hash，30 天）|
| `checkins` | 签到流水（`user_id+day` 唯一索引防重复）|
| `email_codes` | 邮箱验证码|
| `captcha` | 图形验证码|
| `rate_limit` | 短时限流|
| `reg_audit` | 注册审计|
| `login_fail` | 登录失败锁定|
| `admin_logs` | 管理员操作日志|
| `user_actions` | 对用户的操作记录|
| `vpn_violations` | VPN 违规|
| `feedback` | 用户反馈|
| `tag_presets` | 标签预设|
| `ip_blacklist` | IP 黑名单|
| `mail` / `mail_target` / `mail_outbox` | 站内信|
| `visits` / `ip_geo` | 访问记录（ip_geo 已废）|

**首次访问 `admin.php` 自动建表 + 默认管理员 `admin/admin123`。**

### 4.4 安全措施

- 密码 `password_hash()`（bcrypt）
- 验证码 / token / 图形码只存 SHA-256 hash
- 登录失败锁定：5次/15分 → 10次/1小时 → 20次/24小时
- 注册配额：IP 7天3个 / 设备 7天1个 / 域名 7天5个
- 服务端图形验证码（本地无答案）
- IP 黑名单（除 ping 外全拦）
- `data/` 有 `.htaccess` 禁止直接访问
- **用户名支持中文/符号**（2~20 字符，禁 `\ / : * ? " < > |` 和控制字符）

### 4.5 管理后台页面

```
仪表盘 / 用户管理 / 用户详情 / 头像管理 / 公告发布
用户反馈 / IP 黑名单 / 违规记录 / 邮件下发 / 访问分布
排行榜 / 标签管理 / 版本更新 / 注册审计 / 登录审计
操作日志 / 系统设置 / 关于
```

### 4.6 启动命令

```bash
pkill -f "php -S"
cd /storage/emulated/0/MT2/mcp/登录系统服务器
php -d upload_max_filesize=200M -d post_max_size=210M -S 127.0.0.1:8080
```

⚠️ **上传限制参数必须带**，否则 APK 传不上。

### 4.7 PHP 踩过的坑

| 坑 | 后果 | 解法 |
|---|---|---|
| `imagedestroy()` PHP 8.5 | Deprecated → **header() 全失效** → 上传后不跳转 | 删掉 |
| `curl_close()` PHP 8.5 | 同上 | 删掉（对象析构自动关）|
| `display_errors` 开着 | 任何 Warning 破坏跳转 | `ini_set('display_errors','0')` |
| `if(){} else:` 混用 | Parse error | 统一用 `:` |
| 改了 PHP 不重启 | opcache 缓存旧代码 | `pkill -f "php -S"` |
| `$_POST` 超 `post_max_size` | PHP 丢弃整个请求体 | 加诊断页面提示 |
| `declare(strict_types)` 重复 | Fatal error | 一个文件只能一次 |
| `ALTER TABLE` 用 try/catch 吞异常 | **列静默缺失，保存无效** | 先 `PRAGMA table_info` 查存在再 ALTER |

### 4.8 ⚠️ `ok()` 是平铺的，不包 data

```php
function ok(array $extra) { json_out(array_merge(['ok'=>true], $extra)); }
// 返回：{"ok":true, "items":[...], "unread":3}
// 不是：{"ok":true, "data":{...}}
```

客户端解析必须兼容两种：`(r.optJSONObject("data") ?: r)`。
**已踩坑**：`RankApi` 只认 `data.items` → 报「返回格式异常」。

### 4.9 API 接口清单

| action | 说明 |
|---|---|
| `ping` | 健康检查 |
| `visit` | 访问上报（免登录）|
| `captcha_json` | 图形验证码（JSON）|
| `send_code` / `register` / `login` / `logout` / `me` | 账号 |
| `change_password` / `reset_send` / `reset_password` | 密码 |
| `checkin` | 签到（1~300 经验完全随机）|
| `privacy_save` | 隐私设置 |
| `ranking` | 排行榜（全自动，支持 `?by=points\|exp\|streak`）|
| `sync` | 实时同步（公告/版本/称号/名字/积分）|
| `mail_list` / `mail_unread` / `mail_read` / `mail_read_all` / `mail_delete` / `mail_claim` | 站内信 |
| `upload_avatar` / `avatar` | 头像 |
| `download_apk` | APK 下载（支持 Range 续传）|
| `vpn_report` | VPN 上报 |

### 4.10 签到 / 经验规则

```
经验：random_int(1, 300)        完全均匀，无权重
积分：= 经验基础值
连续加成：只加经验，+2%/天，封顶 +50%
等级：Level n 需要 100*(n-1)^2 经验
```

---

## 五、待办

### 🔴 P0-1：**修「Unclosed comment」编译错误**

当前构建失败的唯一原因。搜新增 `.kt` 文件的 `/*` 与 `*/` 是否配对。

### 🔴 P0-2：NFC / 码生成页面接线验证

文件已建但**未确认路由接通**。需确认：
- `MainActivity` 的 `when(route)` 有 `"nfc_home"` / `"nfc_read"` / `"nfc_write"` / `"code"` 分支
- `ui/Home.kt` 的 `FEATURES` 列表已加入口
- `NfcWriteScreen` 的 `WritePayload` 传递方式

### 🟠 P1-1：分区备份（字库/基带）

`PartitionBackup.kt` 核心已完成，**缺 UI**。

已实现：
- 4 种 by-name 路径探测 + `/proc/partitions` 兑底
- `blockdev --getsize64` / `/sys/class/block/*/size` 补大小
- 按名分类：基带 / 字库 / 系统 / 其他
- dd 备份 + 大小校验 + 失败自动清文件
- manifest.txt 生成

输出：`/storage/emulated/0/字库基带备份/标签_分区名.img`

**用户要求「按多系统工具箱的方式」做 UI，但我没见过那个 App 的截图。**
需向用户要截图后再设计。

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
- `ShellObfuscator` 产物是 `.sh` 不是 ELF（V7 需 clang 580MB，已放弃）

---

## 六、外部资源位置

| 资源 | 路径 | 备注 |
|---|---|---|
| Radare2 头文件 | `include/` → 已拷入 `csdemo/app/src/main/cpp/include/` | |
| Radare2 源码 | `radare2-master/` | **不含 sdb（git submodule）** |
| sdb 源码 | `sdb-master/` | **sdb 头文件只能从这里拿** |
| KernelSU 源码 | `KernelSU-main/`（实现在 `manager/`）| 弹窗 / 主页 UI 参考 |
| yourvpndead 源码 | `yourvpndead-reviewed-main/` | VPN 检测参考 |
| shell 混淆参考 | `shell混淆参考/ShellVMP-main/` | V6 混淆器 |
| NFC Tools PRO | `NFC Tools PRO_9.4.apk` | NFC 参考（1987 类）|
| Kernel Flasher | `Kernel Flasher_1.6.0.apk` | 刷写参考（已放弃）|

Radare2 的 `.so` 已全部拷入 `app/src/main/jniLibs/arm64-v8a/`。

---

## 七、关键文件不要碰

- `gradle/wrapper/gradle-wrapper.jar`（二进制）
- `gradle.properties`（`android.aapt2FromMavenOverride` 已删，云端不能有）
- `local.properties`
- `.github/workflows/build.yml`（除 CI 环境问题）
- `app/src/main/assets/juanzeng.png`（捐赠码）

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

**用户会往 `报错.txt` 里贴报错。**

### CI 注意

`ubuntu-latest` 不预装 NDK 27.0.12077973，workflow 已加显式安装：
```yaml
- name: Install NDK 27.0.12077973
  run: |
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;27.0.12077973"
```

---

## 九、一句话总结

**当前版本 = 「KernelSU 风格 UI + 账号系统 + 站内信 + 排行榜/签到/经验 + 称号与名字流光 + Radare2 反编译 + 批量工具」的双端项目。**

**已完成**：主页/底栏/我的/登录注册/头像/标签/反馈/公告/更新(应用内下载)/违规记录/
IP黑名单/邮件下发/站内信+奖励/自动排行榜/签到经验/隐私设置/页面缓存/实时同步/
帧率悬浮窗/Shell混淆/格式转换/金属探测器/VPN实时检测/伪C反编译/
二维码条码生成/NFC读写（UI 已建待接线）

**待办**：修 Unclosed comment（P0）、NFC 接线验证（P0）、分区备份 UI（P1）
