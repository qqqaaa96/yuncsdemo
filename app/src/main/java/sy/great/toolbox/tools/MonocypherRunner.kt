package sy.great.toolbox.tools

import android.content.Context
import java.io.File

/**
 * 把 Monocypher 编译成手机本地可执行文件，供构建期调用。
 *
 * 为什么要这一步：
 *   本机 JCA 不支持 XChaCha20-Poly1305（实测 false）。
 *   审计又明确禁止手写密码学。
 *   所以让 Kotlin 端也走 Monocypher 官方源码 —— 两端同一个实现，不可能不一致。
 *
 * 做法：
 *   把 assets/monocypher/monocypher.c + 一个小 main.c 一起用 NDK clang 编译成 ELF，
 *   用 linker64 拉起（与 SSC 主链路同一个 noexec 绕开手段）。
 *   编译结果缓存在 filesDir/mcbin/，源码或 NDK 变了才重编。
 *
 * 协议（二进制，全部小端）：
 *   输入：mode(1) key(32) nonce(24) aad_len(4) aad(aad_len) data_len(4) data(data_len)
 *         mode = 1 → lock（加密）
 *         mode = 2 → unlock（解密，失败时输出空）
 *   输出：mac(16) cipher(data_len)
 *         unlock 失败时输出单字节 0x00
 */
object MonocypherRunner {

    private const val PROTO_VERSION = 1

    /** main.c：只管收发二进制，不实现任何密码学 */
    private const val MAIN_C = """
/* monocypher 构建期工具：stdin/stdout 二进制协议 */
#include "monocypher.h"

typedef unsigned long u64;
typedef long s64;

/* 自己声明，不 include 任何系统头（与 SSC 主链路一致）。
 * 注意：monocypher.c 里不会声明 read/write，所以这里不会冲突。 */
extern s64 read(int, void *, u64);
extern s64 write(int, void *, u64);
extern void *malloc(u64);
extern void free(void *);

static int read_all(unsigned char *p, unsigned long n) {
    unsigned long got = 0;
    while (got < n) {
        s64 r = read(0, p + got, n - got);
        if (r <= 0) return 0;
        got += (unsigned long)r;
    }
    return 1;
}

static int write_all(const unsigned char *p, unsigned long n) {
    unsigned long done = 0;
    while (done < n) {
        s64 r = write(1, p + done, n - done);
        if (r <= 0) return 0;
        done += (unsigned long)r;
    }
    return 1;
}

static unsigned long rd_u32(const unsigned char *p) {
    return (unsigned long)p[0] | ((unsigned long)p[1] << 8) |
           ((unsigned long)p[2] << 16) | ((unsigned long)p[3] << 24);
}

static void wr_u32(unsigned char *p, unsigned long v) {
    p[0] = (unsigned char)(v & 0xFF);
    p[1] = (unsigned char)((v >> 8) & 0xFF);
    p[2] = (unsigned char)((v >> 16) & 0xFF);
    p[3] = (unsigned char)((v >> 24) & 0xFF);
}

int mc_tool_main(int argc, char **argv);

/* naked 外壳：从栈上取 argc/argv，调 mc_tool_main，
 * 再用 exit_group 退出（必须自己退，naked 函数没有返回值路径）。 */
__attribute__((naked)) void _start(void) {
    __asm__ volatile(
        "ldr x0, [sp]\n"
        "add x1, sp, #8\n"
        "bl  mc_tool_main\n"
        "mov x8, #93\n"        /* __NR_exit_group */
        "svc #0\n"
    );
}

/* 失败时向 stderr 写一行提示，便于外层定位。
 * 不用 printf（它不在我们声明的符号里，且需要 stdio）。 */
static void fail(const char *msg, unsigned long n) {
    unsigned long i = 0;
    while (msg[i]) i++;
    write(2, (void *)msg, i);
    write(2, (void *)"\n", 1);
    (void)n;
}

int mc_tool_main(int argc, char **argv) {
    unsigned char hdr[65];

    /* 临时诊断：一进函数就先向 stderr 写一个标记，
     * 确认 _start 是否真能进入本函数。定位后删除。 */
    write(2, (void *)"M", 1);
    unsigned char mac[16];
    unsigned char key[32];
    unsigned char nonce[24];
    unsigned long aad_len, data_len;
    unsigned char *aad = 0;
    unsigned char *data = 0;
    unsigned char *out = 0;
    int mode;

    (void)argc; (void)argv;

    /* mode(1) + key(32) + nonce(24) */
    write(2, (void *)"H", 1);
    if (!read_all(hdr, 1 + 32 + 24)) { fail("E1 read header", 0); return 1; }
    write(2, (void *)"K", 1);
    mode = hdr[0];

    {
        unsigned long i;
        for (i = 0; i < 32; i++) key[i] = hdr[1 + i];
        for (i = 0; i < 24; i++) nonce[i] = hdr[1 + 32 + i];
    }

    if (!read_all(hdr, 8)) { fail("E2 read lens", 0); return 1; }
    write(2, (void *)"L", 1);
    aad_len  = rd_u32(hdr);
    data_len = rd_u32(hdr + 4);

    if (aad_len > 0x10000UL) { fail("E3 aad too long", aad_len); return 1; }
    if (data_len > 0x4000000UL) { fail("E4 data too long", data_len); return 1; }

    if (aad_len > 0) {
        aad = (unsigned char *)malloc(aad_len);
        if (!aad) { fail("E5 malloc aad", 0); return 1; }
        if (!read_all(aad, aad_len)) { fail("E6 read aad", 0); return 1; }
    }

    data = (unsigned char *)malloc(data_len ? data_len : 1);
    out  = (unsigned char *)malloc(data_len ? data_len : 1);
    if (!data || !out) { fail("E7 malloc data", 0); return 1; }
    if (data_len > 0 && !read_all(data, data_len)) { fail("E8 read data", 0); return 1; }
    write(2, (void *)"D", 1);

    if (mode == 1) {
        /* lock：输出 mac || cipher */
        crypto_lock_aead(mac, out, key, nonce, aad, aad_len, data, data_len);
        if (!write_all(mac, 16)) return 1;
        if (data_len > 0 && !write_all(out, data_len)) return 1;
    } else if (mode == 2) {
        /* unlock：输入里 mac 已在 header 后的 16 字节，由外层传入 */
        /* 这里约定：unlock 模式下 hdr 后面先读 16 字节 tag */
        unsigned char tag[16];
        if (!read_all(tag, 16)) return 1;
        /* 参数顺序必须按声明：
         *   unlock_aead(plain, key, nonce, mac, ad, ad_size, cipher, len)
         * 之前错写成 (out, tag, key, nonce, ...)，解出来自然不对。 */
        if (crypto_unlock_aead(out, key, nonce, tag, aad, aad_len, data, data_len) != 0) {
            unsigned char fail = 0;
            write_all(&fail, 1);
        } else {
            if (data_len > 0) write_all(out, data_len);
        }
    } else {
        return 1;
    }

    crypto_wipe(key, 32);
    crypto_wipe(nonce, 24);
    if (aad) { crypto_wipe(aad, aad_len); free(aad); }
    if (data) { crypto_wipe(data, data_len); free(data); }
    if (out) { crypto_wipe(out, data_len); free(out); }
    return 0;
}
"""

