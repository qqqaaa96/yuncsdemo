package sy.great.toolbox.tools

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * NDK 压缩包解压（无第三方依赖）。
 *
 * 支持格式：
 *   .zip          —— java.util.zip.ZipInputStream
 *   .tgz / .tar.gz —— GZIPInputStream + tar 解析
 *   .tar          —— tar 解析
 *   7z 不支持（提示用户转换）
 *
 * 设计要点：
 *   1. 全程流式处理，不把整个包读进内存（NDK 包可能 600MB+）。
 *   2. 防路径穿越：拒绝 .. 和绝对路径（安全）。
 *   3. 保存 Unix 权限位，解压后执行 chmod 恢复（clang 需要 755）。
 *   4. 进度回调：每写入 N 字节 / 每解一个文件就回调一次。
 */
object ArchiveExtractor {

    /** 支持的后缀 */
    val SUPPORTED = listOf("zip", "tgz", "tar.gz", "tar")

    fun isSupported(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".zip") || n.endsWith(".tgz") ||
            n.endsWith(".tar.gz") || n.endsWith(".tar")
    }

    /** 解压进度 */
    data class Progress(
        /** 已解压的文件数 */
        val filesDone: Int = 0,
        /** 已写入字节数 */
        val bytesWritten: Long = 0,
        /** 当前正在解的文件名（用于弹窗展示） */
        val currentFile: String = "",
        /** 当前阶段（如 "正在解压" / "恢复权限"） */
        val stage: String = "",
        /** 0..1；无法估算总大小时为 -1 */
        val fraction: Float = -1f,
    )

    data class Result(
        val ok: Boolean,
        val message: String = "",
        /** 解压出的顶层目录（如果整个包只有一个根目录） */
        val topDir: File? = null,
        val filesExtracted: Int = 0,
        val bytesExtracted: Long = 0,
    )

    /**
     * 解压到目标目录。
     *
     * @param archive    压缩包本地路径
     * @param destDir    解压目标（会被清空重建）
     * @param onProgress 进度回调（可能在 IO 线程）
     */
    fun extract(
        archive: File,
        destDir: File,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        if (!archive.exists() || !archive.canRead()) {
            return Result(false, "压缩包不存在或不可读")
        }
        val name = archive.name.lowercase()
        if (!isSupported(name)) {
            return Result(false, "不支持的格式。请提供 .zip / .tgz / .tar.gz / .tar")
        }

        // 清空目标目录
        try {
            if (destDir.exists()) destDir.deleteRecursively()
            if (!destDir.mkdirs()) return Result(false, "无法创建目录：${destDir.absolutePath}")
        } catch (e: Throwable) {
            return Result(false, "准备目录失败：${e.message}")
        }

        val totalLen = archive.length()
        var bytesWritten = 0L
        var filesDone = 0
        // 记录需要恢复权限的文件（路径 -> 权限位）
        val permMap = HashMap<File, Int>()

        try {
            val raw = archive.inputStream()
            val buffered = BufferedInputStream(raw, 64 * 1024)

            val result = when {
                name.endsWith(".zip") -> extractZip(
                    buffered, destDir, totalLen,
                    onBytes = { n ->
                        bytesWritten += n
                        onProgress(
                            Progress(
                                filesDone = filesDone,
                                bytesWritten = bytesWritten,
                                fraction = if (totalLen > 0) (bytesWritten.toFloat() / totalLen).coerceIn(0f, 1f) else -1f,
                                stage = "正在解压"
                            )
                        )
                    },
                    onFile = { f ->
                        filesDone++
                        onProgress(
                            Progress(
                                filesDone = filesDone,
                                bytesWritten = bytesWritten,
                                currentFile = f,
                                fraction = if (totalLen > 0) (bytesWritten.toFloat() / totalLen).coerceIn(0f, 1f) else -1f,
                                stage = "正在解压"
                            )
                        )
                    }
                )
                else -> extractTar(
                    if (name.endsWith(".tgz") || name.endsWith(".tar.gz")) {
                        GZIPInputStream(buffered, 64 * 1024)
                    } else {
                        buffered
                    },
                    destDir,
                    permMap,
                    totalLen,
                    onBytes = { n ->
                        bytesWritten += n
                        onProgress(
                            Progress(
                                filesDone = filesDone,
                                bytesWritten = bytesWritten,
                                fraction = if (totalLen > 0) (bytesWritten.toFloat() / totalLen).coerceIn(0f, 1f) else -1f,
                                stage = "正在解压"
                            )
                        )
                    },
                    onFile = { f ->
                        filesDone++
                        onProgress(
                            Progress(
                                filesDone = filesDone,
                                bytesWritten = bytesWritten,
                                currentFile = f,
                                fraction = if (totalLen > 0) (bytesWritten.toFloat() / totalLen).coerceIn(0f, 1f) else -1f,
                                stage = "正在解压"
                            )
                        )
                    }
                )
            }

            if (!result.ok) return result

            // ---- 恢复 Unix 权限（tar 才有）----
            if (permMap.isNotEmpty()) {
                onProgress(Progress(filesDone = filesDone, bytesWritten = bytesWritten, stage = "恢复文件权限"))
                permMap.forEach { (f, mode) ->
                    try {
                        // 只关心可执行位：clang / ld / ar 需要 755
                        if (mode and 0b001_000_000 != 0) {
                            f.setExecutable(true, true)
                            f.setReadable(true, true)
                        }
                    } catch (_: Throwable) {
                    }
                }
            }

            // ---- 找顶层目录 ----
            val children = destDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            val topDir = if (children.size == 1 &&
                (destDir.listFiles()?.count { it.isFile } ?: 0) == 0
            ) children[0] else destDir

            return Result(
                ok = true,
                topDir = topDir,
                filesExtracted = filesDone,
                bytesExtracted = bytesWritten,
            )
        } catch (e: Throwable) {
            return Result(false, "解压失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ============================================================
    // ZIP
    // ============================================================

    private fun extractZip(
        input: InputStream,
        destDir: File,
        totalLen: Long,
        onBytes: (Int) -> Unit,
        onFile: (String) -> Unit,
    ): Result {
        var count = 0
        ZipInputStream(input).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = safeResolve(destDir, entry.name)
                    ?: return Result(false, "压缩包内含非法路径：${entry.name}")

                if (entry.isDirectory) {
                    // 同名文件已存在时不能 mkdirs（会失败）
                    if (!out.isDirectory) {
                        if (out.exists()) out.delete()
                        out.mkdirs()
                    }
                } else {
                    // 同名目录已存在时先删掉（否则写文件会 EISDIR）
                    if (out.isDirectory) out.deleteRecursively()
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                            onBytes(n)
                        }
                    }
                    // 注意：ZipInputStream 拿到的 entry 不带 Unix 权限位
                    // （externalAttributes 只在 ZipFile 里有，且是 Android 专有 API）。
                    // zip 里的二进制默认 644，clang 等需要可执行，所以批量补上。
                    try { out.setExecutable(true, true) } catch (_: Throwable) {}
                    count++
                    onFile(entry.name)
                }
                zis.closeEntry()
            }
        }
        return Result(true, filesExtracted = count)
    }

    // ============================================================
    // TAR（手写解析，零依赖）
    // ============================================================

    private fun extractTar(
        input: InputStream,
        destDir: File,
        permMap: HashMap<File, Int>,
        totalLen: Long,
        onBytes: (Int) -> Unit,
        onFile: (String) -> Unit,
    ): Result {
        var count = 0
        val header = ByteArray(512)

        fun readFully(buf: ByteArray, len: Int): Int {
            var read = 0
            while (read < len) {
                val n = input.read(buf, read, len - read)
                if (n <= 0) break
                read += n
            }
            return read
        }

        while (true) {
            // 读 512 字节块头
            val got = readFully(header, 512)
            if (got < 512) break

            // 全 0 块 = 归档结束
            if (header.all { it == 0.toByte() }) break

            val name = readTarString(header, 0, 100)
            if (name.isBlank()) break

            val sizeStr = readTarString(header, 124, 12).trim()
            val size = sizeStr.toLongOrNull(8) ?: 0L
            val modeStr = readTarString(header, 100, 8).trim()
            val mode = modeStr.toLongOrNull(8)?.toInt() ?: 0

            // 类型标志：'0' 或 0 = 普通文件，'5' = 目录
            // 注意 header[156] 是 Byte，转成无符号 int 再比，避免符号位问题
            val typeFlag = header[156].toInt() and 0xFF

            val padded = ((size + 511) / 512) * 512

            val out = safeResolve(destDir, name)
                ?: return Result(false, "压缩包内含非法路径：$name")

            if (typeFlag == '5'.code) {
                // 目录
                out.mkdirs()
                // 跳过数据区（目录 size 应为 0）
                if (padded > 0) skipFully(input, padded)
            } else if (typeFlag == '0'.code || typeFlag == 0) {
                // 普通文件
                // 关键：tar 里可能存在「同名目录 + 同名文件」的冲突：
                //   先出现 preprocess/xxx（mkdirs 建了 preprocess 目录）
                //   再出现 preprocess（要写文件）—— 会 EISDIR
                // 这里写文件前先删掉同名目录。
                if (out.isDirectory) {
                    out.deleteRecursively()
                }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { fos ->
                    var left = size
                    val buf = ByteArray(64 * 1024)
                    while (left > 0) {
                        val want = minOf(left, buf.size.toLong()).toInt()
                        val n = input.read(buf, 0, want)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        onBytes(n)
                        left -= n
                    }
                }
                // 恢复权限
                if (mode and 0b001_000_000 != 0) {
                    try { out.setExecutable(true, true) } catch (_: Throwable) {}
                }
                permMap[out] = mode
                count++
                onFile(name)
                // 跳过填充
                val remain = padded - size
                if (remain > 0) skipFully(input, remain)
            } else {
                // 符号链接 / 硬链接等：跳过数据区
                if (padded > 0) skipFully(input, padded)
            }
        }
        return Result(true, filesExtracted = count)
    }

    /** 读 tar 头里的 null 结尾字符串 */
    private fun readTarString(buf: ByteArray, offset: Int, len: Int): String {
        var end = offset
        val max = offset + len
        while (end < max && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun skipFully(input: InputStream, len: Long) {
        var left = len
        val buf = ByteArray(8192)
        while (left > 0) {
            val want = minOf(left, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, want)
            if (n <= 0) break
            left -= n
        }
    }

    /**
     * 安全路径解析：拒绝 .. 与绝对路径，防止路径穿越。
     *
     * 返回 null 表示非法。
     */
    private fun safeResolve(destDir: File, entryName: String): File? {
        val normalized = entryName.replace('\\', '/')
        // 拒绝绝对路径
        if (normalized.startsWith("/")) return null
        // 拒绝 ..
        val parts = normalized.split('/')
        if (parts.any { it == ".." }) return null

        val target = File(destDir, normalized)
        val canonical = try { target.canonicalFile } catch (_: Throwable) { return null }
        val base = try { destDir.canonicalFile } catch (_: Throwable) { return null }
        if (!canonical.path.startsWith(base.path + File.separator) &&
            canonical.path != base.path
        ) return null
        return canonical
    }
}
