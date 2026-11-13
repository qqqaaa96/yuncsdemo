package sy.great.toolbox.tools

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * 表达式引擎（纯 Kotlin）。
 *
 * 移植自「数学教学.html」的内联脚本，保持同一套行为：
 *   · normalizeInput：全角转半角、× ÷ − 归一化、上标 ² ³ 转 ^2 ^3
 *   · splitWord：粘连标识符拆分（sintheta -> sin + theta）
 *   · 递归下降解析：expr -> term -> unary -> power -> atom
 *   · 隐式乘法：2x、3(x+1)、sin(x)cos(x)
 *   · 三角函数用【角度制】（与原作者一致）
 *
 * 新增（原版没有但计算器需要）：
 *   · 反三角函数、双曲、cbrt、exp、floor/ceil/round
 *   · 阶乘展开（后缀 !）
 *
 * 不用任何第三方库。
 */
object CalcEngine {

    // ============ 词法与语法树 ============

    /** 语法树节点 */
    sealed interface Node {
        /** 常数 */
        data class Num(val v: Double) : Node
        /** 变量 x（也用于 theta / t / r） */
        data object Var : Node
        /** 取负 */
        data class Neg(val a: Node) : Node
        /** 二元运算 */
        data class Bin(val op: Char, val a: Node, val b: Node) : Node
        /** 一元函数 */
        data class Fun(val name: String, val a: Node) : Node
    }

    /** 解析失败异常 */
    class ParseError(msg: String) : Exception(msg)

    // ============ 函数库 ============

    /** 函数名 -> 实现。三角类为角度制。 */
    private val FUNCS: Map<String, (Double) -> Double> = mapOf(
        "sqrt" to { x: Double -> sqrt(maxOf(0.0, x)) },
        "abs" to { x: Double -> abs(x) },
        // 角度制：与原作者一致
        "sin" to { x: Double -> sin(Math.toRadians(x)) },
        "cos" to { x: Double -> cos(Math.toRadians(x)) },
        "tan" to { x: Double -> tan(Math.toRadians(x)) },
        "asin" to { x: Double -> Math.toDegrees(asin(x.coerceIn(-1.0, 1.0))) },
        "acos" to { x: Double -> Math.toDegrees(acos(x.coerceIn(-1.0, 1.0))) },
        "atan" to { x: Double -> Math.toDegrees(atan(x)) },
        "ln" to { x: Double -> if (x > 0) ln(x) else Double.NaN },
        "log" to { x: Double -> if (x > 0) log10(x) else Double.NaN },
        "exp" to { x: Double -> exp(x) },
        "sinh" to { x: Double -> sinh(x) },
        "cosh" to { x: Double -> cosh(x) },
        "tanh" to { x: Double -> tanh(x) },
        "cbrt" to { x: Double -> cbrt(x) },
        "floor" to { x: Double -> floor(x) },
        "ceil" to { x: Double -> ceil(x) },
        "round" to { x: Double -> round(x) },
    )

    /** 已知标识符（用于 splitWord 最长匹配） */
    private val KNOWN: List<String> = (
        FUNCS.keys + listOf("theta", "pi", "e", "x", "y", "t", "r")
        ).sortedByDescending { it.length }

    // ============ 归一化 ============

