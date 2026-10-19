package sy.great.toolbox.tools

import android.content.Context
import java.io.File

/**
 * Shell 脚本混淆（ShellVMP V6）。
 *
 * 为什么不能用 assets 解压到 filesDir：
 *   Android 10+ 上，App 数据目录（files / code_cache / noBackupFiles）
 *   在多数设备上都挂载为 noexec，chmod 755 也没用，
 *   执行直接报 error=13 Permission denied。
 *
 * 唯一保证可执行的位置是 nativeLibraryDir：
 *   系统从 APK 的 lib/<abi>/ 解压，挂载为 exec。
 *   代价是只接受 lib*.so 命名，而且是只读的。
 *
 * 所以方案是：
 *   1. 二进制以 libshbash.so / libshgzip.so … 的形式放进 jniLibs
 *      依赖库 libreadline.so.8 → libreadline.so.8.so 同样处理
 *   2. 运行时在可写的私有目录建一堆符号链接：
 *        <work>/bin/bash   → nativeLibraryDir/libshbash.so
 *        <work>/lib/libreadline.so.8 → nativeLibraryDir/libreadline.so.8.so
 *      符号链接只要求目标可执行，自己不需要 exec 权限
 *   3. PATH 指向 <work>/bin，LD_LIBRARY_PATH 指向 <work>/lib
 */
object ShellObfuscator {

    /** 在 jniLibs 里的文件名 → 运行时还原的命令名 */
    private val BIN_MAP = linkedMapOf(
        "libshbash.so" to "bash",
        "libshopenssl.so" to "openssl",
        "libshgzip.so" to "gzip",
        "libshsed.so" to "sed",
        "libshcut.so" to "cut",
        "libshtr.so" to "tr",
        "libshgrep.so" to "grep",
        "libshawk.so" to "awk",
        "libshbase64.so" to "base64",
        "libshod.so" to "od",
        "libshsha256sum.so" to "sha256sum",
        "libshsha512sum.so" to "sha512sum"
    )

    /** 依赖库：jniLibs 名 → 运行时名（必须与 ELF 里 DT_NEEDED 完全一致） */
    private val LIB_MAP = linkedMapOf(
        "libandroid-support.so" to "libandroid-support.so",
        "libandroid-selinux.so" to "libandroid-selinux.so",
        // 带版本号的库不能在 jniLibs 里用原名：AGP 只认 lib{nama}.so，
        // libreadline.so.8.so 会被误当成名字里带 .so.8 的文件，
        // 打包时行为不可靠。所以改成无歧义的名字，运行时再链回真名。
        "libshreadline8.so" to "libreadline.so.8",
        "libshncursesw6.so" to "libncursesw.so.6",
        "libiconv.so" to "libiconv.so",
        "libgmp.so" to "libgmp.so",
        "libshcrypto3.so" to "libcrypto.so.3",
        "libpcre2-8.so" to "libpcre2-8.so",
        "libmpfr.so" to "libmpfr.so"
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

    /** 准备状态 */
    private class Env(
        val root: File,
        val binDir: File,
        val libDir: File,
        val nativeDir: String
    )

    /**
     * 建立运行环境（创建符号链接 + 解压 v6.sh）。
     *
     * @return 失败返回 null
     */
    private fun prepare(ctx: Context, force: Boolean = false): Env? {
        val nativeDir = ctx.applicationInfo.nativeLibraryDir ?: return null
        val root = File(ctx.filesDir, "shrun")
        val binDir = File(root, "bin")
        val libDir = File(root, "lib")

        val bashLink = File(binDir, "bash")
        if (!force && bashLink.exists()) {
            return Env(root, binDir, libDir, nativeDir)
        }

        return try {
            if (force) root.deleteRecursively()
            binDir.mkdirs()
            libDir.mkdirs()

            // ---- 创建符号链接 ----
            val ln = File("/system/bin/ln")
            fun link(target: File, linkAt: File) {
                linkAt.delete()
                // 优先用系统 ln，失败再试 Runtime.exec
                val ok = try {
                    val p = ProcessBuilder(
                        ln.absolutePath, "-sf",
                        target.absolutePath, linkAt.absolutePath
                    ).start()
                    p.waitFor() == 0 && linkAt.exists()
                } catch (_: Throwable) {
                    false
                }
                if (!ok && linkAt.exists()) {
                    // 退路：拷贝（但拷贝出来的可能不可执行，后面自检会暴露）
                }
            }

            for ((soName, cmdName) in BIN_MAP) {
                val target = File(nativeDir, soName)
                if (!target.exists()) return null
                link(target, File(binDir, cmdName))
            }
            for ((soName, realName) in LIB_MAP) {
                val target = File(nativeDir, soName)
                if (!target.exists()) return null
                link(target, File(libDir, realName))
            }

            // ---- 解压 v6.sh ----
            val v6 = File(root, "v6.sh")
            ctx.assets.open("shbin/v6.sh").use { input ->
                v6.outputStream().use { out -> input.copyTo(out) }
            }

            if (!bashLink.exists()) return null
            Env(root, binDir, libDir, nativeDir)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 自检：能否真的跑起来内置 bash。
     *
     * @return 空串表示正常，否则返回失败原因
     */
    fun selfCheck(ctx: Context): String {
        val env = prepare(ctx) ?: return "符号链接准备失败（nativeLibraryDir 缺少 libsh*.so）"

        return try {
            val pb = ProcessBuilder(
                File(env.binDir, "bash").absolutePath, "-c",
                "echo READY_OK; uname -m"
            )
            pb.directory(env.root)
            val e = pb.environment()
            e["PATH"] = env.binDir.absolutePath
            e["LD_LIBRARY_PATH"] = env.libDir.absolutePath
            e["HOME"] = env.root.absolutePath
            e["TMPDIR"] = env.root.absolutePath
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            if (code == 0 && out.contains("READY_OK")) ""
            else "bash 启动失败（exit $code）：$out"
        } catch (e: Throwable) {
            "bash 启动异常：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 执行混淆。
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

        val env = prepare(ctx) ?: return Result(false, message = "运行环境准备失败")
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
            val pb = ProcessBuilder(
                File(env.binDir, "bash").absolutePath,
                v6.absolutePath,
                safeIn.absolutePath,
                safeOut.absolutePath
            )
            pb.directory(work)

            val e = pb.environment()
            e["PATH"] = env.binDir.absolutePath
            e["LD_LIBRARY_PATH"] = env.libDir.absolutePath
            e["HOME"] = env.root.absolutePath
            e["TMPDIR"] = work.absolutePath
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
            Result(false, message = "执行异常：" + (e.message ?: e.javaClass.simpleName))
        }
    }
}
