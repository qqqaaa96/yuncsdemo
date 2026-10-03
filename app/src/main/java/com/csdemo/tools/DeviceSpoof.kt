package com.csdemo.tools

import android.content.Context
import java.io.File

/**
 * 设备属性伪装。
 *
 * 实现方式：
 *   1) 优先使用 Magisk 的 resetprop：内存级改写，重启自动失效，不改真实分区。
 *   2) 没有 resetprop 时，尝试 setprop（仅对部分非 ro. 属性有效），并如实告知。
 *
 * 每次写入后都重新 getprop 验证，只有读回值一致才算成功。
 * 原始值会存成快照，供“一键复原”。
 */
object DeviceSpoof {

    private const val PREFS = "csdemo_spoof"

    /** 单条属性的写入结果 */
    data class PropResult(
        val key: String,
        val target: String,
        val actual: String,
        val ok: Boolean
    )

    /** 一次性伪装的结果 */
    data class ApplyResult(
        val results: List<PropResult>,
        val method: String,
        val allOk: Boolean
    ) {
        val okCount: Int get() = results.count { it.ok }
    }

    // ---------------- 工具方法 ----------------

    /** 读当前属性（不用 root） */
    fun readProp(key: String): String = Shell.run("getprop", key).out.trim()

    /** 用 root 读属性（应对个别受限情况） */
    fun readPropRoot(key: String): String =
        Shell.runSu("-c", "getprop " + key, timeoutMs = 8000).result?.out?.trim().orEmpty()

    /** resetprop 是否可用 */
    fun hasResetprop(): Boolean {
        val r = Shell.runSu("-c", "which resetprop", timeoutMs = 6000)
        val out = r.result?.out?.trim().orEmpty()
        if (out.isNotBlank()) return true
        // 部分 Magisk 版本下 resetprop 在 /data/adb/magisk/ 下
        val r2 = Shell.runSu("-c", "ls /data/adb/magisk/resetprop", timeoutMs = 6000)
        return !r2.result?.out.isNullOrBlank()
    }

    private fun resetpropBin(): String {
        val r = Shell.runSu("-c", "which resetprop", timeoutMs = 6000)
        val w = r.result?.out?.trim().orEmpty()
        if (w.isNotBlank()) return w
        return "/data/adb/magisk/resetprop"
    }

    // ---------------- 应用伪装 ----------------

    /**
     * 应用一组属性。
     * ctx 用于保存原始值快照；props 中 key -> value，仅处理 SpoofData.PROP_KEYS 里的合法 key。
     */
    fun applyProps(ctx: Context, props: Map<String, String>): ApplyResult {
        val useResetprop = hasResetprop()
        val results = ArrayList<PropResult>()

        // 先存快照（只存一次，不覆盖已有快照，以免恢复时恢复到伪装值）
        saveSnapshotNow(ctx, props.keys)

        for ((key, value) in props) {
            if (!SpoofData.PROP_KEYS.contains(key)) continue
            writeOne(key, value, useResetprop)
            val actual = readProp(key)
            results.add(
                PropResult(
                    key = key,
                    target = value,
                    actual = actual,
                    ok = actual == value
                )
            )
        }

        return ApplyResult(
            results = results,
            method = if (useResetprop) "resetprop（重启失效）" else "setprop（不保证生效）",
            allOk = results.isNotEmpty() && results.all { it.ok }
        )
    }

    /**
     * 写入单个属性。三重尝试，逐级降级：
     *   1) resetprop -n key value          —— 直接覆写内存属性
     *   2) resetprop --delete 后重写      —— 针对被锁定的旧值
     *   3) setprop key value                 —— 兵底
     */
    private fun writeOne(key: String, value: String, useResetprop: Boolean) {
        val q = quote(value)
        if (useResetprop) {
            val bin = resetpropBin()
            // 第一记：直接覆写
            Shell.runSu("-c", "$bin -n " + key + " " + q, timeoutMs = 12000)
            if (readProp(key) == value) return
            // 第二记：先删后写（针对 ro. 锁定值）
            Shell.runSu("-c", "$bin --delete " + key + " ; $bin -n " + key + " " + q, timeoutMs = 12000)
            if (readProp(key) == value) return
        }
        // 第三记：setprop 兵底
        Shell.runSu("-c", "setprop " + key + " " + q, timeoutMs = 10000)
    }

