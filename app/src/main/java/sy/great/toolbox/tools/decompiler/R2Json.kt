package sy.great.toolbox.tools.decompiler

/**
 * r2 pdfj / pdrj 的 JSON 解析。
 *
 * 为什么自己写：项目没有引 Gson/Moshi，为了一个字段不多的格式
 * 加一个 JSON 库不划算；而且这里只需要极少数类型。
 *
 * 解析目标结构：
 * {
 *   "name": "sym.foo",
 *   "addr": 3456,
 *   "blocks": [{
 *     "offset": 3456,
 *     "size": 24,
 *     "ops": [
 *       {"offset":3456, "type":"push", "disasm":"stp x29, x30, [sp, -0x10]!"},
 *       {"offset":3460, "type":"cjmp", "jump":3480, "fail":3464, "disasm":"cbz w0"}
 *     ]
 *   }]
 * }
 */
object R2Json {

    /** 一条反汇编指令 */
    class Op(
        val offset: Long,
        val type: String,
        val disasm: String,
        val jump: Long?,
        val fail: Long?,
        val ptr: Long?
    )

    /** 一个基本块 */
    class Block(
        val offset: Long,
        val size: Long,
        val ops: List<Op>
    )

    /** 一个函数 */
    class Func(
        val name: String,
        val addr: Long,
        val blocks: List<Block>
    )

    /**
     * 解析 pdfj 输出。
     *
     * @return 解析成功返回 Func；格式不对返回 null（不猜、不造数据）
     */
    fun parseFunc(text: String): Func? {
        if (!text.startsWith("{")) return null
        val root = try {
            JsonReader(text).parseObject()
        } catch (_: Throwable) {
            return null
        }

        val name = (root["name"] as? String) ?: "sub"
        val addr = toLong(root["addr"]) ?: 0L
        val blocksRaw = root["blocks"] as? List<*> ?: return null

        val blocks = ArrayList<Block>(blocksRaw.size)
        for (b in blocksRaw) {
            val bm = b as? Map<*, *> ?: continue
            val boff = toLong(bm["offset"]) ?: continue
            val bsize = toLong(bm["size"]) ?: 0L
            val opsRaw = bm["ops"] as? List<*> ?: emptyList<Any?>()
            val ops = ArrayList<Op>(opsRaw.size)
            for (o in opsRaw) {
                val om = o as? Map<*, *> ?: continue
                val off = toLong(om["offset"]) ?: continue
                // r2 有些版本 op 不带 disasm，只有 opcode
                val dis = (om["disasm"] as? String)
                    ?: (om["opcode"] as? String)
                    ?: ""
                ops.add(
                    Op(
                        offset = off,
                        type = (om["type"] as? String) ?: "unknown",
                        disasm = dis,
                        jump = toLong(om["jump"]),
                        fail = toLong(om["fail"]),
                        ptr = toLong(om["ptr"])
                    )
                )
            }
            blocks.add(Block(boff, bsize, ops))
        }

        if (blocks.isEmpty()) return null
        return Func(name, addr, blocks.sortedBy { it.offset })
    }

    private fun toLong(v: Any?): Long? = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is String -> v.toLongOrNull() ?: v.removePrefix("0x").toLongOrNull(16)
        else -> null
    }

    // ============================================================
    // 极简 JSON 解析器
    // ============================================================

    private class JsonReader(private val s: String) {
        private var i = 0

        fun parseObject(): Map<String, Any?> {
            skipWs()
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWs()
                val k = parseString()
                skipWs()
                expect(':')
                skipWs()
                map[k] = parseValue()
                skipWs()
                val c = peek()
                if (c == ',') { i++; continue }
                if (c == '}') { i++; break }
                throw IllegalStateException("bad obj at $i")
            }
            return map
        }

        private fun parseValue(): Any? {
            skipWs()
            return when (val c = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expectWord("true"); true }
                'f' -> { expectWord("false"); false }
                'n' -> { expectWord("null"); null }
                else -> if (c == '-' || c.isDigit()) parseNumber() else throw IllegalStateException("bad value at $i")
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return list }
            while (true) {
                skipWs()
                list.add(parseValue())
                skipWs()
                val c = peek()
                if (c == ',') { i++; continue }
                if (c == ']') { i++; break }
                throw IllegalStateException("bad arr at $i")
            }
            return list
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) break
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'u' -> {
                                if (i + 4 <= s.length) {
                                    val hex = s.substring(i, i + 4)
                                    i += 4
                                    hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                }
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalStateException("unterminated string")
        }

        private fun parseNumber(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val t = s.substring(start, i)
            return if (t.contains('.') || t.contains('e') || t.contains('E')) {
                t.toDoubleOrNull() ?: 0.0
            } else {
                t.toLongOrNull() ?: 0L
            }
        }

        private fun expectWord(w: String) {
            if (i + w.length <= s.length && s.regionMatches(i, w, 0, w.length)) i += w.length
            else throw IllegalStateException("expect $w at $i")
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun expect(c: Char) {
            if (i >= s.length || s[i] != c) throw IllegalStateException("expect $c at $i")
            i++
        }
    }
}
