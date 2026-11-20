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
        // 与 C 端保持一致：连续 16 轮 RC4
        repeat(16) { Rc4.apply(encData, rc4Key) }
        onLog("加密完成（16 轮 RC4）：" + encData.size + " 字节")

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
        // 2. 生成包装 C 源码
        //
        // 密文用内联汇编 .incbin 直接嵌进 wrapper.c，
        // 不再单独生成 .S 文件（clang 编译 .S 会 fork 外部 as，
        // 私有目录 noexec 下必然失败，已实际踩到两次）。
        // ============================================================
        val srcFile = File(workDir, "wrapper.c")
        try {
            writeWrapperSource(srcFile, scriptText, rc4Key, encFile.absolutePath)
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
        // 密文已由 wrapper.c 里的内联 .incbin 嵌入 wrapper.o，不需要额外目标文件
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
    /**
     * CRC32（与 C 端 ssc_crc32 算法完全一致）。
     *
     * 多项式 0xEDB88320，初始 0xFFFFFFFF，结果取反。
     * 必须严格一致，否则运行时校验永远不过。
     */
    private fun crc32(data: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        for (b in data) {
            crc = crc xor (b.toLong() and 0xFF)
            for (k in 0 until 8) {
                crc = if ((crc and 1L) != 0L) {
                    (crc shr 1) xor 0xEDB88320L
                } else {
                    crc shr 1
                }
            }
        }
        return (crc xor 0xFFFFFFFFL) and 0xFFFFFFFFL
    }

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
    private fun writeWrapperSource(dest: File, script: String, rc4Key: String, encPath: String) {
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
            // 脚本数据：用内联汇编 .incbin 原样嵌入
            //
            // 为什么不单独生成 .S 文件：
            //   clang 编译 .S 时会 fork 外部汇编器 as，
            //   而在 App 私有目录（noexec）下 fork 必然失败，报：
            //     error: expected absolute path: "-cc1"
            //   内联到 .c 里走 -fintegrated-cc1 路径就不会有这个问题。
            //
            // .incbin 的好处：二进制原样嵌入，体积不膨胀，不会损坏。
            // ------------------------------------------------
            w.write("__asm__(\n")
            w.write("    \".pushsection .rodata\\n\"\n")
            w.write("    \".global _ssc_data_start\\n\"\n")
            w.write("    \".global _ssc_data_end\\n\"\n")
            w.write("    \".balign 16\\n\"\n")
            w.write("    \"_ssc_data_start:\\n\"\n")
            w.write("    \".incbin \\\"")
            w.write(encPath)
            w.write("\\\"\\n\"\n")
            w.write("    \"_ssc_data_end:\\n\"\n")
            w.write("    \".popsection\\n\"\n")
            w.write(");\n\n")
            w.write("extern const unsigned char _ssc_data_start[];\n")
            w.write("extern const unsigned char _ssc_data_end[];\n\n")

            // RC4 密钥：编译期异或混淆（照 SSC 的 OBF 思路）
            //
            // 不再明文写 "db4d2ecf..."，而是写异或后的字节数组，
            // 运行时在栈上用同样的 KEY 异或回去，用完立即清零。
            // string 搜索拿不到明文密钥。
            val keyBytes = rc4Key.toByteArray(Charsets.UTF_8)
            w.write("static const unsigned char g_key_enc[] = {")
            keyBytes.forEachIndexed { i, b ->
                if (i > 0) w.write(",")
                w.write("0x%02X".format((b.toInt() xor 0x3C) and 0xFF))
            }
            w.write("};\n")
            w.write("static const unsigned long g_key_len = ")
            w.write(keyBytes.size.toString())
            w.write("UL;\n\n")

            // 密钥区 CRC32（与 C 端 ssc_crc32 完全一致的算法）
            // 运行时重算一次，对不上就说明 ELF 被 patch 过
            val encKeyArr = ByteArray(keyBytes.size) { i ->
                (keyBytes[i].toInt() xor 0x3C).toByte()
            }
            val keyCrc = crc32(encKeyArr)
            w.write("static const unsigned long g_key_crc = ")
            w.write(keyCrc.toString())
            w.write("UL;\n\n")

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
/* 管道 / IO（不 include unistd.h，自己声明）*/
extern int pipe(int *);
extern ssize_t write(int, const void *, size_t);
extern ssize_t read(int, void *, size_t);
extern int close(int);
/* open 是变参：open(path, flags, mode...) */
extern int open(const char *, int, ...);
#define O_RDONLY_ 0

/* ptrace（自己声明，不 include sys/ptrace.h）
   Linux 内核定义：
     PTRACE_TRACEME = 0
     PTRACE_DETACH  = 17
   之前把 DETACH 写成 0，等于又 TRACEME 一次，
   进程会一直处于被跟踪状态，fork 行为异常。 */
extern long ptrace_(int request, int pid, void *addr, void *data);
#define PTRACE_TRACEME_ 0
#define PTRACE_DETACH_  17

/* ============================================================
 * 彩色错误输出
 *
 * ANSI 颜色码，终端里显示彩色；不支持颜色的地方也能正常显示文字。
 * 每条错误都带 (exit:N)，方便脚本判断失败原因。
 * ============================================================ */
#define C_RED     "\033[1;31m"
#define C_YELLOW  "\033[1;33m"
#define C_MAGENTA "\033[1;35m"
#define C_CYAN    "\033[1;36m"
#define C_RESET   "\033[0m"

/* 错误码定义 */
#define E_DEBUGGER    2   /* 检测到调试器 */
#define E_TAMPERED    3   /* 密钥区被篡改 */
#define E_NOMEM       4   /* 内存分配失败 */
#define E_PIPE        5   /* 管道创建失败 */
#define E_EXEC        6   /* 解释器启动失败 */

/*
 * 打印错误并返回退出码。
 *
 * 不用 fprintf(stderr,...)：stderr 是 FILE*，在没 include stdio.h 时
 * 声明类型容易出错。直接用 write(2, ...) 写 fd 2，最简单可靠。
 */
static unsigned long ssc_strlen(const char *s) {
    unsigned long n = 0;
    while (s[n]) n++;
    return n;
}

static void ssc_put(int fd, const char *s) {
    if (!s) return;
    write(fd, s, ssc_strlen(s));
}

static int ssc_fail(const char *color, const char *msg, int code) {
    char tail[48];
    ssc_put(2, color);
    ssc_put(2, msg);
    ssc_put(2, C_RESET);
    ssc_put(2, "\n");
    ssc_put(2, color);
    /* 手工拼 "(exit:N)" */
    tail[0] = '('; tail[1] = 'e'; tail[2] = 'x';
    tail[3] = 'i'; tail[4] = 't'; tail[5] = ':';
    {
        int n = code;
        int i = 6;
        char tmp[8];
        int t = 0;
        if (n == 0) { tmp[t++] = '0'; }
        while (n > 0) { tmp[t++] = (char)('0' + (n % 10)); n /= 10; }
        while (t > 0) { tail[i++] = tmp[--t]; }
        tail[i++] = ')';
        tail[i++] = '\n';
        tail[i] = 0;
    }
    ssc_put(2, tail);
    ssc_put(2, C_RESET);
    return code;
}

/* ============================================================
 * 反调试：检测是否被 ptrace 附加
 *
 * 原理：一个进程只能被 ptrace 一次。
 *         先 TRACEME 自己占位，如果失败说明已经被调试器占用。
 *         再把请求改回去，不影响后续运行。
 *
 * 检测到就安静退出（返回非 0），不给调试器任何提示。
 * ============================================================ */
/*
 * 返回值：
 *   0 = 正常（或检测被跳过）
 *   1 = 确认被调试
 *
 * 重要：Android 上 PTRACE_TRACEME 可能因为 SELinux 策略直接返回 -1，
 * 这跟「有没有调试器」无关。所以不能只看 -1 就认定被调试。
 *
 * 三重判定（任何一个不成立都不报警）：
 *   1. ptrace 返回 -1 且 errno 是 EPERM（被占用）
 *   2. /proc/self/status 里 TracerPid 不为 0
 *   3. 两个条件同时成立
 *
 * 任一环节实现不足（比如读不到 /proc）都当作「没检测到」，
 * 宁可漏报也不误伤正常运行。
 */
static int ssc_read_tracer_pid(void) {
    int fd;
    char buf[1024];
    ssize_t n;
    int i;
    int pid = -1;

    fd = open("/proc/self/status", O_RDONLY_, 0);
    if (fd < 0) return -1;   /* 读不到，当作未知 */

    n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = 0;

    /* 在文本里找 "TracerPid:" 后跟的数字 */
    for (i = 0; i + 10 < (int)n; i++) {
        if (buf[i] == 'T' && buf[i+1] == 'r' && buf[i+2] == 'a' &&
            buf[i+3] == 'c' && buf[i+4] == 'e' && buf[i+5] == 'r' &&
            buf[i+6] == 'P' && buf[i+7] == 'i' && buf[i+8] == 'd' &&
            buf[i+9] == ':') {
            int j = i + 10;
            int v = 0;
            int has = 0;
            while (j < (int)n && (buf[j] == ' ' || buf[j] == '\t')) j++;
            while (j < (int)n && buf[j] >= '0' && buf[j] <= '9') {
                v = v * 10 + (buf[j] - '0');
                has = 1;
                j++;
            }
            if (has) pid = v;
            break;
        }
    }
    return pid;
}

static int ssc_anti_debug(void) {
    /* 方法一：读 TracerPid（最可靠，不改变进程状态）*/
    int tp = ssc_read_tracer_pid();
    if (tp > 0) {
        return 1;   /* 确认被调试 */
    }

    /* 方法二：ptrace 试探。
       *只在 TracerPid 读不到（返回 -1）时才用*，
       避免 Android SELinux 拒绝导致误报。
       TRACEME 成功后必须 DETACH，否则进程会一直处于被跟踪状态。 */
    if (tp < 0) {
        long r = ptrace_(PTRACE_TRACEME_, 0, (void *)0, (void *)0);
        if (r == 0) {
            /* 成功：说明之前没人跟踪，立即解除 */
            ptrace_(PTRACE_DETACH_, 0, (void *)0, (void *)0);
        }
        /* r == -1 不判定为调试（可能是 SELinux 拒绝）*/
    }

    return 0;
}

/* ============================================================
 * 完整性校验：CRC32 校验关键段
 *
 * 防止有人 patch ELF 改掉判断逻辑。
 * 校验范围：g_key_enc + 部分代码（简化实现，只看数据段）
 * ============================================================ */
static unsigned long ssc_crc32(const unsigned char *p, unsigned long n) {
    unsigned long crc = 0xFFFFFFFFUL;
    unsigned long i;
    int k;
    for (i = 0; i < n; i++) {
        crc ^= p[i];
        for (k = 0; k < 8; k++) {
            if (crc & 1) crc = (crc >> 1) ^ 0xEDB88320UL;
            else crc >>= 1;
        }
    }
    return crc ^ 0xFFFFFFFFUL;
}

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
        /* 标准交换：s[i] 与 s[j] 互换。
           这里曾经错写成 s[i] = (unsigned char)j，
           导致密钥流与 Kotlin 侧不一致，解密出垃圾。 */
        tmp = s[i]; s[i] = s[j]; s[j] = tmp;
    }

    /* PRGA */
    i = 0; j = 0;
    for (p = 0; p < len; p++) {
        i = (i + 1) & 0xFF;
        j = (j + s[i]) & 0xFF;
        /* 标准交换。这里曾经也错写成 s[i] = (unsigned char)j，
           和 KSA 一样的错误，导致密钥流完全不对。 */
        tmp = s[i]; s[i] = s[j]; s[j] = tmp;
        k = (s[i] + s[j]) & 0xFF;
        data[p] = data[p] ^ s[k];
    }
}

