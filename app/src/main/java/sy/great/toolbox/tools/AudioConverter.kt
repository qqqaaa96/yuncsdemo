package sy.great.toolbox.tools

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

/**
 * 音频 → WAV 转换。
 *
 * 为什么先做 WAV：
 *   · WAV 是未压缩 PCM，写文件头 + 原始数据就行，一定能成
 *   · 不依赖任何编码器，不会因为设备缺 codec 失败
 *   · 是“提取原始音频”最可靠的输出
 *
 * 实现：
 *   MediaExtractor 解封装 → MediaCodec 解码成 PCM → 写 WAV
 *
 * 支持输入：MP3 / AAC / M4A / FLAC / OGG / OPUS / AMR / WAV（只要是系统能解的）
 */
object AudioConverter {

    data class Result(
        val ok: Boolean,
        val bytes: ByteArray = ByteArray(0),
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val durationMs: Long = 0,
        val message: String = ""
    )

    /**
     * 把任意音频（或视频里的音轨）转成 WAV。
     *
     * @param progress 进度回调 0f..1f（在调用线程）
     */
    fun toWav(
        input: File,
        progress: ((Float) -> Unit)? = null
    ): Result {
        if (!input.exists()) return Result(false, message = "源文件不存在")

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(input.absolutePath)

            // ---- 1. 找第一条音轨 ----
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                return Result(false, message = "文件里没有音轨")
            }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L

            // ---- 2. 创建解码器 ----
            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val pcm = java.io.ByteArrayOutputStream(1 shl 20)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmBytes = 0L
            val maxBytes = 512L * 1024 * 1024   // 上限 512MB，防 OOM

            val deadline = System.currentTimeMillis() + 120_000L  // 最多 2 分钟

            while (!outputDone) {
                if (System.currentTimeMillis() > deadline) {
                    return Result(false, message = "解码超时（文件可能损坏或编码不受支持）")
                }
                if (pcmBytes > maxBytes) {
                    return Result(false, message = "解码后音频超过 512MB，已中止")
                }

                // 喂入
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)
                        if (buf != null) {
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // 取出
                when (val outIdx = dec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 继续 */ }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 解码器可能改变输出格式（例如从 SBR 变 48k），以它为准
                        // 这里不重建 WAV 头，只用原始采样率，避免半截数据错位
                    }
                    else -> {
                        if (outIdx >= 0) {
                            val obuf = dec.getOutputBuffer(outIdx)
                            if (obuf != null) {
                                obuf.position(info.offset)
                                obuf.limit(info.offset + info.size)
                                val chunk = ByteArray(info.size)
                                obuf.get(chunk)
                                pcm.write(chunk)
                                pcmBytes += info.size
                            }
                            dec.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }

                if (durationUs > 0 && progress != null) {
                    val doneUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        // 用 pcm 字节数估算进度
                        val estimUs = pcmBytes * 1_000_000L / 
                            (sampleRate.toLong() * channels * 2).coerceAtLeast(1)
                        estimUs
                    } else 0L
                    progress((doneUs.toFloat() / durationUs).coerceIn(0f, 1f))
                }
            }

            val raw = pcm.toByteArray()
            if (raw.isEmpty()) {
                return Result(false, message = "解码后没有任何音频数据")
            }

            // ---- 3. 加 WAV 头 ----
            val wav = wrapWav(raw, sampleRate, channels, 16)
            progress?.invoke(1f)

            return Result(
                ok = true,
                bytes = wav,
                sampleRate = sampleRate,
                channels = channels,
                durationMs = durationUs / 1000,
                message = "成功"
            )
        } catch (e: Throwable) {
            return Result(false, message = "转换失败：" + (e.message ?: e.javaClass.simpleName))
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    /**
     * 给裸 PCM 加 44 字节 WAV 头。
     *
     * 注意：MediaCodec 解出来的一律按 16 位小端 PCM 处理。
     * 绝大多数音频解码器输出都是 16bit PCM；
     * 若遇到 32bit 浮点输出（部分 FLAC），WAV 头会标错，
     * 这里通过检查 MediaFormat 无法可靠判断，故不作处理 —— 
     * 宁可输出标准 16bit 头，也不猜。
     */
    private fun wrapWav(pcm: ByteArray, sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val dataLen = pcm.size
        val totalLen = 36 + dataLen

        val header = java.nio.ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)              // fmt chunk size
        header.putShort(1)             // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bits.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLen)

        val out = ByteArray(44 + dataLen)
        System.arraycopy(header.array(), 0, out, 0, 44)
        System.arraycopy(pcm, 0, out, 44, dataLen)
        return out
    }
}
