package sy.great.toolbox.tools

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * SSC 脚本编译器：把 Shell 脚本编译成可执行 ELF。
 *
 * 原理（与 SSC 官方一致）：
 *   SSC 不是把 Shell 语义编译成机器码的编译器。
 *   它的做法是生成一个 C++ 包装源码，里面内嵌脚本内容，
 *   编译后得到的是一个「脚本启动器 ELF」：
 *     运行时把内嵌脚本写到临时文件，然后 exec 真正的 shell 执行。
 *
 * 所以产物性质是：
 *   · 脚本内容被编译进 ELF 的 .rodata（静态分析看不到明文脚本）
 *   · 运行时仍需系统有 sh（Android 都自带 /system/bin/sh，没问题）
 *
 * 编译链路：
 *   script.sh → 生成 wrapper.c → clang -target aarch64-linux-android33
 *             → 输出 PIE 可执行文件
 *
 * 完全跑在 App 私有目录，不需要 root / ADB。
 */
object SscCompiler {

    data class Result(
        val ok: Boolean,
        val outputPath: String = "",
        val sizeBytes: Long = 0,
        val log: String = "",
        val message: String = "",
    )

    /**
     * 编译脚本。
     *
     * @param ctx    Context
     * @param script 用户脚本文件
     * @param outDir 输出目录
     * @param onLog  日志回调（逐行）
     */
    fun compile(
        ctx: Context,
        script: File,
        outDir: File,
        onLog: (String) -> Unit = {},
    ): Result {
        val status = NdkEnv.check(ctx)
        if (!status.ready) {
            return Result(false, message = "编译环境未就绪：${status.reason}")
        }

        val scriptText = try {
            script.readText()
        } catch (e: Throwable) {
            return Result(false, message = "读取脚本失败：${e.message}")
        }

        if (scriptText.isBlank()) {
            return Result(false, message = "脚本为空")
        }

        // 生成输出文件名
        if (!outDir.exists() && !outDir.mkdirs()) {
            return Result(false, message = "无法创建输出目录")
        }

        val baseName = script.name.substringBeforeLast('.', script.name)
        val workDir = File(ctx.filesDir, "ssc_work")
        workDir.mkdirs()

        // ---- 生成 C++ 包装源码 ----
        //
        // 关键：必须流式写文件，不能先在内存里拼好整个 String。
        // 脚本 2MB 转成 0xXX, 数组约 12MB，再转 String 再转 ByteArray，
        // 瞬间占 30MB+，在手机上直接 OOM（已实际踩到两次）。
        val srcFile = File(workDir, "wrapper.c")
        try {
            writeWrapperSource(srcFile, scriptText)
        } catch (e: Throwable) {
            return Result(false, message = "生成源码失败：${e.message}")
        }
        onLog("生成包装源码：" + srcFile.absolutePath + "（" + srcFile.length() + " 字节）")

        val outFile = File(outDir, baseName + ".elf")
        val linker = NdkEnv.findLinker()
        onLog("linker: " + (linker ?: "未找到，尝试直接执行"))

        // clang 需要可写的临时目录。
        // 必须用 App 私有目录，/data/local/tmp 普通 App 写不了。
        val clangTmp = File(ctx.filesDir, "ssc_tmp")
        try {
            if (clangTmp.exists()) clangTmp.deleteRecursively()
            clangTmp.mkdirs()
        } catch (_: Throwable) {
        }
        onLog("临时目录: " + clangTmp.absolutePath)

        // ============================================================
        // 分两步编译：先 -c 出 .o，再单独调用 ld.lld 链接
        //
        // 为什么不能一步到底：
        //   clang 驱动在链接阶段会 fork + exec ld，而我们的环境是
        //   私有目录（noexec）+ linker64 启动，子进程根本起不来。
        //   拆成两步后，每一步都是单个进程，不依赖 fork。
        // ============================================================
        val objFile = File(workDir, "wrapper.o")
        val envMap = buildEnv(status, clangTmp)

        // ---- 第 1 步：编译成目标文件 ----
        onLog("===== 第 1 步：编译 =====")
        val ccArgs = buildCompileCommand(status, srcFile, objFile)
        val ccCmd = if (linker != null) listOf(linker) + ccArgs else ccArgs
        onLog("命令: " + ccCmd.joinToString(" "))

        val log = StringBuilder()
        val ccExit = runCommand(
            cmd = ccCmd,
            env = envMap,
            timeoutSec = 300,
            onLine = { line ->
                log.append(line).append('\n')
                onLog(line)
            }
        )

        if (ccExit != 0 || !objFile.exists()) {
            return Result(
                ok = false,
                log = log.toString(),
                message = "编译失败（编译阶段 exit=$ccExit）"
            )
        }
        onLog("编译完成：" + objFile.length() + " 字节")

        // ---- 第 2 步：链接成 ELF ----
        onLog("===== 第 2 步：链接 =====")
        val ldPath = findLinkerTool(status)
        if (ldPath == null) {
            return Result(
                ok = false,
                log = log.toString(),
                message = "没找到 ld.lld 链接器"
            )
        }
        onLog("链接器: " + ldPath)

        val ldArgs = buildLinkCommand(status, objFile, outFile)
        val ldCmd = if (linker != null) listOf(linker) + listOf(ldPath) + ldArgs
                    else listOf(ldPath) + ldArgs
        onLog("命令: " + ldCmd.joinToString(" "))

        val ldExit = runCommand(
            cmd = ldCmd,
            env = envMap,
            timeoutSec = 180,
            onLine = { line ->
                log.append(line).append('\n')
                onLog(line)
            }
        )
        val exitCode = ldExit

        if (exitCode != 0 || !outFile.exists()) {
            return Result(
                ok = false,
                log = log.toString(),
                message = "编译失败（exit=$exitCode）"
            )
        }

        // ---- 恢复可执行权限 ----
        try {
            outFile.setExecutable(true, false)
        } catch (_: Throwable) {
        }

        // ---- 验证是真 ELF ----
        val isElf = try {
            outFile.inputStream().use { i ->
                val b = ByteArray(4)
                i.read(b) == 4 && b[0] == 0x7F.toByte() &&
                    b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() &&
                    b[3] == 'F'.code.toByte()
            }
        } catch (_: Throwable) {
            false
        }

        if (!isElf) {
            return Result(false, log = log.toString(), message = "产物不是有效 ELF")
        }

        return Result(
            ok = true,
            outputPath = outFile.absolutePath,
            sizeBytes = outFile.length(),
            log = log.toString(),
            message = "编译成功"
        )
    }

