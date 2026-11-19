package sy.great.toolbox.tools

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

            // ============================================================
            // 关键：目标文件名必须唯一，否则不同脚本会互相覆盖。
            //
            // 旧实现：File(dir, sanitize(name))
            //   → 文件名相同的脚本会写到同一个路径；
            //   → 更糟的是 sanitize 可能把不同名字洗成同一个，
            //   → 结果就是「不管选什么脚本，编译出来都一样」。
            //
            // 现在：每次导入前先清空 imports 目录，并用时间戳保证唯一。
            // ============================================================
            try {
                dir.listFiles()?.forEach { it.delete() }
            } catch (_: Throwable) {
            }

            val safeName = sanitize(name)
            val stamp = System.currentTimeMillis()
            val target = File(dir, "${stamp}_$safeName")

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

    // ---------------- 复制到 MCP 工作目录 ----------------

    /** MCP 工作目录（与 McpService.workDir() 保持一致） */
    const val MCP_DIR = "/storage/emulated/0/ELF分析处/mcp"

    /**
     * 是否有「所有文件访问」权限。
     *
     * Android 11+ 普通存储权限已失效，写共享存储根目录只能靠 MANAGE_EXTERNAL_STORAGE。
     * 低于 R 的设备不会走到这里（minSdk 33）。
     */
    fun hasAllFilesAccess(): Boolean = try {
        android.os.Environment.isExternalStorageManager()
    } catch (_: Throwable) {
        false
    }

    /**
     * 把已导入的文件复制一份到 MCP 工作目录，供 MCP 工具直接读取。
     *
     * 目录不存在会自动创建；同名文件存在则依次加 (2)(3)… 后缀，不覆盖。
     * 返回目标文件路径，失败返回 null。
     */
    fun copyToMcpDir(srcPath: String, displayName: String): String? {
        return try {
            val src = File(srcPath)
            if (!src.exists() || !src.canRead()) return null

            val dir = File(MCP_DIR)
            if (!dir.exists() && !dir.mkdirs()) return null
            if (!dir.isDirectory) return null

            val safeName = sanitize(displayName.ifBlank { src.name })
            val target = uniqueTarget(dir, safeName)

            src.inputStream().use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            target.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    /** 同名时依次尝试 name(2).ext、name(3).ext… */
    private fun uniqueTarget(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first

        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (i < 1000) {
            val f = File(dir, "$base($i)$ext")
            if (!f.exists()) return f
            i++
        }
        return File(dir, "$base(${System.currentTimeMillis()})$ext")
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

    /** 只过滤文件系统非法字符，保留中文（ELF 文件名常带中文，不能一律变 _） */
    private fun sanitize(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
            .trim()
            .trim('.')
        return if (cleaned.isEmpty()) "file.bin" else cleaned
    }
}
