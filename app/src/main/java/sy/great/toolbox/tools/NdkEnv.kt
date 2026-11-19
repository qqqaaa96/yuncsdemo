package sy.great.toolbox.tools

import android.content.Context
import java.io.File

/**
 * NDK 工具链环境管理。
 *
 * 设计（参考 AIDE）：
 *   · 解压到 App 私有目录 filesDir/ndk/
 *     → 不需要任何存储权限，不受 Scoped Storage 限制
 *   · 二进制是 noexec 目录，用 /system/bin/linker64 拉起（同 ShellObfuscator）
 *   · 不需要 root / ADB / MANAGE_EXTERNAL_STORAGE
 *
 * 目录结构（以官方 NDK 为例）：
 *   android-ndk-r27/
 *     toolchains/llvm/prebuilt/linux-x86_64/bin/clang
 *     toolchains/llvm/prebuilt/linux-x86_64/sysroot/
 *     prebuilt/android-aarch64/  ← Termux 包可能不同
 *
 * 本类不假设固定路径，而是扫描找 clang 可执行文件。
 */
object NdkEnv {

    /** NDK 根目录（App 私有，无需任何权限） */
    fun rootDir(ctx: Context): File = File(ctx.filesDir, "ndk")

    /** 解压后的实际 NDK 根（可能有一层 android-ndk-rNN 包裹） */
    private fun detectNdkRoot(ctx: Context): File? {
        val root = rootDir(ctx)
        if (!root.exists()) return null

        // 情况 1：root 下直接就是 toolchains/
        if (File(root, "toolchains").isDirectory) return root

        // 情况 2：root 下有一个子目录（解开压缩包后多一层）
        val children = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (c in children) {
            if (File(c, "toolchains").isDirectory) return c
        }
        // 情况 3：Termux 风格，直接就是 bin/ + sysroot/
        for (c in children) {
            if (findClangIn(c) != null) return c
        }
        if (findClangIn(root) != null) return root
        return null
    }

    /**
     * 在指定目录下递归找 clang。
     *
     * 优先找 toolchains/llvm/prebuilt/<host>/bin/clang，
     * 这才是 NDK 里真正的编译器驱动。
     *
     * 注意：注释里绝对不能出现星号加斜杠的组合（会提前结束块注释，已踩过）。
     */
    private fun findClangIn(dir: File, depth: Int = 0): File? {
        if (depth > 6) return null
        val files = dir.listFiles() ?: return null

        // 第一轮：只看 bin/ 目录里的，优先级最高
        for (f in files) {
            if (f.isFile && f.parentFile?.name == "bin" && isClangBinary(f)) return f
        }
        // 第二轮：任意位置
        for (f in files) {
            if (f.isFile && isClangBinary(f)) return f
        }
        for (f in files) {
            if (f.isDirectory) {
                findClangIn(f, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun isClangBinary(f: File): Boolean {
        val n = f.name
        // 只要 clang 主程序：clang / clang-18 / clang-17
        // 不能要 clang-check / clang-format / clang-tidy 等 LLVM 工具，
        // 它们名字也以 clang 开头，但并不是编译器驱动。
        if (n == "clang") {
            // 命中
        } else if (n.startsWith("clang-") && n.drop(6).all { it.isDigit() } && n.length in 7..9) {
            // clang-18 / clang-17 这类带版本号的
        } else {
            return false
        }
        if (!f.isFile || f.length() < 1024) return false
        // 检查 ELF 魔数
        return try {
            f.inputStream().use { input ->
                val b = ByteArray(4)
                if (input.read(b) != 4) return false
                b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte() &&
                    b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** 工具链状态 */
    data class Status(
        val ready: Boolean,
        /** clang 可执行文件路径 */
        val clangPath: String = "",
        /** NDK 根目录 */
        val ndkRoot: String = "",
        /** sysroot 路径（可能为空） */
        val sysroot: String = "",
        /** 不可用时的原因 */
        val reason: String = "",
    )

    /** 检测工具链是否就绪 */
    fun check(ctx: Context): Status {
        val root = detectNdkRoot(ctx)
            ?: return Status(false, reason = "未检测到 NDK，请先上传压缩包")

        val clang = findClangIn(root)
            ?: return Status(false, ndkRoot = root.absolutePath, reason = "NDK 目录里没找到 clang 可执行文件")

        // 找 sysroot（不一定有，但没有它编不出 Android ELF）
        val sysroot = findSysroot(root)

        return Status(
            ready = true,
            clangPath = clang.absolutePath,
            ndkRoot = root.absolutePath,
            sysroot = sysroot?.absolutePath ?: "",
        )
    }

    /** 找 sysroot 目录 */
    private fun findSysroot(root: File, depth: Int = 0): File? {
        if (depth > 6) return null
        val files = root.listFiles() ?: return null
        for (f in files) {
            if (f.isDirectory && f.name == "sysroot") return f
        }
        for (f in files) {
            if (f.isDirectory) {
                findSysroot(f, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** 找 linker64（用于绕过 noexec） */
    fun findLinker(): String? {
        val list = listOf(
            "/system/bin/linker64",
            "/apex/com.android.runtime/bin/linker64",
            "/system/bin/linker",
        )
        for (p in list) {
            val f = File(p)
            if (f.exists() && f.canExecute()) return p
        }
        return null
    }

    /** 清空 NDK 目录（重新上传时用） */
    fun clear(ctx: Context): Boolean {
        return try {
            rootDir(ctx).deleteRecursively()
        } catch (_: Throwable) {
            false
        }
    }

    /** NDK 目录占用大小（展示用） */
    fun usedBytes(ctx: Context): Long {
        val root = rootDir(ctx)
        if (!root.exists()) return 0
        return try {
            root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Throwable) {
            0
        }
    }
}