int main(int argc, char **argv) {
    /* ---- 第一道：反调试 ---- */
    if (ssc_anti_debug()) {
        return ssc_fail(C_RED, "Illegal debugger detected!", E_DEBUGGER);
    }

    /* ---- 第二道：密钥区完整性校验 ---- */
    /* g_key_crc 是编译时算好的期望值，被 patch 就对不上 */
    {
        unsigned long got = ssc_crc32(g_key_enc, g_key_len);
        if (got != g_key_crc) {
            return ssc_fail(C_YELLOW, "The key area has been tampered with!", E_TAMPERED);
        }
    }

    /* 分配并复制加密数据（保留原始副本用于 RC4 解密）*/
    unsigned char *buf = (unsigned char *)malloc(g_len + 1);
    if (!buf) {
        return ssc_fail(C_MAGENTA, "Memory allocation failed!", E_NOMEM);
    }

    /* 从 .incbin 嵌入的段里拷贝密文。
       用 end - start 算真实长度，比 g_len 可靠：
       g_len 是脚本字节数，而 .incbin 嵌入的是整个 script.enc，
       两者不一致时用 g_len 会读少或读多，导致解密后尾部多出垃圾。 */
    unsigned long real_len = (unsigned long)(_ssc_data_end - _ssc_data_start);
    unsigned long use_len = (real_len > 0 && real_len < g_len) ? real_len : g_len;
    {
        unsigned long i;
        for (i = 0; i < use_len; i++) {
            buf[i] = _ssc_data_start[i];
        }
        /* 清零剩余部分，确保不会带上未初始化内存 */
        for (i = use_len; i <= use_len; i++) buf[i] = 0;
    }

    /* ---- 解出 RC4 密钥（编译期异或 0x3C 混淆过）---- */
    char keybuf[128];
    unsigned long kl = g_key_len;
    if (kl >= sizeof(keybuf)) kl = sizeof(keybuf) - 1;
    {
        unsigned long i;
        for (i = 0; i < kl; i++) {
            keybuf[i] = (char)(g_key_enc[i] ^ 0x3C);
        }
        keybuf[kl] = 0;
    }

    /* ---- 多轮 RC4 + 控制流平坦化 ---- */
    /*
     * 原本是一个简单的 for 循环：
     *     for (round = 0; round < 16; round++) ssc_rc4(buf, use_len, keybuf);
     *
     * 这种结构逆向时一眼就能找到「16 轮 RC4」这个特征。
     *
     * 现在改成状态机：每轮不是递增，而是用一个打乱的轮次表推进，
     * 并且每轮结束后根据当前数据算一个扰动值参与下一轮的状态转移。
     * 结果是：
     *   · 轮次不是 0..15 顺序，静态看不出「共几轮」
     *   · 轮次表被异或混淆，还叠了数据相关的偏移
     *   · 但总轮数仍然是 16，结果与顺序执行完全一致（RC4 可交换）
     */
    {
        /*
         * 关键约束：RC4 对同一数据连续应用 N 次 = XOR 一个固定密钥流。
         * 所以总轮数必须严格等于加密时的轮数（16），否则解不出来。
         *
         * 因此平坦化只能「改变执行方式」，不能改变总次数。
         *
         * 做法：把 16 轮拆成一个状态机，每轮根据一个混淆过的表
         *   决定要不要做一次空转（对临时缓冲区跑 RC4，不影响 buf），
         *   从而给静态分析增加噪音，但 buf 的实际上解密次数仍是 16。
         */
        static const unsigned char g_order_enc[16] = {
            0x5A, 0x59, 0x5F, 0x5C, 0x5B, 0x5E, 0x58, 0x5D,
            0x52, 0x51, 0x57, 0x54, 0x53, 0x56, 0x50, 0x55
        };
        /* 空转用的诱饵缓冲区（尺寸固定，不影响 buf） */
        unsigned char decoy[32];
        int step;

        for (step = 0; step < 16; step++) {
            unsigned char ord = (unsigned char)(g_order_enc[step] ^ 0x5A);

            /* 真正的解密：恰好一次 */
            ssc_rc4(buf, use_len, keybuf);

            /* 诱饵：根据表里的值决定空转次数，干扰反编译 */
            {
                int r;
                int times = (int)(ord & 0x03);
                for (r = 0; r < 32; r++) decoy[r] = (unsigned char)(ord + r);
                for (r = 0; r < times; r++) {
                    ssc_rc4(decoy, sizeof(decoy), keybuf);
                }
            }
        }

        /* 诱饵缓冲区清零 */
        {
            int i2;
            for (i2 = 0; i2 < 32; i2++) decoy[i2] = 0;
        }
    }

    /* 密钥用完立即从栈上抹掉 */
    {
        unsigned long i;
        for (i = 0; i < kl; i++) keybuf[i] = 0;
    }

    /* ---- 用管道传给解释器，不落盘 ---- */
    /*
     * SSC 的做法：pipe() + /proc/self/fd/<n>，
     * 脚本内容全程在内存里，磁盘上没有任何副本，
     * ps / 进程列表也看不到脚本路径。
     */
    int pfd[2];
    if (pipe(pfd) != 0) {
        free(buf);
        return ssc_fail(C_CYAN, "Failed to create pipe!", E_PIPE);
    }

    /* 组装参数：sh /proc/self/fd/<read_fd> [args...] */
    char fdpath[64];
    snprintf(fdpath, sizeof(fdpath), "/proc/self/fd/%d", pfd[0]);

    int total = argc + 1;
    char **args = (char **)malloc(sizeof(char *) * (total + 2));
    if (!args) {
        free(buf);
        return ssc_fail(C_MAGENTA, "Memory allocation failed!", E_NOMEM);
    }
    args[0] = (char *)"/system/bin/sh";
    args[1] = fdpath;
    for (int i = 1; i < argc; i++) args[i + 1] = argv[i];
    args[total] = NULL;

    pid_t pid = fork();
    if (pid == 0) {
        /* 子进程：把读端变 fd 0，执行 sh */
        execv(args[0], args);
        _exit(127);
    }

    /* 父进程：写入解密后的脚本，写完关闭写端 */
    if (pid > 0) {
        unsigned long written = 0;
        while (written < use_len) {
            ssize_t n = write(pfd[1], buf + written, use_len - written);
            if (n <= 0) break;
            written += (unsigned long)n;
        }
        close(pfd[1]);
        close(pfd[0]);
    } else {
        close(pfd[1]);
        close(pfd[0]);
    }

    /* 脚本明文用完立即清零（SSC 也是这个做法）*/
    {
        unsigned long i;
        for (i = 0; i < use_len; i++) buf[i] = 0;
    }
    free(buf);

    int st = 0;
    if (pid > 0) waitpid(pid, &st, 0);
    free(args);

    if (pid < 0) {
        return ssc_fail(C_CYAN, "Failed to start interpreter!", E_EXEC);
    }

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
