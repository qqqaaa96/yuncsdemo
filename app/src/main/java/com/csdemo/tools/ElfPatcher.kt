package com.csdemo.tools

import java.io.File
import java.io.FileOutputStream

/**
 * ELF 原地修改。
 *
 * 只做一种操作：把已有字符串槽位里的内容替换为新字符串。
 *
 * 安全边界（很重要）：
 *   • 新字符串字节长度 <= 原长度时才允许，剩余字节用 0 填充。
 *   • 不新增字符串、不改指针、不改节区表、不改任何长度字段。
 *   • 因此不会破坏 ELF 结构；改坏了也只影响那一个字符串的内容。
 *
 * 超长的修改会被拒绘，而不是自作主张地扩写（扩写必然要动指针，容易改坏）。
 */
object ElfPatcher {

    /** 修改结果 */
    data class Result(
        val ok: Boolean,
        val message: String,
        val outputPath: String? = null,
        val writtenLen: Int = 0
    )

    /**
     * 把一个字符串槽位改为 newText。
     *
     * @param srcBytes 原始 ELF 字节（不会修改它）
     * @param loc 目标字符串的定位信息
     * @param newText 新内容
     * @param outFile 输出文件（另存）
     */
    fun replaceString(
        srcBytes: ByteArray,
        loc: ElfParser.LocString,
        newText: String,
        outFile: File
    ): Result {
        // 1. 长度检查
        val newBytes = newText.toByteArray(Charsets.UTF_8)
        if (newBytes.size > loc.maxLen) {
            return Result(
                ok = false,
                message = "新字符串太长：原槽位 " + loc.maxLen +
                        " 字节，新内容 " + newBytes.size +
                        " 字节。原地覆写不能变长（变长需改指针，会破坏文件）。"
            )
        }

        // 2. 偏移检查
        val off = loc.fileOff
        if (off < 0 || off + loc.maxLen > srcBytes.size) {
            return Result(ok = false, message = "偏移越界，文件可能已损坏")
        }

        // 3. 校验原内容确实匹配（防止定位漂移改错地方）
        val oldText = String(srcBytes, off, loc.maxLen, Charsets.UTF_8)
        if (oldText != loc.text) {
            return Result(
                ok = false,
                message = "原内容不匹配（文件可能已被其他操作修改），已取消以免改错位置。"
            )
        }

        // 4. 写新内容
        return try {
            val out = srcBytes.copyOf()
            // 清空整个槽位（含 NUL 区）
            for (i in 0 until loc.maxLen) out[off + i] = 0
            // 写入新内容
            System.arraycopy(newBytes, 0, out, off, newBytes.size)
            // 剩下的字节保持 0（NUL 填充）

            val parent = outFile.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            FileOutputStream(outFile).use { it.write(out) }

            Result(
                ok = true,
                message = "已写入 " + newBytes.size + " 字节，剩余 " +
                        (loc.maxLen - newBytes.size) + " 字节用 NUL 填充",
                outputPath = outFile.absolutePath,
                writtenLen = newBytes.size
            )
        } catch (e: Exception) {
            Result(ok = false, message = "写入失败：" + (e.message ?: "未知错误"))
        }
    }

    /** 生成输出文件名（避免覆盖源文件） */
    fun suggestOutputName(ctx: android.content.Context, srcName: String): File {
        val dir = File(ctx.filesDir, "patched")
        if (!dir.exists()) dir.mkdirs()
        val base = srcName.substringBeforeLast('.')
        val ext = srcName.substringAfterLast('.', "so")
        var f = File(dir, base + "_patched." + ext)
        var n = 1
        while (f.exists()) {
            f = File(dir, base + "_patched_" + n + "." + ext)
            n++
        }
        return f
    }
}
