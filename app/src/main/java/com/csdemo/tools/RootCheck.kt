package com.csdemo.tools

import android.os.Build
import java.io.File

/**
 * Root 状态检测。
 *
 * 思路：不靠单一特征下结论，而是分三层交叉验证。
 *   1) 环境层：su 二进制是否存在、Magisk / KernelSU / APatch 等管理器痕迹
 *   2) 授权层：真实拉起 su 并执行命令，看能不能拿到 uid=0
 *   3) 能力层：拿到 root 后能否真正读特权文件 / 真实写入
 *
 * 最终只给三种结论，不含混：
 *   NO_SU        — 系统里没有可用的 su，这台设备没刷 root
 *   DENIED       — 有 su，但未授权 / 被拒 / 超时未确认
 *   GRANTED      — 真实拿到 root 权限
 */
object RootCheck {

    enum class State { NO_SU, DENIED, GRANTED }

    /** 单条检测项 */
    data class Item(
        val name: String,
        val detail: String,
        val level: Level
    )

    enum class Level { OK, WARN, FAIL, INFO }

    data class Report(
        val state: State,
        val items: List<Item>,
        val suPath: String,
        val suVersion: String,
        val manager: String,
        val uidLine: String,
        val selfUid: String,
        val selfUser: String,
        val usedMs: Long
    ) {
        val granted: Boolean get() = state == State.GRANTED
        val hasSu: Boolean get() = state != State.NO_SU
    }

    // ---------- 对外主入口 ----------

    fun scan(): Report {
        val t0 = System.currentTimeMillis()
        val items = ArrayList<Item>()

        // ---- 1. 自身身份（普通权限下能拿到） ----
        val selfId = Shell.run("id").out.trim()
        val selfUid = extractUid(selfId)
        val selfUser = extractUser(selfId)
        items += Item(
            "当前进程身份",
            if (selfId.isBlank()) "未知" else selfId,
            Level.INFO
        )

        // ---- 2. su 二进制 ----
        val suPath = Shell.which("su")
        val suFound = suPath != null
        items += Item(
            "su 二进制",
            if (suFound) suPath!! else "未在 PATH 与常用目录中找到",
            if (suFound) Level.OK else Level.FAIL
        )

        // ---- 3. su 能否被拉起 ----
        val suRunnable = suFound || Shell.suExists()
        items += Item(
            "su 可执行",
            if (suRunnable) "可被拉起" else "无法启动 su",
            if (suRunnable) Level.OK else Level.FAIL
        )

        // ---- 4. root 管理器痕迹 ----
        val manager = detectManager()
        items += Item(
            "Root 管理器",
            manager.ifBlank { "未检测到已知管理器" },
            if (manager.isNotBlank()) Level.OK else Level.INFO
        )

        // ---- 5. su 版本 ----
        val suVersion = readSuVersion(suRunnable)
        if (suVersion.isNotBlank()) {
            items += Item("su 版本", suVersion, Level.INFO)
        }

        // ---- 6. 关键：真实提权测试 ----
        var granted = false
        var uidLine = ""
        var timedOut = false
        if (suRunnable) {
            // 用 su -c id，这是最通用的写法
            val r1 = Shell.runSu("-c", "id", timeoutMs = 25000)
            timedOut = r1.timedOut
            val out1 = r1.result?.out?.trim().orEmpty()
            if (out1.contains("uid=0")) {
                granted = true
                uidLine = out1
            } else if (out1.isNotBlank()) {
                uidLine = out1
            }

            // 第二重：用 whoami 交叉验证（有些 su 对 id 输出格式不同）
            if (!granted) {
                val r2 = Shell.runSu("-c", "whoami", timeoutMs = 15000)
                val out2 = r2.result?.out?.trim().orEmpty()
                if (out2 == "root") {
                    granted = true
                    uidLine = "whoami = root"
                } else if (out2.isNotBlank() && uidLine.isBlank()) {
                    uidLine = out2
                }
            }
        }

        items += Item(
            "提权测试 (su -c id)",
            when {
                granted -> uidLine
                timedOut -> "等待授权超时（未确认）"
                uidLine.isNotBlank() -> uidLine
                suRunnable -> "su 无输出（可能被拒）"
                else -> "跳过（无 su）"
            },
            if (granted) Level.OK else (if (suRunnable) Level.WARN else Level.FAIL)
        )

        // ---- 7. 能力测试：只有已授权时才做 ----
        if (granted) {
            val capTest = Shell.runSu("-c", "cat /proc/version", timeoutMs = 8000)
            val capOk = !capTest.result?.out.isNullOrBlank()
            items += Item(
                "特权文件读取",
                if (capOk) "成功读取 /proc/version" else "失败",
                if (capOk) Level.OK else Level.WARN
            )
        }

        // ---- 8. 部分设备上可作为辅助信号 ----
        val tags = Shell.run("getprop", "ro.build.tags").out.trim()
        items += Item(
            "build tags",
            tags.ifBlank { "未知" },
            if (tags.contains("test-keys")) Level.WARN else Level.INFO
        )
        val dbg = Shell.run("getprop", "ro.debuggable").out.trim()
        items += Item(
            "ro.debuggable",
            dbg.ifBlank { "0" },
            if (dbg == "1") Level.WARN else Level.INFO
        )
        val secure = Shell.run("getprop", "ro.secure").out.trim()
        items += Item(
            "ro.secure",
            secure.ifBlank { "1" },
            if (secure == "0") Level.WARN else Level.INFO
        )

        val state = when {
            granted -> State.GRANTED
            suRunnable -> State.DENIED
            else -> State.NO_SU
        }

        return Report(
            state = state,
            items = items,
            suPath = suPath ?: "未找到",
            suVersion = suVersion,
            manager = manager.ifBlank { "无" },
            uidLine = uidLine,
            selfUid = selfUid,
            selfUser = selfUser,
            usedMs = System.currentTimeMillis() - t0
        )
    }

