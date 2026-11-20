package sy.great.toolbox.tools

import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/**
 * gzexe 风格的自解压可执行文件生成器。
 *
 * 输出结构：
 *   [#!/bin/sh 自解压头][gzip 压缩后的原始内容]
 *
 * 运行时由 shell 头用 tail 跳过自身，把后面的字节 gzip -cd 解压到临时目录，
 * chmod 700 后执行。支持 ELF 可执行文件和 shell 脚本。
 *
 * 为什么不用 base64：
 *   直接追加二进制，体积比 base64 小约 33%，且没有解码开销。
 */
object GzShell {

    data class Result(
        val ok: Boolean,
        val outputPath: String = "",
        val sizeBytes: Long = 0,
        val message: String = "",
    )

    /**
     * 生成自解压文件。
     *
     * @param input     源文件（ELF 或 shell 脚本）
     * @param output    输出路径
     */
    fun build(input: File, output: File): Result {
        if (!input.exists() || !input.canRead()) {
            return Result(false, message = "源文件不存在或不可读")
        }

        val raw = try {
            input.readBytes()
        } catch (e: Throwable) {
            return Result(false, message = "读取源文件失败：${e.message}")
        }

        if (raw.isEmpty()) {
            return Result(false, message = "源文件为空")
        }

        // ---- 生成 gzip 数据 ----
        // mtime 固定为 0，保证同样输入产生同样输出（可复现）
        val compressed = try {
            java.io.ByteArrayOutputStream().use { bos ->
                // GZIPOutputStream 不带 mtime 参数，写完后手动改头里的 4 字节
                GZIPOutputStream(bos, 9).use { gz ->
                    gz.write(raw)
                }
                val bytes = bos.toByteArray()
                // gzip 头：10 字节，[4..7] 是 MTIME（小端）
                if (bytes.size > 10) {
                    bytes[4] = 0
                    bytes[5] = 0
                    bytes[6] = 0
                    bytes[7] = 0
                }
                bytes
            }
        } catch (e: Throwable) {
            return Result(false, message = "压缩失败：${e.message}")
        }

        // ---- 生成头部 ----
        val header = buildHeader(input.name)

        try {
            FileOutputStream(output).use { out ->
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(compressed)
            }
            // 加执行位
            output.setExecutable(true, false)
            output.setReadable(true, false)
        } catch (e: Throwable) {
            return Result(false, message = "写入输出失败：${e.message}")
        }

        return Result(
            ok = true,
            outputPath = output.absolutePath,
            sizeBytes = output.length(),
        )
    }

    /**
     * 生成 shell 自解压头。
     *
     * skip 是压缩数据起始行号，head 的最后一个换行之后紧跟着 gzip 数据。
     */
    private fun renderHeader(skip: String, origName: String): String = """#!/bin/sh
skip=$skip
set -e
tab='	'
nl='
'
IFS=" ${'$'}tab${'$'}nl"
umask=`umask`
umask 77
gztmpdir=
trap 'res=${'$'}?
  test -n "${'$'}gztmpdir" && rm -fr "${'$'}gztmpdir"
  (exit ${'$'}res); exit ${'$'}res
' 0 1 2 3 5 10 13 15
case ${'$'}TMPDIR in
  / | /*/) ;;
  /*) TMPDIR=${'$'}TMPDIR/;;
  *) TMPDIR=${'$'}TMPDIR/;;
esac
if type mktemp >/dev/null 2>&1; then
  gztmpdir=`mktemp -d "${'$'}{TMPDIR}gztmpXXXXXXXXX"`
else
  gztmpdir=${'$'}{TMPDIR}gztmp${'$'}${'$'}; mkdir ${'$'}gztmpdir
fi || { (exit 127); exit 127; }
if type mktemp >/dev/null 2>&1; then
  gztmp=${'$'}(mktemp "${'$'}gztmpdir/XXXXXXXXXX")
  rm -f "${'$'}gztmp"
else
  gztmp="${'$'}gztmpdir/${'$'}${'$'}${'$'}RANDOM"
fi
case `printf 'X\n' | tail -n +1 2>/dev/null` in
X) tail_n=-n;;
*) tail_n=;;
esac
if tail ${'$'}tail_n +${'$'}skip <"${'$'}0" | gzip -cd > "${'$'}gztmp"; then
  umask ${'$'}umask
  chmod 700 "${'$'}gztmp"
  (sleep 5; rm -fr "${'$'}gztmpdir") 2>/dev/null &
  "${'$'}gztmp" ${'$'}{1+"${'$'}@"}; res=${'$'}?
else
  printf >&2 '%s\n' "Cannot decompress ${'$'}0"
  (exit 127); res=127
fi; exit ${'$'}res
""".replace("__SKIP__", skip)

    /** 头部最终生成：两遍计算保证 skip 正确 */
    private fun buildHeader(origName: String): String {
        // 第一遍：占位符长度不影响行数
        val probe = renderHeader("__SKIP__", origName)
        val skip = probe.count { it == '\n' } + 1
        val final = renderHeader(skip.toString(), origName)
        // 校验：替换数字后行数不能变
        require(final.count { it == '\n' } + 1 == skip) { "skip 行数计算异常" }
        return final
    }
}