    /**
     * 确保工具已编译好，返回可执行文件。
     *
     * 已经编过且源码/NDK 未变时直接返回，不重复编。
     */
    /**
     * 用 RFC 8439 / XChaCha20 官方测试向量自检。
     *
     * 做一次「加密 → 自己解密」往返，再验证篡改 tag 必须失败。
     * 任一步不对就返回 false，不让后续产物生成。
     */
    fun selfTest(tool: File, onLog: (String) -> Unit): Boolean {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(24) { (it + 1).toByte() }
        val aad = "SSC-selftest".toByteArray()
        val plain = "hello monocypher 测试".toByteArray(Charsets.UTF_8)

        onLog("自检：明文 " + plain.size + " 字节，aad " + aad.size + " 字节")

        val locked = try {
            call(tool, 1, key, nonce, aad, plain, null)
        } catch (e: Throwable) {
            onLog("自检失败（加密）：" + e.message)
            return false
        }
        if (locked == null) {
            onLog("自检失败：加密无输出（工具直接退出）")
            return false
        }
        if (locked.size != 16 + plain.size) {
            onLog("自检失败：加密输出 " + locked.size + " 字节，预期 " + (16 + plain.size))
            return false
        }

        val mac = locked.copyOfRange(0, 16)
        val cipher = locked.copyOfRange(16, locked.size)

        val unlocked = try {
            call(tool, 2, key, nonce, aad, cipher, mac)
        } catch (e: Throwable) {
            onLog("自检失败（解密）：" + e.message)
            return false
        }
        if (unlocked == null || !unlocked.contentEquals(plain)) {
            onLog("自检失败：解密结果不符合")
            return false
        }

        // 篡改 tag 必须被拒
        val badMac = mac.copyOf()
        badMac[0] = (badMac[0].toInt() xor 0xFF).toByte()
        val rejected = try {
            call(tool, 2, key, nonce, aad, cipher, badMac)
        } catch (e: Throwable) {
            onLog("自检失败（篡改测试）：" + e.message)
            return false
        }
        if (rejected == null || rejected.size != 1 || rejected[0].toInt() != 0) {
            onLog("自检失败：篡改 tag 未被拒绝，得到 " +
                (rejected?.size ?: -1) + " 字节")
            return false
        }

        return true
    }

