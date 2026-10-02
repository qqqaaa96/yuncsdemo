package com.csdemo.tools

import java.io.File

/**
 * SELinux 状态读写。
 *
 * 真实实现：
 *   读：getenforce / /sys/fs/selinux/enforce
 *   写：setenforce 0|1（需 root，重启恢复）
 *
 * 内核编译时关闭 SELinux 的设备（Disabled）无法通过 setenforce 改变。
 */
object Selinux {

    enum class Mode { ENFORCING, PERMISSIVE, DISABLED, UNKNOWN }

    data class State(
        val mode: Mode,
        val raw: String,
        val mounted: Boolean,
        val canWrite: Boolean,
        val policyVersion: String,
        val currentContext: String
    ) {
        val switchable: Boolean get() = mode != Mode.DISABLED && mode != Mode.UNKNOWN && canWrite
    }

    private const val ENFORCE_PATH = "/sys/fs/selinux/enforce"

    fun read(): State {
        val mounted = File("/sys/fs/selinux").exists()

        // 优先 getenforce（最准）
        var raw = Shell.run("getenforce").out.trim()
        if (raw.isBlank()) {
            raw = Shell.runSu("-c", "getenforce", timeoutMs = 8000).result?.out?.trim().orEmpty()
        }

        val mode = when {
            raw.equals("Enforcing", true) -> Mode.ENFORCING
            raw.equals("Permissive", true) -> Mode.PERMISSIVE
            raw.equals("Disabled", true) -> Mode.DISABLED
            else -> {
                // 兵底：读 /sys/fs/selinux/enforce
                val v = readEnforceFile()
                when (v) {
                    "1" -> Mode.ENFORCING
                    "0" -> Mode.PERMISSIVE
                    else -> Mode.UNKNOWN
                }
            }
        }

        // 能否写入：先看文件权限，再看 /sys/fs/selinux 是否只读
        val canWrite = mounted && File(ENFORCE_PATH).exists() && run {
            // 用 root 试写当前值（幂等，不改语义）
            val cur = readEnforceFile()
            if (cur.isBlank()) return@run false
            val r = Shell.runSu("-c", "echo " + cur + " > " + ENFORCE_PATH, timeoutMs = 8000)
            val after = readEnforceFile()
            after == cur
        }

        val policyVer = readSys("/sys/fs/selinux/policyvers")
        val context = Shell.run("id", "-Z").out.trim().ifBlank {
            Shell.runSu("-c", "id -Z", timeoutMs = 6000).result?.out?.trim().orEmpty()
        }

        return State(
            mode = mode,
            raw = raw.ifBlank { "未知" },
            mounted = mounted,
            canWrite = canWrite,
            policyVersion = policyVer,
            currentContext = context
        )
    }

    private fun readEnforceFile(): String = try {
        val f = File(ENFORCE_PATH)
        if (f.exists() && f.canRead()) f.readText().trim()
        else Shell.runSu("-c", "cat " + ENFORCE_PATH, timeoutMs = 6000).result?.out?.trim().orEmpty()
    } catch (e: Exception) {
        ""
    }

    private fun readSys(path: String): String = try {
        val f = File(path)
        if (f.exists() && f.canRead()) f.readText().trim() else ""
    } catch (e: Exception) {
        ""
    }

    /** 切换到强制模式 */
    fun setEnforcing(): Pair<Boolean, String> {
        val r = Shell.runSu("-c", "setenforce 1", timeoutMs = 10000)
        val now = Shell.run("getenforce").out.trim()
        val ok = now.equals("Enforcing", true)
        return ok to if (ok) "已切换到强制模式" else describeFail(r.result?.text().orEmpty())
    }

    /** 切换到宽容模式 */
    fun setPermissive(): Pair<Boolean, String> {
        val r = Shell.runSu("-c", "setenforce 0", timeoutMs = 10000)
        val now = Shell.run("getenforce").out.trim()
        val ok = now.equals("Permissive", true)
        return ok to if (ok) "已切换到宽容模式" else describeFail(r.result?.text().orEmpty())
    }

    private fun describeFail(stderr: String): String {
        if (stderr.isBlank()) {
            return "切换失败：设备可能处于 Disabled，或 ROM 禁止修改"
        }
        return "切换失败：" + stderr
    }
}