    // ---------- 辅助 ----------

    private fun extractUid(idLine: String): String {
        val m = Regex("uid=(\\d+)").find(idLine) ?: return ""
        return m.groupValues[1]
    }

    private fun extractUser(idLine: String): String {
        // id 输出形如：uid=0(root) gid=0(root) ...
        val m = Regex("uid=\\d+\\((.*?)\\)").find(idLine) ?: return ""
        return m.groupValues[1]
    }

    /**
     * 检查已知 root 方案的特征路径 / 属性。
     * 返回管理器名称，未检测到返回空串。
     */
    private fun detectManager(): String {
        // Magisk
        val magiskVer = Shell.run("getprop", "ro.magisk.version").out.trim()
        if (magiskVer.isNotBlank()) return "Magisk $magiskVer"
        if (File("/sbin/.magisk").exists()) return "Magisk（/sbin/.magisk）"
        if (File("/data/adb/magisk").exists()) return "Magisk（/data/adb/magisk）"
        if (Shell.which("magisk") != null) return "Magisk（magisk 二进制）"
        if (File("/data/adb/modules").exists()) return "Magisk（modules）"

        // KernelSU
        if (File("/data/adb/ksu").exists()) return "KernelSU"
        if (Shell.which("ksud") != null) return "KernelSU（ksud）"
        val ksuVer = Shell.run("getprop", "ro.kernelsu.version").out.trim()
        if (ksuVer.isNotBlank()) return "KernelSU $ksuVer"

        // APatch
        if (Shell.which("apd") != null) return "APatch"
        if (File("/data/adb/ap").exists()) return "APatch"

        // SuperSU
        if (File("/system/xbin/daemonsu").exists()) return "SuperSU"
        if (File("/system/etc/init.d/99SuperSUDaemon").exists()) return "SuperSU"
        if (Shell.which("supersu") != null) return "SuperSU"

        // 其他常见 su 管理
        if (File("/system/app/Superuser.apk").exists()) return "Superuser"
        if (File("/data/adb/ksu/bin/ksud").exists()) return "KernelSU"

        return ""
    }

    private fun readSuVersion(suRunnable: Boolean): String {
        if (!suRunnable) return ""
        // Magisk 的 su 支持 -v
        val r = Shell.runSu("-v", timeoutMs = 5000)
        val v = r.result?.out?.trim().orEmpty()
        if (v.isNotBlank()) return v
        // 退而求其次：版本字符串字段
        return ""
    }

    // ---------- 设备概览（供设备信息页用） ----------

    fun deviceSummary(): List<Pair<String, String>> = listOf(
        "型号" to (Build.MANUFACTURER + " " + Build.MODEL),
        "设备代号" to Build.DEVICE,
        "Android" to (Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"),
        "版本类型" to Build.TYPE,
        "主板" to Build.BOARD,
        "硬件" to Build.HARDWARE,
        "ABI" to Build.SUPPORTED_ABIS.joinToString(", "),
        "内核" to System.getProperty("os.version").orEmpty()
    )
}