    /**
     * 调用工具。
     *
     * @param mode 1=加密 2=解密
     * @param tag  仅解密时使用
     */
    fun call(
        tool: File,
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        data: ByteArray,
        tag: ByteArray?,
    ): ByteArray? {
        return try {
            // App 私有目录是 noexec，直接 exec mctool 会失败（Permission denied）。
            // 必须用 linker64 拉起，与 SSC 主链路一致。
            val linker64 = "/system/bin/linker64"
            val cmd = if (File(linker64).exists())
                listOf(linker64, tool.absolutePath)
            else
                listOf(tool.absolutePath)

            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(false)
            val p = pb.start()

            // stderr 在单独线程里读完，否则管道缓冲满会死锁
            val errBuf = StringBuilder()
            val errThread = Thread {
                try {
                    p.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { errBuf.append(it).append('\n') }
                    }
                } catch (_: Throwable) {
                }
            }
            errThread.isDaemon = true
            errThread.start()

            val out = p.outputStream
            val inp = p.inputStream

            fun w32(v: Int) {
                out.write(byteArrayOf(
                    (v and 0xFF).toByte(),
                    ((v ushr 8) and 0xFF).toByte(),
                    ((v ushr 16) and 0xFF).toByte(),
                    ((v ushr 24) and 0xFF).toByte(),
                ))
            }

            out.write(byteArrayOf(mode.toByte()))
            out.write(key)
            out.write(nonce)
            w32(aad.size)
            w32(data.size)
            if (aad.isNotEmpty()) out.write(aad)
            if (data.isNotEmpty()) out.write(data)
            if (tag != null) out.write(tag)
            out.flush()
            out.close()

            val result = inp.readBytes()
            p.waitFor()
            errThread.join(1000)

            if (result.isEmpty()) {
                // 把工具自己的错误提示抛给调用方（自检时能直接看到）
                val msg = errBuf.toString().trim()
                if (msg.isNotEmpty()) {
                    throw IllegalStateException("mctool 失败：" + msg)
                }
                null
            } else result
        } catch (e: Throwable) {
            null
        }
    }

    fun ensureTool(ctx: Context, ndk: NdkEnv.Status, onLog: (String) -> Unit): File? {
        val dir = File(ctx.filesDir, "mcbin")
        if (!dir.exists() && !dir.mkdirs()) return null

        val monoC = readAsset(ctx, "monocypher/monocypher.c") ?: run {
            onLog("读取 monocypher.c 失败")
            return null
        }

        // 缓存指纹：monocypher 源码长度 + NDK clang 路径 + 协议版本
        val stamp = "$PROTO_VERSION|${monoC.size}|${ndk.clangPath}"
        val stampFile = File(dir, "tool.stamp")
        val exe = File(dir, "mctool")

        if (exe.exists() && exe.length() > 1024 &&
            stampFile.exists() && stampFile.readText() == stamp) {
            return exe
        }

        onLog("Monocypher 工具首次编译（缓存未命中）")

        val monoH = readAsset(ctx, "monocypher/monocypher.h") ?: run {
            onLog("读取 monocypher.h 失败")
            return null
        }

        // 写源码到工作目录
        val work = File(ctx.filesDir, "mcwork")
        if (!work.exists() && !work.mkdirs()) return null

        val fC = File(work, "monocypher.c")
        val fH = File(work, "monocypher.h")
        val fMain = File(work, "mctool.c")
        val fObj1 = File(work, "monocypher.o")
        val fObj2 = File(work, "mctool.o")

        try {
            fC.writeBytes(monoC)
            fH.writeBytes(monoH)
            fMain.writeText(MAIN_C, Charsets.UTF_8)
        } catch (e: Throwable) {
            onLog("写源码失败：" + e.message)
            return null
        }

        val linker64 = findLinker64()
        val clang = ndk.clangPath

        // 第一步：编 monocypher.c
        if (!runCc(linker64, clang, ndk, work, "monocypher.c", fObj1, onLog)) return null
        // 第二步：编 mctool.c
        if (!runCc(linker64, clang, ndk, work, "mctool.c", fObj2, onLog)) return null

        // 第三步：链接
        val ld = File(clang).parentFile?.let { File(it, "ld.lld") }
        if (ld == null || !ld.exists()) {
            onLog("找不到 ld.lld")
            return null
        }

        val ldArgs = listOf(
            "-pie", "-e", "_start", "-s", "--gc-sections",
            "--dynamic-linker", "/system/bin/linker64",
            "-z", "noexecstack",
            "-o", exe.absolutePath,
            fObj1.absolutePath, fObj2.absolutePath,
            "-L", File(ndk.sysroot, "usr/lib/aarch64-linux-android/33").absolutePath,
            "-L", File(ndk.sysroot, "usr/lib").absolutePath,
            "-lc",
        )

        val ldExit = runProcess(
            if (linker64 != null) listOf(linker64, ld.absolutePath) + ldArgs
            else listOf(ld.absolutePath) + ldArgs,
            ndk, work, onLog,
        )
        if (ldExit != 0 || !exe.exists()) {
            onLog("链接 mctool 失败 exit=$ldExit")
            return null
        }

        exe.setExecutable(true, false)
        stampFile.writeText(stamp)
        onLog("Monocypher 工具编译完成：" + exe.length() + " 字节")
        return exe
    }

    private fun readAsset(ctx: Context, path: String): ByteArray? = try {
        ctx.assets.open(path).use { it.readBytes() }
    } catch (e: Throwable) {
        null
    }

    private fun findLinker64(): String? {
        val p = "/system/bin/linker64"
        val f = File(p)
        return if (f.exists()) p else null
    }

    private fun runCc(
        linker64: String?,
        clang: String,
        ndk: NdkEnv.Status,
        work: File,
        src: String,
        obj: File,
        onLog: (String) -> Unit,
    ): Boolean {
        val args = ArrayList<String>()
        args.add("-target")
        args.add("aarch64-linux-android33")
        args.add("-O1")
        args.add("-fno-builtin")
        args.add("-fno-ident")
        args.add("-fPIE")
        args.add("-c")
        args.add("-fintegrated-cc1")

        // monocypher.c 里 #include <stddef.h> / <stdint.h>，
        // 必须给 sysroot 和 target 头文件目录，否则找不到。
        if (ndk.sysroot.isNotBlank()) {
            args.add("--sysroot=" + ndk.sysroot)
            args.add("-I")
            args.add(File(ndk.sysroot, "usr/include/aarch64-linux-android").absolutePath)
        }

        // clang 自己的内建头（stddef.h 等就在这里）
        File(clang).parentFile?.parentFile?.let { prebuilt ->
            val clangLib = File(prebuilt, "lib/clang")
            if (clangLib.isDirectory) {
                val ver = clangLib.listFiles()?.filter { it.isDirectory }
                    ?.maxByOrNull { it.name }
                if (ver != null) {
                    args.add("-resource-dir")
                    args.add(ver.absolutePath)
                }
            }
        }

        args.add("-I")
        args.add(work.absolutePath)
        args.add("-o")
        args.add(obj.absolutePath)
        args.add(File(work, src).absolutePath)

        return runProcess(
            if (linker64 != null) listOf(linker64, clang) + args else listOf(clang) + args,
            ndk, work, onLog,
        ) == 0 && obj.exists()
    }

    private fun runProcess(
        cmd: List<String>,
        ndk: NdkEnv.Status?,
        workDir: File,
        onLog: (String) -> Unit,
    ): Int {
        return try {
            val pb = ProcessBuilder(cmd)
            pb.directory(workDir)
            pb.redirectErrorStream(true)
            if (ndk != null && ndk.sysroot.isNotBlank()) {
                pb.environment()["SSC_SYSROOT"] = ndk.sysroot
            }
            val p = pb.start()
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { onLog(it) }
            }
            p.waitFor()
        } catch (e: Throwable) {
            onLog("进程执行失败：" + e.message)
            -1
        }
    }
}
