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
            // ============================================================
            // 不 include 任何系统头文件。
            //
            // 原因：用户的 NDK 可能存在头文件残缺（已实际遇到 asm/types.h 缺失），
            // 而且我们的 C 程序只用了极少的 libc 函数，自己声明更可控，
            // 也能避开不同 NDK 版本头文件差异。
            // ============================================================


            // ---- 脚本数据（ChaCha20 加密）----
            //
            // 之前用单字节异或 0x5A，实测等于没加密：
            //   密文 ^ 0x5A 一句就能还原，任何逆向工具秒解。
            //
            // 现在用 ChaCha20：
            //   · 每个编译生成全新的随机 key 和 nonce
            //   · 加密后的数据与随机字节无法区分
            //   · 即使拿到 ELF 也无法直接看出脚本内容
            val cipher = ChaCha20.encrypt(bytes)

            w.write("static const unsigned char g_data[] = {\n")
            val hex = HEX_UPPER
            val line = StringBuilder(32 * 6 + 8)
            val enc = cipher.data
            for (i in enc.indices) {
                val x = enc[i].toInt() and 0xFF
                line.setLength(0)
                line.append(" 0x").append(hex[x ushr 4]).append(hex[x and 0x0F]).append(',')
                w.write(line.toString())
                if ((i + 1) % 16 == 0) w.write("\n")
            }
            w.write("\n    0x00\n};\n\n")

            // 密钥 / nonce / 原文长度
            w.write("static const unsigned char g_key[32] = {")
            cipher.key.forEachIndexed { i, b ->
                if (i > 0) w.write(",")
                w.write("0x%02X".format(b.toInt() and 0xFF))
            }
            w.write("};\n")

            w.write("static const unsigned char g_nonce[12] = {")
            cipher.nonce.forEachIndexed { i, b ->
                if (i > 0) w.write(",")
                w.write("0x%02X".format(b.toInt() and 0xFF))
            }
            w.write("};\n")

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

/* ---- ChaCha20 解密（与 Kotlin 侧完全一致）---- */
#define ROTL32(v, c) ((unsigned int)(((v) << (c)) | ((v) >> (32 - (c)))))

static void ssc_quarter(unsigned int *s, int a, int b, int c, int d) {
    s[a] += s[b]; s[d] = ROTL32(s[d] ^ s[a], 16);
    s[c] += s[d]; s[b] = ROTL32(s[b] ^ s[c], 12);
    s[a] += s[b]; s[d] = ROTL32(s[d] ^ s[a], 8);
    s[c] += s[d]; s[b] = ROTL32(s[b] ^ s[c], 7);
}

/* 解密 g_data -> out（长度 len） */
static void ssc_decrypt(unsigned char *out, const unsigned char *in,
                        unsigned long len, const unsigned char *key,
                        const unsigned char *nonce) {
    unsigned int st[16], x[16], ks[64];
    unsigned long off = 0;
    unsigned int counter = 1;
    int i, r;

    st[0] = 0x61707865;
    st[1] = 0x3320646e;
    st[2] = 0x79622d32;
    st[3] = 0x6b206574;

    for (i = 0; i < 8; i++) {
        st[4 + i] = (unsigned int)key[i * 4] |
                    ((unsigned int)key[i * 4 + 1] << 8) |
                    ((unsigned int)key[i * 4 + 2] << 16) |
                    ((unsigned int)key[i * 4 + 3] << 24);
    }

    while (off < len) {
        st[12] = counter;
        st[13] = (unsigned int)nonce[0] | ((unsigned int)nonce[1] << 8) |
                 ((unsigned int)nonce[2] << 16) | ((unsigned int)nonce[3] << 24);
        st[14] = (unsigned int)nonce[4] | ((unsigned int)nonce[5] << 8) |
                 ((unsigned int)nonce[6] << 16) | ((unsigned int)nonce[7] << 24);
        st[15] = (unsigned int)nonce[8] | ((unsigned int)nonce[9] << 8) |
                 ((unsigned int)nonce[10] << 16) | ((unsigned int)nonce[11] << 24);

        for (i = 0; i < 16; i++) x[i] = st[i];

        for (r = 0; r < 10; r++) {
            ssc_quarter(x, 0, 4, 8, 12);
            ssc_quarter(x, 1, 5, 9, 13);
            ssc_quarter(x, 2, 6, 10, 14);
            ssc_quarter(x, 3, 7, 11, 15);
            ssc_quarter(x, 0, 5, 10, 15);
            ssc_quarter(x, 1, 6, 11, 12);
            ssc_quarter(x, 2, 7, 8, 13);
            ssc_quarter(x, 3, 4, 9, 14);
        }

        for (i = 0; i < 16; i++) {
            unsigned int v = x[i] + st[i];
            ks[i * 4]     = (unsigned char)(v & 0xFF);
            ks[i * 4 + 1] = (unsigned char)((v >> 8) & 0xFF);
            ks[i * 4 + 2] = (unsigned char)((v >> 16) & 0xFF);
            ks[i * 4 + 3] = (unsigned char)((v >> 24) & 0xFF);
        }

        {
            unsigned long n = (len - off) < 64 ? (len - off) : 64;
            unsigned long k;
            for (k = 0; k < n; k++) {
                out[off + k] = in[off + k] ^ ks[k];
            }
            off += n;
        }
        counter++;
    }
}

int main(int argc, char **argv) {
    /* ChaCha20 解密脚本 */
    unsigned char *buf = (unsigned char *)malloc(g_len + 1);
    if (!buf) { printf("malloc failed\n"); return 1; }
    ssc_decrypt(buf, g_data, g_len, g_key, g_nonce);
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
    private fun buildLinkCommand(status: NdkEnv.Status, obj: File, out: File): List<String> {
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
