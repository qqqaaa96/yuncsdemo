package sy.great.toolbox.tools

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * V7 ELF 产物构建（Termux 接力模式）。
 *
 * 为什么不内置编译器：
 *   V7 的 elf 线需要用 clang 编译 elfrun_gen.c，
 *   Termux 的 clang + ndk-multilib 合计约 580MB。
 *   把这个塞进 APK 不现实（用户下不下来、装不上）。
 *
 * 所以 V7 的处理方式是“导出构建包”：
 *   1. App 把 v6/v7 脚本、用户脚本、一键构建脚本打包成 zip
 *   2. 用户把 zip 导到 Download
 *   3. 在 Termux 里一行命令解压并构建
 *
 * V6 不依赖编译器，直接在 App 内完成（见 ShellObfuscator）。
 */
object V7Builder {

    data class Result(
        val ok: Boolean,
        val zipPath: String = "",
        val sizeBytes: Long = 0,
        val message: String = ""
    )

    /**
     * 生成 V7 构建包。
     *
     * @param script 用户脚本
     * @param outDir 输出目录
     */
    fun buildPackage(
        ctx: Context,
        script: File,
        outDir: File,
        selfContained: Boolean = true
    ): Result {
        if (!script.exists()) return Result(false, message = "源文件不存在")
        if (script.length() == 0L) return Result(false, message = "源文件为空")

        return try {
            outDir.mkdirs()
            val zip = File(outDir, "v7_build.zip")
            if (zip.exists()) zip.delete()

            ZipOutputStream(FileOutputStream(zip).buffered()).use { zos ->
                // ---- V6 混淆器 ----
                addAsset(ctx, zos, "shellvmp/v6/shell_script_obfuscator_v6.sh", "v6/shell_script_obfuscator_v6.sh")

                // ---- V7 源码 ----
                for (name in listOf("v7_build.sh", "elfrun.c", "blobgen.c", "crypto_core.h", "v7_wipe.h", "v7_wrap.sh")) {
                    addAsset(ctx, zos, "shellvmp/v7/$name", "v7/$name")
                }

                // ---- 用户脚本 ----
                zos.putNextEntry(ZipEntry("input.sh"))
                zos.write(script.readBytes())
                zos.closeEntry()

                // ---- 一键构建脚本 ----
                zos.putNextEntry(ZipEntry("build.sh"))
                zos.write(buildScript(selfContained).toByteArray())
                zos.closeEntry()
            }

            Result(true, zip.absolutePath, zip.length(), "成功")
        } catch (e: Throwable) {
            Result(false, message = "打包失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun addAsset(ctx: Context, zos: ZipOutputStream, assetPath: String, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        ctx.assets.open(assetPath).use { it.copyTo(zos) }
        zos.closeEntry()
    }

    /**
     * 生成给 Termux 用的一键构建脚本。
     */
    private fun buildScript(selfContained: Boolean): String {
        val sb = StringBuilder()
        sb.append("#!/data/data/com.termux/files/usr/bin/bash\n")
        sb.append("# V7 ELF 构建脚本（由 csdemo 生成）\n")
        sb.append("# 用法：bash build.sh\n")
        sb.append("set -e\n\n")

        sb.append("cd \"$(dirname \"$0\")\"\n")
        sb.append("WORK=\"$PWD/out\"\n")
        sb.append("mkdir -p \"$WORK/tmp\"\n\n")

        // 环境检查
        sb.append("echo '[1/3] 检查依赖...'\n")
        sb.append("command -v clang >/dev/null 2>&1 || {\n")
        sb.append("  echo '缺少 clang，请先执行：pkg install -y clang ndk-multilib'\n")
        sb.append("  exit 1\n")
        sb.append("}\n")
        sb.append("[ -f \"$PREFIX/opt/ndk-multilib/aarch64-linux-android/lib/libc.a\" ] || \\\n")
        sb.append("  [ -f \"$PREFIX/lib/libc.a\" ] || {\n")
        sb.append("  echo '缺少静态库，请先执行：pkg install -y ndk-multilib'\n")
        sb.append("  exit 1\n")
        sb.append("}\n\n")

        // 构建
        sb.append("echo '[2/3] 构建 ELF...'\n")
        sb.append("export PATH=\"$PREFIX/bin:$PATH\"\n")
        sb.append("export TMPDIR=\"$WORK/tmp\"\n\n")
        sb.append("V7_MODE=elf \\\n")
        sb.append("V7_CC=clang \\\n")
        sb.append("V7_OBF=\"$PWD/v6/shell_script_obfuscator_v6.sh\" \\\n")
        if (selfContained) {
            // 内嵌 bash：产物在裸环境也能跑，但体积大一些
            sb.append("V7_SELF=1 \\\n")
        }
        sb.append("ANDROID_GATE=0 \\\n")
        sb.append("bash ./v7/v7_build.sh \"$PWD/input.sh\" \"$WORK/protected.elf\"\n\n")

        // 输出
        sb.append("echo '[3/3] 完成'\n")
        sb.append("ls -la \"$WORK/protected.elf\"\n")
        sb.append("file \"$WORK/protected.elf\"\n")
        sb.append("echo\n")
        sb.append("echo \"产物路径：$WORK/protected.elf\"\n")
        sb.append("echo '运行： unset LD_PRELOAD && ./out/protected.elf'\n")

        return sb.toString()
    }
}
