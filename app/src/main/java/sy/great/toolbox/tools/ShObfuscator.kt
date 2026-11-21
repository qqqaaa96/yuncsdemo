package sy.great.toolbox.tools

/**
 * Shell 脚本高强度静态混淆。
 *
 * 定位：把纯文本 .sh 混淆成「静态阅读成本极高」的另一份纯文本 .sh，
 * 输出仍由 /system/bin/sh 直接执行，不产生 ELF，不引入任何外部依赖。
 *
 * ⚠ 必须说清的边界（不吹）：
 *   shell 脚本最终必须以「解释器能读懂的文本」交给 sh -s，
 *   所以混淆的性质是「加大逆向成本」，不是「静态不可解」。
 *   真正对抗有能力者的东西是外层 RC4 + 完整性校验，这一步是第二道墙。
 *
 * 设计原则：
 *   1. 只新增，不修改任何现有加密逻辑（SscCompiler 里只插一行调用）
 *   2. 零外部依赖：只用 sh 内建语法（: / true / eval / printf / 变量赋值）
 *   3. 只用 POSIX 保证的能力：不用 \x（busybox 版本差异大），
 *      改用 printf '%b' + 八进制 \NNN
 *   4. 识别不了的结构一律原样保留，宁可少混淆也不破坏语义
 */
object ShObfuscator {

    /** 混淆强度（体积倍率上限，用于控制膨胀） */
    private const val MAX_EXPAND_RATIO = 1200

    /**
     * 混淆入口。
     *
     * 返回的文本可直接交给 sh -s 执行，语义与输入完全一致。
     */
    fun obfuscate(src: String, seed: Long = System.nanoTime()): String {
        val rnd = java.util.Random(seed)
        var text = src

        // 统一换行，去掉 BOM
        text = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')

        // 拆掉 shebang（保留信息，外面重新加）
        val lines = text.split('\n').toMutableList()
        var shebang = "#!/system/bin/sh"
        if (lines.isNotEmpty() && lines[0].startsWith("#!")) {
            shebang = lines[0].trim()
            lines.removeAt(0)
        }

        // 逐行做静态改写（保守，识别不了就原样）
        val rewritten = rewriteLines(lines, rnd)

        // 整体再套一层 eval 解码：正文转八进制，磁盘上看不到任何原文
        return buildDecoderWrapper(shebang, rewritten, rnd)
    }

    // ============================================================
    // 第 1 层：逐行静态改写
    // ============================================================

    /**
     * 逐行改写。
     *
     * 处理顺序很关键：先判断「必须原样保留」的行，剩下的才做变换。
     */
    private fun rewriteLines(lines: List<String>, rnd: java.util.Random): String {
        val out = StringBuilder()
        var inHeredoc: String? = null

        for (raw in lines) {
            val line = raw

            // ---- here-doc 体内原样保留 ----
            if (inHeredoc != null) {
                out.append(line).append('\n')
                if (line.trim() == inHeredoc || line == inHeredoc) inHeredoc = null
                continue
            }

            // 检测 here-doc 起始（<< EOF / <<'EOF' / <<-EOF）
            val hd = detectHeredoc(line)
            if (hd != null) {
                out.append(line).append('\n')
                inHeredoc = hd
                continue
            }

            // ---- 空行：随机插入无害语句 ----
            if (line.isBlank()) {
                if (rnd.nextInt(100) < 35) out.append(noopStatement(rnd)).append('\n')
                out.append('\n')
                continue
            }

            // ---- 纯注释行：换成随机诱饵注释 ----
            val t = line.trimStart()
            if (t.startsWith("#")) {
                out.append(decoyComment(rnd)).append('\n')
                continue
            }

            // ---- 去掉行尾注释（引号外的 #）----
            val noComment = stripTrailingComment(line)

            // ---- 命令名转义 + 字符串拆分 ----
            val transformed = escapeCommandWord(noComment, rnd)

            out.append(transformed).append('\n')

            // 随机插死代码分支（包住一份无害副本，不改变执行结果）
            if (rnd.nextInt(100) < 12) {
                out.append(deadCodeBlock(rnd)).append('\n')
            }
        }

        return out.toString()
    }

