package com.csdemo.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * 文件导入。
 *
 * 支持从系统文件选择器（SAF）导入，也支持直接路径。
 * 导入后的文件拷贝到应用私有目录，便于反复读取。
 */
object FileImporter {

    data class Imported(
        val displayName: String,
        val size: Long,
        val localPath: String
    )

    /** 从 content Uri 导入到应用缓存目录 */
    fun importFromUri(ctx: Context, uri: Uri): Imported? {
        return try {
            val name = queryName(ctx, uri) ?: "imported.bin"
            val dir = File(ctx.filesDir, "imports")
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, sanitize(name))

            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            Imported(
                displayName = name,
                size = target.length(),
                localPath = target.absolutePath
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 直接读本地路径 */
    fun importFromPath(path: String): Imported? {
        return try {
            val f = File(path)
            if (!f.exists() || !f.canRead()) return null
            Imported(
                displayName = f.name,
                size = f.length(),
                localPath = f.absolutePath
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 读取导入文件的字节 */
    fun read(path: String): ByteArray? = try {
        val f = File(path)
        if (f.exists() && f.canRead()) f.readBytes() else null
    } catch (e: Exception) {
        null
    }

    private fun queryName(ctx: Context, uri: Uri): String? {
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sanitize(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
