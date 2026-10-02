package com.csdemo.tools

import android.os.Process
import com.csdemo.IUserService

/**
 * Shizuku UserService 实现。
 *
 * 这个类的实例由 Shizuku 反序列化并运行在 **shell（或 root）身份** 的独立进程里，
 * 因此这里执行的命令天然拥有 adb shell / root 权限。
 *
 * 注意：
 *   · 不要在此类中引用应用进程的状态（如 Compose、Context），
 *     它跑在另一个进程、另一份 classloader 下。
 *   · 必须有一个公开的无参构造函数（Shizuku 靠它反射创建）。
 */
class UserService : IUserService.Stub() {

    /** 执行命令，返回合并后的输出（stdout + stderr） */
    override fun exec(command: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = StringBuilder()
            p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') }
            p.errorStream.bufferedReader().forEachLine { out.append(it).append('\n') }
            p.waitFor()
            out.toString()
        } catch (e: Throwable) {
            "exec failed: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** 返回当前服务进程身份，用于确认是否真的拿到了 shell 权限 */
    override fun whoAmI(): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("id"))
            val line = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            val uid = Process.myUid()
            if (line.isBlank()) "uid=$uid" else line
        } catch (e: Throwable) {
            "uid=" + Process.myUid()
        }
    }

    /** 销毁：Shizuku 会结束本进程 */
    override fun destroy() {
        System.exit(0)
    }
}
