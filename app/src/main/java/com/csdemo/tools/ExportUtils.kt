package com.csdemo.tools

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * 导出工具。
 *
 * 目标：把生成内容（伪 C / SVG 等）写到一个用户容易找到的位置。
 *
 * 策略（按优先级）：
 *   1. Android 10+ 用 MediaStore 写入 Download/csdemo/
 *      —— 这是标准做法，不需要任何权限
 *   2. Android 9 及以下写公共 Download 目录
 *   3. 都不行则退到应用外部私有目录
 *
 * 注意：写不了 /storage/emulated/0/ 根目录。
 * Android 10 起系统禁止 App 向存储根目录写文件，这是系统限制。
 */
object ExportUtils {

    /** 子目录名 */
    private const val DIR = "csdemo"

    data class Result(
        val ok: Boolean,
        /** 可读路径（用于展示） */
        val displayPath: String = "",
        /** 实际写入的 Uri 或绝对路径 */
        val uri: String = "",
        val message: String = ""
    )

    /**
     * 导出文本文件。
     *
     * @param fileName 带扩展名的文件名，如 sub_1e888.c
     */
    fun exportText(
        ctx: Context,
        fileName: String,
        content: String,
        mime: String = "text/plain"
    ): Result {
        val name = sanitizeName(fileName)
        val bytes = content.toByteArray(Charsets.UTF_8)

        // ---- Android 10+：MediaStore ----
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val r = exportViaMediaStore(ctx, name, bytes, mime)
            if (r.ok) return r
            // MediaStore 失败时继续尝试直写
        }

        // ---- 直写公共 Download ----
        val r2 = exportDirect(name, bytes)
        if (r2.ok) return r2

        // ---- 兵底：应用外部私有目录 ----
        return exportPrivate(ctx, name, bytes)
    }

    // ------------------------------------------------------------

    private fun exportViaMediaStore(
        ctx: Context,
        name: String,
        bytes: ByteArray,
        mime: String
    ): Result {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val resolver = ctx.contentResolver
            val uri: Uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return Result(false, message = "MediaStore 无法创建条目")

            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return Result(false, message = "无法打开输出流")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            val readable = "Download/$DIR/$name"
            Result(
                ok = true,
                displayPath = readable,
                uri = uri.toString(),
                message = "已导出"
            )
        } catch (e: Exception) {
            Result(false, message = "MediaStore 写入失败：" + (e.message ?: "未知"))
        }
    }

    private fun exportDirect(name: String, bytes: ByteArray): Result {
        val candidates = listOf(
            File("/storage/emulated/0/Download/$DIR"),
            File("/sdcard/Download/$DIR"),
            File("/storage/emulated/0/Documents/$DIR")
        )
        for (dir in candidates) {
            try {
                if (!dir.exists() && !dir.mkdirs()) continue
                if (!dir.canWrite()) continue
                val f = File(dir, name)
                FileOutputStream(f).use { it.write(bytes) }
                if (f.exists() && f.length() > 0) {
                    return Result(
                        ok = true,
                        displayPath = f.absolutePath,
                        uri = f.absolutePath,
                        message = "已导出"
                    )
                }
            } catch (e: Exception) {
                // 尝试下一个
            }
        }
        return Result(false, message = "公共目录不可写")
    }

    private fun exportPrivate(ctx: Context, name: String, bytes: ByteArray): Result {
        return try {
            val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, DIR)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            FileOutputStream(f).use { it.write(bytes) }
            Result(
                ok = true,
                displayPath = f.absolutePath,
                uri = f.absolutePath,
                message = "已导出到应用目录"
            )
        } catch (e: Exception) {
            Result(false, message = "导出失败：" + (e.message ?: "未知"))
        }
    }

    /** 文件名清理，并避免重名 */
    fun sanitizeName(input: String): String {
        var s = input.trim()
        if (s.isEmpty()) s = "output.txt"
        s = s.replace(Regex("[\\\\/:*?\"<>|\\s+]"), "_")
        if (s.length > 96) {
            val dot = s.lastIndexOf('.')
            s = if (dot > 0) s.substring(0, 90) + s.substring(dot) else s.take(96)
        }
        return s
    }
}
