package sy.great.toolbox.tools

/**
 * 账号系统总开关。
 *
 * 本项目已改为「纯净版工具盒」：不登录、不联网账号、不拉公告。
 * 但账号相关的全部代码（AuthApi / RankApi / MailApi / SyncCenter /
 * LoginScreen / RegisterScreen / ProfilePage 的账号区 ……）**一行都没删**。
 *
 * 想恢复全部账号功能：把 ENABLED 改成 true 重新编译即可。
 *
 * 关闭时会自动隐藏：
 *   · 首启登录页 / 注册页 / 邮箱验证
 *   · 主页右上角邮件、排行榜、与服务器建立连接
 *   · 启动时的访问上报 / 同步轮询 / 延迟探测
 */
object AccountFeature {

    /** 账号系统开关。false = 纯净模式（当前） */
    const val ENABLED = false
}
