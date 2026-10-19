package sy.great.toolbox.tools

import android.content.Context
import java.io.File

/**
 * Shell 脚本混淆（ShellVMP V6）。
 *
 * 执行机制：
 *   Android 10+ 上 App 数据目录普遍 noexec，直接跑二进制会 error=13。
 *   所以统一用 /system/bin/linker64 加载解压出来的 ELF：
 *     linker64 <bash 路径> <脚本> [参数]
 *   linker64 是系统可执行文件，不受 App 目录 noexec 限制。
 *
 * PATH 策略：
 *   内置目录优先（openssl/gzip/sha*/sed/cut/tr/grep/awk/base64/od），
 *   追加 /system/bin 作兜底（V6 还会用 uname/mktemp/cat/rm/mkdir/chmod/head/tail/wc/mv/seq/date）。
 */
object ShellObfuscator {

    /** 需要解压的可执行文件（assets 里叫 xxx.so，解压后还原成 xxx） */
    private val BINS = listOf(
        "bash", "openssl", "gzip", "sed", "cut", "tr",
        "grep", "awk", "base64", "od", "sha256sum", "sha512sum"
    )

    /** 运行时依赖库 */
    private val LIBS = listOf(
        "libandroid-support.so",
        "libandroid-selinux.so",
        "libreadline.so.8",
        "libncursesw.so.6",
        "libiconv.so",
        "libgmp.so",
        "libcrypto.so.3",
        "libpcre2-8.so",
        "libmpfr.so"
    )

    data class Result(
        val ok: Boolean,
        val outputPath: String = "",
        val sizeBytes: Long = 0,
        val log: String = "",
        val message: String = ""
    )

    enum class Level(val display: String) {
        Normal("普通"),
        Strong("强"),
        Extreme("极端")
    }

    private class Env(
        val root: File,
        val binDir: File,
        val libDir: File,
        val linker: String
    )

    private fun findLinker(): String? {
        val list = listOf(
            "/system/bin/linker64",
            "/apex/com.android.runtime/bin/linker64",
            "/system/bin/linker"
        )
        for (p in list) {
            val f = File(p)
            if (f.exists() && f.canExecute()) return p
        }
        return null
    }

    private fun copyAsset(ctx: Context, assetPath: String, dest: File) {
        ctx.assets.open(assetPath).use { input ->
            dest.outputStream().use { out ->
                input.copyTo(out)
            }
        }
    }

    /** 解压环境；已就绪则直接复用 */
    private fun prepare(ctx: Context, force: Boolean = false): Env? {
        val linker = findLinker() ?: return null
        val root = File(ctx.filesDir, "shrun")
        val binDir = File(root, "bin")
        val libDir = File(root, "lib")
        val bash = File(binDir, "bash")
        val v6 = File(root, "v6.sh")

        if (!force && bash.exists() && bash.length() > 0 && v6.exists()) {
            return Env(root, binDir, libDir, linker)
        }

        return try {
            if (force) root.deleteRecursively()
            binDir.mkdirs()
            libDir.mkdirs()

            for (name in BINS) {
                copyAsset(ctx, "shbin/bin/$name.so", File(binDir, name))
            }
            for (name in LIBS) {
                copyAsset(ctx, "shbin/lib/$name", File(libDir, name))
            }
            copyAsset(ctx, "shbin/v6.sh", v6)

            for (name in BINS) File(binDir, name).setReadable(true, false)
            for (name in LIBS) File(libDir, name).setReadable(true, false)
            v6.setReadable(true, false)

            Env(root, binDir, libDir, linker)
        } catch (e: Throwable) {
            null
        }
    }

    /** 构造 linker64 启动命令 */
    private fun linkerCommand(env: Env, prog: String, vararg args: String): List<String> {
        val cmd = ArrayList<String>()
        cmd.add(env.linker)
        cmd.add(File(env.binDir, prog).absolutePath)
        for (a in args) cmd.add(a)
        return cmd
    }