    // ============================================================
    // 包装源码生成
    // ============================================================

    /**
     * 生成 C 包装源码。
     *
     * 设计：
     *   1. 脚本内容以 unsigned char 数组内嵌，编译进 .rodata
     *      → 静态字符串搜索看不到明文脚本
     *   2. 运行时写到私有目录临时文件，然后 fork+exec /system/bin/sh
     *   3. 脚本数据做简单异或，抬高一点静态分析门槛
     */
    private val HEX_UPPER = "0123456789ABCDEF".toCharArray()

    /**
     * 流式写出包装源码。
     *
     * 为什么不用 StringBuilder：
     *   脚本 2MB → 0xXX, 数组约 12MB 字符 → StringBuilder 再转 String
     *   再转 ByteArray，中途同时存在 3 份，手机直接 OOM。
     *
     * 现在：边算边写到文件，内存占用固定 64KB。
     */
    private fun writeWrapperSource(dest: File, script: String) {
        val bytes = script.toByteArray(Charsets.UTF_8)

        dest.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("/* SSC wrapper generated by csdemo */\n")
            w.write("#include <stdio.h>\n")
            w.write("#include <stdlib.h>\n")
            w.write("#include <string.h>\n")
            w.write("#include <unistd.h>\n")
            w.write("#include <sys/stat.h>\n")
            w.write("#include <sys/wait.h>\n")
            w.write("#include <errno.h>\n\n")

            // ---- 脚本数据 - ---
            // 用 unsigned char 数组，但每行 32 字节，体积约为原脚本的 6 倍。
            // 这是可接受的：clang 处理 6 倍大小的数组比 30 倍小得多，
            // 而且我们是流式写，不占内存。
            w.write("static const unsigned char g_data[] = {\n")
            val hex = HEX_UPPER
            val line = StringBuilder(32 * 6 + 8)
            for (i in bytes.indices) {
                val x = (bytes[i].toInt() xor 0x5A) and 0xFF
                line.setLength(0)
                line.append(" 0x").append(hex[x ushr 4]).append(hex[x and 0x0F]).append(',')
                w.write(line.toString())
                if ((i + 1) % 16 == 0) w.write("\n")
            }
            w.write("\n    0x00\n};\n\n")
            w.write("static const unsigned long g_len = ")
            w.write(bytes.size.toString())
            w.write("UL;\n\n")

            // ---- 主体逻辑 ----
            w.write(MAIN_BODY)
        }
    }

    /** 包装程序主体（与脚本内容无关，可直接用常量） */
    private val MAIN_BODY: String = """
int main(int argc, char **argv) {
    /* 解密脚本 */
    unsigned char *buf = (unsigned char *)malloc(g_len + 1);
    if (!buf) { perror("malloc"); return 1; }
    for (unsigned long i = 0; i < g_len; i++) {
        buf[i] = g_data[i] ^ 0x5A;
    }
    buf[g_len] = 0;

    /* 写到临时文件 */
    const char *tmpdir = getenv("TMPDIR");
    if (!tmpdir) tmpdir = "/data/local/tmp";
    char path[512];
    snprintf(path, sizeof(path), "%s/.ssc_%d.sh", tmpdir, (int)getpid());

    FILE *f = fopen(path, "wb");
    if (!f) {
        snprintf(path, sizeof(path), "./.ssc_%d.sh", (int)getpid());
        f = fopen(path, "wb");
        if (!f) { perror("fopen"); free(buf); return 1; }
    }
    fwrite(buf, 1, g_len, f);
    fclose(f);
    free(buf);
    chmod(path, 0700);

    /* 组装参数：sh script [args...] */
    int total = argc + 1;
    char **args = (char **)malloc(sizeof(char *) * (total + 1));
    if (!args) { unlink(path); return 1; }
    args[0] = (char *)"/system/bin/sh";
    args[1] = path;
    for (int i = 1; i < argc; i++) args[i + 1] = argv[i];
    args[total] = NULL;

    pid_t pid = fork();
    if (pid == 0) {
        execv(args[0], args);
        _exit(127);
    }

    int st = 0;
    if (pid > 0) waitpid(pid, &st, 0);
    unlink(path);
    free(args);

    if (WIFEXITED(st)) return WEXITSTATUS(st);
    return 1;
}
"""

    // ============================================================
    // 命令构建与执行
    // ============================================================

    /**
     * 第 1 步：编译成目标文件（.o）。
     *
     * -c 只做编译不做链接，避免 clang 在最后一步 fork ld。
     */
    private fun buildCompileCommand(status: NdkEnv.Status, src: File, obj: File): List<String> {
        val clang = status.clangPath
        val args = ArrayList<String>()
        args.add(clang)
        args.add("-target")
        args.add("aarch64-linux-android33")
        args.add("-O2")
        args.add("-fPIE")
        args.add("-c")            // 只编译

        // 禁止 clang 拆分 cc1 子进程：
        // 私有目录 noexec，fork 出来的 cc1 起不来，会报
        //   error: expected absolute path: "-cc1"
        args.add("-fintegrated-cc1")

        args.add("-o")
        args.add(obj.absolutePath)
        args.add(src.absolutePath)

        // sysroot（如果有）
        if (status.sysroot.isNotBlank()) {
            args.add("--sysroot=" + status.sysroot)

            // ============================================================
            // 关键：修复 asm/types.h not found
            //
            // NDK 的 sysroot/usr/include/asm 是个符号链接，指向
            //   aarch64-linux-android/asm
            // 但我们用 Kotlin 手写的 tar/zip 解压器不创建符号链接
            // （tar 里的 typeFlag '2' 只跳过数据区），所以这个目录不存在，
            // clang 就报：
            //   fatal error: 'asm/types.h' file not found
            //
            // 修法：直接把真实目录加进 include 搜索路径。
            // ============================================================
            val sysrootDir = File(status.sysroot)
            val archAsmCandidates = listOf(
                File(sysrootDir, "usr/include/aarch64-linux-android/asm"),
                File(sysrootDir, "usr/include/aarch64-linux-android-33/asm"),
            )
            for (asmDir in archAsmCandidates) {
                if (asmDir.isDirectory) {
                    // 把父目录加进去，这样 #include <asm/types.h> 能命中
                    args.add("-I")
                    args.add(asmDir.parentFile!!.absolutePath)
                    break
                }
            }
        }

        // ============================================================
        // 关键：让 clang 能找到自己的内部子工具
        //
        // 用 linker64 启动 clang 时，clang 自己 fork 出来的子进程
        // （clang -cc1 / ld / as）无**在私有目录执行，直接报：
        //   error: expected absolute path: "-cc1"
        //
        // -B 告诉 clang：去这个目录找 ld / as / 相关工具
        // -resource-dir 告诉 clang：去哪找内置头文件
        // ============================================================
        val clangBinDir = File(status.clangPath).parentFile
        if (clangBinDir != null) {
            args.add("-B")
            args.add(clangBinDir.absolutePath)

            // NDK 的 resource dir 通常在 bin/../lib/clang/<version>
            val resDir = findResourceDir(clangBinDir)
            if (resDir != null) {
                args.add("-resource-dir")
                args.add(resDir.absolutePath)
            }
        }
        return args
    }

    /**
     * 第 2 步：用 ld.lld 链接成 PIE 可执行文件。
     *
     * 参数按 NDK 默认可执行文件链接方式给出（与 clang 驱动生成的一致）：
     *   -pie -static-libgcc -dynamic-linker /system/bin/linker64
     */
    private fun buildLinkCommand(status: NdkEnv.Status, obj: File, out: File): List<String> {
        val sysroot = if (status.sysroot.isNotBlank()) status.sysroot else ""
        val args = ArrayList<String>()

        args.add("-pie")
        args.add("--gc-sections")
        args.add("-z")
        args.add("noexecstack")
        args.add("-z")
        args.add("relro")
        args.add("-z")
        args.add("now")
        args.add("-o")
        args.add(out.absolutePath)
        args.add(obj.absolutePath)

        // 动态链接器（Android 固定路径）
        args.add("--dynamic-linker")
        args.add("/system/bin/linker64")

        // libc / libdl
        if (sysroot.isNotBlank()) {
            val libDir = File(sysroot, "usr/lib/aarch64-linux-android/33")
            val libDirAlt = File(sysroot, "usr/lib/aarch64-linux-android")
            val libRoot = when {
                libDir.isDirectory -> libDir
                libDirAlt.isDirectory -> libDirAlt
                else -> null
            }
            if (libRoot != null) {
                args.add("-L")
                args.add(libRoot.absolutePath)
            }
            args.add("-L")
            args.add(File(sysroot, "usr/lib").absolutePath)
        }

        // 只链接必须的系统库
        args.add("-lc")
        args.add("-ldl")

        return args
    }

    /** 找 ld.lld / ld 链接器 */
    private fun findLinkerTool(status: NdkEnv.Status): String? {
        val binDir = File(status.clangPath).parentFile ?: return null
        val candidates = listOf(
            "ld.lld", "ld", "lld",
            "aarch64-linux-android33-clang",
        )
        for (name in candidates) {
            val f = File(binDir, name)
            if (f.exists() && f.isFile && f.length() > 1024) return f.absolutePath
        }
        return null
    }

    /**
     * 找 clang 的 resource dir（内置头文件目录，如 stddef.h）。
     *
     * 典型位置：
     *   toolchains/llvm/prebuilt/<host>/lib/clang/21
     */
    private fun findResourceDir(binDir: File): File? {
        return try {
            val prebuilt = binDir.parentFile ?: return null
            val clangLib = File(prebuilt, "lib/clang")
            if (!clangLib.isDirectory) return null
            // 取版本号最大的子目录
            return clangLib.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.name }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 构建 clang 运行环境。
     *
     * 关键：临时目录必须指向 App 私有目录。
     * 之前写的 /data/local/tmp 是 shell 用户的目录，
     * 普通 App 根本写不进去，clang 会报：
     *   unable to make temporary file: Permission denied
     *
     * 也不能用 getExternalFilesDir（部分机型也有权限问题）。
     * 用 filesDir/tmp：确定可写、不受 Scoped Storage 限制。
     */
    private fun buildEnv(status: NdkEnv.Status, tmpDir: File): Map<String, String> {
        val env = HashMap<String, String>()
        env["PATH"] = "/system/bin:/system/xbin"
        env["HOME"] = tmpDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["TMP"] = tmpDir.absolutePath
        env["TEMP"] = tmpDir.absolutePath

        // clang 需要知道自己在哪，否则找不到同目录的 ld / as
        val clangBin = File(status.clangPath).parentFile
        if (clangBin != null) {
            env["PATH"] = clangBin.absolutePath + ":" + env["PATH"]
        }

        if (status.sysroot.isNotBlank()) {
            env["SYSROOT"] = status.sysroot
        }
        return env
    }

    /**
     * 执行命令。
     *
     * 关键：App 私有目录是 noexec，不能直接执行 clang。
     * 用 linker64 拉起：linker64 <clang路径> <参数...>
     * 这是 ShellObfuscator 已经验证过的做法。
     */
    private fun runCommand(
        cmd: List<String>,
        env: Map<String, String>,
        timeoutSec: Int,
        onLine: (String) -> Unit,
    ): Int {
        return try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            pb.environment().putAll(env)

            val p = pb.start()
            val finished = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                onLine("超时，已终止：${timeoutSec}s")
                return -1
            }

            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { onLine(it) }
            }
            p.exitValue()
        } catch (e: Throwable) {
            onLine("执行异常：${e.message ?: e.javaClass.simpleName}")
            -1
        }
    }
}