    /**
     * 检测 here-doc 定界符，返回 null 表示没有。
     *
     * ⚠ 必须排除两类误判（否则会把后面整段脚本吞成 heredoc）：
     *   1. 引号内的 "<<"（如 echo "a<<b"）
     *   2. $((1<<2)) 这类算术移位
     */
    private fun detectHeredoc(line: String): String? {
        var inSingle = false
        var inDouble = false
        var escaped = false
        var idx = -1
        var i = 0

        // 先找到「引号外」的 << 位置
        while (i < line.length - 1) {
            val c = line[i]
            if (escaped) { escaped = false; i++; continue }
            when {
                c == '\\' && !inSingle -> { escaped = true; i++; continue }
                c == '\'' && !inDouble -> { inSingle = !inSingle; i++; continue }
                c == '"' && !inSingle -> { inDouble = !inDouble; i++; continue }
                !inSingle && !inDouble && c == '<' && line[i + 1] == '<' -> {
                    idx = i
                    break
                }
            }
            i++
        }
        if (idx < 0) return null

        // 排除算术移位：$(()) / (()) 里的 <<
        // 判断依据：当前位置之前，左括号比右括号多（粗略但足够）
        var depth = 0
        for (j in 0 until idx) {
            if (line[j] == '(') depth++
            if (line[j] == ')') depth--
        }
        if (depth > 0) return null

        var p = idx + 2
        // <<< 是 herestring，不是 heredoc
        if (p < line.length && line[p] == '<') return null
        // 跳过 <<- 和空白
        if (p < line.length && line[p] == '-') p++
        while (p < line.length && (line[p] == ' ' || line[p] == '\t')) p++
        if (p >= line.length) return null

        val quote = if (line[p] == '\'' || line[p] == '"') line[p++] else '\u0000'
        val start = p
        while (p < line.length) {
            val c = line[p]
            if (quote != '\u0000') {
                if (c == quote) break
            } else {
                if (c == ' ' || c == '\t' || c == ';' || c == '|' || c == '&') break
            }
            p++
        }
        if (p == start) return null
        return line.substring(start, p)
    }

