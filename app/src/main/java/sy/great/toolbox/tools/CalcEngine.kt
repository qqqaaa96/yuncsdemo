package sy.great.toolbox.tools

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

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
        /**
         * 变量。
         *
         * 带名字：同一个表达式里 x 和 a 是两个不同的值，
         * 求值时由 Map 提供（见 evalMap）。
         */
        data class Var(val name: String) : Node
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

    /**
     * 函数库（科学计算器完整版）。
     *
     * 分五类，共 30 个：
     *
     * 【基本】sqrt cbrt abs sign
     * 【三角】sin cos tan cot sec csc              —— 角度制
     * 【反三角】asin acos atan acot asec acsc      —— 返回角度
     * 【双曲】sinh cosh tanh asinh acosh atanh
     * 【指数对数】exp ln log log2 pow10
     * 【取整】floor ceil round trunc frac
     *
     * 三角为什么用角度制：
     *   与 HTML 原版一致（原版就是 x * PI / 180），
     *   也符合国内教学/计算器习惯。弧度版另用 rad 系列函数。
     *
     * 为什么 KNOWN 从 FN 派生（见下方）：
     *   手动维护两份列表一定会不一致，
     *   之前就出现过“splitWord 认得、FN 没实现”导致报未知符号。
     */
    private val FN: Map<String, (Double) -> Double> = mapOf(
        // ---- 基本 ----
        "sqrt" to { x: Double -> if (x < 0) Double.NaN else sqrt(x) },
        "cbrt" to { x: Double -> Math.cbrt(x) },
        "abs" to { x: Double -> abs(x) },
        "sign" to { x: Double -> if (x > 0) 1.0 else if (x < 0) -1.0 else 0.0 },

        // ---- 三角（角度制）----
        "sin" to { x: Double -> sin(Math.toRadians(x)) },
        "cos" to { x: Double -> cos(Math.toRadians(x)) },
        "tan" to { x: Double -> tan(Math.toRadians(x)) },
        "cot" to { x: Double -> 1.0 / tan(Math.toRadians(x)) },
        "sec" to { x: Double -> 1.0 / cos(Math.toRadians(x)) },
        "csc" to { x: Double -> 1.0 / sin(Math.toRadians(x)) },

        // ---- 弧度版三角（与上面的区别：直接吃弧度）----
        "sinr" to { x: Double -> sin(x) },
        "cosr" to { x: Double -> cos(x) },
        "tanr" to { x: Double -> tan(x) },

        // ---- 反三角（返回角度）----
        "asin" to { x: Double -> if (x < -1 || x > 1) Double.NaN else Math.toDegrees(Math.asin(x)) },
        "acos" to { x: Double -> if (x < -1 || x > 1) Double.NaN else Math.toDegrees(Math.acos(x)) },
        "atan" to { x: Double -> Math.toDegrees(Math.atan(x)) },
        // acot 取值范围 (0°, 180°)：
        //   x > 0 → atan(1/x)
        //   x < 0 → atan(1/x) + 180°
        //   x = 0 → 90°
        "acot" to { x: Double ->
            when {
                x == 0.0 -> 90.0
                x > 0 -> Math.toDegrees(Math.atan(1.0 / x))
                else -> Math.toDegrees(Math.atan(1.0 / x)) + 180.0
            }
        },
        "asec" to { x: Double -> if (abs(x) < 1) Double.NaN else Math.toDegrees(Math.acos(1.0 / x)) },
        "acsc" to { x: Double -> if (abs(x) < 1) Double.NaN else Math.toDegrees(Math.asin(1.0 / x)) },

        // ---- 双曲 ----
        "sinh" to { x: Double -> Math.sinh(x) },
        "cosh" to { x: Double -> Math.cosh(x) },
        "tanh" to { x: Double -> Math.tanh(x) },
        "asinh" to { x: Double -> Math.log(x + sqrt(x * x + 1)) },
        "acosh" to { x: Double -> if (x < 1) Double.NaN else Math.log(x + sqrt(x * x - 1)) },
        "atanh" to { x: Double -> if (abs(x) >= 1) Double.NaN else 0.5 * Math.log((1 + x) / (1 - x)) },

        // ---- 指数 / 对数 ----
        "exp" to { x: Double -> Math.exp(x) },
        "ln" to { x: Double -> if (x <= 0) Double.NaN else ln(x) },
        "log" to { x: Double -> if (x <= 0) Double.NaN else Math.log10(x) },
        "log2" to { x: Double -> if (x <= 0) Double.NaN else Math.log(x) / Math.log(2.0) },
        "pow10" to { x: Double -> Math.pow(10.0, x) },

        // ---- 取整 ----
        "floor" to { x: Double -> Math.floor(x) },
        "ceil" to { x: Double -> Math.ceil(x) },
        "round" to { x: Double -> Math.round(x).toDouble() },
        "trunc" to { x: Double -> if (x >= 0) Math.floor(x) else Math.ceil(x) },
        "frac" to { x: Double -> x - (if (x >= 0) Math.floor(x) else Math.ceil(x)) },

        // ---- 阶乘（后缀 ! 会被转成 fact(x)）----
        //
        // 只接受非负整数，上限 170（超过就溢出 double）。
        // 非整数返回 NaN（不自动取整，避免猜到用户意图）。
        "fact" to { x: Double ->
            when {
                !x.isFinite() -> Double.NaN
                x < 0 -> Double.NaN
                x > 170 -> Double.POSITIVE_INFINITY
                abs(x - Math.round(x)) > 1e-9 -> Double.NaN
                else -> {
                    var r = 1.0
                    for (i in 2..Math.round(x).toInt()) r *= i
                    r
                }
            }
        },
    )

    // ============ 变量体系 ============
    //
    // 设计：
    //   · a~z 单字母全部当变量（包括 a/b/c/k/m/p/q/w 等参数名）
    //   · theta / alpha / beta / gamma / delta / omega 等多字母也当变量
    //   · x / y / t / r / theta 是“自变量”，其余是“参数”
    //
    // 为什么要这样：
    //   旧版本只认 x/y/t/r/n/theta，写 a*sin(x) 会报“未知符号 a”。
    //   计算器必须能算带参数的函数，比如：
    //     y = (x^2)^(1/3) + 0.9*(8 - x^2)^(1/2)*sin(a*pi*x)
    //   这里的 a 就应该是可调参数，不是错误。

    /** 自变量名（会被当作“图上的横轴”） */
    private val MAIN_VARS: List<String> = listOf("theta", "t", "x", "y", "r")

    /**
     * 多字母变量名。
     *
     * theta 必须在这里！
     *   它是极坐标的自变量，五个字母，
     *   之前没包含 → isVarName 返回 false → 报“未知符号 theta”。
     */
    private val MULTI_PARAMS: Set<String> = setOf(
        "theta",   // 极坐标角度（必须有）
        "alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta",
        "kappa", "lambda", "mu", "nu", "xi", "rho", "sigma", "tau",
        "upsilon", "chi", "psi", "omega",
        "phi",     // 黄金比例常数，但用户也可能当变量
    )

    /** 是否单字母变量（a~z） */
    private fun isSingleLetterVar(s: String): Boolean =
        s.length == 1 && s[0] in 'a'..'z'

    /** 是否为变量名（含自变量与参数） */
    private fun isVarName(s: String): Boolean =
        isSingleLetterVar(s) || s in MULTI_PARAMS || s == "theta"

    /**
     * 常数表。
     *
     * 【必须定义在 KNOWN 之前】
     * Kotlin object 的成员属性按【声明顺序】初始化，
     * KNOWN 里引用了 CONSTS.keys，如果 CONSTS 在后面，
     * 初始化时会读到未初始化的 null -> 编译报错
     * “Variable 'CONSTS' must be initialized”。
     *
     * phi = 黄金比例 (1+√5)/2 ≈ 1.618
     */
    private val CONSTS: Map<String, Double> = mapOf(
        "pi" to Math.PI,
        "e" to Math.E,
        "phi" to (1.0 + sqrt(5.0)) / 2.0,
    )

    /**
     * 已知标识符（splitWord 最长匹配用）。
     *
     * 从 FN + CONSTS + MULTI_PARAMS + 全部单字母自动派生，
     * 避免手写两份导致不一致。
     */
    private val KNOWN: List<String> = (
        FN.keys +
            CONSTS.keys +
            MULTI_PARAMS +
            listOf(
                // 单字母变量与参数（a~z 全部）
                "a", "b", "c", "d", "f", "g", "h", "i", "j",
                "k", "l", "m", "n", "o", "p", "q", "r", "s",
                "t", "u", "v", "w", "x", "y", "z",
            )
        ).distinct().sortedByDescending { it.length }

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
    /**
     * 判断是否为隐函数（含等号且两边都有变量）。
     *
     * 例：
     *   (x^2+y^2-1)^3 - x^2*y^3 = 0    → 隐函数
     *   x^2 + y^2 = 4                  → 隐函数（圆）
     *   y = sin(x)                      → 普通函数（y 单独在左）
     */
    fun isImplicit(src: String?): Boolean {
        val s = normalizeInput(src)
        if (!s.contains('=')) return false
        if (s.count { it == '=' } != 1) return false
        // 单纯 y= / f(x)= / r= 开头不算隐函数
        if (Regex("^[a-zA-Z]=").containsMatchIn(s)) return false
        if (Regex("^[a-zA-Z]\\s*\\(.*\\)=").containsMatchIn(s)) return false
        return true
    }

    /**
     * 把隐函数转成 F(x, y) = 左边 − 右边。
     *
     * 例：
     *   (x^2+y^2-1)^3 - x^2*y^3 = 0
     *   → (x^2+y^2-1)^3 - x^2*y^3 - 0
     */
    fun implicitExpr(src: String?): String {
        val s = normalizeInput(src)
        val i = s.indexOf('=')
        if (i < 0) return s
        val left = s.substring(0, i)
        val right = s.substring(i + 1)
        return "($left)-($right)"
    }

    /** 输入类型（自动识别） */
    enum class Kind {
        /** y = f(x)，或直接写 f(x) */
        EXPLICIT,
        /** r = f(theta) */
        POLAR,
        /** F(x, y) = 0 */
        IMPLICIT,
    }

    /**
     * 自动判断输入是什么类型。
     *
     * 规则（与「数学教学.html」的 G.classify 一致）：
     *   · 含等号且左侧是单独的 y → 显函数
     *   · 含等号且左侧是单独的 r → 极坐标
     *   · 只含 theta 无等号 → 极坐标
     *   · 其余含等号的（两边都有 x/y）→ 隐函数
     *   · 什么都没有 → 显函数
     */
    fun classify(src: String?): Kind {
        val s = normalizeInput(src)
        if (s.isEmpty()) return Kind.EXPLICIT

        if (s.contains('=')) {
            // y = ... 但右侧不能再有 =
            if (s.startsWith("y=")) return Kind.EXPLICIT
            if (s.startsWith("r=")) return Kind.POLAR
            // f(x)= / y(x)= 也算显函数
            if (Regex("^[a-zA-Z]\\([a-zA-Z]\\)=").containsMatchIn(s)) return Kind.EXPLICIT
            // 两边都有变量 → 隐函数
            return Kind.IMPLICIT
        }

        // 无等号：含 theta 当极坐标，否则当显函数
        if (s.contains("theta")) return Kind.POLAR
        return Kind.EXPLICIT
    }

    /** 编译隐函数为 F(x, y) */
    fun compileImplicit(src: String?): (Double, Double) -> Double {
        val ast = buildAst(implicitExpr(src))
        return { x, y ->
            evalMap(ast) { name ->
                when (name) {
                    "x" -> x
                    "y" -> y
                    else -> 0.0
                }
            }
        }
    }

    /**
     * 去掉 y= / f(x)= / r= 之类的前缀。
     *
     * 严格按原版，【不】额外删逗号：
     *   normalizeInput 会把中文逗号转成半角逗号，
     *   而原版 lex 里 "," 是合法运算符字符，不会报错。
     */
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
            // 运算符白名单。
            //
            // 注意：这里【不包含 =】。
            //   计算器只接受表达式，不接受方程；
            //   方程页会在调用前把 a=b 变成 (a)-(b)。
            //   如果 = 走到这里，说明调用方没处理等式。
            // 运算符表：'+-*/^(),|!'
            //
            // '!' 是后缀阶乘，只作用于数字/括号。
            // 隐式乘法判定里已经处理了它（见 buildAst 的 prevEndsValue），
            // 所以 3!2 会被拆成 3! * 2 而不是报错。
            if ("+-*/^(),|!".indexOf(c) >= 0) {
                t.add(Tok.O(c))
                i++
                continue
            }

            // 遇到 = 给明确提示（原版直接报“无法识别：=”
            //   这里保留提示但文案更清楚，不影响解析行为）
            if (c == '=') {
                throw ParseError(
                    "无法识别字符「=」。\n" +
                        "· 画方程（如 (x²+y²-1)³-x²y³=0）请切到「隐函数」模式\n" +
                        "· 求解方程请用「方程」页"
                )
            }

            throw ParseError("无法识别字符「$c」（第 ${i + 1} 个字符）")
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
                    // 与原版一致：
                    //   prev.k === 'n' 或
                    //   (prev.k === 'i' && prev.v !== 'pi' && prev.v !== 'e' && !FN[prev.v]) 或
                    //   prev.v === ')' || prev.v === '|'
                    //
                    // 注意：原版没有 '!'（不支持阶乘），这里也不加。
                    prev is Tok.N -> true
                    prev is Tok.I -> prev.name !in CONSTS && !FN.containsKey(prev.name)
                    // '!' 是一个值的结束（3! * 2 要插乘号）
                    prev is Tok.O -> prev.c == ')' || prev.c == '|' || prev.c == '!' || prev.c == '%'
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

        // 递归下降需要互相调用（expr->term->unary->power->atom->expr），
        // 而 Kotlin 的局部函数不能前向引用，所以改用「可重入解析器对象」：
        // 把游标和 5 个递归函数都放进一个内部类，成员函数可自由互调用。
        val parser = Parser(out)
        val ast0 = parser.parseExpr()
        if (!parser.atEnd()) throw ParseError("多余内容")
        return ast0
    }

    /**
     * 递归下降解析器。
     *
     * 为什么单独抽成内部类：
     *   解析表达式必然互相递归（加减→乘除→一元→幂→原子→括号→加减），
     *   局部函数做不到这种循环调用，只能用具名类加成员函数。
     */
    private class Parser(private val toks: List<Tok>) {

        private var p = 0

        fun atEnd(): Boolean = p >= toks.size

        private fun peek(): Tok? = if (p < toks.size) toks[p] else null

        private fun eat(c: Char): Tok.O {
            val x = peek()
            if (x !is Tok.O || x.c != c) throw ParseError("缺少 '$c'")
            p++
            return x
        }

        /** 加减 */
        fun parseExpr(): Node {
            var a = parseTerm()
            while (true) {
                val x = peek()
                if (x is Tok.O && (x.c == '+' || x.c == '-')) {
                    p++
                    a = Node.Bin(x.c, a, parseTerm())
                } else break
            }
            return a
        }

        /** 乘除 */
        private fun parseTerm(): Node {
            var a = parseUnary()
            while (true) {
                val x = peek()
                if (x is Tok.O && (x.c == '*' || x.c == '/')) {
                    p++
                    a = Node.Bin(x.c, a, parseUnary())
                } else break
            }
            return a
        }

        /** 一元正负 */
        private fun parseUnary(): Node {
            val x = peek()
            if (x is Tok.O && x.c == '-') { p++; return Node.Neg(parseUnary()) }
            if (x is Tok.O && x.c == '+') { p++; return parseUnary() }
            return parsePower()
        }

        /** 幂（右结合） */
        private fun parsePower(): Node {
            val base = parseAtom()
            val x = peek()
            if (x is Tok.O && x.c == '^') {
                p++
                return Node.Bin('^', base, parseUnary())
            }
            return base
        }

        /**
         * 处理后缀阶乘：3! / (2+1)! / x!
         *
         * 必须定义在 parseAtom() 【之前】：
         * Kotlin 局部/成员函数按声明顺序解析（成员函数不受限，
         * 但保持习惯顺序，避免以后改成局部函数时出错）。
         *
         * 阶乘直接求值成常数：
         *   引擎只支持数值求值，不保留符号阶乘，
         *   所以 x! 在 x 未确定时无法计算 → 将节点包成函数调用。
         */
        private fun withFactorial(node: Node): Node {
            val x = peek()
            if (x is Tok.O && x.c == '!') {
                p++
                // 阶乘作为一元函数节点保留（求值时算）
                return Node.Fun("fact", node)
            }
            return node
        }

        /** 原子项：数字 / 括号 / |x| / 变量 / 函数调用 */
        private fun parseAtom(): Node {
            val x = peek() ?: throw ParseError("表达式不完整")

            // ---- 数字（可跟后缀 ! ）----
            if (x is Tok.N) {
                p++
                return withFactorial(Node.Num(x.v))
            }

            // ---- 括号 / 绝对值（也可跟后缀 ! ）----
            if (x is Tok.O) {
                if (x.c == '(') {
                    p++
                    val e = parseExpr()
                    eat(')')
                    return withFactorial(e)
                }
                if (x.c == '|') {
                    p++
                    val e = parseExpr()
                    eat('|')
                    return Node.Fun("abs", e)
                }
            }

            // ---- 标识符 ----
            if (x is Tok.I) {
                p++

                // 常数优先（pi / e / phi 不能当变量）
                CONSTS[x.name]?.let { return Node.Num(it) }

                // 变量：a~z 单字母 + alpha/beta 等多字母
                if (isVarName(x.name)) return Node.Var(x.name)

                if (FN.containsKey(x.name)) {
                    // sin^2(x)：先读指数，再读参数
                    val nx = peek()
                    if (nx is Tok.O && nx.c == '^') {
                        p++
                        val expNode = parseUnary()
                        val argNode = if (peek() is Tok.O && (peek() as Tok.O).c == '(') {
                            p++
                            val a = parseExpr()
                            eat(')')
                            a
                        } else {
                            parsePower()
                        }
                        return Node.Bin('^', Node.Fun(x.name, argNode), expNode)
                    }

                    // 普通函数调用
                    if (peek() is Tok.O && (peek() as Tok.O).c == '(') {
                        p++
                        val a = parseExpr()
                        eat(')')
                        return Node.Fun(x.name, a)
                    }
                    // 无括号：sin x
                    return Node.Fun(x.name, parsePower())
                }
                throw ParseError("未知符号 " + x.name)
            }
            throw ParseError("无法解析")
        }
    }

    // ============ 求值 ============

    /**
     * 求值（单变量简化版）。
     *
     * x 会赋给【所有】变量名：
     *   因为图像绘制只沿一个自变量走，
     *   其他参数应当用 evalMap 单独指定。
     */
    fun eval(ast: Node, x: Double = 0.0): Double = evalMap(ast) { x }

    /**
     * 按名字求值。
     *
     * vars 给每个变量名一个值（如 { x=2.0, a=1.5 }）；
     * 未提供的变量默认 0。
     *
     * 这是支持带参函数的关键：
     *   y = sin(a*pi*x)  里的 a 与 x 是独立的，
     *   绘图时遍历 x、固定 a。
     */
    fun evalMap(ast: Node, vars: (String) -> Double): Double = when (ast) {
        is Node.Num -> ast.v
        is Node.Var -> vars(ast.name)
        is Node.Neg -> -evalMap(ast.a, vars)
        is Node.Bin -> {
            val a = evalMap(ast.a, vars)
            val b = evalMap(ast.b, vars)
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
            val f = FN[ast.name] ?: return Double.NaN
            f(evalMap(ast.a, vars))
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

    // 阶乘已移除：HTML 原版不支持 '!'，为保持一致也不支持。

    // ============ 数值求根 ============

    /**
     * 在 [a, b] 内数值求根。
     *
     * 完全按 HTML 原版实现：
     *   · 等分 900 段扫描
     *   · 变号处做 50 次二分
     *   · 去重阈值 2e-4
     *
     * 为什么参数跟原版一致：
     *   段数太少会漏掉相邻近的两个根；
     *   二分次数太少精度不够（50 次 → 区间宽度缩到 2^-50）；
     *   去重阈值太小会把同一个根重复计数。
     */
    fun findRoots(f: (Double) -> Double, a: Double, b: Double): List<Double> {
        val res = ArrayList<Double>()
        val n = 900

        fun push(v: Double) {
            for (r in res) if (abs(r - v) < 2e-4) return
            res.add(v)
        }

        var px = a
        var py: Double? = null

        for (i in 0..n) {
            val x = a + (b - a) * i / n
            val y = try {
                f(x)
            } catch (_: Throwable) {
                Double.NaN
            }

            if (!y.isFinite()) {
                py = null
                px = x
                continue
            }

            // 取非空局部值。
            //
            // 注意声明为 Double（不是 Double?）：
            //   写成 val pv = prevY 时，因为 prevY 显式声明为 Double?，
            //   pv 也会被推断成 Double?，后面 flo * fm 仍会报
            //   “Operator call is prohibited on a nullable receiver”。
            //   这里用 elvis + return 强制取非空值。
            val prevY = py
            if (prevY != null) {
                val pv: Double = prevY
                if (y == 0.0) {
                    push(x)
                } else if (pv * y < 0) {
                    // 变号：二分 50 次
                    var lo = px
                    var hi = x
                    var flo: Double = pv
                    for (k in 0 until 50) {
                        val mid = (lo + hi) / 2
                        val fm = f(mid)
                        if (flo * fm <= 0) {
                            hi = mid
                        } else {
                            lo = mid
                            flo = fm
                        }
                    }
                    push((lo + hi) / 2)
                }
            }
            px = x
            py = y
        }
        return res
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
                is Node.Var -> {
                    // 只有自变量（x）能当多项式变量；
                    // 出现 a/b/theta 等参数时不是关于 x 的多项式，返回 null
                    // （方程页会回退到数值求根）。
                    return if (n.name == "x") listOf(0.0, 1.0) else null
                }
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
                        // 与 HTML 原版一致：
                        //   指数必须是【常数节点】且为【整数】且【0 ≤ e ≤ 4】。
                        // HTML 就是 `n.b.k === 'n' && Number.isInteger(v) && v>=0 && v<=4`。
                        //
                        // 之前我放宽到 12 并且用「系数数组长度=1」判断，
                        // 会把非整数指数（如 x^0.5）误判成多项式，导致方程求解给出错误结果。
                        val base = walk(n.a, depth + 1) ?: return null
                        if (n.b !is Node.Num) return null
                        val e = n.b.v
                        if (e < 0 || e > 4 || abs(e - Math.round(e)) > 1e-9) return null
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

    // ============ 变量检测与参数化求值 ============

    /**
     * 表达式里用到的变量。
     *
     * primary：自变量（图上的横轴）
     * params ：其余变量（a、b、k 等），用于滑块调参
     */
    data class VarInfo(
        val primary: String,
        val params: List<String> = emptyList(),
    ) {
        val hasParam: Boolean get() = params.isNotEmpty()
    }

    /**
     * 扫描表达式，找出变量。
     *
     * "自动检测变量"：
     *   用户写 r=1-sin(theta)、y=x^2、x=t^2 都能识别，
     *   不需要手动声明哪个是自变量。
     *
     * 优先级（同时出现多个时）：theta > t > x > y > r > n
     *   theta 优先是因为极坐标最常见；
     *   这样 r=1-sin(theta) 会以 theta 为自变量。
     */
    fun detectVar(src: String?): VarInfo {
        var s = normalizeInput(src)

        // 先剥掉等式左边（y= / r= / f(x)= ），
        // 否则 y=sin(x) 里的 y 会被误认为参数。
        if (s.contains('=')) {
            val i = s.indexOf('=')
            val left = s.substring(0, i)
            // 左边只有单个字母/函数形式时，整段丢弃
            if (Regex("^[a-zA-Z](\\([a-zA-Z]\\))?$").matches(left)) {
                s = s.substring(i + 1)
            }
        }

        // 1. 找出所有“独立”的单字母（不在函数名内部的）
        //    做法：先把所有已知函数名和常数名从串里挖掉，剩下的单字母才是变量。
        var rest = s
        // 长名先挖（避免 alpha 被当成 a+l+p+h+a）
        (FN.keys + CONSTS.keys + MULTI_PARAMS).sortedByDescending { it.length }.forEach { name ->
            rest = rest.replace(name, " ")
        }

        val found = LinkedHashSet<String>()
        // 多字母参数（alpha 等）单独找
        MULTI_PARAMS.forEach { name ->
            if (s.contains(name)) found.add(name)
        }
        // 单字母变量
        rest.forEach { ch ->
            if (ch in 'a'..'z') found.add(ch.toString())
        }

        // 2. 选自变量：按优先级 theta > t > x > y > r
        var primary = "x"
        for (cand in MAIN_VARS) {
            if (found.contains(cand)) {
                primary = cand
                break
            }
        }
        // 都没有变量时默认 x（保证图像能画一条水平线）
        if (found.isEmpty()) primary = "x"

        // 3. 其余当参数
        val params = found.filter { it != primary }
        return VarInfo(primary, params)
    }

    /**
     * 把表达式编译成「接受参数 k 的函数」。
     *
     * 用途：滑块调参。表达式里的一个变量当作自变量，
     * 另一个位置（如 a*sin(x) 里的 a）当参数——但计算器 UI 里
     * 我们只提供「一个自变量」的滑块，所以这里把自变量固定，
     * 让 k 去替换自变量本身。
     *
     * 简单说：compiled(x) = f(x)，滑块改变的就是 x 的取值。
     *   （图形的「疏密」由视窗范围与 x 范围决定，见 GraphCalc）
     */
    fun compileWithVar(src: String?, varName: String): (Double) -> Double {
        val ast = buildAst(src)
        return { v -> eval(ast, v) }
    }

    /**
     * 编译成功率「自变量 + 参数」的函数。
     *
     * 用法（绘图）：
     *   val ast = compileAst("sin(a*pi*x)")
     *   // 绘曲线时遍历 x，固定 a=1.5
     *   val f = { x: Double -> evalMap(ast) { name -> if (name == "a") 1.5 else x } }
     *
     * 这样 a 与 x 是独立的，不会把 a 也当成 x。
     */
    fun compileAst(src: String?): Node = buildAst(src)

    /**
     * 自动视窗：采样函数，算出包围盒，再加上边距。
     *
     * 这是 HTML 的 autoRange()，没有它图像就会“画得不对”：
     *   固定视窗 [−10,10] 会把你写的函数裁掉一大半，
     *   比如 (x^2)^(1/3) 在 x=10 时已经到 4.6，
     *   而 0.9*(8−x^2)^(1/2) 只在 |x|<2.83 有意义。
     *
     * @param f 被绘制的函数
     * @param x0 x1 采样区间
     * @param pad 边距比例（HTML 用 0.12）
     * @return [x0, x1, y0, y1]，无有效点时返回 null
     */
    fun autoRange(
        f: (Double) -> Double,
        x0: Double,
        x1: Double,
        n: Int = 600,
        pad: Double = 0.12,
    ): DoubleArray? {
        var mnx = Double.POSITIVE_INFINITY
        var mxx = Double.NEGATIVE_INFINITY
        var mny = Double.POSITIVE_INFINITY
        var mxy = Double.NEGATIVE_INFINITY
        var count = 0

        for (i in 0..n) {
            val x = x0 + (x1 - x0) * i / n
            val y = try {
                f(x)
            } catch (_: Throwable) {
                Double.NaN
            }
            if (!y.isFinite() || abs(y) > 1e5) continue
            count++
            if (x < mnx) mnx = x
            if (x > mxx) mxx = x
            if (y < mny) mny = y
            if (y > mxy) mxy = y
        }
        if (count == 0) return null

        // 退化情形（水平线/竖直线）
        if (mxx - mnx < 1e-9) { mnx -= 1; mxx += 1 }
        if (mxy - mny < 1e-9) { mny -= 1; mxy += 1 }

        val dx = (mxx - mnx) * pad
        val dy = (mxy - mny) * pad
        return doubleArrayOf(mnx - dx, mxx + dx, mny - dy, mxy + dy)
    }

    /**
     * 把自动范围调整到与画布宽高比一致（避免图形被拉伸变形）。
     *
     * HTML 里就是：
     *   if (w/h < aspect) 扩展 x；否则扩展 y
     */
    fun fitAspect(range: DoubleArray, aspect: Double): DoubleArray {
        val w = range[1] - range[0]
        val h = range[3] - range[2]
        if (w <= 0 || h <= 0 || aspect <= 0) return range

        return if (w / h < aspect) {
            val nw = h * aspect
            val cx = (range[0] + range[1]) / 2
            doubleArrayOf(cx - nw / 2, cx + nw / 2, range[2], range[3])
        } else {
            val nh = w / aspect
            val cy = (range[2] + range[3]) / 2
            doubleArrayOf(range[0], range[1], cy - nh / 2, cy + nh / 2)
        }
    }

    /**
     * 探测函数在给定视窗内的值域与变化剧烈程度。
     *
     * 用于自动决定「滑块上限」：
     *   · 变化平缓的函数（如 x^2 在 [-10,10]）→ 小上限就够
     *   · 变化剧烈的函数（如 sin(50x)）→ 需要大上限才能看出细节
     *
     * @return 样本数、值域、斜率中位数
     */
    data class Shape(
        val samples: Int,
        val minY: Double,
        val maxY: Double,
        /** 相邻样本 y 差绝对值的中位数（越小越平缓） */
        val medianSlope: Double,
        /** 有效样本占比 0..1 */
        val validRatio: Double,
    ) {
        /** 图形在垂直方向占满视窗的程度 0..1 */
        val verticalFill: Double
            get() = if (maxY - minY < 1e-12) 0.0
            else ((maxY - minY) / (maxY - minY + 1e-12)).coerceIn(0.0, 1.0)
    }

    /**
     * 采样分析函数形状（供滑块上限自适应）。
     *
     * @param x0 x1 采样区间
     * @param n 采样点数（建议 400）
     */
    fun analyzeShape(f: (Double) -> Double, x0: Double, x1: Double, n: Int = 400): Shape {
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var valid = 0
        val slopes = ArrayList<Double>(n)
        var prevY = Double.NaN

        for (i in 0..n) {
            val x = x0 + (x1 - x0) * i / n
            val y = try {
                f(x)
            } catch (_: Throwable) {
                Double.NaN
            }
            if (!y.isFinite() || abs(y) > 1e8) {
                prevY = Double.NaN
                continue
            }
            valid++
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            if (prevY.isFinite()) slopes.add(abs(y - prevY))
            prevY = y
        }

        if (valid == 0) return Shape(n, 0.0, 0.0, 0.0, 0.0)
        slopes.sort()
        val med = if (slopes.isEmpty()) 0.0 else slopes[slopes.size / 2]
        return Shape(n, minY, maxY, med, valid.toDouble() / (n + 1))
    }
}
