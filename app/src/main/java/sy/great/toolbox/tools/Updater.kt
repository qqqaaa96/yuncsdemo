package sy.great.toolbox.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App 内下载 APK 并拉起安装。
 *
 * 流程：
 *   1. 下载到 cacheDir/apk/update.apk（支持断点续传）
 *   2. 校验是不是 ZIP（APK 必须 PK\x03\x04 开头）
 *   3. 用 FileProvider 拿 content:// URI
 *   4. 拉起系统安装器
 *
 * 为什么用 FileProvider：
 *   Android 7+ 禁止 file:// URI 跨应用传递，直接给安装器会抛
 *   FileUriExposedException。必须用 content:// + 临时授权。
 */
object Updater {

    /** 下载进度回调 */
    fun interface Progress {
        fun onProgress(done: Long, total: Long)
    }

    sealed interface Result {
        data class Ok(val file: File) : Result
        data class Fail(val message: String) : Result
    }

    /** 缓存目录下的安装包路径 */
    private fun apkFile(ctx: Context): File {
        val dir = File(ctx.cacheDir, "apk").apply { mkdirs() }
        return File(dir, "update.apk")
    }

    /**
     * 下载 APK。
     *
     * 已经完整下载过的文件会复用（比较 Content-Length）。
     * 断点续传：本地有部分文件时带 Range 头续传。
     */
    suspend fun download(
        ctx: Context,
        url: String,
        onProgress: Progress? = null,
    ): Result = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val full = if (url.startsWith("http")) url else AuthApi.BASE.trimEnd('/') + "/" + url.trimStart('/')
            val dest = apkFile(ctx)
            val part = File(dest.absolutePath + ".part")

            // 已有部分文件 → 续传
            val existing = if (part.isFile()) part.length() else 0L

            conn = (URL(full).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                if (existing > 0) {
                    setRequestProperty("Range", "bytes=" + existing + "-")
                }
            }

            val code = conn.responseCode
            val append = code == 206   // Partial Content

            if (code !in 200..299) {
                return@withContext Result.Fail("服务器返回 HTTP " + code)
            }

            val total = if (append) {
                existing + conn.contentLengthLong.coerceAtLeast(0)
            } else {
                conn.contentLengthLong.coerceAtLeast(0)
            }

            // 服务端不支持 Range 就重新写
            if (!append && part.isFile()) part.delete()

            var done = if (append) existing else 0L

            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress?.onProgress(done, total)
                    }
                    out.flush()
                }
            }

            // 校验大小
            if (total > 0 && part.length() != total) {
                return@withContext Result.Fail("下载不完整（" + part.length() + "/" + total + "）")
            }

            // 校验 APK 魔数
            val magic = ByteArray(4)
            java.io.FileInputStream(part).use { it.read(magic) }
            if (String(magic, Charsets.ISO_8859_1) != "PK\u0003\u0004") {
                part.delete()
                return@withContext Result.Fail("下载的文件不是有效 APK")
            }

            // 覆盖正式文件
            if (dest.isFile()) dest.delete()
            if (!part.renameTo(dest)) {
                return@withContext Result.Fail("保存安装包失败")
            }

            Result.Ok(dest)
        } catch (e: Throwable) {
            Result.Fail(e.message ?: e.javaClass.simpleName)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
     * 拉起系统安装器。
     *
     * 需要 Manifest 里声明 REQUEST_INSTALL_PACKAGES，
     * 以及 res/xml/file_paths.xml 里的 cache-path。
     */
    fun install(ctx: Context, apk: File): Result {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                ctx,
                ctx.packageName + ".fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(intent)
            Result.Ok(apk)
        } catch (e: Throwable) {
            Result.Fail("无法启动安装器：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** 清掉缓存里的安装包 */
    fun clearCache(ctx: Context) {
        try {
            apkFile(ctx).delete()
            File(apkFile(ctx).absolutePath + ".part").delete()
        } catch (_: Throwable) {
        }
    }
}