    /**
     * 输入归一化：中文/全角符号 → 英文半角，抹去所有空白。
     * 完全对照原 JS 的 normalizeInput。
     */
    fun normalizeInput(src: String?): String {
        var s = src ?: ""

        // 1. 全角 → 半角
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = ch.code
            when {
                c in 0xFF01..0xFF5E -> sb.append((c - 0xFEE0).toChar())
                c == 0x3000 -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        s = sb.toString()

        // 2. 括号统一
        val openSet = "\uFF08\uFF3B\uFF5B\u3010\u3008\u2329\u300C\u300E"
        val closeSet = "\uFF09\uFF3D\uFF5D\u3011\u3009\u232A\u300D\u300F"
        s = s.map { ch -> if (openSet.indexOf(ch) >= 0) '(' else ch }.joinToString("")
        s = s.map { ch -> if (closeSet.indexOf(ch) >= 0) ')' else ch }.joinToString("")

        // 3. 运算符与标点
        val mulSet = "\u00D7\u2715\u2716\u22C5\u00B7\u2219"
        val divSet = "\u00F7\u2215\u2044"
        val minSet = "\u2212\u2013\u2014\u2015\uFE63"
        s = s.map { ch -> if (mulSet.indexOf(ch) >= 0) '*' else ch }.joinToString("")
        s = s.map { ch -> if (divSet.indexOf(ch) >= 0) '/' else ch }.joinToString("")
        s = s.map { ch -> if (minSet.indexOf(ch) >= 0) '-' else ch }.joinToString("")
        s = s.replace('\u3002', '.')

        // 4. 上标数字 → ^n
        s = s.replace("\u2070", "^0").replace("\u00B9", "^1")
            .replace("\u00B2", "^2").replace("\u00B3", "^3")
            .replace("\u2074", "^4").replace("\u2075", "^5")
            .replace("\u2076", "^6").replace("\u2077", "^7")
            .replace("\u2078", "^8").replace("\u2079", "^9")

        // 5. 希腊字母
        s = s.replace("\u03C0", "pi").replace("\u03A0", "pi")
            .replace("\u03B8", "theta").replace("\u03D1", "theta")
            .replace("\u221A", "sqrt")

        // 6. 去空白
        return s.replace(Regex("\\s+"), "")
    }

    /** 去掉 y= / f(x)= / r= 之类的前缀 */
    fun prep(src: String?): String {
        var s = normalizeInput(src)
        s = s.replace(Regex("^[a-zA-Z]\\s*\\(\\s*[a-zA-Z]\\s*\\)="), "")
        s = s.replace(Regex("^[a-zA-Z]="), "")
        return s
    }

    /** 是否为极坐标形式 */
    fun looksPolar(src: String?): Boolean {
        val s = normalizeInput(src)
        if (s.startsWith("r=")) return true
        if (s.contains("theta")) return true
        return false
    }

    // ============ 词法 ============

    private sealed interface Tok {
        data class N(val v: Double) : Tok
        data class I(val name: String) : Tok
        data class O(val c: Char) : Tok
    }

    /** 把粘连的标识符拆成已知符号序列 */
    private fun splitWord(word: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        var guard = 0
        while (i < word.length && guard++ < 200) {
            var matched: String? = null
            for (cand in KNOWN) {
                if (word.startsWith(cand, i)) {
                    matched = cand
                    break
                }
            }
            if (matched != null) {
                out.add(matched)
                i += matched.length
            } else {
                out.add(word.substring(i))
                break
            }
        }
        return if (out.isEmpty()) listOf(word) else out
    }

    private fun lex(s: String): List<Tok> {
        val t = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isDigit() || c == '.') {
                var j = i
                while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
                val v = s.substring(i, j).toDoubleOrNull()
                    ?: throw ParseError("数字无效：" + s.substring(i, j))
                t.add(Tok.N(v))
                i = j
                continue
            }
            if (c.isLetter() || c == '_') {
                var j = i
                while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                val word = s.substring(i, j).lowercase()
                splitWord(word).forEach { t.add(Tok.I(it)) }
                i = j
                continue
            }
            if ("+-*/^(),|!".indexOf(c) >= 0) {
                t.add(Tok.O(c))
                i++
                continue
            }
            throw ParseError("无法识别：$c")
        }
        return t
    }

    // ============ 语法分析 ============

    /** 解析表达式为语法树 */
    fun buildAst(src: String?): Node {
        val raw = lex(prep(src))
        if (raw.isEmpty()) throw ParseError("表达式为空")

        // 补隐式乘法
        val out = ArrayList<Tok>(raw.size + 8)
        for (cur in raw) {
            val prev = out.lastOrNull()
            if (prev != null) {
                val prevEndsValue = when {
                    prev is Tok.N -> true
                    prev is Tok.I -> prev.name != "pi" && prev.name != "e" && !FUNCS.containsKey(prev.name)
                    prev is Tok.O -> prev.c == ')' || prev.c == '|' || prev.c == '!'
                    else -> false
                }
                val curStartsValue = when {
                    cur is Tok.N -> true
                    cur is Tok.I -> true
                    cur is Tok.O -> cur.c == '(' || cur.c == '|'
                    else -> false
                }
                if (prevEndsValue && curStartsValue) out.add(Tok.O('*'))
            }
            out.add(cur)
        }

        var p = 0
        fun peek(): Tok? = if (p < out.size) out[p] else null
        fun eat(c: Char): Tok.O {
            val x = peek()
            if (x !is Tok.O || x.c != c) throw ParseError("缺少 '$c'")
            p++
            return x
        }

        fun expr(): Node {
            var a = term()
            while (true) {
                val x = peek()
                if (x is Tok.O && (x.c == '+' || x.c == '-')) {
                    p++
                    a = Node.Bin(x.c, a, term())
                } else break
            }
            return a
        }

        fun term(): Node {
            var a = unary()
            while (true) {
                val x = peek()
                if (x is Tok.O && (x.c == '*' || x.c == '/')) {
                    p++
                    a = Node.Bin(x.c, a, unary())
                } else break
            }
            return a
        }

        fun unary(): Node {
            val x = peek()
            if (x is Tok.O && x.c == '-') { p++; return Node.Neg(unary()) }
            if (x is Tok.O && x.c == '+') { p++; return unary() }
            return power()
        }

        fun power(): Node {
            val base = atom()
            val x = peek()
            if (x is Tok.O && x.c == '^') {
                p++
                return Node.Bin('^', base, unary())
            }
            return base
        }

        // 后缀阶乘：n! / (expr)!
        //
        // 用局部函数会形成循环依赖（atom -> 阶乘 -> atom），
        // Kotlin 局部函数又要求先声明后使用，所以直接把逻辑内联进 atom，
        // 不单独抽函数。

        fun atom(): Node {
            val x = peek() ?: throw ParseError("表达式不完整")
            if (x is Tok.N) {
                p++
                // 数字后可能跟 !
                val nx = peek()
                if (nx is Tok.O && nx.c == '!') {
                    p++
                    return Node.Num(factorialOf(x.v))
                }
                return Node.Num(x.v)
            }
            if (x is Tok.O) {
                if (x.c == '(') {
                    p++
                    val e = expr()
                    eat(')')
                    // 右括号后可能跟 !
                    val nx = peek()
                    if (nx is Tok.O && nx.c == '!') {
                        p++
                        return Node.Num(factorialOf(eval(e, 0.0)))
                    }
                    return e
                }
                if (x.c == '|') {
                    p++
                    val e = expr()
                    eat('|')
                    return Node.Fun("abs", e)
                }
            }
            if (x is Tok.I) {
                p++
                when (x.name) {
                    "x", "y", "t", "theta", "r" -> return Node.Var
                    "pi" -> return Node.Num(PI)
                    "e" -> return Node.Num(Math.E)
                }
                val fn = FUNCS[x.name]
                if (fn != null) {
                    // sin^2(x) 形式：先读指数，再读参数
                    val nx = peek()
                    if (nx is Tok.O && nx.c == '^') {
                        p++
                        val expNode = unary()
                        val argNode = run {
                            val after = peek()
                            if (after is Tok.O && after.c == '(') {
                                p++
                                val a = expr()
                                eat(')')
                                a
                            } else {
                                power()
                            }
                        }
                        return Node.Bin('^', Node.Fun(x.name, argNode), expNode)
                    }
                    val after = peek()
                    if (after is Tok.O && after.c == '(') {
                        p++
                        val a = expr()
                        eat(')')
                        return Node.Fun(x.name, a)
                    }
                    return Node.Fun(x.name, power())
                }
                throw ParseError("未知符号 " + x.name)
            }
            throw ParseError("无法解析")
        }

        val ast = expr()
        if (p < out.size) throw ParseError("多余内容")
        return ast
    }

    // ============ 求值 ============

    /** 求值：x 为变量取值 */
    fun eval(ast: Node, x: Double = 0.0): Double = when (ast) {
        is Node.Num -> ast.v
        Node.Var -> x
        is Node.Neg -> -eval(ast.a, x)
        is Node.Bin -> {
            val a = eval(ast.a, x)
            val b = eval(ast.b, x)
            when (ast.op) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                '/' -> if (b == 0.0) Double.NaN else a / b
                '^' -> a.pow(b)
                else -> Double.NaN
            }
        }
        is Node.Fun -> {
            val f = FUNCS[ast.name] ?: return Double.NaN
            f(eval(ast.a, x))
        }
    }

    /** 编译：返回 (ast, f) */
    fun compile(src: String?): Pair<Node, (Double) -> Double> {
        val ast = buildAst(src)
        val f = { x: Double -> eval(ast, x) }
        // 快速验证一次，把明显错误提前抛出
        val test = f(1.234)
        if (test.isNaN() && !isExpectedNaN(ast)) {
            // 不算致命错误，绘图时逐点判 NaN 即可
        }
        return ast to f
    }

    private fun isExpectedNaN(ast: Node): Boolean = false

    /** 直接算一个不含变量的表达式 */
    fun calc(src: String?): Double = eval(buildAst(src), 0.0)

    // ============ 阶乘 ============

    /** 阶乘：只支持非负整数，上限 170（否则溢出） */
    fun factorialOf(n: Double): Double {
        val v = if (n.isFinite()) n.toLong() else -1L
        if (v < 0L || v > 170L) return Double.NaN
        var r = 1.0
        for (i in 2..v) r *= i
        return r
    }

    // ============ 多项式系数 ============

    /**
     * 提取多项式系数（低次到高次）。
     * 非多项式返回 null。
     */
    fun asPoly(ast: Node): List<Double>? {
        fun walk(n: Node, depth: Int): List<Double>? {
            if (depth > 8) return null
            when (n) {
                is Node.Num -> return listOf(n.v)
                Node.Var -> return listOf(0.0, 1.0)
                is Node.Neg -> return walk(n.a, depth + 1)?.map { -it }
                is Node.Bin -> when (n.op) {
                    '+', '-' -> {
                        val a = walk(n.a, depth + 1) ?: return null
                        val b = walk(n.b, depth + 1) ?: return null
                        val len = maxOf(a.size, b.size)
                        val r = ArrayList<Double>(len)
                        for (i in 0 until len) {
                            val av = if (i < a.size) a[i] else 0.0
                            val bv = if (i < b.size) b[i] else 0.0
                            r.add(av + if (n.op == '-') -bv else bv)
                        }
                        return r
                    }
                    '*' -> {
                        val a = walk(n.a, depth + 1) ?: return null
                        val b = walk(n.b, depth + 1) ?: return null
                        val r = DoubleArray(a.size + b.size - 1)
                        for (i in a.indices) for (j in b.indices) r[i + j] += a[i] * b[j]
                        return r.toList()
                    }
                    '/' -> {
                        val a = walk(n.a, depth + 1) ?: return null
                        val b = walk(n.b, depth + 1) ?: return null
                        if (b.size != 1 || b[0] == 0.0) return null
                        return a.map { it / b[0] }
                    }
                    '^' -> {
                        val base = walk(n.a, depth + 1) ?: return null
                        val exp = walk(n.b, depth + 1) ?: return null
                        if (exp.size != 1) return null
                        val e = exp[0]
                        if (e < 0 || e > 12 || abs(e - Math.round(e)) > 1e-9) return null
                        var out = listOf(1.0)
                        repeat(Math.round(e).toInt()) {
                            val r = DoubleArray(out.size + base.size - 1)
                            for (i in out.indices) for (j in base.indices) r[i + j] += out[i] * base[j]
                            out = r.toList()
                        }
                        return out
                    }
                    else -> return null
                }
                is Node.Fun -> return null
            }
        }
        return walk(ast, 0)
    }
}