    /** 配置子进程环境 */
    private fun applyEnv(pb: ProcessBuilder, env: Env, workDir: File) {
        val e = pb.environment()
        e["PATH"] = env.binDir.absolutePath + ":/system/bin"
        e["LD_LIBRARY_PATH"] = env.libDir.absolutePath
        e["HOME"] = env.root.absolutePath
        e["TMPDIR"] = workDir.absolutePath
    }

    /** 诊断信息 */
    fun diagnose(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("linker: ").append(findLinker() ?: "未找到").append('\n')
        sb.append("root: ").append(File(ctx.filesDir, "shrun").absolutePath).append('\n')
        val bash = File(ctx.filesDir, "shrun/bin/bash")
        sb.append("bash: ").append(if (bash.exists()) "存在 " + bash.length() else "缺失")
        return sb.toString()
    }

    /** 跑一段 bash 脚本，返回（输出, 退出码） */
    private fun runShell(env: Env, dir: File, script: String): Pair<String, Int> {
        return try {
            val pb = ProcessBuilder(linkerCommand(env, "bash", "-c", script))
            pb.directory(dir)
            applyEnv(pb, env, dir)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            Pair(out, code)
        } catch (e: Throwable) {
            Pair("启动异常：" + (e.message ?: e.javaClass.simpleName), -1)
        }
    }

    /**
     * 三级自检。
     *
     * 1) BASH_OK          bash 能启动
     * 2) DEPENDENCIES_OK  V6 所需工具能实际执行
     * 3) SHELLVMP_READY   v6.sh 能正常载入
     */
    fun selfCheck(ctx: Context): String {
        val env = prepare(ctx) ?: return "环境准备失败\n" + diagnose(ctx)

        val s1 = runShell(env, env.root, "echo BASH_OK")
        if (s1.second != 0 || !s1.first.contains("BASH_OK")) {
            return "阶段1 BASH_OK 失败（exit " + s1.second + "）\n" + s1.first + "\n" + diagnose(ctx)
        }

        val probeParts = ArrayList<String>()
        probeParts.add("echo DEPS_START")
        probeParts.add("for t in openssl gzip sha256sum sha512sum sed cut tr grep awk base64 od; do")
        probeParts.add("  command -v \$t >/dev/null 2>&1 || { echo MISSING:\$t; exit 2; }")
        probeParts.add("done")
        probeParts.add("openssl version >/dev/null || { echo FAIL:openssl; exit 3; }")
        probeParts.add("echo x | gzip -c >/dev/null || { echo FAIL:gzip; exit 3; }")
        probeParts.add("echo x | sha256sum >/dev/null || { echo FAIL:sha256sum; exit 3; }")
        probeParts.add("echo x | sha512sum >/dev/null || { echo FAIL:sha512sum; exit 3; }")
        probeParts.add("echo x | sed s/x/y/ >/dev/null || { echo FAIL:sed; exit 3; }")
        probeParts.add("echo x | cut -c1 >/dev/null || { echo FAIL:cut; exit 3; }")
        probeParts.add("echo x | tr x y >/dev/null || { echo FAIL:tr; exit 3; }")
        probeParts.add("echo x | grep x >/dev/null || { echo FAIL:grep; exit 3; }")
        probeParts.add("echo x | awk '{print}' >/dev/null || { echo FAIL:awk; exit 3; }")
        probeParts.add("echo x | base64 >/dev/null || { echo FAIL:base64; exit 3; }")
        probeParts.add("echo x | od -An -td1 >/dev/null || { echo FAIL:od; exit 3; }")
        probeParts.add("for t in uname mktemp cat rm mkdir chmod head tail wc mv seq date; do")
        probeParts.add("  command -v \$t >/dev/null 2>&1 || { echo MISSING:\$t; exit 4; }")
        probeParts.add("done")
        probeParts.add("echo DEPS_OK")
        val probe = probeParts.joinToString("\n")

        val s2 = runShell(env, env.root, probe)
        if (s2.second != 0 || !s2.first.contains("DEPS_OK")) {
            return "阶段2 DEPENDENCIES_OK 失败（exit " + s2.second + "）\n" + s2.first
        }

        val v6 = File(env.root, "v6.sh")
        if (!v6.exists()) return "阶段3 SHELLVMP_READY 失败：v6.sh 缺失"
        val s3 = runShell(env, env.root, "bash '" + v6.absolutePath + "' 2>&1 || true")
        val txt = s3.first
        val ok = txt.contains("用法") || txt.contains("usage", true) ||
            txt.contains("Usage", true) || txt.contains("参数") || txt.contains("输入")
        if (!ok) {
            return "阶段3 SHELLVMP_READY 失败\nv6 未输出用法：\n" + txt.take(800)
        }
        return ""
    }

