package com.csdemo.tools

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ADB shell 模式（基于 Shizuku UserService）。
 *
 * 原理：
 *   · Shizuku 由用户通过 ADB / 无线调试启动；
 *   · 本应用申请 Shizuku 权限（弹窗）；
 *   · 授权后通过 bindUserService 把一个实现了 IUserService 的实例
 *     交给 Shizuku，它会将该实例运行在 shell（或 root）身份的进程里；
 *   · 之后调用该实例的 exec() 就等于以 adb shell 身份执行命令。
 *
 * 三种身份：
 *   user     —— 普通应用（既无 root 也无 adb）
 *   adbshell —— 通过 Shizuku 以 shell 身份运行
 *   root     —— 真实 su 提权（由 RootCheck 判定）
 */
object AdbShell {

    enum class Mode { USER, ADB_SHELL, ROOT }

    /** Shizuku 是否可用（已安装且服务在跑） */
    fun available(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    /** 是否已获得 Shizuku 授权 */
    fun granted(): Boolean = try {
        available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /** 可用但未授权 */
    fun needsRequest(): Boolean = try {
        available() && !granted()
    } catch (e: Throwable) {
        false
    }

    /** 申请权限（主线程调用，会弹 Shizuku 授权框） */
    fun requestPermission(requestCode: Int = 4210) {
        try {
            if (available() && !granted()) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Throwable) {
            // 忽略：由用户在 Shizuku 弹窗决定
        }
    }

    // ----------------------------------------------------------------
    // UserService 绑定
    // ----------------------------------------------------------------

    @Volatile
    private var service: com.csdemo.IUserService? = null

    @Volatile
    private var binding = false

    private val lock = Any()

    /**
     * 绑定 UserService（同步阻塞，等待连接完成）。
     * 返回 true 表示已可用。
     */
    fun ensureService(context: Context): Boolean {
        if (service != null) return true
        if (!granted()) return false

        synchronized(lock) {
            if (service != null) return true
            if (binding) return false
            binding = true
        }

        val latch = CountDownLatch(1)
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, UserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("adb_shell")
            .debuggable(false)
            .version(1)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) {
                service = if (binder != null) com.csdemo.IUserService.Stub.asInterface(binder) else null
                binding = false
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                binding = false
            }
        }

        return try {
            Shizuku.bindUserService(args, conn)
            // 连接需要在主线程完成，这里等服务回调
            latch.await(8, TimeUnit.SECONDS) && service != null
        } catch (e: Throwable) {
            binding = false
            false
        }
    }

    /** 已绑定的服务是否可用 */
    fun serviceReady(): Boolean = service != null

    /**
     * 以 shell 身份执行命令。
     * 未绑定 / 未授权时返回 null。
     */
    fun exec(context: Context, command: String): Shell.Result? {
        if (!granted()) return null
        if (service == null && !ensureService(context)) return null
        return try {
            val out = service?.exec(command) ?: return null
            Shell.Result(0, out, "")
        } catch (e: Throwable) {
            null
        }
    }

    /** 服务进程自身的身份（便于确认是否真的是 shell） */
    fun whoAmI(context: Context): String? {
        if (!granted()) return null
        if (service == null && !ensureService(context)) return null
        return try {
            service?.whoAmI()
        } catch (e: Throwable) {
            null
        }
    }

    /** 解绑 */
    fun unbind() {
        try {
            service?.destroy()
        } catch (_: Throwable) {
        }
        service = null
    }

    /** 检测身份，优先级 root > adb shell > user */
    fun detectMode(rootGranted: Boolean): Mode = when {
        rootGranted -> Mode.ROOT
        granted() -> Mode.ADB_SHELL
        else -> Mode.USER
    }
}
