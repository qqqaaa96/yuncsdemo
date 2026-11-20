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

        // 用原始字节读，再由我们自己转 UTF-8。
        // readText 遇到非 UTF-8 字节会静默丢内容或抛异常，
        // 这是「不管放什么脚本，产物都一样」的原因之一。
        val rawBytes = try {
            script.readBytes()
        } catch (e: Throwable) {
            return Result(false, message = "读取脚本失败：${e.message}")
        }

        // 诊断日志：让用户看到实际读到了什么
        onLog("读取脚本：" + script.name)
        onLog("文件路径：" + script.absolutePath)
        onLog("文件大小：" + rawBytes.size + " 字节")
        if (rawBytes.isNotEmpty()) {
            val head = rawBytes.take(48).joinToString(" ") {
                "%02X".format(it.toInt() and 0xFF)
            }
            onLog("前 48 字节：" + head)
        }

        if (rawBytes.isEmpty()) {
            return Result(false, message = "脚本文件是空的（0 字节）")
        }

        val scriptText = String(rawBytes, Charsets.UTF_8)

        if (scriptText.isBlank()) {
            return Result(false, message = "脚本内容为空（只有空白字符）")
        }

        onLog("脚本文本长度：" + scriptText.length + " 字符")
        onLog("脚本预览：" + scriptText.take(80).replace("\n", "\\n"))

        // 生成输出文件名
        if (!outDir.exists() && !outDir.mkdirs()) {
            return Result(false, message = "无法创建输出目录")
        }

        val baseName = script.name.substringBeforeLast('.', script.name)
        val workDir = File(ctx.filesDir, "ssc_work")
        workDir.mkdirs()

        // ============================================================
        // 1. RC4 加密脚本（与 SSC 一致：编译时加密，密钥随机生成）
        // ============================================================
        val rc4Key = Rc4.randomKey()
        onLog("RC4 密钥：" + rc4Key)

        val encData = rawBytes.copyOf()
        Rc4.apply(encData, rc4Key)
        onLog("加密完成：" + encData.size + " 字节")

        // 前 16 字节十六进制预览，方便确认加密真的变了
        if (encData.isNotEmpty()) {
            val preview = encData.take(16).joinToString(" ") {
                "%02X".format(it.toInt() and 0xFF)
            }
            onLog("密文前 16 字节：" + preview)
        }

        // 写密文到磁盘（供 .incbin 引用）
        val encFile = File(workDir, "script.enc")
        try {
            encFile.writeBytes(encData)
        } catch (e: Throwable) {
            return Result(false, message = "写入密文失败：${e.message}")
        }
        onLog("密文文件：" + encFile.absolutePath)

        // ============================================================
        // 2. 生成 assembler 文件（用 .incbin 把密文嵌进 ELF）
        //
        // 为什么要用 .incbin 而不是 C 数组：
        //   · C 数组要把二进制转成 0xXX, 文本，体积膨胀 6 倍，
        //     20MB 脚本会变成 120MB 源码，直接 OOM
        //   · .incbin 是原样嵌入，体积与原文一致
        //   · 这正是 SSC 用 ld -r -b binary 在做的事，
        //     但 .incbin 不依赖 objcopy/ld 的 -b 参数，更可靠
        // ============================================================
        val asmFile = File(workDir, "script.S")
        try {
            asmFile.writeText(
                """
                |    .section .rodata
                |    .global _ssc_data_start
                |    .global _ssc_data_end
                |    .balign 16
                |_ssc_data_start:
                |    .incbin "${encFile.absolutePath}"
                |_ssc_data_end:
                |""".trimMargin()
            )
        } catch (e: Throwable) {
            return Result(false, message = "生成汇编失败：${e.message}")
        }
        onLog("生成汇编：" + asmFile.absolutePath + "（" + asmFile.length() + " 字节）")

        // ============================================================
        // 3. 生成包装 C 源码（不含脚本数据，只引用符号）
        // ============================================================
        val srcFile = File(workDir, "wrapper.c")
        try {
            writeWrapperSource(srcFile, scriptText, rc4Key)
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
        // 强制清理旧产物，避免「编译失败但文件还在」时拿到旧结果
        val objFile = File(workDir, "wrapper.o")
        try {
            if (objFile.exists()) objFile.delete()
            if (outFile.exists()) outFile.delete()
        } catch (_: Throwable) {
        }
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

        // ---- 第 1.5 步：把加密数据汇编成目标文件 ----
        onLog("===== 第 1.5 步：嵌入加密数据 =====")
        val dataObj = File(workDir, "script.o")
        try {
            if (dataObj.exists()) dataObj.delete()
        } catch (_: Throwable) {
        }

        val asmArgs = listOf(
            status.clangPath,
            "-target", "aarch64-linux-android33",
            "-c",
            // 必须加：私有目录 noexec，不加会 fork cc1 失败，报
            //   error: expected absolute path: "-cc1"
            "-fintegrated-cc1",
            asmFile.absolutePath,
            "-o", dataObj.absolutePath,
        )
        val asmCmd = if (linker != null) listOf(linker) + asmArgs else asmArgs
        onLog("命令: " + asmCmd.joinToString(" "))

        val asmExit = runCommand(
            cmd = asmCmd,
            env = envMap,
            timeoutSec = 300,
            onLine = { line ->
                log.append(line).append('\n')
                onLog(line)
            }
        )

        if (asmExit != 0 || !dataObj.exists()) {
            return Result(
                ok = false,
                log = log.toString(),
                message = "嵌入加密数据失败（exit=$asmExit）"
            )
        }
        onLog("加密数据已嵌入：" + dataObj.length() + " 字节")

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

        val ldArgs = buildLinkCommand(status, objFile, dataObj, outFile)
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
    /**
     * 生成包装 C 源码。
     *
     * 关键：**不把脚本数据写进 C 源码**。
     *
     * 照 SSC 的真实做法：脚本用 ld -r -b binary 或 .incbin 单独链接进 ELF，
     * wrapper.c 里只用 extern 声明符号就能拿到密文。
     *
     * 好处：
     *   · 20MB 脚本也不会撑爆内存（不用转成 120MB 的 0xXX, 数组）
     *   · 二进制数据不经文本转义，不会损坏
     *   · 与 SSC 行为一致
     */
    private fun writeWrapperSource(dest: File, script: String, rc4Key: String) {
        val bytes = script.toByteArray(Charsets.UTF_8)

        dest.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("/* SSC wrapper generated by csdemo */\n")
            // ============================================================
            // 不 include 任何系统头文件。
            //
            // 原因：用户的 NDK 可能存在头文件残缺（已实际遇到 asm/types.h 缺失），
            // 而且我们的 C 程序只用了极少的 libc 函数，自己声明更可控，
            // 也能避开不同 NDK 版本头文件差异。
            // ============================================================


            // ------------------------------------------------
            // 脚本数据：由 .incbin 单独嵌进 ELF，不写在这里。
            //
            // 符号名由 script.S 里的 .global 定义：
            //   _ssc_data_start / _ssc_data_end
            // ------------------------------------------------
            w.write("extern const unsigned char _ssc_data_start[];\n")
            w.write("extern const unsigned char _ssc_data_end[];\n\n")

            // RC4 密钥（编译期写入，运行时无需外部输入）
            w.write("static const char g_rc4_key[] = \"")
            w.write(rc4Key)
            w.write("\";\n\n")

            w.write("static const unsigned long g_len = ")
            w.write(bytes.size.toString())
            w.write("UL;\n\n")

            // ---- 主体逻辑 ----
            w.write(MAIN_BODY)
        }
    }

    /** 包装程序主体（与脚本内容无关，可直接用常量） */
    private val MAIN_BODY: String = """
/* size_t 在 ARM64 上是 8 字节，与 unsigned long 一致 */
typedef unsigned long size_t;
typedef long ssize_t;

typedef struct _ssc_FILE FILE;
typedef int pid_t;

/* 不用 stddef.h，自己定义 NULL */
#ifndef NULL
#define NULL ((void *)0)
#endif

extern void *malloc(size_t);
extern void free(void *);
extern FILE *fopen(const char *, const char *);
extern size_t fwrite(const void *, size_t, size_t, FILE *);
extern int fclose(FILE *);
extern int snprintf(char *, size_t, const char *, ...);
extern char *getenv(const char *);
extern int chmod(const char *, unsigned int);
extern int unlink(const char *);
extern int getpid(void);
extern pid_t fork(void);
extern int execv(const char *, char *const []);
extern void _exit(int);
extern int waitpid(pid_t, int *, int);
extern int printf(const char *, ...);

/* 自己解 wait 状态，不依赖 sys/wait.h */
#define SSC_WIFEXITED(s)   (((s) & 0x7f) == 0)
#define SSC_WEXITSTATUS(s) (((s) & 0xff00) >> 8)

int main(int argc, char **argv);

/* ============================================================
 * 必须自己写 _start。
 *
 * 为什么不能直接把 -e 设成 main：
 *   main 是普通 C 函数，参数经由 x0/x1 传递。
 *   但 ELF 加载后寄存器是垃圾值，内核只保证：
 *     sp 指向 [argc][argv0][argv1]...[NULL][envp...]
 *   所以需要 _start 从栈上读 argc/argv，再调 main。
 *   直接跳 main 会拿垃圾指针 → Segmentation fault（已实际踩到）。
 *
 * ARM64 调用约定：main 的参数在 x0=argc, x1=argv。
 * ============================================================ */
__attribute__((naked)) void _start(void) {
    __asm__ volatile(
        "ldr x0, [sp]\n"            // x0 = argc
        "add x1, sp, #8\n"          // x1 = &argv[0]
        "bl  main\n"                // 调 main
        "mov x8, #93\n"             // __NR_exit_group
        "svc #0\n"                  // 退出
        "brk #0\n"                  // 保险
    );
}

/* ---- RC4 解密（与 SSC 一致）---- */
static void ssc_rc4(unsigned char *data, unsigned long len, const char *key) {
    unsigned char s[256];
    int i, j, k;
    unsigned long p;
    int klen = 0;
    unsigned char tmp;

    while (key[klen]) klen++;
    if (klen == 0) return;

    /* KSA */
    for (i = 0; i < 256; i++) s[i] = (unsigned char)i;
    j = 0;
    for (i = 0; i < 256; i++) {
        j = (j + s[i] + (unsigned char)key[i % klen]) & 0xFF;
        tmp = s[i]; s[i] = (unsigned char)j; s[j] = tmp;
    }

    /* PRGA */
    i = 0; j = 0;
    for (p = 0; p < len; p++) {
        i = (i + 1) & 0xFF;
        j = (j + s[i]) & 0xFF;
        tmp = s[i]; s[i] = (unsigned char)j; s[j] = tmp;
        k = (s[i] + s[j]) & 0xFF;
        data[p] = data[p] ^ s[k];
    }
}

int main(int argc, char **argv) {
    /* 分配并复制加密数据（保留原始副本用于 RC4 解密）*/
    unsigned char *buf = (unsigned char *)malloc(g_len + 1);
    if (!buf) { printf("malloc failed\n"); return 1; }

    /* 从 .incbin 嵌入的段里拷贝密文 */
    {
        unsigned long i;
        for (i = 0; i < g_len; i++) {
            buf[i] = _ssc_data_start[i];
        }
    }

    /* RC4 解密（与 SSC 一致）*/
    ssc_rc4(buf, g_len, g_rc4_key);
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
        if (!f) { printf("fopen failed\n"); free(buf); return 1; }
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

    if (SSC_WIFEXITED(st)) return SSC_WEXITSTATUS(st);
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
            // 修复 asm/types.h not found
            //
            // 背景：
            //   NDK 里 sysroot/usr/include/asm 本来是个符号链接，
            //   指向 aarch64-linux-android/asm。
            //   但 AIDE 定制的 NDK 包里直接把链接删了，所以这个目录
            //   完全不存在，clang 就报：
            //     fatal error: 'asm/types.h' file not found
            //
            // 解法（三级兼容）：
            //   1. 真目录存在 → 直接用
            //   2. 不存在但有 asm-generic → 自动建 asm 目录并复制头文件
            //   3. 都没有 → 从 asm-generic 兜底
            // ============================================================
            val sysrootDir = File(status.sysroot)
            val incDir = File(sysrootDir, "usr/include")

            // 候选真目录。
            //
            // 不同 NDK 包里 arch 目录名差别很大（已实际遇到 i686-linux-android），
            // 所以不能写死 aarch64，先按优先级试，最后扫一遍 usr/include/*/asm。
            // 目标 arch 的 asm 目录（aarch64）
            val targetAsm = File(incDir, "aarch64-linux-android/asm")

            // 第一优先：目标 arch 自己的 asm（有内容才算）
            // 第二优先：任何其它 arch 的 asm（asm/*.h 跨架构通用，都是转发到 asm-generic）
            val priority = listOf(
                "aarch64-linux-android/asm",
                "aarch64-linux-android-33/asm",
                "i686-linux-android/asm",
                "arm-linux-androideabi/asm",
                "armv7a-linux-androideabi/asm",
                "asm",
            )
            val realAsm = priority
                .map { File(incDir, it) }
                .firstOrNull { it.isDirectory && it.listFiles()?.isNotEmpty() == true }

            if (realAsm != null) {
                // 找到了有内容的 asm 目录
                args.add("-I")
                args.add(realAsm.parentFile!!.absolutePath)

                // ============================================================
                // 关键补丁：如果目标 arch 自己的 asm 目录不存在或为空，
                // 把找到的那份复制过去。
                //
                // 已实际遇到：NDK 里 aarch64-linux-android/ 只有 41.65K（残缺），
                // 而 i686-linux-android/asm/ 有 66 个完整头文件。
                // asm/*.h 都只是转发到 asm-generic，所以跨架构借用是安全的。
                // ============================================================
                if (realAsm != targetAsm &&
                    (!targetAsm.isDirectory || targetAsm.listFiles()?.isEmpty() != false)
                ) {
                    try {
                        targetAsm.mkdirs()
                        realAsm.listFiles()?.forEach { src ->
                            if (src.isFile) {
                                val dst = File(targetAsm, src.name)
                                if (!dst.exists()) src.copyTo(dst, overwrite = true)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            } else {
                // 情况 2/3：没有 asm 目录，从 asm-generic 自动补齐
                val asmGeneric = File(incDir, "asm-generic")
                if (asmGeneric.isDirectory) {
                    val fakeAsm = File(incDir, "_ssc_asm")
                    try {
                        if (!fakeAsm.exists()) fakeAsm.mkdirs()
                        // 把 asm-generic 里的头文件全部复制过来作为 asm/*
                        asmGeneric.listFiles()?.forEach { src ->
                            if (src.isFile) {
                                val dst = File(fakeAsm, src.name)
                                if (!dst.exists()) src.copyTo(dst, overwrite = true)
                            }
                        }
                        // 关键：建一个临时 include 根，里面放 asm -> _ssc_asm
                        val fakeRoot = File(sysrootDir, "usr/_ssc_inc")
                        fakeRoot.mkdirs()
                        val asmLink = File(fakeRoot, "asm")
                        if (!asmLink.exists()) {
                            try {
                                java.nio.file.Files.createSymbolicLink(
                                    asmLink.toPath(), fakeAsm.toPath()
                                )
                            } catch (_: Throwable) {
                                // 不支持符号链接时，直接复制一份到 asm/
                                asmLink.mkdirs()
                                fakeAsm.listFiles()?.forEach { src ->
                                    if (src.isFile) {
                                        src.copyTo(File(asmLink, src.name), overwrite = true)
                                    }
                                }
                            }
                        }
                        args.add("-I")
                        args.add(fakeRoot.absolutePath)
                    } catch (_: Throwable) {
                    }
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
    private fun buildLinkCommand(
        status: NdkEnv.Status,
        obj: File,
        dataObj: File,
        out: File,
    ): List<String> {
        val sysroot = if (status.sysroot.isNotBlank()) status.sysroot else ""
        val args = ArrayList<String>()

        // ============================================================
        // 关键：必须显式指定入口点。
        //
        // 正常情况下 _start 由 crtbegin_dynamic.o 提供，但我们没链 crt 文件，
        // ld.lld 会报：
        //   warning: cannot find entry symbol _start; not setting start address
        // 结果产物是个「没有入口点」的废 ELF，一运行就段错误。
        //
        // 直接 -e main 让入口指向 main，绕开 crt 依赖。
        // ============================================================
        // PIE 必须写在最前面：Android 加载器只接受 PIE 可执行文件，
        // 缺了它运行时会报：
        //   Android only supports position-independent executables (-fPIE)
        args.add("-pie")

        // 入口点必须是 _start（自己写的汇编入口），不能是 main。
        // main 直接当入口会因 argc/argv 未初始化而段错误。
        args.add("-e")
        args.add("_start")

        args.add("--gc-sections")
        args.add("-z")
        args.add("noexecstack")
        args.add("-o")
        args.add(out.absolutePath)
        args.add(obj.absolutePath)
        // 加密数据目标文件（含 _ssc_data_start / _ssc_data_end）
        args.add(dataObj.absolutePath)

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

        // 链接 libc（-ldl 在 Android 5+ 已并入 libc，不能单独链）
        args.add("-lc")

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
