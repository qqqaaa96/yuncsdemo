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

        // ============================================================
        // 0. 先做 shell 静态混淆（只新增，不改后续加密链路）
        //
        // 输入：原脚本纯文本
        // 输出：混淆后的纯文本 shell（仍由 /system/bin/sh 直接执行）
        //
        // 之后走的就是和以前完全一样的 RC4 → 生成 wrapper.c → 编译 流程，
        // rawBytes 换成混淆结果即可，后面一行都没改。
        // ============================================================
        val obfuscatedText = try {
            ShObfuscator.obfuscate(scriptText)
        } catch (e: Throwable) {
            onLog("SH 混淆失败，回退到原脚本：" + e.message)
            scriptText
        }
        onLog("SH 混淆完成：" + scriptText.length + " → " + obfuscatedText.length + " 字符")
        val obfuscatedBytes = obfuscatedText.toByteArray(Charsets.UTF_8)

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

        // 加密对象是「混淆后的脚本」，不是原脚本。
        val encData = obfuscatedBytes.copyOf()
        // 与 C 端保持一致：连续 RC4_ROUNDS 轮。
        //
        // 致命陷阱：RC4 是异或流密码，对同一数据应用偶数次 = 还原成明文。
        // 之前用 16 轮，结果密文和明文完全一样（等于没加密，已实际碰到）。
        // 必须用奇数轮，且两端严格一致。
        repeat(RC4_ROUNDS) { Rc4.apply(encData, rc4Key) }
        onLog("加密完成（${RC4_ROUNDS} 轮 RC4）：" + encData.size + " 字节")

        // 自检：密文不能等于明文（奇数轮下不可能，但防将来改错）
        if (obfuscatedBytes.size == encData.size && obfuscatedBytes.contentEquals(encData)) {
            return Result(false, message = "加密失败：密文与明文相同（轮数配置错误）")
        }

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
            // 传混淆后的文本：g_len 必须等于密文长度（即混淆后字节数），
            // 否则运行时 payload_len 校验必然失败。
            writeWrapperSource(srcFile, obfuscatedText, rc4Key, encFile.absolutePath)
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

        // ---- 追加元数据（128 字节，含 SHA-256 自检摘要）----
        val metaOk = try {
            appendMeta(outFile, encData, rc4Key)
            true
        } catch (e: Throwable) {
            onLog("写入元数据失败：${e.message}")
            false
        }

        if (!metaOk) {
            return Result(false, log = log.toString(), message = "写入元数据失败")
        }
        onLog("已追加元数据：${CryptoCSource.META_SIZE} 字节（v${CryptoCSource.META_VERSION}）")

        return Result(
            ok = true,
            outputPath = outFile.absolutePath,
            sizeBytes = outFile.length(),
            log = log.toString(),
            message = "编译成功"
        )
    }

    /**
     * 向 ELF 末尾追加 128 字节元数据。
     *
     * 布局见 CryptoCSource 里的地址常量，顺序：
     *   [body][meta 128B]
     *
     * 自检摘要覆盖 [0, size-128)，即 body 全部内容。
     */
    private fun appendMeta(elf: File, payload: ByteArray, rc4Key: String) {
        val body0 = elf.readBytes()

        // ---- 先插入膨胀填充区 ----
        //
        // 目的：
        //   1. 产物体积明显变大，不再是「几 KB 的空壳」
        //   2. 填充内容随机，干扰「改一处后重算摘要」的定位
        //   3. 填充区参与 SHA-256 覆盖范围，掏空/修改都会被检出
        //
        // 填充量：取脚本长度的 2 倍，最少 4 KB，最多 512 KB，
        // 且按 8 字节对齐（避免破坏 ELF 解析习惯）。
        // 填充量：500 KB ~ 900 KB 随机，按 8 字节对齐
        val padLen = run {
            val rnd = java.security.SecureRandom()
            val base = 500 * 1024
            val span = 400 * 1024
            ((base + rnd.nextInt(span)) / 8 * 8)
        }
        val pad = buildDecoyRegion(padLen)

        // ---- 向填充区随机位置插入明文警告 ----
        //
        // 为什么插在填充区：
        //   其他位置（节区表中间、符号表、程序头）插字节会破坏 ELF 结构，
        //   导致加载失败或工具解析错乱。
        //   填充区本来就不属于任何段，随便改不影响程序运行。
        //
        // 效果：
        //   用文本编辑器 / strings 翻开，能看到「随机二进制 + 文件被保护！ + 随机二进制」，
        //   每个警告块位置都不同，每次编译也不同。
        scatterNotices(pad)

        java.io.FileOutputStream(elf, true).use { out ->
            out.write(pad)
        }

        val body = elf.readBytes()

        // 1) 算 body（含填充）的 SHA-256
        val selfDigest = sha256(body)

        // 2) 算密文的摘要和 tag
        val payloadDigest = sha256(payload)

        // Poly1305 tag：真正的 MAC，不再用摘要前 16 字节占位。
        //
        // MAC 密钥由四个密钥碎片现场派生，不额外落盘任何数据：
        //   prk[i] = ka[i%kl] ^ kb[i%kl] ^ (kc[i%kl]*3) ^ (kd[i%kl]*7) ^ 0xA5
        // 任一片被改 → prk 变 → tag 对不上。
        //
        // 注意：必须与 C 端 ssc_derive_prk() 逐字节一致。
        val payloadTag = poly1305(derivePrk(rc4Key), payload)

        // 3) 组装 128 字节
        val meta = ByteArray(CryptoCSource.META_SIZE)

        // version (0..3)
        putU32LE(meta, CryptoCSource.OFF_VERSION, CryptoCSource.META_VERSION)

        // payload digest (4..35)
        payloadDigest.copyInto(meta, CryptoCSource.OFF_PAYLOAD_DIGEST)

        // payload tag (36..51)
        payloadTag.copyInto(meta, CryptoCSource.OFF_PAYLOAD_TAG)

        // self digest (52..83)
        selfDigest.copyInto(meta, CryptoCSource.OFF_SELF_DIGEST)

        // payload len (84..87)
        putU32LE(meta, CryptoCSource.OFF_PAYLOAD_LEN, payload.size)

        // reserved (88..119) 全 0，留给 Ed25519

        // padding (124..127) 随机
        val rnd = java.security.SecureRandom()
        for (i in CryptoCSource.OFF_PADDING until CryptoCSource.META_SIZE) {
            meta[i] = rnd.nextInt(256).toByte()
        }

        // meta crc (120..123) 覆盖 0..119
        val metaCrc = crc32(meta.copyOfRange(0, CryptoCSource.OFF_META_CRC))
        putU32LE(meta, CryptoCSource.OFF_META_CRC, metaCrc.toInt())

        // 4) 追加到文件
        java.io.FileOutputStream(elf, true).use { out ->
            out.write(meta)
        }
    }

    /**
     * 明文警告（不混淆）。
     *
     * 放在 ELF 里显眼位置，用文本编辑器或 strings 直接可读。
     * 作用是「声明」而非「保密」。
     */
    /**
     * 明文警告（完整段落）。
     *
     * 每条都是多行，前后带空行，在文本编辑器里能看清。
     * 共 10 段，分散在 .rodata 里。
     */
    private val warnBanner = listOf(
        // 1
        "！此文件由 sytools 保护！\n" +
        "请勿反编译/破解/篡改！\n" +
        "您的一切反编译行为\n" +
        "将被视为攻击\n" +
        "一切后果由攻击者自行承担",
        // 2
        "THIS FILE IS PROTECTED BY SYTOOLS\n" +
        "DO NOT DECOMPILE / CRACK / TAMPER\n" +
        "ANY REVERSE ENGINEERING ACTION\n" +
        "WILL BE TREATED AS AN ATTACK\n" +
        "ALL CONSEQUENCES ARE BORNE BY THE ATTACKER",
        // 3
        "========================================\n" +
        "此程序已加密保护\n" +
        "逆向分析将触发完整性校验\n" +
        "篡改任一字节将导致无法运行\n" +
        "========================================",
        // 4
        "警告：检测到未经授权的分析行为\n" +
        "本文件包含调试器检测与代码完整性校验\n" +
        "请立即停止反编译操作",
        // 5
        "WARNING\n" +
        "This binary is protected by SYTOOLS\n" +
        "Unauthorized analysis is prohibited\n" +
        "Violation will be logged and reported",
        // 6
        "\n\n文件被保护！\n文件被保护！\n文件被保护！\n文件被保护！\n\n",
        // 7
        "\n\nFILE IS PROTECTED\nFILE IS PROTECTED\nFILE IS PROTECTED\nFILE IS PROTECTED\n\n",
        // 8
        "版权声明\n" +
        "本软件受著作权法保护\n" +
        "未经授权不得复制、修改、分发\n" +
        "违者将追究法律责任",
        // 9
        "COPYRIGHT NOTICE\n" +
        "This software is protected by copyright law\n" +
        "Unauthorized copying, modification or distribution\n" +
        "is strictly prohibited",
        // 10
        "========================================\n" +
        "SYTOOLS PROTECTED BINARY\n" +
        "integrity check enabled\n" +
        "debugger detection enabled\n" +
        "anti-tamper enabled\n" +
        "========================================",
    )

    /** 分散短句总数（会被拆成每 8 条一组分散存放） */
    private const val SHORT_NOTICE_COUNT = 240

    /** C 字符串转义 */
    private fun escapeC(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * 假字符串池。
     *
     * 两处都要用：
     *   1. 填充区（文件尾部，分析工具默认看不到）
     *   2. 真实 .rodata（分析工具能直接看到）
     *
     * 放进 .rodata 的是「看起来像真的」路径与错误信息，
     * 让 elf_strings 输出一片桑眼，真正有用的那几条埋在里面。
     */
    private val DECOY_STRINGS = arrayOf(
        "/data/local/tmp/.cache_%d",
        "/data/data/sy.great.toolbox/files/ssc_tmp/%d",
        "libcrypto.so.%d",
        "libssl.so.%d",
        "libnative-lib.so",
        "assets/index.android.bundle",
        "Failed to allocate %d bytes",
        "EINVAL: invalid argument %d",
        "EACCES: permission denied %d",
        "https://example.invalid/api/v%d",
        "https://127.0.0.1:8888/hook%d",
        "__android_log_print",
        "__android_log_write",
        "JNI_OnLoad",
        "JNI_OnUnload",
        "__cxa_finalize",
        "pthread_create",
        "pthread_detach",
        "dlopen",
        "dlsym",
        "sigaction",
        "sigemptyset",
        "setuid",
        "setgid",
        "chmod",
        "unlink",
        "kallsyms",
        "TracerPid:",
        "frida",
        "frida-server",
        "gum-js-loop",
        "xposed",
        "XposedBridge",
        "magisk",
        "magiskd",
        "zygisk",
        "lsposed",
        "substrate",
        "Signature verification failed",
        "Illegal state detected",
        "integrity check v%d",
        "unsupported architecture %d",
        "unexpected end of stream %d",
        "buffer overflow at 0x%08x",
        "mmap failed: %d",
        "mprotect failed: %d",
        "fork failed: %d",
        "/proc/self/maps",
        "/proc/self/status",
        "/proc/self/exe",
        "/proc/self/cmdline",
        "/proc/net/tcp",
        "/sys/class/net/%d/address",
    )

    /**
     * 向填充区随机位置插入明文警告。
     *
     * 设计：
     *   1. 把警告切成多种长度的小块（单句、两块、完整段）
     *   2. 随机选位置、随机选块、随机间隔
     *   3. 每块前后补 0x00 或换行，保证 strings 能完整提取
     *   4. 不覆盖已有内容（直接覆写填充区的随机字节）
     *
     * 为什么能安全覆写：
     *   填充区里的字节本来就不会被执行，
     *   也不属于任何节区，覆写不影响 ELF 加载与运行。
     */
    private fun scatterNotices(pad: ByteArray) {
        val rnd = java.security.SecureRandom()
        val size = pad.size
        if (size < 4096) return

        // 碎片池：不同长度，保证散布后看起来不像模板
        val pieces = arrayOf(
            "文件被保护！",
            "FILE IS PROTECTED",
            "此文件由 sytools 保护",
            "请勿反编译/破解/篡改",
            "您的一切反编译行为将被视为攻击",
            "一切后果由攻击者自行承担",
            "REVERSE ENGINEERING IS PROHIBITED",
            "UNAUTHORIZED ANALYSIS WILL BE REPORTED",
            "此程序已加密保护\n逆向分析将触发完整性校验",
            "篡改任一字节将导致无法运行",
            "SYTOOLS PROTECTED BINARY",
            "integrity check enabled",
            "debugger detection enabled",
            "anti-tamper enabled",
            "版权声明\n本软件受著作权法保护",
            "未经授权不得复制、修改、分发",
            "COPYRIGHT NOTICE",
            "THIS SOFTWARE IS PROTECTED BY COPYRIGHT LAW",
            "WARNING",
            "DETECTED UNAUTHORIZED ANALYSIS",
        )

        // 第一块：完整警告（长段），放在靠前的位置
        val fullWarn = (
            "\n\n！此文件由 sytools 保护！\n" +
            "请勿反编译/破解/篡改！\n" +
            "您的一切反编译行为\n" +
            "将被视为攻击\n" +
            "一切后果由攻击者自行承担\n\n"
        ).toByteArray(Charsets.UTF_8)

        fun writeAt(off: Int, bytes: ByteArray) {
            if (off < 0 || off + bytes.size > size) return
            bytes.copyInto(pad, off)
        }

        // 插 1 个完整警告（靠前 5%~15% 区域）
        run {
            val start = (size * (5 + rnd.nextInt(10)) / 100)
            writeAt(start, fullWarn)
        }

        // 插 1 个英文完整警告（靠后 60%~80%）
        run {
            val en = (
                "\n\nTHIS FILE IS PROTECTED BY SYTOOLS\n" +
                "DO NOT DECOMPILE / CRACK / TAMPER\n" +
                "ANY REVERSE ENGINEERING ACTION\n" +
                "WILL BE TREATED AS AN ATTACK\n" +
                "ALL CONSEQUENCES ARE BORNE BY THE ATTACKER\n\n"
            ).toByteArray(Charsets.UTF_8)
            val start = (size * (60 + rnd.nextInt(20)) / 100)
            writeAt(start, en)
        }

        // 下面插大量小块：均匀铺满整个填充区
        val count = 260
        for (i in 0 until count) {
            val txt = pieces[rnd.nextInt(pieces.size)]
            // 前后补换行，让 strings 能完整提取
            val chunk = ("\n" + txt + "\n").toByteArray(Charsets.UTF_8)
            // 随机位置，但避开两端 64 字节
            val maxOff = size - chunk.size - 64
            if (maxOff <= 64) break
            val off = 64 + rnd.nextInt(maxOff - 64)
            writeAt(off, chunk)
        }
    }

    /**
     * 生成诱饵区（ 500~900 KB）。
     *
     * 不是纯随机字节，而是「看起来像真实代码」的内容：
     *
     *   1. 合法 ARM64 指令序列（MOV/ADD/LDR/STR/B/CBZ 等）
     *      逆向工具反汇编时能看到成百上千个「函数」，全部需要人工排除。
     *   2. 内嵌假字符串（路径/错误信息/URL），迷惑字符串分析
     *   3. 假函数序言/尾声（stp x29,x30 / ldp x29,x30 / ret）
     *   4. 随机跳转目标，不形成完整 CFG，拖慢分析
     *
     * 关键：每次编译生成的内容都不同（SecureRandom 驱动），
     * 不存在固定特征码。
     */
    private fun buildDecoyRegion(size: Int): ByteArray {
        val out = ByteArray(size)
        val rnd = java.security.SecureRandom()
        var pos = 0

        // 常用 ARM64 指令模板（4 字节编码）
        // 都是合法但无意义/危险的指令，不会被执行
        fun emit32(w: Int) {
            if (pos + 4 > size) return
            out[pos] = (w and 0xFF).toByte()
            out[pos + 1] = ((w ushr 8) and 0xFF).toByte()
            out[pos + 2] = ((w ushr 16) and 0xFF).toByte()
            out[pos + 3] = ((w ushr 24) and 0xFF).toByte()
            pos += 4
        }

        fun emitRandBytes(n: Int) {
            val k = minOf(n, size - pos)
            if (k <= 0) return
            val buf = ByteArray(k)
            rnd.nextBytes(buf)
            buf.copyInto(out, pos)
            pos += k
        }

        // 假字符串复用同一个池（与 .rodata 里的一致）
        val fakeStrings = DECOY_STRINGS

        while (pos + 64 < size) {
            // 每块随机选一种「模式」
            when (rnd.nextInt(6)) {
                0 -> {
                    // 假函数：prologue + 若干运算 + epilogue
                    emit32(0xA9BF7BFD.toInt())          // stp x29, x30, [sp, #-16]!
                    emit32(0x910003FD.toInt())          // mov x29, sp
                    val n = 2 + rnd.nextInt(10)
                    repeat(n) {
                        when (rnd.nextInt(4)) {
                            0 -> emit32(0x91000000.toInt() or (rnd.nextInt(0x3FF) shl 10) or
                                        (31 shl 5) or rnd.nextInt(31))   // add xD, xN, #imm
                            1 -> emit32(0xF9400000.toInt() or (rnd.nextInt(0xFF) shl 10) or
                                        (31 shl 5) or rnd.nextInt(31))   // ldr xD, [xN, #imm]
                            2 -> emit32(0xF9000000.toInt() or (rnd.nextInt(0xFF) shl 10) or
                                        (31 shl 5) or rnd.nextInt(31))   // str xD, [xN, #imm]
                            else -> emit32(0xD2800000.toInt() or (rnd.nextInt(0xFFFF) shl 5) or
                                        rnd.nextInt(31))                  // movz xD, #imm
                        }
                    }
                    emit32(0xA8C17BFD.toInt())          // ldp x29, x30, [sp], #16
                    emit32(0xD65F03C0.toInt())          // ret
                }
                1 -> {
                    // 假跳转链
                    val n = 2 + rnd.nextInt(8)
                    repeat(n) {
                        emit32(0x14000000.toInt() or rnd.nextInt(0x3FFFFFF))  // b offset
                    }
                }
                2 -> {
                    // 假条件分支
                    val n = 2 + rnd.nextInt(6)
                    repeat(n) {
                        emit32(0x54000000.toInt() or (rnd.nextInt(0x7FFFF) shl 5) or rnd.nextInt(16))  // b.cond
                    }
                }
                3 -> {
                    // 假字符串 + 填充对齐
                    val s = fakeStrings[rnd.nextInt(fakeStrings.size)]
                    val line = String.format(s, rnd.nextInt(9999)).toByteArray()
                    val k = minOf(line.size, size - pos - 1)
                    if (k > 0) {
                        line.copyInto(out, pos, 0, k)
                        pos += k
                    }
                    out[pos++] = 0
                    // 补齐到 4 字节对齐
                    while (pos % 4 != 0 && pos < size) out[pos++] = 0
                }
                4 -> {
                    // 假 NOP sled / hint
                    val n = 4 + rnd.nextInt(20)
                    repeat(n) { emit32(0xD503201F.toInt()) }  // nop
                }
                else -> {
                    // 纯随机字节（小幅）
                    emitRandBytes(8 + rnd.nextInt(56))
                }
            }
        }

        // 末尾剩余用随机字节填满
        if (pos < size) emitRandBytes(size - pos)
        return out
    }

    /** SHA-256 */
    private fun sha256(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * 从 RC4 密钥派生 Poly1305 MAC 密钥（32 字节）。
     *
     * 公式（双方严格一致）：
     *   prk[i] = ks[i%kl] ^ (ks[(i+5)%kl]*3) ^ (ks[(i+9)%kl]*7)
     *            ^ (ks[(i+13)%kl]*11) ^ 0xA5      （乘法结果均取低 8 位）
     *
     * 其中 ks = RC4 密钥的字节（两端同源）。
     *
     * ⚠ 必须与 C 端 ssc_derive_prk() 逐字节一致，
     *   否则运行时 tag 校验必然失败（产物退出码 3）。
     */
    private fun derivePrk(rc4Key: String): ByteArray {
        val ks = rc4Key.toByteArray(Charsets.UTF_8)
        val kl = ks.size
        val prk = ByteArray(32)
        for (i in 0 until 32) {
            val a = ks[i % kl].toInt() and 0xFF
            val b = ((ks[(i + 5) % kl].toInt() and 0xFF) * 3) and 0xFF
            val c = ((ks[(i + 9) % kl].toInt() and 0xFF) * 7) and 0xFF
            val d = ((ks[(i + 13) % kl].toInt() and 0xFF) * 11) and 0xFF
            prk[i] = ((a xor b xor c xor d xor 0xA5) and 0xFF).toByte()
        }
        return prk
    }

    /**
     * Poly1305 MAC（32 字节密钥 → 16 字节 tag）。
     *
     * 多项式 2^130-5，26 位 limb 实现。
     * 必须与 CryptoCSource.AEAD 的 C 实现逐位一致。
     */
    private fun poly1305(key: ByteArray, msg: ByteArray): ByteArray {
        val r0 = le32(key, 0) and 0x3FFFFFFL
        val r1 = (le32(key, 3) ushr 2) and 0x3FFFFFFL
        val r2 = (le32(key, 6) ushr 4) and 0x3FFFFFFL
        val r3 = (le32(key, 9) ushr 6) and 0x3FFFFFFL
        val r4 = (le32(key, 12) ushr 8) and 0x3FFFFFFL

        var h0 = 0L; var h1 = 0L; var h2 = 0L; var h3 = 0L; var h4 = 0L

        // 按 16 字节块处理，末块不足 16 字节补 0x01（2^128 隐含位）
        var off = 0
        while (off < msg.size) {
            val n = minOf(16, msg.size - off)
            val blk = ByteArray(17)
            msg.copyInto(blk, 0, off, off + n)
            blk[n] = 0x01.toByte()

            val t0 = le32(blk, 0) and 0x3FFFFFFL
            val t1 = (le32(blk, 3) ushr 2) and 0x3FFFFFFL
            val t2 = (le32(blk, 6) ushr 4) and 0x3FFFFFFL
            val t3 = (le32(blk, 9) ushr 6) and 0x3FFFFFFL
            val t4 = (le32(blk, 12) ushr 8) and 0x1FFFFFFL
            val t5 = blk[16].toLong() and 0x03L

            h0 += t0
            h1 += t1
            h2 += t2
            h3 += t3
            h4 += t4 or (t5 shl 25)

            val d0 = h0 * r0 + h1 * (r4 * 5) + h2 * (r3 * 5) + h3 * (r2 * 5) + h4 * (r1 * 5)
            val d1 = h0 * r1 + h1 * r0 + h2 * (r4 * 5) + h3 * (r3 * 5) + h4 * (r2 * 5)
            val d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * (r4 * 5) + h4 * (r3 * 5)
            val d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * (r4 * 5)
            val d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

            var c = d0 ushr 26; h0 = d0 and 0x3FFFFFFL
            var dd = d1 + c; c = dd ushr 26; h1 = dd and 0x3FFFFFFL
            dd = d2 + c; c = dd ushr 26; h2 = dd and 0x3FFFFFFL
            dd = d3 + c; c = dd ushr 26; h3 = dd and 0x3FFFFFFL
            dd = d4 + c; c = dd ushr 26; h4 = dd and 0x3FFFFFFL
            h0 += c * 5
            c = h0 ushr 26; h0 = h0 and 0x3FFFFFFL
            h1 += c

            off += n
        }

        // 进位归一
        var c = h1 ushr 26; h1 = h1 and 0x3FFFFFFL; h2 += c
        c = h2 ushr 26; h2 = h2 and 0x3FFFFFFL; h3 += c
        c = h3 ushr 26; h3 = h3 and 0x3FFFFFFL; h4 += c
        c = h4 ushr 26; h4 = h4 and 0x3FFFFFFL; h0 += c * 5
        c = h0 ushr 26; h0 = h0 and 0x3FFFFFFL; h1 += c

        // h -= (2^130-5)
        var f0 = h0 + 5; var cc = (f0 ushr 26).toInt(); f0 = f0 and 0x3FFFFFFL
        var f1 = h1 + cc; cc = (f1 ushr 26).toInt(); f1 = f1 and 0x3FFFFFFL
        var f2 = h2 + cc; cc = (f2 ushr 26).toInt(); f2 = f2 and 0x3FFFFFFL
        var f3 = h3 + cc; cc = (f3 ushr 26).toInt(); f3 = f3 and 0x3FFFFFFL
        val f4 = h4 + cc
        if ((f4 ushr 26) != 0L) {
            h0 = f0 and 0x3FFFFFFL
            h1 = f1 and 0x3FFFFFFL
            h2 = f2 and 0x3FFFFFFL
            h3 = f3 and 0x3FFFFFFL
            h4 = f4 and 0x3FFFFFFL
        }

        var g0 = h0 or (h1 shl 26)
        var g1 = (h1 ushr 6) or (h2 shl 20)
        var g2 = (h2 ushr 12) or (h3 shl 14)
        var g3 = (h3 ushr 18) or (h4 shl 8)

        val p0 = le32(key, 16)
        val p1 = le32(key, 20)
        val p2 = le32(key, 24)
        val p3 = le32(key, 28)

        var sum = (g0 and 0xFFFFFFFFL) + p0
        g0 = sum and 0xFFFFFFFFL
        sum = (g1 and 0xFFFFFFFFL) + p1 + (sum ushr 32)
        g1 = sum and 0xFFFFFFFFL
        sum = (g2 and 0xFFFFFFFFL) + p2 + (sum ushr 32)
        g2 = sum and 0xFFFFFFFFL
        sum = (g3 and 0xFFFFFFFFL) + p3 + (sum ushr 32)
        g3 = sum and 0xFFFFFFFFL

        val tag = ByteArray(16)
        putU32LE(tag, 0, g0.toInt())
        putU32LE(tag, 4, g1.toInt())
        putU32LE(tag, 8, g2.toInt())
        putU32LE(tag, 12, g3.toInt())
        return tag
    }

    /** 读 32 位小端，越界按 0 补（块尾需要读满 4 字节） */
    private fun le32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) {
            val idx = off + i
            val by = if (idx < b.size) (b[idx].toInt() and 0xFF) else 0
            v = v or (by.toLong() shl (8 * i))
        }
        return v
    }

    /** 写 32 位小端 */
    private fun putU32LE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
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
     * RC4 轮数。
     *
     * 必须是奇数：RC4 是异或流密码，偶数轮会相互抵消（自反），
     * 16 轮的结果就是明文，等于没加密（已实际碰到）。
     */
    private const val RC4_ROUNDS = 17

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
            // ============================================================
            // 密钥不得以任何完整形式落盘。
            //
            // 旧做法：g_key_enc[i] = key[i] ^ 0x3C
            //          -> 攻击者拿 ^0x3C 一算就出密钥。
            // 而且 -O2 会直接把它折成明文写进 .rodata。
            //
            // 新做法：拆成两个碎片数组 A、B，
            //   A[i] = key[i] ^ rnd[i]
            //   B[i] = rnd[i]
            //   key[i] = A[i] ^ B[i]
            // 单独看任一数组都是随机字节，必须两个都拿到且知道组合方式。
            // ============================================================
            val keyBytes = rc4Key.toByteArray(Charsets.UTF_8)
            val rnd = java.security.SecureRandom()

            // 4 碎片：A ^ B ^ C ^ D == key
            // 单独看任何一片都是随机字节，少任何一片都解不出来。
            val fragA = ByteArray(keyBytes.size)
            val fragB = ByteArray(keyBytes.size)
            val fragC = ByteArray(keyBytes.size)
            val fragD = ByteArray(keyBytes.size)
            for (i in keyBytes.indices) {
                val rb = rnd.nextInt(256).toByte()
                val rc = rnd.nextInt(256).toByte()
                val rd = rnd.nextInt(256).toByte()
                fragB[i] = rb
                fragC[i] = rc
                fragD[i] = rd
                // A = key ^ B ^ C ^ D  →  A ^ B ^ C ^ D = key
                fragA[i] = (keyBytes[i].toInt() xor
                    rb.toInt() xor rc.toInt() xor rd.toInt()).toByte()
            }

            w.write("static volatile const unsigned char g_ka[] = {")
            fragA.forEachIndexed { i, b ->
                if (i > 0) w.write(",")
                w.write("0x%02X".format(b.toInt() and 0xFF))
            }
            w.write("};\n")

            w.write("static volatile const unsigned char g_kb[] = {")
            fragB.forEachIndexed { i, b ->
                if (i > 0) w.write(",")
                w.write("0x%02X".format(b.toInt() and 0xFF))
            }
            w.write("};\n")

            w.write("static volatile const unsigned char g_kc[] = {")
            fragC.forEachIndexed { i, b ->
                if (i > 0) w.write(",")
                w.write("0x%02X".format(b.toInt() and 0xFF))
            }
            w.write("};\n")

            w.write("static volatile const unsigned char g_kd[] = {")
            fragD.forEachIndexed { i, b ->
                if (i > 0) w.write(",")
                w.write("0x%02X".format(b.toInt() and 0xFF))
            }
            w.write("};\n")

            w.write("static const unsigned long g_key_len = ")
            w.write(keyBytes.size.toString())
            w.write("UL;\n\n")

            // 4 碎片联合 CRC：与 C 端算法严格一致
            //   got = crc32(A) ^ (crc32(B)*K) ^ (crc32(C)*K2) ^ (crc32(D)*K3)
            val cA = crc32(fragA)
            val cB = crc32(fragB)
            val cC = crc32(fragC)
            val cD = crc32(fragD)
            val keyCrc = (
                cA xor
                ((cB * 0x9E3779B1L) and 0xFFFFFFFFL) xor
                ((cC * 0x85EBCA77L) and 0xFFFFFFFFL) xor
                ((cD * 0xC2B2AE3DL) and 0xFFFFFFFFL)
            ) and 0xFFFFFFFFL
            w.write("static const unsigned long g_key_crc = ")
            w.write(keyCrc.toString())
            w.write("UL;\n\n")

            // RC4 轮数：必须与 Kotlin 侧 RC4_ROUNDS 一致，且必须是奇数
            w.write("#define RC4_ROUNDS_C ")
            w.write(RC4_ROUNDS.toString())
            w.write("\n\n")

            w.write("static const unsigned long g_len = ")
            w.write(bytes.size.toString())
            w.write("UL;\n\n")

            // ---- Poly1305 MAC 密钥派生（由四个密钥碎片现场生成）----
            //
            // 必须与 Kotlin 侧 derivePrk() 逐字节一致：
            //   prk[i] = ka[i%kl] ^ (kb[(i+5)%kl]*3) ^ (kc[(i+9)%kl]*7)
            //            ^ (kd[(i+13)%kl]*11) ^ 0xA5   （乘法取低 8 位）
            // 先用四碎片合成 RC4 密钥，再从该密钥派生 prk。
            // 必须与 Kotlin 侧 derivePrk(rc4Key) 完全相同：
            // 两端输入都是「RC4 密钥字符串的字节」。
            w.write("static void ssc_derive_prk(unsigned char out[32]) {\n")
            w.write("    unsigned char ks[128];\n")
            w.write("    unsigned long i; unsigned long kl = g_key_len;\n")
            w.write("    if (kl == 0) kl = 1;\n")
            w.write("    if (kl >= sizeof(ks)) kl = sizeof(ks) - 1;\n")
            w.write("    for (i = 0; i < kl; i++) {\n")
            w.write("        ks[i] = (unsigned char)(g_ka[i] ^ g_kb[i] ^ g_kc[i] ^ g_kd[i]);\n")
            w.write("    }\n")
            w.write("    for (i = 0; i < 32; i++) {\n")
            w.write("        unsigned int a = ks[i % kl];\n")
            w.write("        unsigned int b = (unsigned int)((ks[(i + 5) % kl] * 3u) & 0xFFu);\n")
            w.write("        unsigned int c = (unsigned int)((ks[(i + 9) % kl] * 7u) & 0xFFu);\n")
            w.write("        unsigned int d = (unsigned int)((ks[(i + 13) % kl] * 11u) & 0xFFu);\n")
            w.write("        out[i] = (unsigned char)((a ^ b ^ c ^ d ^ 0xA5u) & 0xFFu);\n")
            w.write("    }\n")
            w.write("    for (i = 0; i < sizeof(ks); i++) ks[i] = 0;\n")
            w.write("}\n\n")

            // ---- 运行时消息表（全部异或加密，磁盘上看不到明文）----
            //
            // 以前这些字符串直接写在 ssc_fail() 调用处，
            // elf_strings 能直接看到：
            //   "Illegal debugger detected!"
            //   "Executable integrity check failed!"
            // 等于告诉逆向者「这里有自检、有反调试」。
            //
            // 现在改成：字节数组（逐字节异或 0x5B），运行时先解到栈上再用。
            val messages = listOf(
                "Executable integrity check failed!",
                "Illegal debugger detected!",
                "The key area has been tampered with!",
                "Memory allocation failed!",
                "Failed to create pipe!",
                "Failed to start interpreter!",
                "(exit:",
                "/proc/self/exe",
                "/proc/self/status",
                "/proc/self/fd/%d",
                "/system/bin/sh",
                "TracerPid:",
            )
            w.write("/* msg table (xor 0x5B) */\n")
            w.write("static const unsigned char g_msg[][64] __attribute__((used)) = {\n")
            messages.forEach { m ->
                val b = m.toByteArray(Charsets.UTF_8)
                w.write("    {")
                b.forEachIndexed { i, by ->
                    if (i > 0) w.write(",")
                    w.write("0x%02X".format((by.toInt() xor 0x5B) and 0xFF))
                }
                w.write("},\n")
            }
            w.write("};\n")
            w.write("#define MSG_INTEGRITY 0\n")
            w.write("#define MSG_DEBUGGER  1\n")
            w.write("#define MSG_TAMPERED  2\n")
            w.write("#define MSG_NOMEM     3\n")
            w.write("#define MSG_PIPE      4\n")
            w.write("#define MSG_EXEC      5\n")
            w.write("#define MSG_EXIT      6\n")
            w.write("#define MSG_EXE_PATH  7\n")
            w.write("#define MSG_STATUS    8\n")
            w.write("#define MSG_FD_FMT    9\n")
            w.write("#define MSG_SH_PATH   10\n")
            w.write("#define MSG_TRACER    11\n\n")

            // ---- 诱饵字符串（写入 .rodata，让 elf_strings 看到大量干扰）----
            //
            // 为什么放进 C 数组：
            //   放在文件尾部的话，elf_strings / readelf 默认不扫，
            //   混淆效果白做。写进 .rodata 才是「真能看到」的干扰。
            w.write("/* decoy string pool */\n")
            // __attribute__((used))：强制编译器保留，不管有没有引用
            w.write("static const char *const g_decoy[] __attribute__((used)) = {\n")
            DECOY_STRINGS.forEach { s ->
                w.write("    \"")
                w.write(s.replace("\\", "\\\\").replace("\"", "\\\""))
                w.write("\",\n")
            }
            w.write("    0\n};\n")

            // 防止未使用的数组被 -O1 优化掉：
            // 用一个 volatile 指针指向它，并在 main 里读一次
            w.write("static volatile unsigned int g_decoy_sum = 0;\n\n")

            // ---- 密码学代码（SHA-256 + 元数据自检）----
            w.write(CryptoCSource.SHA256)
            w.write("\n")
            w.write(CryptoCSource.META_CHECK)
            w.write("\n")
            // Poly1305：密文真实性校验（防止改密文解出恶意脚本）
            w.write(CryptoCSource.AEAD)
            w.write("\n")

            // ---- 主体逻辑 ----
            w.write(MAIN_BODY)
        }
    }

    /** 包装程序主体（与脚本内容无关，可直接用常量） */
    private val MAIN_BODY: String = """
/*
 * 不能写 typedef unsigned long size_t;
 * clang 内建了 size_t，重定义会报 redefinition。
 * 所以全用自定义名字：u64 / s64 / ssz。
 * ARM64 上 unsigned long / long 都是 8 字节，与内核定义一致。
 */
typedef unsigned long u64;
typedef long s64;
typedef int pid_t_;

/* 不用 stddef.h，自己定义 NULL */
#ifndef NULL
#define NULL ((void *)0)
#endif

extern void *malloc(u64);
extern void free(void *);
extern int snprintf(char *, u64, const char *, ...);
extern char *getenv(const char *);
extern int chmod(const char *, unsigned int);
extern int unlink(const char *);
extern int getpid(void);
extern pid_t_ fork(void);
extern int execv(const char *, char *const []);
extern int dup2(int, int);
extern void _exit(int);
extern int waitpid(pid_t_, int *, int);
extern int printf(const char *, ...);
/* 管道 / IO
 *
 * open / close / read / lseek 已在 CryptoCSource.META_CHECK 里声明，
 * 这里不要再重复声明，否则会报 conflicting types。
 * 只补 pipe / write（那两个没在那边用过）。
 */
extern int pipe(int *);
extern s64 write(int, const void *, u64);

/* ptrace（自己声明，不 include sys/ptrace.h）
   Linux 内核定义：
     PTRACE_TRACEME = 0
     PTRACE_DETACH  = 17
   之前把 DETACH 写成 0，等于又 TRACEME 一次，
   进程会一直处于被跟踪状态，fork 行为异常。 */
/*
 * 直接声明成 ptrace（与 libc 符号名一致）。
 * 因为没有 include <sys/ptrace.h>，不会与头文件声明冲突。
 * 之前写成 ptrace_ 导致链接报 undefined symbol。
 */
extern long ptrace(int request, int pid, void *addr, void *data);

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
#define E_INTEGRITY   7   /* ELF 完整性校验失败 */

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

/*
 * 把消息表里第 idx 条解密到栈上缓冲，返回指针。
 *
 * 为什么这样做：
 *   字符串直接写在调用处，会被 elf_strings 原样列出，
 *   等于把「有自检 / 有反调试」写成说明文档。
 *   改成异或字节表 + 运行时解密，静态看不到任何完整文本。
 */
/*
 * 注意：返回的是同一块静态缓冲区，不能同时持有两个结果。
 * 调用方如果需要在同一条语句里用两个（如 ssc_fail 里 msg + 前缀），
 * 必须先拷贝到自己的局部缓冲。
 */
static char *ssc_msg(int idx) {
    static char buf[80];
    int i;
    for (i = 0; i < 63; i++) {
        unsigned char c = g_msg[idx][i];
        if (c == 0) break;
        buf[i] = (char)(c ^ 0x5B);
    }
    buf[i] = 0;
    return buf;
}

static int ssc_fail(const char *color, int msgIdx, int code) {
    char tail[48];
    ssc_put(2, color);
    ssc_put(2, ssc_msg(msgIdx));
    ssc_put(2, C_RESET);
    ssc_put(2, "\n");
    ssc_put(2, color);
    /* 手工拼 "(exit:N)"，前缀从消息表取 */
    {
        char *pfx = ssc_msg(MSG_EXIT);
        int k = 0;
        while (pfx[k] && k < 8) { tail[k] = pfx[k]; k++; }
        {
            int n = code;
            int i = k;
            char tmp[8];
            int t = 0;
            if (n == 0) { tmp[t++] = '0'; }
            while (n > 0) { tmp[t++] = (char)('0' + (n % 10)); n /= 10; }
            while (t > 0) { tail[i++] = tmp[--t]; }
            tail[i++] = ')';
            tail[i++] = '\n';
            tail[i] = 0;
        }
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
    s64 n;
    int i;
    int pid = -1;

    fd = open(ssc_msg(MSG_STATUS), SSC_O_RDONLY, 0);
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
        long r = ptrace(PTRACE_TRACEME_, 0, (void *)0, (void *)0);
        if (r == 0) {
            /* 成功：说明之前没人跟踪，立即解除 */
            ptrace(PTRACE_DETACH_, 0, (void *)0, (void *)0);
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
static unsigned long ssc_crc32(const volatile unsigned char *p, unsigned long n) {
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

int ssc_real_main(int argc, char **argv);

/*
 * 函数头混淆：main 不再直接是真实函数体。
 *
 * 为什么：
 *   标准 C 函数开头是 stp x29,x30,[sp,#-N]! / mov x29,sp，
 *   反编译器（IDA / Ghidra / r2）一眼识别为函数入口，
 *   自动建 CFG 并回溯调用关系。
 *
 * 现在：
 *   · main 是 naked 函数，只有一段随机指令序列
 *   · 中间夹 b.nv / cbz 等永不成立的条件分支，打乱线性扫描
 *   · 真实逻辑在 ssc_real_main，只能通过跳转到达
 *   · 跳转偏移随机计算（基于函数地址），不是固定常量
 */
/* 返回类型必须是 int：naked 模式下 C 编译器不去检查，
   但 -Wmain-return-type 会报 warning，改成 int 即可。
   实际返回值由 ssc_real_main 提供，b 是尾调用，不影响。 */
__attribute__((naked)) int main(void) {
    __asm__ volatile(
        "eor x9, x9, x9\n"
        "add x9, x9, #0x5A\n"
        "eor x10, x10, x10\n"
        "add x10, x10, #0x3C\n"
        "cbz x9, 1f\n"          /* x9 != 0，永不跳转到标号 1（但干扰扫描）*/
        "1:\n"
        "b  ssc_real_main\n"
    );
}

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

/* 消息表访问器（定义在后面，先声明）*/
static char *ssc_msg(int idx);

/* ============================================================
 * Poly1305 全量认证（与 Kotlin 侧 poly1305() 逐位一致）
 *
 * 自包含实现，不复用 CryptoCSource.AEAD 的单块原语：
 * 那套是按「magic 块」写的，短块语义不匹配。
 * 这里直接按规范：h += (m || 0x01) 后乘 r。
 * ============================================================ */
static void ssc_poly_auth(const unsigned char key[32],
                          const unsigned char *msg, unsigned long len,
                          unsigned char tag[16]) {
    unsigned long r0, r1, r2, r3, r4;
    unsigned long h0 = 0, h1 = 0, h2 = 0, h3 = 0, h4 = 0;
    unsigned long off = 0;
    unsigned long p0, p1, p2, p3;
    unsigned long g0, g1, g2, g3, sum;
    unsigned long c; unsigned long long d0, d1, d2, d3, d4, dd;
    unsigned long f0, f1, f2, f3, f4;
    int i;

    /* r = key[0..15] clamp 到 26 位分组 */
    r0 = ((unsigned long)key[0] | ((unsigned long)key[1] << 8) |
          ((unsigned long)key[2] << 16) | ((unsigned long)key[3] << 24)) & 0x3FFFFFFUL;
    r1 = (((unsigned long)key[3] >> 2) | ((unsigned long)key[4] << 6) |
          ((unsigned long)key[5] << 14) | ((unsigned long)key[6] << 22)) & 0x3FFFFFFUL;
    r2 = (((unsigned long)key[6] >> 4) | ((unsigned long)key[7] << 4) |
          ((unsigned long)key[8] << 12) | ((unsigned long)key[9] << 20)) & 0x3FFFFFFUL;
    r3 = (((unsigned long)key[9] >> 6) | ((unsigned long)key[10] << 2) |
          ((unsigned long)key[11] << 10) | ((unsigned long)key[12] << 18)) & 0x3FFFFFFUL;
    r4 = (((unsigned long)key[12] >> 8) | ((unsigned long)key[13] << 0) |
          ((unsigned long)key[14] << 8) | ((unsigned long)key[15] << 16)) & 0x3FFFFFFUL;

    p0 = (unsigned long)key[16] | ((unsigned long)key[17] << 8) |
         ((unsigned long)key[18] << 16) | ((unsigned long)key[19] << 24);
    p1 = (unsigned long)key[20] | ((unsigned long)key[21] << 8) |
         ((unsigned long)key[22] << 16) | ((unsigned long)key[23] << 24);
    p2 = (unsigned long)key[24] | ((unsigned long)key[25] << 8) |
         ((unsigned long)key[26] << 16) | ((unsigned long)key[27] << 24);
    p3 = (unsigned long)key[28] | ((unsigned long)key[29] << 8) |
         ((unsigned long)key[30] << 16) | ((unsigned long)key[31] << 24);

    while (off < len) {
        unsigned long n = len - off;
        unsigned char blk[17];
        unsigned long t0, t1, t2, t3, t4, t5;
        if (n > 16) n = 16;
        for (i = 0; i < 17; i++) blk[i] = 0;
        for (i = 0; i < (int)n; i++) blk[i] = msg[off + i];
        blk[n] = 0x01;            /* 隐含高位：2^(8*n) */

        t0 = ((unsigned long)blk[0] | ((unsigned long)blk[1] << 8) |
              ((unsigned long)blk[2] << 16) | ((unsigned long)blk[3] << 24)) & 0x3FFFFFFUL;
        t1 = (((unsigned long)blk[3] >> 2) | ((unsigned long)blk[4] << 6) |
              ((unsigned long)blk[5] << 14) | ((unsigned long)blk[6] << 22)) & 0x3FFFFFFUL;
        t2 = (((unsigned long)blk[6] >> 4) | ((unsigned long)blk[7] << 4) |
              ((unsigned long)blk[8] << 12) | ((unsigned long)blk[9] << 20)) & 0x3FFFFFFUL;
        t3 = (((unsigned long)blk[9] >> 6) | ((unsigned long)blk[10] << 2) |
              ((unsigned long)blk[11] << 10) | ((unsigned long)blk[12] << 18)) & 0x3FFFFFFUL;
        t4 = (((unsigned long)blk[12] >> 8) | ((unsigned long)blk[13] << 0) |
              ((unsigned long)blk[14] << 8) | ((unsigned long)blk[15] << 16)) & 0x1FFFFFFUL;
        t5 = (unsigned long)blk[16] & 0x03UL;

        h0 += t0;
        h1 += t1;
        h2 += t2;
        h3 += t3;
        h4 += t4 | (t5 << 25);

        d0 = (unsigned long long)h0 * r0 + (unsigned long long)h1 * (r4 * 5) +
             (unsigned long long)h2 * (r3 * 5) + (unsigned long long)h3 * (r2 * 5) +
             (unsigned long long)h4 * (r1 * 5);
        d1 = (unsigned long long)h0 * r1 + (unsigned long long)h1 * r0 +
             (unsigned long long)h2 * (r4 * 5) + (unsigned long long)h3 * (r3 * 5) +
             (unsigned long long)h4 * (r2 * 5);
        d2 = (unsigned long long)h0 * r2 + (unsigned long long)h1 * r1 +
             (unsigned long long)h2 * r0 + (unsigned long long)h3 * (r4 * 5) +
             (unsigned long long)h4 * (r3 * 5);
        d3 = (unsigned long long)h0 * r3 + (unsigned long long)h1 * r2 +
             (unsigned long long)h2 * r1 + (unsigned long long)h3 * r0 +
             (unsigned long long)h4 * (r4 * 5);
        d4 = (unsigned long long)h0 * r4 + (unsigned long long)h1 * r3 +
             (unsigned long long)h2 * r2 + (unsigned long long)h3 * r1 +
             (unsigned long long)h4 * r0;

        c = (unsigned long)(d0 >> 26); h0 = (unsigned long)d0 & 0x3FFFFFFUL;
        dd = d1 + c; c = (unsigned long)(dd >> 26); h1 = (unsigned long)dd & 0x3FFFFFFUL;
        dd = d2 + c; c = (unsigned long)(dd >> 26); h2 = (unsigned long)dd & 0x3FFFFFFUL;
        dd = d3 + c; c = (unsigned long)(dd >> 26); h3 = (unsigned long)dd & 0x3FFFFFFUL;
        dd = d4 + c; c = (unsigned long)(dd >> 26); h4 = (unsigned long)dd & 0x3FFFFFFUL;
        h0 += c * 5;
        c = h0 >> 26; h0 &= 0x3FFFFFFUL;
        h1 += c;

        off += n;
    }

    /* 进位归一 */
    c = h1 >> 26; h1 &= 0x3FFFFFFUL; h2 += c;
    c = h2 >> 26; h2 &= 0x3FFFFFFUL; h3 += c;
    c = h3 >> 26; h3 &= 0x3FFFFFFUL; h4 += c;
    c = h4 >> 26; h4 &= 0x3FFFFFFUL; h0 += c * 5;
    c = h0 >> 26; h0 &= 0x3FFFFFFUL; h1 += c;

    /* h -= (2^130 - 5) */
    f0 = h0 + 5; c = f0 >> 26; f0 &= 0x3FFFFFFUL;
    f1 = h1 + c; c = f1 >> 26; f1 &= 0x3FFFFFFUL;
    f2 = h2 + c; c = f2 >> 26; f2 &= 0x3FFFFFFUL;
    f3 = h3 + c; c = f3 >> 26; f3 &= 0x3FFFFFFUL;
    f4 = h4 + c;
    if (f4 >> 26) {
        h0 = f0 & 0x3FFFFFFUL;
        h1 = f1 & 0x3FFFFFFUL;
        h2 = f2 & 0x3FFFFFFUL;
        h3 = f3 & 0x3FFFFFFUL;
        h4 = f4 & 0x3FFFFFFUL;
    }

    g0 = h0 | (h1 << 26);
    g1 = (h1 >> 6) | (h2 << 20);
    g2 = (h2 >> 12) | (h3 << 14);
    g3 = (h3 >> 18) | (h4 << 8);

    sum = (g0 & 0xFFFFFFFFUL) + p0; g0 = sum & 0xFFFFFFFFUL;
    sum = (g1 & 0xFFFFFFFFUL) + p1 + (sum >> 32); g1 = sum & 0xFFFFFFFFUL;
    sum = (g2 & 0xFFFFFFFFUL) + p2 + (sum >> 32); g2 = sum & 0xFFFFFFFFUL;
    sum = (g3 & 0xFFFFFFFFUL) + p3 + (sum >> 32); g3 = sum & 0xFFFFFFFFUL;

    tag[0]  = (unsigned char)(g0 & 0xFF);
    tag[1]  = (unsigned char)((g0 >> 8) & 0xFF);
    tag[2]  = (unsigned char)((g0 >> 16) & 0xFF);
    tag[3]  = (unsigned char)((g0 >> 24) & 0xFF);
    tag[4]  = (unsigned char)(g1 & 0xFF);
    tag[5]  = (unsigned char)((g1 >> 8) & 0xFF);
    tag[6]  = (unsigned char)((g1 >> 16) & 0xFF);
    tag[7]  = (unsigned char)((g1 >> 24) & 0xFF);
    tag[8]  = (unsigned char)(g2 & 0xFF);
    tag[9]  = (unsigned char)((g2 >> 8) & 0xFF);
    tag[10] = (unsigned char)((g2 >> 16) & 0xFF);
    tag[11] = (unsigned char)((g2 >> 24) & 0xFF);
    tag[12] = (unsigned char)(g3 & 0xFF);
    tag[13] = (unsigned char)((g3 >> 8) & 0xFF);
    tag[14] = (unsigned char)((g3 >> 16) & 0xFF);
    tag[15] = (unsigned char)((g3 >> 24) & 0xFF);
}

/* ============================================================
 * 密文真实性校验（长度 + SHA-256 + Poly1305 tag）
 *
 * 为什么不在 ssc_self_check 里做：
 *   那里只有文件描述符，拿不到 _ssc_data_start。
 *   密文符号在本编译单元里，直接指针访问更省事。
 *
 * 返回：0 = 通过，非 0 = 元数据不匹配
 * ============================================================ */
static int ssc_check_payload(const unsigned char *data, unsigned long len) {
    unsigned char meta[SSC_META_SIZE];
    unsigned char got[32];
    unsigned char tag[16];
    unsigned char prk[32];
    ssc_sha_ctx sc;
    unsigned long off;
    unsigned int want_len;
    unsigned int have_len;
    int fd;

    /* 读末尾元数据（读不到就不校验，避免误杀旧版产物）*/
    fd = open(ssc_msg(MSG_EXE_PATH), SSC_O_RDONLY, 0);
    if (fd < 0) return 0;
    {
        long sz = ssc_lseek_end(fd);
        if (sz <= (long)SSC_META_SIZE) { close(fd); return 0; }
        if (ssc_pread(fd, meta, SSC_META_SIZE,
                      (unsigned long long)(sz - (long)SSC_META_SIZE)) != SSC_META_SIZE) {
            close(fd); return 0;
        }
    }
    close(fd);

    /* 1) 长度：必须等于实际嵌入的密文长度 */
    want_len = (unsigned int)meta[SSC_OFF_PAY_LEN] |
               ((unsigned int)meta[SSC_OFF_PAY_LEN+1] << 8) |
               ((unsigned int)meta[SSC_OFF_PAY_LEN+2] << 16) |
               ((unsigned int)meta[SSC_OFF_PAY_LEN+3] << 24);
    have_len = (unsigned int)g_len;
    if (want_len != have_len) return 2;

    /* 2) SHA-256(密文) 与 payload_digest 一致 */
    ssc_sha_init(&sc);
    ssc_sha_update(&sc, data, len);
    ssc_sha_final(&sc, got);
    if (!ssc_ct_eq(got, meta + SSC_OFF_PAY_DIGEST, 32)) return 4;

    /* 3) Poly1305 tag 校验（防篡改后能解出恶意明文）*/
    ssc_derive_prk(prk);
    ssc_poly_auth(prk, data, len, tag);
    if (!ssc_ct_eq(tag, meta + SSC_OFF_PAY_TAG, 16)) {
        /* prk 立即清零 */
        for (off = 0; off < 32; off++) prk[off] = 0;
        return 5;
    }
    for (off = 0; off < 32; off++) prk[off] = 0;

    return 0;
}

int ssc_real_main(int argc, char **argv) {
    /* 引用诱饵字符串表，防止被 -O1 删除。
     *
     * 关键：必须真的读取字符串内容（逐字节累加），
     * 否则编译器只看指针赋值，能证明数据不会被访问，
     * 整张表仍会被优化掉（已实测：elf_strings 只返回 45 条真实字符串）。
     *
     * 累加结果写进 volatile 全局，编译器无法静态求值。
     */
    {
        int di = 0;
        volatile unsigned int dsum = 0;
        while (g_decoy[di]) {
            const char *p = g_decoy[di];
            int ci = 0;
            while (p[ci]) { dsum = dsum + (unsigned int)(unsigned char)p[ci]; ci++; }
            di++;
        }
        g_decoy_sum = dsum;
        if (g_decoy_sum == 0xFFFFFFFFu) return 1;
    }

    /* ---- 第零道：ELF 完整性自检 ----
     *
     * 自我读取 ELF，校验末尾 128 字节元数据里的 SHA-256。
     * 任何字节被改（包括元数据自己）都会失败。
     *
     * argv[0] 不一定是可执行文件路径，所以用 /proc/self/exe。
     */
    if (ssc_self_check(ssc_msg(MSG_EXE_PATH)) != 0) {
        return ssc_fail(C_MAGENTA, MSG_INTEGRITY, E_INTEGRITY);
    }

    /* ---- 第一道：反调试 ---- */
    if (ssc_anti_debug()) {
        return ssc_fail(C_RED, MSG_DEBUGGER, E_DEBUGGER);
    }

    /* ---- 第二道：密钥碎片完整性校验 ---- */
    /* 两个碎片分别算 CRC，再与编译时值比对。
       任一片被 patch 都会对不上。 */
    {
        unsigned long c1 = ssc_crc32(g_ka, g_key_len);
        unsigned long c2 = ssc_crc32(g_kb, g_key_len);
        unsigned long c3 = ssc_crc32(g_kc, g_key_len);
        unsigned long c4 = ssc_crc32(g_kd, g_key_len);
        unsigned long got = (
            c1 ^
            ((c2 * 0x9E3779B1UL) & 0xFFFFFFFFUL) ^
            ((c3 * 0x85EBCA77UL) & 0xFFFFFFFFUL) ^
            ((c4 * 0xC2B2AE3DUL) & 0xFFFFFFFFUL)
        ) & 0xFFFFFFFFUL;
        if (got != g_key_crc) {
            return ssc_fail(C_YELLOW, MSG_TAMPERED, E_TAMPERED);
        }
    }

    /* ---- 第三道：密文真实性（长度 / SHA-256 / Poly1305 tag）----
     *
     * 密文写在 .rodata 里也挡不住直接改它。
     * tag 对不上就说明密文被改过，解出来的明文不可信，直接拒绝。
     */
    if (ssc_check_payload(_ssc_data_start, g_len) != 0) {
        return ssc_fail(C_YELLOW, MSG_TAMPERED, E_TAMPERED);
    }

    /* 分配并复制加密数据（保留原始副本用于 RC4 解密）*/
    unsigned char *buf = (unsigned char *)malloc(g_len + 1);
    if (!buf) {
        return ssc_fail(C_MAGENTA, MSG_NOMEM, E_NOMEM);
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

    /* ---- 解出 RC4 密钥（编译期异或 0x3C 混淆过）----
     *
     * 关键：keybuf 必须 volatile。
     * 否则 -O2 会把「g_key_enc[i] ^ 0x3C」在编译期算出来，
     * 直接把明文密钥写进 .rodata（已实际发生：搜到了 "7ecf656575b260a3"）。
     * volatile 强制运行时逐字节计算，常量折叠失效。
     */
    volatile char keybuf[128];
    unsigned long kl = g_key_len;
    if (kl >= sizeof(keybuf)) kl = sizeof(keybuf) - 1;
    {
        unsigned long i;
        /* 四碎片异或还原密钥：A^B^C^D = key。
           任一碎片单独看都是随机字节。 */
        for (i = 0; i < kl; i++) {
            keybuf[i] = (char)(g_ka[i] ^ g_kb[i] ^ g_kc[i] ^ g_kd[i]);
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
         * 所以总轮数必须严格等于加密时的轮数（RC4_ROUNDS_C），否则解不出来。
         * 而且轮数必须是奇数：偶数轮会相互抵消，密文等于明文。
         *
         * 因此平坦化只能「改变执行方式」，不能改变总次数。
         *
         * 做法：把 N 轮拆成一个状态机，每轮对诱饵缓冲区做一次空转，
         *   给静态分析增加噪音，但 buf 的真实解密次数严格等于 RC4_ROUNDS_C。
         */
        /*
         * 轮次表不再存进 .rodata。
         *
         * 之前用 static const 数组，即使加了 volatile，
         * 数据本身仍会以明文落在只读段（已实测搜到 "ZY_\\[^X]..."）。
         *
         * 现在改成运行时由密钥碎片现场派生，
         * 磁盘上不出现任何连续表。
         */
        unsigned char ord_buf[RC4_ROUNDS_C];
        {
            int s; int kk = (int)kl;
            if (kk <= 0) kk = 1;
            for (s = 0; s < RC4_ROUNDS_C; s++) {
                unsigned int a = g_ka[s % kk];
                unsigned int b = g_kb[s % kk];
                ord_buf[s] = (unsigned char)(((a * 31u) + b + (unsigned int)s) & 0x7F);
            }
        }

        /* 空转用的诱饵缓冲区（尺寸固定，不影响 buf） */
        unsigned char decoy[32];
        int step;

        for (step = 0; step < RC4_ROUNDS_C; step++) {
            unsigned char ord = ord_buf[step];

            /*
             * 这里不再解密整份 buf。
             * 改成标记：只在需要在管道前分块解密。
             * 为避免多轮 RC4 语义不一致，实际解密放到下面的流式循环里统一做。
             */
            (void)ord;

            /* 诱饵：根据表里的值决定空转次数，干扰反编译 */
            {
                int r;
                int times = (int)(ord & 0x03);
                for (r = 0; r < 32; r++) decoy[r] = (unsigned char)(ord + r);
                for (r = 0; r < times; r++) {
                    ssc_rc4(decoy, sizeof(decoy), (const char *)keybuf);
                }
            }
        }

        /* 诱饵缓冲区与派生表清零 */
        {
            int i2;
            for (i2 = 0; i2 < 32; i2++) decoy[i2] = 0;
            for (i2 = 0; i2 < RC4_ROUNDS_C; i2++) ord_buf[i2] = 0;
        }
    }

    /* 密钥暂不清零：下面的流式解密还要用 */

    /* ---- 用管道传给解释器，不落盘 ---- */
    /*
     * SSC 的做法：pipe() + /proc/self/fd/<n>，
     * 脚本内容全程在内存里，磁盘上没有任何副本，
     * ps / 进程列表也看不到脚本路径。
     */
    int pfd[2];
    if (pipe(pfd) != 0) {
        free(buf);
        return ssc_fail(C_CYAN, MSG_PIPE, E_PIPE);
    }

    /* 组装参数：sh /proc/self/fd/<read_fd> [args...] */
    char fdpath[64];
    snprintf(fdpath, sizeof(fdpath), ssc_msg(MSG_FD_FMT), pfd[0]);

    int total = argc + 1;
    char **args = (char **)malloc(sizeof(char *) * (total + 2));
    if (!args) {
        free(buf);
        return ssc_fail(C_MAGENTA, MSG_NOMEM, E_NOMEM);
    }
    args[0] = ssc_msg(MSG_SH_PATH);
    args[1] = fdpath;
    for (int i = 1; i < argc; i++) args[i + 1] = argv[i];
    args[total] = NULL;

    pid_t_ pid = fork();
    if (pid == 0) {
        /*
         * 子进程：把读端复制到 fd 0，然后执行 sh。
         *
         * 关键：
         *   1. 先 dup2(pfd[0], 0) 把管道读端变成标准输入；
         *   2. 再关闭多余的 fd（包括写端 pfd[1]），
         *      否则 sh 读 stdin 时永远等不到 EOF（写端还有副本没关）；
         *   3. 最后直接运行 sh（读 fd 0），不再依赖 /proc/self/fd。
         *
         * 之前用 /proc/self/fd/<n> 且没关写端，导致死锁：
         * 父进程写完等 sh 退出，sh 等管道 EOF（永远等不到）。
         */
        dup2(pfd[0], 0);
        close(pfd[0]);
        close(pfd[1]);

        /* 参数改成：sh -s [原参数...]，-s 表示从 stdin 读脚本 */
        args[1] = (char *)"-s";
        execv(args[0], args);
        _exit(127);
    }

    /* 父进程：流式解密 + 写入，写完关闭写端 */
    if (pid > 0) {
        /* 父进程不读，立即关掉读端，避免自己占用 */
        close(pfd[0]);

        /*
         * 重要：RC4 是流密码，每一轮都作用于整份数据。
         *
         * 之前错误地按 4KB 分块，每块单独跑 17 轮。
         * 那是错的：RC4 的 S 盒状态是连续推进的，
         * 拆块后每块都从初始状态重新开始，
         * 导致只有第一块解对，后面全是乱码
         * （已实际发生：脚本前 93 行正常，之后解码失败）。
         *
         * 正确做法：必须整份跑完 17 轮，再分块写管道。
         * 写一块立即清零一块，尽量缩短明文存活时间。
         */
        {
            /* 整份 17 轮解密 */
            {
                int round;
                for (round = 0; round < RC4_ROUNDS_C; round++) {
                    ssc_rc4(buf, use_len, (const char *)keybuf);
                }
            }

            /* 分块写入，每写一块立刻清零 */
            {
                unsigned long off = 0;
                while (off < use_len) {
                    unsigned long n = use_len - off;
                    if (n > 4096) n = 4096;

                    unsigned long w = 0;
                    while (w < n) {
                        s64 r = write(pfd[1], buf + off + w, n - w);
                        if (r <= 0) break;
                        w += (unsigned long)r;
                    }

                    /* 已写出的部分清零 */
                    {
                        unsigned long k;
                        for (k = 0; k < w; k++) buf[off + k] = 0;
                    }

                    if (w < n) break;   /* 子进程提前退出 */
                    off += n;
                }
            }
        }

        close(pfd[1]);
    } else {
        close(pfd[0]);
        close(pfd[1]);
    }

    /* 密钥用完了，立即从栈上抹掉。
       流式解密期间密钥必须存活，所以清零放在这里。 */
    {
        unsigned long i;
        for (i = 0; i < kl; i++) keybuf[i] = 0;
    }

    /* 密文缓冲也清零（虽然只是密文，但减少可分析痕迹）*/
    {
        unsigned long i;
        for (i = 0; i < use_len; i++) buf[i] = 0;
    }
    free(buf);

    int st = 0;
    if (pid > 0) waitpid(pid, &st, 0);
    free(args);

    if (pid < 0) {
        return ssc_fail(C_CYAN, MSG_EXEC, E_EXEC);
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

        // ============================================================
        // 优化级别必须是 -O1，不能用 -O2/-O3。
        //
        // -O2 实测造成的问题：
        //   1. 把 g_key_enc[i] ^ 0x3C 在编译期算出来，
        //      明文 RC4 密钥直接写进 .rodata（搜到了 "7ecf656575b260a3"）；
        //   2. 把 '0' + (n%10) 优化成 256 字节查表；
        //   3. 把 g_order_enc 折叠成顺序数组。
        // -O1 保留基本优化，但不会做这种跨语句的常量折叠。
        // ============================================================
        args.add("-O1")

        // 禁用内置函数优化：阻止 '0'+n → 查表、memcpy → 内联展开 等
        args.add("-fno-builtin")

        // 注意：不用 -fkeep-static-consts（那是 GCC 参数，clang 不认）。
        // clang 下用 __attribute__((used)) 保数据，已在源码里处理。
        // -fno-builtin 已经在那里，不再加无效参数。

        // 去掉编译器指纹（.comment 段里的 "clang version x.x"）
        args.add("-fno-ident")

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

        // 剥离所有符号：不留 .symtab / .strtab。
        //
        // 不剥离的话，`elf_symbols` 能直接列出：
        //   ssc_fail / ssc_anti_debug / _ssc_data_start / g_order_enc ...
        // 逆向者一眼就知道用了哪些手法（已实际发生）。
        // ld.lld 的 -s 等价于 --strip-all。
        args.add("-s")

        // 注意：ld.lld 不支持 --discard-section（那是 GNU ld 的参数），
        // 加了会报 unknown argument。
        // .comment 已经在 clang 侧用 -fno-ident 阻止生成，不需要在链接时丢弃。

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