    /**
     * 去掉行尾注释。
     *
     * 只在「引号外」识别 # 且 # 前是空白，避免误伤 $# / ${#var} / 'a#b'。
     */
    private fun stripTrailingComment(line: String): String {
        var inSingle = false
        var inDouble = false
        var escaped = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (escaped) {
                escaped = false
                i++
                continue
            }
            when {
                c == '\\' && !inSingle -> { escaped = true; i++; continue }
                c == '\'' && !inDouble -> { inSingle = !inSingle; i++; continue }
                c == '"' && !inSingle -> { inDouble = !inDouble; i++; continue }
                c == '#' && !inSingle && !inDouble -> {
                    // # 前必须是空白或行首，才是注释
                    if (i == 0 || line[i - 1] == ' ' || line[i - 1] == '\t') {
                        return line.substring(0, i).trimEnd()
                    }
                }
            }
            i++
        }
        return line
    }

    /**
     * 把行首命令名转义。
     *
     * echo → \e\c\h\o
     * 只处理行首的简单单词，遇到管道 / && / ; 就停，不做猜猜。
     */
    private fun escapeCommandWord(line: String, rnd: java.util.Random): String {
        val trimmed = line.trimStart()
        val indent = line.substring(0, line.length - trimmed.length)

        // 以这些开头的行不改（关键字 / 赋值 / 重定向 / 引号）
        if (trimmed.isEmpty()) return line
        if (trimmed[0] == '#' || trimmed[0] == '"' || trimmed[0] == '\'') return line
        if (trimmed.startsWith("if ") || trimmed.startsWith("then") ||
            trimmed.startsWith("else") || trimmed.startsWith("elif ") ||
            trimmed.startsWith("fi") || trimmed.startsWith("do") ||
            trimmed.startsWith("done") || trimmed.startsWith("while ") ||
            trimmed.startsWith("until ") || trimmed.startsWith("for ") ||
            trimmed.startsWith("case ") || trimmed.startsWith("esac") ||
            trimmed.startsWith("function ") || trimmed.startsWith("{") ||
            trimmed.startsWith("}") || trimmed.startsWith("return") ||
            trimmed.startsWith("local ") || trimmed.startsWith("export ") ||
            trimmed.startsWith("readonly ") || trimmed.startsWith("declare ") ||
            trimmed.startsWith("shift") || trimmed.startsWith("exit") ||
            trimmed.startsWith("break") || trimmed.startsWith("continue") ||
            trimmed.startsWith("trap ") || trimmed.startsWith("eval ") ||
            trimmed.startsWith("exec ") || trimmed.startsWith("source ") ||
            trimmed.startsWith(". ")
        ) return line

        // 形如 VAR=value 的赋值行不改命令名
        val eq = trimmed.indexOf('=')
        if (eq > 0) {
            val head = trimmed.substring(0, eq)
            if (head.isNotEmpty() && head.all { it.isLetterOrDigit() || it == '_' }) return line
        }

        // 取第一个 token
        var end = 0
        while (end < trimmed.length) {
            val c = trimmed[end]
            if (c == ' ' || c == '\t' || c == ';' || c == '|' || c == '&' || c == '<' || c == '>') break
            end++
        }
        if (end <= 0) return line

        val word = trimmed.substring(0, end)
        // 只转义纯字母命令名
        if (word.isEmpty() || !word.all { it.isLetter() }) return line
        // 太短的没必要（a / x 之类一般是别名或变量）
        if (word.length < 2) return line

        // 随机选择：整词转义 / 部分字符用引号
        val escaped = if (rnd.nextBoolean()) {
            word.toCharArray().joinToString("") { "\\$it" }
        } else {
            // 拆成 "ec"ho 形式：任意切开一次
            val cut = 1 + rnd.nextInt(word.length - 1)
            "\"" + word.substring(0, cut) + "\"" + word.substring(cut)
        }

        return indent + escaped + trimmed.substring(end)
    }

    /** 随机无效语句（不改变 $? 以外任何状态，且用 : 保证返回 0） */
    private fun noopStatement(rnd: java.util.Random): String {
        return when (rnd.nextInt(6)) {
            0 -> ":"
            1 -> ": # " + randomJunk(rnd)
            2 -> "true"
            3 -> {
                // 自引用赋值：未定义变量赋给自己，无害，但看起来像真实代码。
                // 注意：不能用 "$v_" 这种写法，Kotlin 会当字符串模板解析。
                val nm = "v_" + hex(rnd, 6)
                nm + "=" + "$" + "{" + nm + "}" + " 2>/dev/null"
            }
            else -> ":  \t"
        }
    }

    /** 诱饵注释（看起来像真实注释） */
    private fun decoyComment(rnd: java.util.Random): String {
        val pool = arrayOf(
            "# 初始化模块",
            "# 检查运行环境",
            "# 临时兼容处理",
            "# TODO: 后续优化",
            "# 版本号占位",
            "# 路径规范化",
            "# 权限校验（已内联）",
            "# 保持与旧版本一致",
            "# 错误码映射",
            "# 日志开关",
        )
        return pool[rnd.nextInt(pool.size)] + " " + hex(rnd, 4)
    }

    /** 死代码块：永远不会执行，但结构完整，干扰阅读 */
    private fun deadCodeBlock(rnd: java.util.Random): String {
        val v = "d_" + hex(rnd, 8)
        return buildString {
            append("if [ \"$v\" = \"")
            append(hex(rnd, 10))
            append("\" ]; then\n")
            append("  : ").append(hex(rnd, 8)).append('\n')
            append("  $v=0\n")
            append("fi")
        }
    }

    // ============================================================
    // 第 2 层：外层 eval 解码包装
    // ============================================================

    /**
     * 把正文转成八进制串，外面套一层解码器。
     *
     * 只用 POSIX 技能：
     *   printf '%b' '\101\102'  —— %b 是 POSIX 规定的，支持八进制转义
     *   把还原结果交给 eval
     *
     * 为什么不用 \x：
     *   部分 busybox 的 printf 不识别 \xHH，会原样输出。
     *   八进制 \NNN 是 POSIX printf 的基本能力，兼容性最好。
     *
     * 为什么不用 base64：
     *   依赖外部命令 base64，不是所有环境都有。
     */
    private fun buildDecoderWrapper(
        shebang: String,
        body: String,
        rnd: java.util.Random,
    ): String {
        val bytes = body.toByteArray(Charsets.UTF_8)

        // 体积保护：膨胀太多就跳过外层包装（内部改写仍然保留）
        val maxBytes = (bytes.size.toLong() * MAX_EXPAND_RATIO).coerceAtMost(256L * 1024 * 1024)
        val estSize = bytes.size.toLong() * 5  // 每字节最多 '\' + 3 位八进制 = 4，留余量
        if (estSize > maxBytes) {
            // 极端大脚本：不做外层包装，返回内部改写结果
            return "$shebang\n" + body
        }

        // NUL 保护：$() 捕获输出时 NUL 会被截断，导致尾部丢失。
        // shell 脚本本身不应含 NUL，真含了就不做 eval 包装。
        if (bytes.any { it.toInt() == 0 }) {
            return "$shebang\n" + body
        }

        // 生成八进制串。
        //
        // 用 \0NNN（POSIX %b 明确规定的八进制形式）。
        // 裸 \NNN 在部分实现里会被当成 \1 + 01 解析，必须带前导 0。
        val oct = StringBuilder(bytes.size * 5)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            oct.append('\\').append('0')
            oct.append(('0' + ((v shr 6) and 0x07)))
            oct.append(('0' + ((v shr 3) and 0x07)))
            oct.append(('0' + (v and 0x07)))
        }

        // 拆成多段，避免单行过长（部分 sh 对行长度有限制）
        val chunkSize = 16000
        val chunks = oct.chunked(chunkSize)

        // 变量名随机化
        val vAcc = "_" + hex(rnd, 8)
        val vTmp = "_" + hex(rnd, 8)

        val sb = StringBuilder()
        sb.append(shebang).append('\n')
        sb.append("# ").append(hex(rnd, 12)).append('\n')
        sb.append(vAcc).append("=''\n")

        // 逐段追加：printf '%b' '<八进制>' 的输出累加到 vAcc
        for (c in chunks) {
            sb.append(vTmp).append("=$(printf '%b' '")
            sb.append(c)
            sb.append("')\n")
            // 注意：不能用 "${" 字面量，Kotlin 会把它当未闭合模板。
            // 用单引号包一个 $ 再拼。
            sb.append(vAcc).append("=\"").append(vAcc)
            sb.append('$').append('{').append(vTmp).append('}').append("\"\n")
        }

        // 执行。
        //
        // eval 在当前 shell 里执行，所以 $@ / $# / $0 天然可见，不需要转发。
        // 故意不把 $@ 拼进 eval 字符串：那样会变成 eval 自己的位置参数，
        // 脚本里看到的 $# / $1 就不对了。
        sb.append("eval \"").append('$').append('{').append(vAcc).append('}').append("\"\n")

        // 清掉变量，减少内存残留
        sb.append(vAcc).append("=''\n")
        sb.append(vTmp).append("=''\n")

        return sb.toString()
    }

    // ============================================================
    // 工具
    // ============================================================

    private fun hex(rnd: java.util.Random, n: Int): String {
        val cs = "0123456789abcdef"
        val sb = StringBuilder(n)
        repeat(n) { sb.append(cs[rnd.nextInt(16)]) }
        return sb.toString()
    }

    private fun randomJunk(rnd: java.util.Random): String = hex(rnd, 6 + rnd.nextInt(8))
}