    /** 把机型 + 芯片组装成待写属性表 */
    fun buildProps(phone: SpoofData.Phone?, chip: SpoofData.Chip?): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        if (phone != null) {
            m["ro.product.model"] = phone.model
            m["ro.product.brand"] = phone.brand
            m["ro.product.manufacturer"] = phone.manufacturer
            m["ro.product.device"] = phone.device
            m["ro.product.name"] = phone.product
        }
        if (chip != null) {
            m["ro.product.board"] = chip.platform
            m["ro.board.platform"] = chip.platform
            m["ro.hardware"] = chip.hardware
            // Android 12+ 官方芯片字段，设置里“处理器”读这两个
            m["ro.soc.model"] = chip.socModel
            m["ro.soc.manufacturer"] = chip.socManufacturer
        }
        return m
    }

    // ---------------- 电量伪装 ----------------

    /**
     * 电量伪装模式。
     *   NORMAL —— 正常伪装：1 ~ 100
     *   FUNNY  —— 沙雕伪装：0 ~ 999999
     */
    enum class BatteryMode(val label: String, val min: Int, val max: Int) {
        NORMAL("正常伪装", 1, 100),
        FUNNY("沙雕伪装", 0, 999999),
    }

    /**
     * 用 shell 执行一条命令，自动选择可用身份：
     *   有 root → su -c
     *   无 root 但有 ADB(Shizuku) → Shizuku shell
     * 都没有则返回 null。
     *
     * 返回值：失败（无可用身份/执行异常）为 null；成功为命令输出文本。
     */
    private fun execShellAuto(ctx: Context, command: String): String? {
        // 1) root 优先
        if (RootState.useRoot() || runCatching { Shell.runSu("-c", "id", timeoutMs = 6000).result?.out?.contains("uid=0") == true }.getOrDefault(false)) {
            val r = Shell.runSu("-c", command, timeoutMs = 12000)
            if (r.invoked && r.result != null) {
                return (r.result.out + r.result.err).trim()
            }
        }
        // 2) ADB（Shizuku）
        val adb = AdbShell.exec(ctx.applicationContext, command)
        if (adb != null) return (adb.out + adb.err).trim()
        return null
    }

    /**
     * 应用电量伪装。
     *
     * 真正生效的方式是系统命令（不需要改属性）：
     *     dumpsys battery set level N
     * 它会把系统向所有应用上报的电量改成 N（上限受系统约束）。
     *
     * 执行身份：root 优先，其次 ADB(Shizuku)。两者都没有则如实失败。
     */
    fun applyBattery(ctx: Context, percent: Int, mode: BatteryMode): ApplyResult {
        val clamped = percent.coerceIn(mode.min, mode.max)
        val cmd = "dumpsys battery set level " + clamped
        val out = execShellAuto(ctx, cmd)
        val ok = out != null
        val results = listOf(
            PropResult(
                key = "dumpsys battery level",
                target = clamped.toString(),
                actual = out ?: "无可用身份（需 root 或 ADB）",
                ok = ok
            )
        )
        return ApplyResult(
            results = results,
            method = when {
                RootState.useRoot() -> "root（su -c dumpsys battery）"
                AdbShell.granted() -> "ADB（Shizuku dumpsys battery）"
                else -> "无可用身份"
            },
            allOk = ok
        )
    }

    /**
     * 还原电量伪装：
     *     dumpsys battery reset
     */
    fun restoreBattery(ctx: Context): ApplyResult {
        val out = execShellAuto(ctx, "dumpsys battery reset")
        val ok = out != null
        val results = listOf(
            PropResult(
                key = "dumpsys battery reset",
                target = "reset",
                actual = out ?: "无可用身份（需 root 或 ADB）",
                ok = ok
            )
        )
        return ApplyResult(results, "已还原电量伪装", ok)
    }

    // ---------------- 快照与复原 ----------------

    /** 快照：key -> 真实原值 */
    fun snapshot(ctx: Context): Map<String, String> {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = LinkedHashMap<String, String>()
        for (k in SpoofData.PROP_KEYS) {
            val v = sp.getString(k, null) ?: continue
            out[k] = v
        }
        return out
    }

    fun hasSnapshot(ctx: Context): Boolean = snapshot(ctx).isNotEmpty()

    /** 保存当前原值快照；已存在的 key 不覆盖 */
    private fun saveSnapshotNow(ctx: Context, keys: Set<String>) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ed = sp.edit()
        for (k in keys) {
            if (!SpoofData.PROP_KEYS.contains(k)) continue
            if (sp.contains(k)) continue // 已存过，不覆盖
            val v = readProp(k)
            ed.putString(k, v)
        }
        ed.apply()
    }

    /** 一键复原到快照值 */
    fun restore(ctx: Context): ApplyResult {
        val snap = snapshot(ctx)
        if (snap.isEmpty()) {
            return ApplyResult(emptyList(), "无快照", false)
        }
        val useResetprop = hasResetprop()
        val bin = if (useResetprop) resetpropBin() else ""
        val results = ArrayList<PropResult>()

        for ((key, value) in snap) {
            val cmd = if (useResetprop) {
                "$bin -n " + key + " " + quote(value)
            } else {
                "setprop " + key + " " + quote(value)
            }
            Shell.runSu("-c", cmd, timeoutMs = 12000)
            val actual = readProp(key)
            results.add(PropResult(key, value, actual, actual == value))
        }

        // 复原成功后清掉快照
        if (results.all { it.ok }) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }

        return ApplyResult(
            results = results,
            method = if (useResetprop) "resetprop 复原" else "setprop 复原",
            allOk = results.all { it.ok }
        )
    }

    /** 当前是否处于伪装状态（有快照且当前值与快照不同） */
    fun isSpoofed(ctx: Context): Boolean {
        val snap = snapshot(ctx)
        if (snap.isEmpty()) return false
        for ((k, v) in snap) {
            if (readProp(k) != v) return true
        }
        return false
    }

    private fun quote(s: String): String {
        // 属性值可能含空格，统一加引号
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
