package sy.great.toolbox.tools

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Shell 脚本混淆（ShellVMP V6）。
 *
 * ── 为什么最终用 linker64 加载 ──
 *
 * Android 10+ 上，App 的数据目录（files / code_cache / noBackupFiles）
 * 普遍挂载为 noexec，chmod 755 也无效，直接跑就是 error=13 Permission denied。
 *
 * 把二进制改名成 lib*.so 放进 jniLibs 也不可靠：
 * AGP 的 mergeNativeLibs 要求它们是共享库（带 DT_SONAME），
 * 而 bash / gzip 这些是带 PT_INTERP 的可执行程序，会被过滤掉，
 * 结果是 nativeLibraryDir 里根本没有这些文件。
 *
 * 唯一确定可行的机制：
 *   用系统的动态链接器 /system/bin/linker64 显式加载任意路径的 ELF。
 *   linker64 自己是系统可执行文件，不受 App 数据目录 noexec 限制；
 *   它会把我们的 bash 当作“主程序”映射并跳进去执行。
 *   这是无 root 环境运行自带二进制的通行做法。
 *
 * 所以：
 *   · 二进制回到 assets（100% 会被打包）
 *   · 首次使用解压到 filesDir/shrun/{bin,lib}
 *   · 执行时统一走 linker64
 *   · 依赖库用 LD_LIBRARY_PATH 指向解压出来的 lib 目录
 */
object ShellObfuscator {

    /**
     * 需要解压的可执行文件。
     *
     * 注意：assets 里它们都以 ".so" 结尾（如 bash.so）。
     * 原因：AGP 的 noCompress 只认后缀，无后缀文件会被 deflate 压缩，
     * 解压出来的 ELF 是坏的。给个 .so 后缀能命中默认不压缩规则，
     * 运行时再把后缀去掉还原成真实命令名。
     */
    private val BINS = listOf(
        "bash", "openssl", "gzip", "sed", "cut", "tr",
        "grep", "awk", "base64", "od", "sha256sum", "sha512sum"
    )

