package com.csdemo.tools

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * ADB shell 模式（基于 Shizuku）。
 *
 * 思路（与 rish 一致，但走进程内 API）：
 *   · Shizuku 由用户通过 ADB / 无线调试启动；
 *   · 本应用向 Shizuku 申请权限（弹窗）；
 *   · 拿到权限后用 Shizuku.newProcess(...) 以 shell 身份执行命令。
 *
 * 三种身份：
 *   user     —— 普通应用身份（既无 root 也无 adb）
 *   adbshell —— 通过 Shizuku 以 shell 身份运行
 *   root     —— 真实 su 提权
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

    /** 当前是否需要向用户申请权限（可用但未授权） */
    fun needsRequest(): Boolean = try {
        available() && !granted()
    } catch (e: Throwable) {
        false
    }

    /** 申请权限（需在主线程调用，会弹 Shizuku 授权框） */
    fun requestPermission(requestCode: Int = 4210) {
        try {
            if (available() && !granted()) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Throwable) {
            // 忽略：授权由用户在 Shizuku 弹窗里决定
        }
    }

    /**
     * 以 shell 身份执行命令。
     * 未授权 / 未启动时返回 null。
     */
    fun exec(cmd: Array<String>): Shell.Result? {
        if (!granted()) return null
        return try {
            val p = Shizuku.newProcess(cmd, null, null)
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            Shell.Result(p.exitValue(), out, err)
        } catch (e: Throwable) {
            null
        }
    }

    /** 便捷：执行单条 shell 命令字符串（走 sh -c） */
    fun execShell(command: String): Shell.Result? =
        exec(arrayOf("sh", "-c", command))

    /**
     * 检测当前身份，优先级：root > adb shell > user。
     * root 由外部传入（RootCheck 已测过，不重复提权）。
     */
    fun detectMode(rootGranted: Boolean): Mode = when {
        rootGranted -> Mode.ROOT
        granted() -> Mode.ADB_SHELL
        else -> Mode.USER
    }
}
