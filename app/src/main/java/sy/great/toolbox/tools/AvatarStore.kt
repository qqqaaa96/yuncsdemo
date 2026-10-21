package sy.great.toolbox.tools

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 头像处理与缓存。
 *
 * 职责：
 *   1. 从相册选图 → 居中裁方 → 缩放 → 压缩成 JPEG
 *   2. 本地缓存（filesDir/avatar_<uid>.jpg），断网也能显示
 *   3. 从服务端拉取并刷新缓存
 */
object AvatarStore {

    /** 本地缓存文件 */
    fun localFile(ctx: Context, uid: Int): File = File(ctx.filesDir, "avatar_$uid.jpg")

    /** 本地是否有头像缓存文件（不判断内容有效性） */
    fun hasLocal(ctx: Context, uid: Int): Boolean {
        return try {
            val f = localFile(ctx, uid)
            f.exists() && f.length() > 100
        } catch (_: Throwable) {
            false
        }
    }

    /** 读本地缓存，没有则返回 null */
    fun loadLocal(ctx: Context, uid: Int): Bitmap? {
        return try {
            val f = localFile(ctx, uid)
            if (!f.exists() || f.length() == 0L) return null
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (_: Throwable) {
            null
        }
    }

    /** 写入本地缓存 */
    fun saveLocal(ctx: Context, uid: Int, jpeg: ByteArray) {
        try {
            val f = localFile(ctx, uid)
            f.writeBytes(jpeg)
        } catch (_: Throwable) {
        }
    }

    /**
     * 从相册选图：居中裁方 → 缩放到 size×size → JPEG 压缩。
     *
     * @return JPEG 字节；失败返回 null
     */
    fun pickAndCompress(
        resolver: ContentResolver,
        uri: Uri,
        size: Int = 512,
        quality: Int = 88
    ): ByteArray? {
        return try {
            // 一次性把字节读进内存，再解码。
            //
            // 不能开两次 openInputStream：
            //   相册返回的 Uri 是临时授权，第二次打开可能失败；
            //   两次流的解码状态也不一致，容易拿到 null。
            val raw = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            if (raw.isEmpty()) return null

            // 先读尺寸，决定采样率，避免大图 OOM
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = calcSample(bounds.outWidth, bounds.outHeight, size * 2)
            }

            val src = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
                ?: return null

            // 居中裁正方形
            val side = minOf(src.width, src.height)
            val x = (src.width - side) / 2
            val y = (src.height - side) / 2
            val square = if (side < src.width || side < src.height)
                Bitmap.createBitmap(src, x, y, side, side) else src

            // 缩放
            val scaled = if (square.width != size) {
                Bitmap.createScaledBitmap(square, size, size, true)
            } else square

            val out = ByteArrayOutputStream(64 * 1024)
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)

            if (scaled !== src) scaled.recycle()
            if (square !== src && square !== scaled) square.recycle()
            src.recycle()

            val data = out.toByteArray()
            if (data.isEmpty()) null else data
        } catch (e: Throwable) {
            // 保留最后一次失败原因，供界面显示
            lastError = (e.message ?: e.javaClass.simpleName)
            null
        }
    }

    /** 最近一次 pickAndCompress 失败的原因（供界面提示） */
    @Volatile
    var lastError: String = ""
        private set

    private fun calcSample(w: Int, h: Int, target: Int): Int {
        var sample = 1
        var cur = maxOf(w, h)
        while (cur / 2 >= target) {
            cur /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * 从服务端拉取头像并写入本地缓存。
     *
     * @param version 服务端口径的 avatar_at，用于防缓存
     * @return 拉取成功返回 true
     */
    fun fetchAndCache(ctx: Context, uid: Int, version: Int): Boolean {
        if (uid <= 0 || version <= 0) return false
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(AuthApi.avatarUrl(uid, version))
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                doInput = true
            }
            if (conn.responseCode !in 200..299) return false
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size < 100) return false
            saveLocal(ctx, uid, bytes)
            true
        } catch (_: Throwable) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}
