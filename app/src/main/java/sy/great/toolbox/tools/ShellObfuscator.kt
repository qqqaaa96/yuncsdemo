package sy.great.toolbox.tools

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * Shell 脚本混淆（ShellVMP V6）。
 *
 * 为什么需要内置 bash：
 *   Android 系统只有 /system/bin/sh（mksh），没有 bash；
 *   也没有 sha512sum / openssl 等 V6 必需的命令。
 *   所以把 Termux 的 bash + coreutils + openssl 及其依赖打包进 assets，
 *   首次使用时解压到应用私有目录（那里可执行），并限定 PATH/LD_LIBRARY_PATH 只指向它们。
 *
 * 产物本身仍然需要 bash 环境才能运行：
 *   · 导出的 .sh 需要 bash 4.x 及以上（不能用 mksh）
 *   · 本 App 可以选择“直接在本机运行”走同样的内置 bash
 */
object ShellObfuscator {

    /** 内置环境解压后的目录 */
    private const val DIR = "shbin"

    /** 必须随包发放的可执行文件 */
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

    /** 混淆强度档位 */
    enum class Level(val display: String) {
        Normal("普通"),
        Strong("强"),
        Extreme("极端")
    }

    /**
     * 解压内置环境。
     *
     * @param force 为 true 时先清空再重解（升级包后用）
     * @return 可用则返回 root 目录，失败返回 null
     */
    fun ensureEnv(ctx: Context, force: Boolean = false): File? {
        // 依次尝试多个目录。
        //
        // 为什么不是只用 filesDir：
        //   部分 ROM / 加固环境会把 /data/user/0/<pkg>/files 挂成 noexec，
        //   即使 chmod 755 也会 exec 失败（error=13）。
        //   codeCacheDir 与 noBackupFilesDir 通常可执行，作为回退。
        val candidates = listOf(
            File(ctx.codeCacheDir, DIR),
            File(ctx.noBackupFilesDir, DIR),
            File(ctx.filesDir, DIR)
        )
        for (dir in candidates) {
            val r = ensureEnvAt(ctx, dir, force)
            if (r != null) return r
        }
        return null
    }

    /** 在指定根目录下准备环境；失败返回 null */
    private fun ensureEnvAt(ctx: Context, root: File, force: Boolean): File? {
        val binDir = File(root, "bin")
        val libDir = File(root, "lib")
        val bash = File(binDir, "bash")
        val v6 = File(root, "v6.sh")

        if (!force && bash.exists() && bash.canExecute() && v6.exists()) {
            return root
        }

        return try {
            if (force) root.deleteRecursively()
            binDir.mkdirs()
            libDir.mkdirs()

            // ---- 解压二进制 ----
            for (name in BINS) {
                copyAsset(ctx, "shbin/bin/$name", File(binDir, name))
            }
            for (name in LIBS) {
                copyAsset(ctx, "shbin/lib/$name", File(libDir, name))
            }
            copyAsset(ctx, "shbin/v6.sh", v6)

            // ---- 赋可执行权限 ----
            //
            // 注意：File.setExecutable 在部分 ROM 上不生效（尤其开了应用加固/沙箱的），
            // 会导致 ProcessBuilder 报 error=13 Permission denied。
            // 这里先试 Java API，再用 chmod 650 兜底。
            fun makeExec(f: File) {
                f.setReadable(true, false)
                f.setWritable(false, false)
                f.setExecutable(true, false)
            }
            for (name in BINS) makeExec(File(binDir, name))
            for (name in LIBS) makeExec(File(libDir, name))
            v6.setReadable(true, false)
            v6.setExecutable(true, false)

            // chmod 兜底：直接调系统 chmod 命令
            try {
                val chmod = ProcessBuilder(
                    "/system/bin/chmod", "750",
                    File(binDir, "bash").absolutePath
                ).start()
                chmod.waitFor()
                for (name in BINS) {
                    ProcessBuilder("/system/bin/chmod", "750", File(binDir, name).absolutePath)
                        .start().waitFor()
                }
                ProcessBuilder("/system/bin/chmod", "750", v6.absolutePath)
                    .start().waitFor()
            } catch (_: Throwable) {
                // chmod 不可用就算了，后面还有诊断
            }

            if (!bash.canExecute()) return null

            // ---- 校验解压结果 ----
            //
            // assets 如果被 aapt 压缩过，解压出来的 ELF 会损坏，
            // 表现就是 ProcessBuilder 报 error=13 Permission denied（很误导）。
            // 这里直接比对文件头与大小，坏了一开始就报清楚。
            val head = ByteArray(4)
            File(binDir, "bash").inputStream().use { it.read(head) }
            val isElf = head[0] == 0x7F.toByte() &&
                    head[1] == 'E'.code.toByte() &&
                    head[2] == 'L'.code.toByte() &&
                    head[3] == 'F'.code.toByte()
            if (!isElf) {
                // 清掉坏文件，下次会重解
                root.deleteRecursively()
                return null
            }
            root
        } catch (e: Throwable) {
            null
        }
    }

