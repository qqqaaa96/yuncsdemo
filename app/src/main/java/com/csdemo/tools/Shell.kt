package com.csdemo.tools

import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

/**
 * 命令执行与 Root 检测。全部基于真实的 Process 调用，没有模拟数据。
 */
object Shell {

    data class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
        fun text(): String = (out + if (err.isBlank()) "" else "\n" + err).trim()
    }

    fun run(vararg cmd: String): Result {
        return try {
            val p = ProcessBuilder(*cmd).start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val done = p.waitFor(20, TimeUnit.SECONDS)
            if (!done) p.destroy()
            Result(if (done) p.exitValue() else -1, out, err)
        } catch (e: Exception) {
            Result(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * root 执行结果。
     * invoked=true 表示 su 进程确实起来并完成了（不管命令成败）。
     * granted 需要调用方根据命令输出自行判定。
     */
    class RootResult(val invoked: Boolean, val result: Shell.Result?, val timedOut: Boolean) {
        /** 命令是否成功执行完成且退出码为 0，可作为“写入成功”的判据 */
        fun succeeded(): Boolean {
            val r = result
            return invoked && r != null && r.code == 0
        }
    }

    /**
     * 用 su -c 'cmd' 跑一条命令。
     * 这是最兼容的调用方式：Magisk / SuperSU / KernelSU 都支持 su -c。
     *
     * 超时策略：授权弹窗需要用户手动点确认，所以默认给 20 秒；
     * 超时视为“未完成”，由调用方按未授权处理。
     */
    fun runRoot(vararg cmd: String): RootResult {
        val shellCmd = cmd.joinToString(" ")
        return runSu("-c", shellCmd, timeoutMs = 20000)
    }

    /**
     * 低层 su 调用。参数直接拼在 su 后面。
     */
    fun runSu(vararg args: String, timeoutMs: Long = 20000): RootResult {
        var p: Process? = null
        return try {
            val pb = ProcessBuilder(listOf("su") + args)
            pb.redirectErrorStream(true)
            p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroy()
                RootResult(false, null, true)
            } else {
                val code = try { p.exitValue() } catch (e: Exception) { -1 }
                RootResult(true, Result(code, out, ""), false)
            }
        } catch (e: Exception) {
            try { p?.destroy() } catch (_: Exception) {}
            RootResult(false, null, false)
        }
    }

    /**
     * 检测 su 本身能不能被拉起（即系统里存不存在可执行的 su）。
     * 只看进程能不能起来，不看授权结果。
     */
    fun suExists(): Boolean {
        if (which("su") != null) return true
        val r = runSu("-v", timeoutMs = 3000)
        return r.invoked || r.timedOut
    }

    fun readFile(path: String): String? = try {
        val f = java.io.File(path)
        if (!f.exists() || !f.canRead()) null else f.readText()
    } catch (e: Exception) {
        null
    }

    fun which(name: String): String? {
        val dirs = (System.getenv("PATH") ?: "").split(':') +
                listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/system/sbin")
        for (d in dirs) {
            if (d.isBlank()) continue
            val f = java.io.File(d, name)
            if (f.exists() && f.canExecute()) return f.absolutePath
        }
        return null
    }
}
