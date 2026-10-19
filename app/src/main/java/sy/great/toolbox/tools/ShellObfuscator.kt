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

    /** 需要解压的可执行文件 */
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
                copyAsset(ctx, "shbin/bin/$name", File(binDir, name))
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

    /** 为子进程配置环境变量 */
    private fun applyEnv(pb: ProcessBuilder, env: Env, workDir: File) {
        val e = pb.environment()
        e["PATH"] = env.binDir.absolutePath
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

    /** 自检 */
    fun selfCheck(ctx: Context): String {
        val env = prepare(ctx)
            ?: return "环境准备失败\n" + diagnose(ctx)

        return try {
            val cmd = linkerCommand(env, "bash", "-c", "echo READY_OK; uname -m")
            val pb = ProcessBuilder(cmd)
            pb.directory(env.root)
            applyEnv(pb, env, env.root)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            if (code == 0 && out.contains("READY_OK")) ""
            else "bash 启动失败（exit $code）\n$out\n" + diagnose(ctx)
        } catch (e: Throwable) {
            "bash 启动异常：${e.message ?: e.javaClass.simpleName}\n" + diagnose(ctx)
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