    private fun copyAsset(ctx: Context, assetPath: String, dest: File) {
        ctx.assets.open(assetPath).use { input: InputStream ->
            dest.outputStream().use { out ->
                input.copyTo(out)
            }
        }
    }

    /**
     * 自检：内部 bash 能否跑起来，v6 脚本所需工具是否齐备。
     *
     * @return 自检通过返回空串，否则返回失败原因
     */
    fun selfCheck(ctx: Context): String {
        val root = ensureEnv(ctx)
            ?: return "内置环境解压失败（三个候选目录均无法执行或解压后文件损坏）"
        if (!File(root, "bin/bash").canExecute()) {
            return "bash 不可执行：" + File(root, "bin/bash").absolutePath
        }
        val binDir = File(root, "bin")
        val libDir = File(root, "lib")

        return try {
            val cmd = listOf(
                File(binDir, "bash").absolutePath, "-c",
                "echo READY; " +
                    "for t in bash openssl gzip sed cut tr grep awk base64 od sha256sum sha512sum; do " +
                    "command -v \$t >/dev/null 2>&1 || { echo MISSING:\$t; exit 1; }; done; " +
                    "echo ALL_OK"
            )
            val pb = ProcessBuilder(cmd)
            pb.directory(root)
            val env = pb.environment()
            env["PATH"] = binDir.absolutePath
            env["LD_LIBRARY_PATH"] = libDir.absolutePath
            env["HOME"] = root.absolutePath
            env["TMPDIR"] = root.absolutePath
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            if (code == 0 && out.contains("ALL_OK")) "" else "自检失败（exit $code）：$out"
        } catch (e: Throwable) {
            "自检异常：" + (e.message ?: e.javaClass.simpleName) +
                "\n目录：" + root.absolutePath
        }
    }

    /**
     * 执行混淆。
     *
     * @param input  源 .sh 文件
     * @param outDir 产物目录
     * @param level  强度档位
     * @param onLog  实时日志回调（在调用线程）
     */
    fun obfuscate(
        ctx: Context,
        input: File,
        outDir: File,
        level: Level,
        onLog: ((String) -> Unit)? = null
    ): Result {
        if (!input.exists()) return Result(false, message = "源文件不存在")
        if (input.length() == 0L) return Result(false, message = "源文件为空")

        val root = ensureEnv(ctx) ?: return Result(false, message = "内置环境解压失败")
        val binDir = File(root, "bin")
        val libDir = File(root, "lib")
        val v6 = File(root, "v6.sh")
        if (!v6.exists()) return Result(false, message = "v6.sh 缺失")

        val work = File(root, "work")
        if (!work.exists()) work.mkdirs()

        // 输入拷到工作目录，避免外部路径带空格/中文导致 bash 解析出问题
        val safeIn = File(work, "input.sh")
        try {
            input.copyTo(safeIn, overwrite = true)
        } catch (e: Throwable) {
            return Result(false, message = "读取源文件失败：" + (e.message ?: ""))
        }

        val safeOut = File(work, "output.sh")
        if (safeOut.exists()) safeOut.delete()

        return try {
            val pb = ProcessBuilder(
                File(binDir, "bash").absolutePath,
                v6.absolutePath,
                safeIn.absolutePath,
                safeOut.absolutePath
            )
            pb.directory(work)

            val env = pb.environment()
            // 只指向内置目录，避免宿主路径干扰（V6 会把 command -v 的结果烘焙进产物）
            env["PATH"] = binDir.absolutePath
            env["LD_LIBRARY_PATH"] = libDir.absolutePath
            env["HOME"] = root.absolutePath
            env["TMPDIR"] = work.absolutePath
            // 关闭安卓门控：产物要在用户自己选的设备上跑，不绑死
            env["ANDROID_GATE"] = "0"
            // AES 模式：已有 openssl，强度最高
            env["CRYPTO_MODE"] = "aes"
            // 防止 V6 自己再调上层 lint（需要 python3，这里没有）
            env["V6_LINT"] = "0"

            when (level) {
                Level.Normal -> {
                    env["JUNK_LEVEL"] = "0"
                    env["DECOY_LEVEL"] = "0"
                    env["KEY_ENTROPY"] = "0"
                }
                Level.Strong -> {
                    env["JUNK_LEVEL"] = "2"
                    env["DECOY_LEVEL"] = "1"
                    env["KEY_ENTROPY"] = "1"
                }
                Level.Extreme -> {
                    env["JUNK_LEVEL"] = "4"
                    env["DECOY_LEVEL"] = "3"
                    env["KEY_ENTROPY"] = "1"
                    // 极端档：拉高 PBKDF2 迭代，启动慢但爆破代价高
                    env["L1_ITER"] = "900000"
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
                    message = "混淆失败（exit $code）" + if (log.isBlank()) "" else "：" + log.lineSequence().lastOrNull { it.isNotBlank() }
                )
            }

            // 产物拷到目标目录
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
            Result(false, message = "执行异常：" + (e.message ?: e.javaClass.simpleName))
        }
    }
}