    /** 执行混淆 */
    fun obfuscate(
        ctx: Context,
        input: File,
        outDir: File,
        level: Level,
        onLog: ((String) -> Unit)? = null
    ): Result {
        if (!input.exists()) return Result(false, message = "源文件不存在")
        if (input.length() == 0L) return Result(false, message = "源文件为空")

        val env = prepare(ctx) ?: return Result(false, message = "环境准备失败\n" + diagnose(ctx))
        val v6 = File(env.root, "v6.sh")
        if (!v6.exists()) return Result(false, message = "v6.sh 缺失")

        val work = File(env.root, "work")
        if (!work.exists()) work.mkdirs()

        val safeIn = File(work, "input.sh")
        try {
            input.copyTo(safeIn, overwrite = true)
        } catch (e: Throwable) {
            return Result(false, message = "读取源文件失败：" + (e.message ?: ""))
        }

        val safeOut = File(work, "output.sh")
        if (safeOut.exists()) safeOut.delete()

        return try {
            val cmd = linkerCommand(env, "bash", v6.absolutePath, safeIn.absolutePath, safeOut.absolutePath)
            val pb = ProcessBuilder(cmd)
            pb.directory(work)
            applyEnv(pb, env, work)

            val e = pb.environment()
            e["ANDROID_GATE"] = "0"
            e["CRYPTO_MODE"] = "aes"
            e["V6_LINT"] = "0"

            when (level) {
                Level.Normal -> {
                    e["JUNK_LEVEL"] = "0"
                    e["DECOY_LEVEL"] = "0"
                    e["KEY_ENTROPY"] = "0"
                }
                Level.Strong -> {
                    e["JUNK_LEVEL"] = "2"
                    e["DECOY_LEVEL"] = "1"
                    e["KEY_ENTROPY"] = "1"
                }
                Level.Extreme -> {
                    e["JUNK_LEVEL"] = "4"
                    e["DECOY_LEVEL"] = "3"
                    e["KEY_ENTROPY"] = "1"
                    e["L1_ITER"] = "900000"
                }
            }

            pb.redirectErrorStream(true)
            val p = pb.start()

            val log = StringBuilder()
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    log.append(line).append('\n')
                    onLog?.invoke(line)
                }
            }
            val code = p.waitFor()

            if (code != 0 || !safeOut.exists() || safeOut.length() == 0L) {
                val tail = log.lineSequence().lastOrNull { it.isNotBlank() } ?: ""
                return Result(
                    ok = false,
                    log = log.toString(),
                    message = "混淆失败（exit " + code + "）" + tail
                )
            }

            outDir.mkdirs()
            val finalOut = File(outDir, "protected.sh")
            safeOut.copyTo(finalOut, overwrite = true)

            Result(
                ok = true,
                outputPath = finalOut.absolutePath,
                sizeBytes = finalOut.length(),
                log = log.toString(),
                message = "成功"
            )
        } catch (e: Throwable) {
            Result(false, message = "执行异常：" + (e.message ?: e.javaClass.simpleName) + "\n" + diagnose(ctx))
        }
    }
}
