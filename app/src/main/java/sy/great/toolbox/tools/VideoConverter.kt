package sy.great.toolbox.tools

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * 视频处理。
 *
 * 提供三种能力：
 *   1. 重封装（remux）：不改编码，只换容器，秒级完成、无损
 *   2. 提取音频：把音轨单独导出成 WAV
 *   3. 抽帧转 GIF：解码视频帧 → 逐帧编码 GIF
 *
 * 不做什么：
 *   · 不重新编码视频（x264 需要 ffmpeg，几百 MB，不现实）
 *   · 不改分辨率 / 码率（同样需要重编码）
 *
 * 这些如果做不到，就不要假装能做。
 */
object VideoConverter {

    data class Result(
        val ok: Boolean,
        val bytes: ByteArray = ByteArray(0),
        val message: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val durationMs: Long = 0
    )

    /** 视频基本信息 */
    data class Info(
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val videoMime: String,
        val audioMime: String,
        val hasAudio: Boolean
    )

    /** 读取视频信息 */
    fun info(input: File): Info? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(input.absolutePath)
            var vMime = ""
            var aMime = ""
            var w = 0
            var h = 0
            var dur = 0L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && vMime.isEmpty()) {
                    vMime = mime
                    if (f.containsKey(MediaFormat.KEY_WIDTH)) w = f.getInteger(MediaFormat.KEY_WIDTH)
                    if (f.containsKey(MediaFormat.KEY_HEIGHT)) h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    if (f.containsKey(MediaFormat.KEY_DURATION)) dur = f.getLong(MediaFormat.KEY_DURATION) / 1000
                } else if (mime.startsWith("audio/") && aMime.isEmpty()) {
                    aMime = mime
                }
            }
            if (vMime.isEmpty()) null
            else Info(w, h, dur, vMime, aMime, aMime.isNotEmpty())
        } catch (_: Throwable) {
            null
        } finally {
            try { ex.release() } catch (_: Throwable) {}
        }
    }

    /**
     * 重封装：只换容器，不重编码。
     *
     * MP4 ⇄ MKV ⇄ WEBM 之间转。因为不改编码，速度极快且无损。
     * 但 WebM 容器不接受 H.264 视频，所以 MP4→WebM 会失败——这是容器限制，会如实告知。
     */
    fun remux(input: File, outFile: File, outputFormat: Int): Result {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(input.absolutePath)

            // 建一个非空的局部引用。
            //
            // 原来的写法是直接给外层可空的 `var muxer: MediaMuxer?` 赋值，
            // 后面又直接 muxer.addTrack / start / writeSampleData，
            // 在可空接收者上调方法会让编译器的重载解析错乱，
            // 报成 No value passed for parameter 'p1'（误导性很强）。
            val mux = MediaMuxer(outFile.absolutePath, outputFormat)
            muxer = mux

            val trackMap = HashMap<Int, Int>()   // 输入 track → 输出 track
            var outTrackCount = 0

            // 逐个 track 拷贝格式
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue

                // WebM 只接受 VP8/VP9/AV1 + Vorbis/Opus
                if (outputFormat == MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM) {
                    val ok = mime.contains("vp8", true) || mime.contains("vp9", true) ||
                            mime.contains("av1", true) || mime.contains("vorbis", true) ||
                            mime.contains("opus", true)
                    if (!ok) {
                        return Result(false, message = "无法转为 WebM：源编码为 " + mime +
                                "（WebM 只接受 VP8/VP9/AV1 与 Vorbis/Opus）")
                    }
                }

                val ot = mux.addTrack(f)
                trackMap[i] = ot
                outTrackCount++
            }

            if (outTrackCount == 0) {
                return Result(false, message = "没有可拷贝的音视频轨")
            }
            mux.start()

            val bufSize = 2 * 1024 * 1024
            val buf = ByteBuffer.allocate(bufSize)
            val info = MediaCodec.BufferInfo()

            // 不用解构 for ((k, v) in map)：
            // 在部分 Kotlin 编译器版本下，Map 解构会报
            //   No value passed for parameter 'p1'
            // 改为显式 entries 遍历，语义完全一致且不会有推断问题。
            for (entry in trackMap.entries) {
                val inTrack = entry.key
                val outTrack = entry.value
                ex.selectTrack(inTrack)
                // Android 从 API 16 起 seekTo 必须带 mode：
                //   SEEK_TO_PREVIOUS_SYNC / SEEK_TO_NEXT_SYNC / SEEK_TO_CLOSEST_SYNC
                // 旧的无参 seekTo(long) 已废弃且很多实现直接抛异常，
                // 这里用 SEEK_TO_PREVIOUS_SYNC（等同于回到开头）。
                ex.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (true) {
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = ex.sampleTime
                    // 注意运算优先级：必须给按位与加括号，
                    // 否则会被解析成 `flags and (SAMPLE_FLAG_SYNC != 0)`，
                    // 也就是把 Boolean 传给 Int.and(Int)，直接编译失败。
                    val isSync = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    info.flags = if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    mux.writeSampleData(outTrack, buf, info)
                    if (!ex.advance()) break
                }
                ex.unselectTrack(inTrack)
            }

            mux.stop()
            return Result(true, message = "成功", durationMs = 0)
        } catch (e: Throwable) {
            return Result(false, message = "重封装失败：" + (e.message ?: e.javaClass.simpleName))
        } finally {
            try { muxer?.release() } catch (_: Throwable) {}
            try { ex.release() } catch (_: Throwable) {}
        }
    }

    /**
     * 抽帧转 GIF。
     *
     * @param maxFrames 最多取多少帧（GIF 体积大，默认 60）
     * @param fps 目标帧率（默认 10，足够表达动态）
     * @param maxSide 长边上限（默认 480，控制体积）
     */
    fun toGif(
        input: File,
        maxFrames: Int = 60,
        fps: Int = 10,
        maxSide: Int = 480,
        progress: ((Float) -> Unit)? = null
    ): Result {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(input.absolutePath)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) { trackIndex = i; format = f; break }
            }
            if (trackIndex < 0 || format == null) {
                return Result(false, message = "文件里没有视频轨")
            }
            ex.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val srcW = if (format.containsKey(MediaFormat.KEY_WIDTH))
                format.getInteger(MediaFormat.KEY_WIDTH) else 0
            val srcH = if (format.containsKey(MediaFormat.KEY_HEIGHT))
                format.getInteger(MediaFormat.KEY_HEIGHT) else 0
            if (srcW <= 0 || srcH <= 0) {
                return Result(false, message = "无法读取视频尺寸")
            }

            val scale = if (maxSide > 0 && maxOf(srcW, srcH) > maxSide)
                maxSide.toFloat() / maxOf(srcW, srcH) else 1f
            val outW = (srcW * scale).toInt().coerceAtLeast(2)
            val outH = (srcH * scale).toInt().coerceAtLeast(2)

            // 帧间隔（微秒）
            val frameIntervalUs = if (fps > 0) 1_000_000L / fps else 100_000L
            var nextKeepUs = 0L

            val frames = ArrayList<Bitmap>()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadline = System.currentTimeMillis() + 120_000L

            while (!outputDone && frames.size < maxFrames) {
                if (System.currentTimeMillis() > deadline) break

                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)
                        if (buf != null) {
                            val size = ex.readSampleData(buf, 0)
                            if (size < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                }

                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    // 按时间抽帧
                    if (info.presentationTimeUs >= nextKeepUs) {
                        val image = try {
                            dec.getOutputImage(outIdx)
                        } catch (_: Throwable) {
                            null
                        }
                        if (image != null && android.os.Build.VERSION.SDK_INT >= 23) {
                            val bmp = imageToBitmap(image, outW, outH)
                            if (bmp != null) {
                                frames.add(bmp)
                                nextKeepUs += frameIntervalUs
                                progress?.invoke(
                                    (frames.size.toFloat() / maxFrames).coerceIn(0f, 1f)
                                )
                            }
                        }
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }

            if (frames.isEmpty()) {
                return Result(false, message = "未能解出任何视频帧")
            }

            // 编码 GIF
            val bos = ByteArrayOutputStream(1 shl 20)
            val gifOk = try {
                encodeGif(frames, bos, 1000 / fps.coerceAtLeast(1))
            } catch (e: Throwable) {
                false
            }
            for (b in frames) try { b.recycle() } catch (_: Throwable) {}

            if (!gifOk) {
                return Result(false, message = "GIF 编码失败（设备不支持 ImageIO）")
            }
            progress?.invoke(1f)

            return Result(true, bos.toByteArray(), "成功", outW, outH,
                frames.size * frameIntervalUs / 1000)
        } catch (e: Throwable) {
            return Result(false, message = "转 GIF 失败：" + (e.message ?: e.javaClass.simpleName))
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { ex.release() } catch (_: Throwable) {}
        }
    }

    /** Image → 缩放后的 Bitmap */
    private fun imageToBitmap(image: android.media.Image, w: Int, h: Int): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bmpW = image.width + rowPadding / pixelStride
            val raw = Bitmap.createBitmap(bmpW, image.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            raw.copyPixelsFromBuffer(buffer)

            val cropped = if (bmpW != image.width)
                Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
            else raw
            if (cropped !== raw) raw.recycle()

            if (cropped.width == w && cropped.height == h) cropped
            else {
                val scaled = Bitmap.createScaledBitmap(cropped, w, h, true)
                if (scaled !== cropped) cropped.recycle()
                scaled
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 编码 GIF。
     *
     * 不用 ImageIO（Android 没有），
     * 自己做最小 GIF89a 编码器：调色板量化 + LZW。
     * 为控制复杂度，用固定 3-3-2 色调色板（256 色）。
     */
    private fun encodeGif(frames: List<Bitmap>, out: ByteArrayOutputStream, delayMs: Int): Boolean {
        if (frames.isEmpty()) return false
        val w = frames[0].width
        val h = frames[0].height

        // ---- GIF 文件头 ----
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(out, w)
        writeShort(out, h)
        out.write(0xF7)          // 全局色表存在 + 256 色
        out.write(0)             // 背景色索引
        out.write(0)             // 像素宽高比

        // ---- 全局调色板 3-3-2 ----
        for (i in 0 until 256) {
            val r = (i shr 5) and 0x07
            val g = (i shr 2) and 0x07
            val b = i and 0x03
            out.write(r * 255 / 7)
            out.write(g * 255 / 7)
            out.write(b * 255 / 3)
        }

        // ---- Netscape 循环扩展 ----
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(1); writeShort(out, 0); out.write(0)

        // ---- 每帧 ----
        val pixels = IntArray(w * h)
        for (bmp in frames) {
            // 图形控制扩展
            out.write(0x21); out.write(0xF9); out.write(4)
            out.write(0x04)
            writeShort(out, delayMs / 10)
            out.write(0); out.write(0)

            // 图像描述符
            out.write(0x2C)
            writeShort(out, 0); writeShort(out, 0)
            writeShort(out, w); writeShort(out, h)
            out.write(0)

            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val idx = ByteArray(w * h)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                idx[i] = (((r shr 5) shl 5) or ((g shr 5) shl 2) or (b shr 6)).toByte()
            }
            writeLzw(out, idx, 8)
        }

        out.write(0x3B)   // trailer
        return true
    }

    private fun writeShort(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF)
        o.write((v shr 8) and 0xFF)
    }

    /** GIF LZW 编码（最小实现） */
    private fun writeLzw(out: ByteArrayOutputStream, data: ByteArray, minCodeSize: Int) {
        out.write(minCodeSize)

        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var nextCode = endCode + 1

        val dict = HashMap<String, Int>()
        fun resetDict() {
            dict.clear()
            for (i in 0 until clearCode) dict[i.toString()] = i
            codeSize = minCodeSize + 1
            nextCode = endCode + 1
        }
        resetDict()

        val bitBuf = BitWriter(out)
        bitBuf.write(clearCode, codeSize)

        var cur = ""
        for (b in data) {
            val c = (b.toInt() and 0xFF).toString()
            val combo = cur + "," + c
            if (dict.containsKey(combo)) {
                cur = combo
            } else {
                if (cur.isNotEmpty()) bitBuf.write(dict[cur] ?: 0, codeSize)
                if (nextCode < 4096) {
                    dict[combo] = nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    bitBuf.write(clearCode, codeSize)
                    resetDict()
                }
                cur = c
            }
        }
        if (cur.isNotEmpty()) bitBuf.write(dict[cur] ?: 0, codeSize)
        bitBuf.write(endCode, codeSize)
        bitBuf.finish()
    }

    /** GIF 位流写入器（LSB 优先，按 255 字节分块） */
    private class BitWriter(private val out: ByteArrayOutputStream) {
        private val block = ByteArrayOutputStream()
        private var cur = 0
        private var bits = 0

        fun write(code: Int, size: Int) {
            var c = code
            var s = size
            while (s > 0) {
                cur = cur or ((c and 1) shl bits)
                c = c ushr 1
                bits++
                s--
                if (bits == 8) {
                    block.write(cur)
                    cur = 0
                    bits = 0
                    flushIfNeeded()
                }
            }
        }

        private fun flushIfNeeded() {
            if (block.size() >= 255) flushBlock()
        }

        private fun flushBlock() {
            if (block.size() == 0) return
            val arr = block.toByteArray()
            out.write(arr.size)
            out.write(arr)
            block.reset()
        }

        fun finish() {
            if (bits > 0) {
                block.write(cur)
                cur = 0
                bits = 0
            }
            flushBlock()
            out.write(0)   // block terminator
        }
    }
}
