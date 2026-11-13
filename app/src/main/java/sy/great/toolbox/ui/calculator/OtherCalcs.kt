package sy.great.toolbox.ui.calculator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.tools.CalcEngine
import sy.great.toolbox.tools.CalcFormat
import sy.great.toolbox.ui.theme.LocalPalette
import kotlin.math.abs
import kotlin.math.sqrt

/* ============================================================
 * 4. 方程求解
 * ============================================================ */

@Composable
fun EquationCalc() {
    var input by remember { mutableStateOf("x^2 - 5x + 6 = 0") }
    var unknown by remember { mutableStateOf("x") }
    var output by remember { mutableStateOf("输入方程后点击求解") }
    var isErr by remember { mutableStateOf(false) }

    fun solve() {
        isErr = false
        try {
            val raw = input.trim()
            if (raw.isEmpty()) throw IllegalArgumentException("请输入方程")
            val v = unknown.trim().ifBlank { "x" }

            val sides = raw.split("=")
            val exprStr = when (sides.size) {
                2 -> "(" + sides[0] + ")-(" + sides[1] + ")"
                1 -> raw
                else -> throw IllegalArgumentException("方程只能有一个等号")
            }

            // 把未知数统一成 x
            val norm = CalcEngine.normalizeInput(exprStr).replace(v, "x")
            val ast = CalcEngine.buildAst(norm)
            val coeffs = CalcEngine.asPoly(ast)

            if (coeffs == null) {
                // 数值求解：扫描 + 二分
                val f = { t: Double -> CalcEngine.eval(ast, t) }
                val roots = ArrayList<Double>()
                val step = 0.5
                var px = -100.0
                var py = f(px)
                var gx = px + step
                while (gx <= 100.0) {
                    val gy = f(gx)
                    if (py.isFinite() && gy.isFinite() && py * gy <= 0 && py != gy) {
                        var lo = px
                        var hi = gx
                        var loy = py
                        for (i in 0 until 60) {
                            val mid = (lo + hi) / 2
                            val my = f(mid)
                            if (loy * my <= 0) hi = mid else { lo = mid; loy = my }
                        }
                        val r = (lo + hi) / 2
                        if (roots.none { abs(it - r) < 1e-6 }) roots.add(r)
                    }
                    px = gx; py = gy; gx += step
                }
                if (roots.isEmpty()) {
                    output = "在 [-100, 100] 内未找到实根\n可能无解，或解在该范围外"
                    isErr = true
                } else {
                    output = "数值解（近似）：\n" + roots.mapIndexed { i, r ->
                        "$v${i + 1} = " + CalcFormat.num(r, 10)
                    }.joinToString("\n")
                }
                return
            }

            val c = coeffs.toMutableList()
            while (c.isNotEmpty() && abs(c.last()) < 1e-12) c.removeAt(c.size - 1)
            if (c.isEmpty()) {
                output = "等式恒成立（所有值都是解）"
                isErr = true
                return
            }

            when (c.size - 1) {
                1 -> {
                    val b = c[1]
                    val a0 = c[0]
                    output = "一次方程\n$v = " + CalcFormat.num(-a0 / b, 12)
                }
                2 -> {
                    val A = c[2]
                    val B = c[1]
                    val C = c[0]
                    val D = B * B - 4 * A * C
                    val lines = mutableListOf<String>()
                    lines.add("二次方程")
                    lines.add("a = ${CalcFormat.num(A)}   b = ${CalcFormat.num(B)}   c = ${CalcFormat.num(C)}")
                    lines.add("Δ = b² − 4ac = ${CalcFormat.num(D)}")
                    if (abs(D) < 1e-12) {
                        lines.add("Δ = 0，两个相等实根")
                        lines.add("$v = " + CalcFormat.num(-B / (2 * A), 12))
                    } else if (D > 0) {
                        val s = sqrt(D)
                        lines.add("Δ > 0，两个不等实根")
                        lines.add("$v₁ = " + CalcFormat.num((-B + s) / (2 * A), 12))
                        lines.add("$v₂ = " + CalcFormat.num((-B - s) / (2 * A), 12))
                    } else {
                        val re = -B / (2 * A)
                        val im = sqrt(-D) / (2 * A)
                        lines.add("Δ < 0，无实数根（共轭复根）")
                        lines.add("$v = ${CalcFormat.num(re)} ± ${CalcFormat.num(abs(im))} i")
                    }
                    output = lines.joinToString("\n")
                }
                else -> {
                    output = "次数为 ${c.size - 1}，暂只支持一次/二次方程的解析解\n" +
                        "系数（低次→高次）：" + c.joinToString(", ") { CalcFormat.num(it) }
                    isErr = true
                }
            }
        } catch (e: Throwable) {
            output = "错误：" + (e.message ?: e.javaClass.simpleName)
            isErr = true
        }
    }

    CalcPage {
        CalcLabel("方程（支持 2x+3=7、x^2-5x+6=0）")
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        CalcLabel("未知数")
        OutlinedTextField(
            value = unknown,
            onValueChange = { unknown = it },
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        CalcButton("求解") { solve() }
        Spacer(Modifier.height(10.dp))
        CalcOutput(output, isErr)
    }
}

/* ============================================================
 * 5. 矩阵计算
 * ============================================================ */

@Composable
fun MatrixCalc() {
    var ta by remember { mutableStateOf("1 2\n3 4") }
    var tb by remember { mutableStateOf("5 6\n7 8") }
    var output by remember { mutableStateOf("选择运算") }
    var isErr by remember { mutableStateOf(false) }

    fun parseMat(text: String): List<List<Double>> {
        val rows = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) throw IllegalArgumentException("矩阵为空")
        val m = rows.map { line ->
            line.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map { tok ->
                tok.toDoubleOrNull() ?: throw IllegalArgumentException("无法识别：$tok")
            }
        }
        val cols = m[0].size
        if (m.any { it.size != cols }) throw IllegalArgumentException("每行列数必须一致")
        return m
    }

    fun matText(m: List<List<Double>>): String =
        m.joinToString("\n") { row -> row.joinToString("\t") { CalcFormat.num(it) } }

    fun run(action: () -> Pair<String, Boolean>) {
        val (t, e) = try { action() } catch (ex: Throwable) {
            "错误：" + (ex.message ?: ex.javaClass.simpleName) to true
        }
        output = t
        isErr = e
    }

    CalcPage {
        CalcLabel("矩阵 A（每行一行，空格或逗号分隔）")
        OutlinedTextField(value = ta, onValueChange = { ta = it }, modifier = Modifier.fillMaxWidth().height(88.dp),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
        Spacer(Modifier.height(8.dp))
        CalcLabel("矩阵 B")
        OutlinedTextField(value = tb, onValueChange = { tb = it }, modifier = Modifier.fillMaxWidth().height(88.dp),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalcButton("A+B", primary = false) {
                run {
                    val A = parseMat(ta); val B = parseMat(tb)
                    if (A.size != B.size || A[0].size != B[0].size) throw IllegalArgumentException("尺寸不同")
                    "A + B =\n" + matText(A.indices.map { i -> A[i].indices.map { j -> A[i][j] + B[i][j] } }) to false
                }
            }
            CalcButton("A−B", primary = false) {
                run {
                    val A = parseMat(ta); val B = parseMat(tb)
                    if (A.size != B.size || A[0].size != B[0].size) throw IllegalArgumentException("尺寸不同")
                    "A − B =\n" + matText(A.indices.map { i -> A[i].indices.map { j -> A[i][j] - B[i][j] } }) to false
                }
            }
            CalcButton("A×B", primary = false) {
                run {
                    val A = parseMat(ta); val B = parseMat(tb)
                    if (A[0].size != B.size) throw IllegalArgumentException("A 列数 ≠ B 行数")
                    val R = A.indices.map { i ->
                        B[0].indices.map { j ->
                            (0 until B.size).sumOf { k -> A[i][k] * B[k][j] }
                        }
                    }
                    "A × B =\n" + matText(R) to false
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalcButton("行列式", primary = false) {
                run {
                    val A = parseMat(ta)
                    if (A.size != A[0].size) throw IllegalArgumentException("行列式只对方阵有效")
                    fun det(m: List<List<Double>>): Double {
                        val n = m.size
                        if (n == 1) return m[0][0]
                        if (n == 2) return m[0][0] * m[1][1] - m[0][1] * m[1][0]
                        var s = 0.0
                        for (c in 0 until n) {
                            val sub = (1 until n).map { r ->
                                (0 until n).filter { it != c }.map { m[r][it] }
                            }
                            s += (if (c % 2 == 0) 1 else -1) * m[0][c] * det(sub)
                        }
                        return s
                    }
                    "det(A) = " + CalcFormat.num(det(A), 12) to false
                }
            }
            CalcButton("转置", primary = false) {
                run {
                    val A = parseMat(ta)
                    val R = A[0].indices.map { j -> A.indices.map { i -> A[i][j] } }
                    "Aᵀ =\n" + matText(R) to false
                }
            }
            CalcButton("逆矩阵", primary = false) {
                run {
                    val A = parseMat(ta)
                    val n = A.size
                    if (n != A[0].size) throw IllegalArgumentException("求逆只对方阵有效")
                    val M = A.mapIndexed { i, row ->
                        row.toMutableList().apply {
                            for (j in 0 until n) add(if (i == j) 1.0 else 0.0)
                        }
                    }
                    for (col in 0 until n) {
                        var piv = -1
                        var best = 0.0
                        for (r in col until n) if (abs(M[r][col]) > best) { best = abs(M[r][col]); piv = r }
                        if (piv < 0 || best < 1e-12) throw IllegalArgumentException("矩阵不可逆")
                        val tmp = M[col]; M[col] = M[piv]; M[piv] = tmp
                        val pv = M[col][col]
                        for (j in 0 until 2 * n) M[col][j] /= pv
                        for (r in 0 until n) {
                            if (r == col) continue
                            val f = M[r][col]
                            if (abs(f) < 1e-15) continue
                            for (j in 0 until 2 * n) M[r][j] -= f * M[col][j]
                        }
                    }
                    val inv = M.map { it.subList(n, 2 * n).toList() }
                    "A⁻¹ =\n" + matText(inv) to false
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        CalcOutput(output, isErr)
    }
}

/* ============================================================
 * 6. 统计
 * ============================================================ */

@Composable
fun StatCalc() {
    var input by remember { mutableStateOf("12, 15, 18, 20, 22, 25, 30") }
    var output by remember { mutableStateOf("") }
    var isErr by remember { mutableStateOf(false) }

    fun calc() {
        isErr = false
        try {
            val nums = input.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map {
                it.toDoubleOrNull() ?: throw IllegalArgumentException("无法识别：$it")
            }
            if (nums.isEmpty()) throw IllegalArgumentException("数据为空")

            val n = nums.size
            val sum = nums.sum()
            val mean = sum / n
            val sorted = nums.sorted()
            val median = if (n % 2 == 1) sorted[(n - 1) / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2

            val freq = nums.groupingBy { it }.eachCount()
            val maxF = freq.values.maxOrNull() ?: 0
            val modes = freq.filterValues { it == maxF }.keys

            val varSum = nums.sumOf { (it - mean) * (it - mean) }
            val varP = varSum / n
            val varS = if (n > 1) varSum / (n - 1) else 0.0

            val lines = mutableListOf<String>()
            lines.add("个数 n = $n")
            lines.add("总和 Σ = " + CalcFormat.num(sum, 12))
            lines.add("平均值 x̄ = " + CalcFormat.num(mean, 12))
            lines.add("中位数 = " + CalcFormat.num(median, 12))
            lines.add("众数 = " + if (maxF > 1) modes.joinToString(", ") { CalcFormat.num(it) } else "无重复值")
            lines.add("最小 = ${CalcFormat.num(sorted.first(), 12)}   最大 = ${CalcFormat.num(sorted.last(), 12)}")
            lines.add("极差 = " + CalcFormat.num(sorted.last() - sorted.first(), 12))
            lines.add("总体方差 σ² = " + CalcFormat.num(varP, 10))
            lines.add("总体标准差 σ = " + CalcFormat.num(sqrt(varP), 10))
            if (n > 1) {
                lines.add("样本方差 s² = " + CalcFormat.num(varS, 10))
                lines.add("样本标准差 s = " + CalcFormat.num(sqrt(varS), 10))
            }
            output = lines.joinToString("\n")
        } catch (e: Throwable) {
            output = "错误：" + (e.message ?: e.javaClass.simpleName)
            isErr = true
        }
    }

    CalcPage {
        CalcLabel("数据（空格、逗号或换行分隔）")
        OutlinedTextField(value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth().height(96.dp),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
        Spacer(Modifier.height(12.dp))
        CalcButton("计算") { calc() }
        Spacer(Modifier.height(10.dp))
        CalcOutput(output, isErr)
    }
}

/* ============================================================
 * 7. 进制转换
 * ============================================================ */

@Composable
fun BaseCalc() {
    var input by remember { mutableStateOf("255") }
    var base by remember { mutableStateOf(10) }
    var output by remember { mutableStateOf("") }
    var isErr by remember { mutableStateOf(false) }

    fun convert() {
        isErr = false
        try {
            val s = input.trim().removePrefix("0b").removePrefix("0o").removePrefix("0x")
            if (s.isEmpty()) throw IllegalArgumentException("请输入数值")
            val digits = "0123456789abcdef".take(base)
            s.forEach { ch ->
                if (digits.indexOf(ch.lowercaseChar()) < 0) {
                    throw IllegalArgumentException("$base 进制不允许字符 '$ch'")
                }
            }
            val v = s.toLongOrNull(base) ?: throw IllegalArgumentException("无法解析")

            val lines = mutableListOf<String>()
            lines.add("十进制    $v")
            lines.add("二进制    " + v.toString(2))
            lines.add("八进制    " + v.toString(8))
            lines.add("十六进制  " + v.toString(16).uppercase())
            lines.add("")
            lines.add("32 位二进制")
            val b32 = String.format("%32s", v.toInt().toUInt().toString(2)).replace(' ', '0')
            lines.add(b32.chunked(8).joinToString(" "))
            output = lines.joinToString("\n")
        } catch (e: Throwable) {
            output = "错误：" + (e.message ?: e.javaClass.simpleName)
            isErr = true
        }
    }

    CalcPage {
        CalcLabel("输入值")
        OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        CalcLabel("输入进制")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2 to "二", 8 to "八", 10 to "十", 16 to "十六").forEach { (b, name) ->
                CalcButton("${name}进制", primary = base == b) { base = b; convert() }
            }
        }
        Spacer(Modifier.height(10.dp))
        CalcButton("转换") { convert() }
        Spacer(Modifier.height(10.dp))
        CalcOutput(output, isErr)
    }
}

/* ============================================================
 * 8. 单位换算
 * ============================================================ */

/** 单位换算数据：类别 -> (单位名 -> 换到基准单位的系数) */
private val UNIT_CATS: Map<String, Map<String, Double>> = linkedMapOf(
    "长度" to linkedMapOf(
        "毫米 mm" to 0.001, "厘米 cm" to 0.01, "米 m" to 1.0,
        "千米 km" to 1000.0, "英寸 in" to 0.0254, "英尺 ft" to 0.3048,
        "英里 mi" to 1609.344, "海里 nmi" to 1852.0,
    ),
    "面积" to linkedMapOf(
        "平方厘米 cm²" to 0.0001, "平方米 m²" to 1.0, "平方千米 km²" to 1e6,
        "公顷 ha" to 10000.0, "亩" to 666.6667, "平方英尺 ft²" to 0.092903,
    ),
    "体积" to linkedMapOf(
        "毫升 mL" to 0.001, "升 L" to 1.0, "立方米 m³" to 1000.0,
        "加仑 gal" to 3.78541, "立方英尺 ft³" to 28.3168,
    ),
    "质量" to linkedMapOf(
        "毫克 mg" to 0.001, "克 g" to 1.0, "千克 kg" to 1000.0,
        "吨 t" to 1e6, "斤" to 500.0, "磅 lb" to 453.592, "盎司 oz" to 28.3495,
    ),
    "速度" to linkedMapOf(
        "米/秒 m/s" to 1.0, "千米/时 km/h" to 0.277778,
        "英里/时 mph" to 0.44704, "节 kn" to 0.514444,
    ),
    "数据" to linkedMapOf(
        "字节 B" to 1.0, "千字节 KB" to 1024.0, "兆字节 MB" to 1048576.0,
        "吉字节 GB" to 1073741824.0, "太字节 TB" to 1099511627776.0,
    ),
)

@Composable
fun UnitCalc() {
    val pal = LocalPalette.current
    val catNames = UNIT_CATS.keys.toList()

    var cat by remember { mutableStateOf(catNames.first()) }
    var units by remember { mutableStateOf(UNIT_CATS[cat]!!.keys.toList()) }
    var from by remember { mutableStateOf(units.first()) }
    var to by remember { mutableStateOf(units.getOrElse(1) { units.first() }) }
    var value by remember { mutableStateOf("1") }
    var output by remember { mutableStateOf("") }
    var isErr by remember { mutableStateOf(false) }

    fun convert() {
        isErr = false
        try {
            val v = value.trim().toDoubleOrNull() ?: throw IllegalArgumentException("数值无效")
            val m = UNIT_CATS[cat] ?: throw IllegalArgumentException("类别不存在")
            val f1 = m[from] ?: throw IllegalArgumentException("源单位不存在")
            val f2 = m[to] ?: throw IllegalArgumentException("目标单位不存在")
            val r = v * f1 / f2
            output = CalcFormat.num(v) + " " + from + "\n= " + CalcFormat.num(r, 12) + " " + to
        } catch (e: Throwable) {
            output = "错误：" + (e.message ?: e.javaClass.simpleName)
            isErr = true
        }
    }

    CalcPage {
        CalcLabel("类别")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            catNames.take(3).forEach { c ->
                CalcButton(c, primary = cat == c) {
                    cat = c
                    units = UNIT_CATS[c]!!.keys.toList()
                    from = units.first()
                    to = units.getOrElse(1) { units.first() }
                    output = ""
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            catNames.drop(3).forEach { c ->
                CalcButton(c, primary = cat == c) {
                    cat = c
                    units = UNIT_CATS[c]!!.keys.toList()
                    from = units.first()
                    to = units.getOrElse(1) { units.first() }
                    output = ""
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        CalcLabel("数值")
        OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
            modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(10.dp))

        CalcLabel("从（当前：$from）")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            units.take(3).forEach { u ->
                CalcButton(u.substringBefore(' '), primary = from == u) { from = u }
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            units.drop(3).take(3).forEach { u ->
                CalcButton(u.substringBefore(' '), primary = from == u) { from = u }
            }
        }

        Spacer(Modifier.height(10.dp))

        CalcLabel("到（当前：$to）")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            units.take(3).forEach { u ->
                CalcButton(u.substringBefore(' '), primary = to == u) { to = u }
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            units.drop(3).take(3).forEach { u ->
                CalcButton(u.substringBefore(' '), primary = to == u) { to = u }
            }
        }

        Spacer(Modifier.height(12.dp))
        CalcButton("换算") { convert() }
        Spacer(Modifier.height(10.dp))
        CalcOutput(output, isErr)
    }
}

/* ============================================================
 * 9. 几何计算
 * ============================================================ */

/** 形状 -> 参数名列表 */
private val SHAPES: Map<String, List<String>> = linkedMapOf(
    "圆形" to listOf("半径 r"),
    "矩形" to listOf("长 a", "宽 b"),
    "三角形" to listOf("底 b", "高 h"),
    "梯形" to listOf("上底 a", "下底 b", "高 h"),
    "球体" to listOf("半径 r"),
    "圆柱" to listOf("半径 r", "高 h"),
    "圆锥" to listOf("半径 r", "高 h"),
    "正方体" to listOf("棱长 a"),
)

@Composable
fun GeometryCalc() {
    val shapes = SHAPES.keys.toList()
    var shape by remember { mutableStateOf(shapes.first()) }
    var params by remember {
        mutableStateOf(SHAPES[shape]!!.associateWith { "1" })
    }
    var output by remember { mutableStateOf("") }
    var isErr by remember { mutableStateOf(false) }

    fun p(key: String): Double {
        val v = params.entries.firstOrNull { it.key.startsWith(key) }?.value?.toDoubleOrNull()
        return v ?: throw IllegalArgumentException("请填写有效的 $key")
    }

    fun calc() {
        isErr = false
        try {
            val PI = Math.PI
            val lines = mutableListOf<String>()
            when (shape) {
                "圆形" -> {
                    val r = p("半径")
                    if (r <= 0) throw IllegalArgumentException("半径必须大于 0")
                    lines.add("周长 C = 2πr = " + CalcFormat.num(2 * PI * r, 12))
                    lines.add("面积 S = πr² = " + CalcFormat.num(PI * r * r, 12))
                    lines.add("直径 d = " + CalcFormat.num(2 * r, 12))
                }
                "矩形" -> {
                    val a = p("长"); val b = p("宽")
                    lines.add("面积 S = a×b = " + CalcFormat.num(a * b, 12))
                    lines.add("周长 P = 2(a+b) = " + CalcFormat.num(2 * (a + b), 12))
                    lines.add("对角线 = " + CalcFormat.num(sqrt(a * a + b * b), 12))
                }
                "三角形" -> {
                    val b = p("底"); val h = p("高")
                    lines.add("面积 S = 底×高÷2 = " + CalcFormat.num(b * h / 2, 12))
                }
                "梯形" -> {
                    val a = p("上底"); val b = p("下底"); val h = p("高")
                    lines.add("面积 S = (a+b)×h÷2 = " + CalcFormat.num((a + b) * h / 2, 12))
                }
                "球体" -> {
                    val r = p("半径")
                    lines.add("体积 V = 4/3·πr³ = " + CalcFormat.num(4.0 / 3 * PI * r * r * r, 12))
                    lines.add("表面积 S = 4πr² = " + CalcFormat.num(4 * PI * r * r, 12))
                }
                "圆柱" -> {
                    val r = p("半径"); val h = p("高")
                    lines.add("体积 V = πr²h = " + CalcFormat.num(PI * r * r * h, 12))
                    lines.add("侧面积 = 2πrh = " + CalcFormat.num(2 * PI * r * h, 12))
                    lines.add("表面积 = 2πr²+2πrh = " + CalcFormat.num(2 * PI * r * r + 2 * PI * r * h, 12))
                }
                "圆锥" -> {
                    val r = p("半径"); val h = p("高")
                    val l = sqrt(r * r + h * h)
                    lines.add("母线 l = √(r²+h²) = " + CalcFormat.num(l, 12))
                    lines.add("体积 V = 1/3·πr²h = " + CalcFormat.num(PI * r * r * h / 3, 12))
                    lines.add("侧面积 = πrl = " + CalcFormat.num(PI * r * l, 12))
                    lines.add("表面积 = πr²+πrl = " + CalcFormat.num(PI * r * r + PI * r * l, 12))
                }
                "正方体" -> {
                    val a = p("棱长")
                    lines.add("体积 V = a³ = " + CalcFormat.num(a * a * a, 12))
                    lines.add("表面积 S = 6a² = " + CalcFormat.num(6 * a * a, 12))
                    lines.add("对角线 = √3·a = " + CalcFormat.num(sqrt(3.0) * a, 12))
                }
            }
            output = lines.joinToString("\n")
        } catch (e: Throwable) {
            output = "错误：" + (e.message ?: e.javaClass.simpleName)
            isErr = true
        }
    }

    CalcPage {
        CalcLabel("形状")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            shapes.take(4).forEach { s ->
                CalcButton(s, primary = shape == s) {
                    shape = s
                    params = SHAPES[s]!!.associateWith { "1" }
                    output = ""
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            shapes.drop(4).forEach { s ->
                CalcButton(s, primary = shape == s) {
                    shape = s
                    params = SHAPES[s]!!.associateWith { "1" }
                    output = ""
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        SHAPES[shape]!!.forEach { key ->
            CalcLabel(key)
            OutlinedTextField(
                value = params[key] ?: "1",
                onValueChange = { params = params + (key to it) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(6.dp))
        CalcButton("计算") { calc() }
        Spacer(Modifier.height(10.dp))
        CalcOutput(output, isErr)
    }
}