    /** 它们的运行时依赖 */
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
        /** 系统动态链接器路径 */
        val linker: String
    )

    /** 找系统动态链接器 */
    private fun findLinker(): String? {
        val candidates = listOf(
            "/system/bin/linker64",
            "/apex/com.android.runtime/bin/linker64",
            "/system/bin/linker"
        )
        for (p in candidates) {
            val f = File(p)
            if (f.exists() && f.canExecute()) return p
        }
        return null
    }

    /** 解压环境；已就绪则直接返回 */
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
                // assets 里叫 xxx.so，解压后还原成 xxx
                copyAsset(ctx, "shbin/bin/$name.so", File(binDir, name))
            }
            for (name in LIBS) {
                copyAsset(ctx, "shbin/lib/$name", File(libDir, name))
            }
            copyAsset(ctx, "shbin/v6.sh", v6)

            // 这里不需要 exec 权限（走 linker64），但保留可读即可
            for (name in BINS) File(binDir, name).setReadable(true, false)
            for (name in LIBS) File(libDir, name).setReadable(true, false)
            v6.setReadable(true, false)

            Env(root, binDir, libDir, linker)
        } catch (e: Throwable) {
            null
        }
    }

    private fun copyAsset(ctx: Context, assetPath: String, dest: File) {
        ctx.assets.open(assetPath).use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
    }

    /**
     * 构造用 linker64 启动某个内置程序的命令。
     *
     * 形式：linker64 <可执行文件绝对路径> [参数...]
     */
    private fun linkerCommand(env: Env, progName: String, vararg args: String): List<String> {
        val prog = File(env.binDir, progName).absolutePath
        val cmd = ArrayList<String>(args.size + 2)
        cmd.add(env.linker)
        cmd.add(prog)
        cmd.addAll(args)
        return cmd
    }

    /**
     * 为子进程配置环境变量。
     *
     * PATH 必须包含 /system/bin：
     *   V6 除 openssl/gzip/sha*/sed/cut/tr/grep/awk/base64/od 之外，
     *   还会调用 uname、mktemp、cat、rm、mkdir、chmod、head、tail、wc、mv、seq、date。
     *   这十来个没必要自己打包（约 2MB），Android 的 toybox 里全都有。
     *   所以策略是：内置目录优先，系统目录兜底。
     *
     * 不用 /system/xbin：新系统已经不存在。
     */
    private fun applyEnv(pb: ProcessBuilder, env: Env, workDir: File) {
        val e = pb.environment()
        e["PATH"] = env.binDir.absolutePath + ":/system/bin"
        e["LD_LIBRARY_PATH"] = env.libDir.absolutePath
        e["HOME"] = env.root.absolutePath
        e["TMPDIR"] = workDir.absolutePath
    }

    /** 诊断信息 */
    fun diagnose(ctx: Context): String {
        val linker = findLinker()
        val root = File(ctx.filesDir, "shrun")
        val sb = StringBuilder()
        sb.append("linker: ").append(linker ?: "未找到").append('\n')
        sb.append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
        sb.append("root: ").append(root.absolutePath).append('\n')
        val bash = File(root, "bin/bash")
        sb.append("bash 存在: ").append(bash.exists())
        if (bash.exists()) sb.append("，大小 ").append(bash.length())
        return sb.toString()
    }

    /**
     * 三级自检（按 GPT 的建议拆开，避免“READY_OK 但不可用”的误判）。
     *
     *   1. BASH_OK          —— bash 能启动
     *   2. DEPENDENCIES_OK  —— V6 所需工具全部可执行（不只是 command -v）
     *   3. SHELLVMP_READY   —— 实际跑一遍 v6.sh --help / 空参，确认能进入逻辑
     *
     * @return 空串表示全部通过，否则返回具体阶段与原因
     */
    fun selfCheck(ctx: Context): String {
        val env = prepare(ctx)
            ?: return "环境准备失败\n" + diagnose(ctx)

        // ---- 阶段 1：bash 可启动 ----
        val bashOut = runShell(env, env.root, "echo BASH_OK; echo \$0")
        if (bashOut.second != 0 || !bashOut.first.contains("BASH_OK")) {
            return "阶段1 BASH_OK 失败（exit ${bashOut.second}）\n${bashOut.first}\n" + diagnose(ctx)
        }

        // ---- 阶段 2：依赖工具逐个实际执行 ----
        // 不用单纯 command -v：那只证明能找到，不证明能跑。
        val probe = buildString {
            append("echo DEPS_START\n")
            // 我们自己打包的
            append("for t in openssl gzip sha256sum sha512sum sed cut tr grep awk base64 od; do\n")
            append("  command -v \$t >/dev/null 2>&1 || { echo MISSING:\$t; exit 2; }\n")
            append("done\n")
            // 实际执行一遍，确认依赖库完整
            append("openssl version >/dev/null || { echo FAIL:openssl; exit 3; }\n")
            append("echo x | gzip -c >/dev/null || { echo FAIL:gzip; exit 3; }\n")
            append("echo x | sha256sum >/dev/null || { echo FAIL:sha256sum; exit 3; }\n")
            append("echo x | sha512sum >/dev/null || { echo FAIL:sha512sum; exit 3; }\n")
            append("echo x | sed s/x/y/ >/dev/null || { echo FAIL:sed; exit 3; }\n")
            append("echo x | cut -c1 >/dev/null || { echo FAIL:cut; exit 3; }\n")
            append("echo x | tr x y >/dev/null || { echo FAIL:tr; exit 3; }\n")
            append("echo x | grep x >/dev/null || { echo FAIL:grep; exit 3; }\n")
            append("echo x | awk '{print}' >/dev/null || { echo FAIL:awk; exit 3; }\n")
            append("echo x | base64 >/dev/null || { echo FAIL:base64; exit 3; }\n")
            append("echo x | od -An -td1 >/dev/null || { echo FAIL:od; exit 3; }\n")
            // 系统兜底工具（V6 也会用）
            append("for t in uname mktemp cat rm mkdir chmod head tail wc mv seq date; do\n")
            append("  command -v \$t >/dev/null 2>&1 || { echo MISSING:\$t; exit 4; }\n")
            append("done\n")
            append("echo DEPS_OK\n")
        }
        val depsOut = runShell(env, env.root, probe)
        if (depsOut.second != 0 || !depsOut.first.contains("DEPS_OK")) {
            return "阶段2 DEPENDENCIES_OK 失败（exit ${depsOut.second}）\n${depsOut.first}"
        }

        // ---- 阶段 3：v6.sh 能否真正载入 ----
        // 不带参数运行，V6 会打印用法并返回非 0；
        // 只要能打印出“用法/usage”，就说明脚本已完整解析并进入主逻辑。
        val v6 = File(env.root, "v6.sh")
        if (!v6.exists()) return "阶段3 SHELLVMP_READY 失败：v6.sh 缺失"
        val v6Out = runShell(env, env.root, "bash '${v6.absolutePath}' 2>&1 || true")
        val txt = v6Out.first
        val signalled = txt.contains("用法") || txt.contains("usage", true) ||
            txt.contains("Usage", true) || txt.contains("输入") || txt.contains("参数")
        if (!signalled) {
            return "阶段3 SHELLVMP_READY 失败：v6.sh 未正常输出用法\n" + txt.take(600)
        }

        return ""
    }

    /** 跑一段 bash 脚本，返回（输出, 退出码） */
    private fun runShell(env: Env, dir: File, script: String): Pair<String, Int> {
        return try {
            val pb = ProcessBuilder(
                linkerCommand(env, "bash", "-c", script)
            )
            pb.directory(dir)
            applyEnv(pb, env, dir)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            out to code
        } catch (e: Throwable) {
            ("启动异常：${e.message ?: e.javaClass.simpleName}") to -1
        }
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

        val env = prepare(ctx) ?: return Result(false, message = "运行环境准备失败\n" + diagnose(ctx))
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
            val cmd = linkerCommand(
                env, "bash",
                v6.absolutePath,
                safeIn.absolutePath,
                safeOut.absolutePath
            )
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
                return Result(
                    false,
                    log = log.toString(),
                    message = "混淆失败（exit $code）" +
                        if (log.isBlank()) "" else "：" + log.lineSequence().lastOrNull { it.isNotBlank() }
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
