package sy.great.toolbox.ui.calculator

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sy.great.toolbox.tools.CalcEngine
import sy.great.toolbox.tools.CalcFormat

/**
 * 普通计算器：四则运算、百分比、正负号。
 */
@Composable
fun BasicCalc() {
    var expr by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("0") }
    var err by remember { mutableStateOf(false) }

    fun calc() {
        if (expr.isBlank()) return
        try {
            val v = CalcEngine.calc(expr)
            result = CalcFormat.num(v)
            err = !v.isFinite()
            if (err) result = "无法计算"
        } catch (e: Throwable) {
            result = e.message ?: "表达式错误"
            err = true
        }
    }

    fun add(s: String) {
        expr += s
        err = false
    }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        CalcDisplay(expr = expr, result = result, isError = err)

        Spacer(Modifier.height(12.dp))

        KeyGrid(
            rows = listOf(
                listOf(
                    Triple("AC", KeyKind.DEL) {
                        expr = ""; result = "0"; err = false
                    },
                    Triple("⌫", KeyKind.FN) {
                        if (expr.isNotEmpty()) expr = expr.dropLast(1)
                    },
                    Triple("%", KeyKind.OP) { add("/100") },
                    Triple("÷", KeyKind.OP) { add("/") },
                ),
                listOf(
                    Triple("7", KeyKind.NUM) { add("7") },
                    Triple("8", KeyKind.NUM) { add("8") },
                    Triple("9", KeyKind.NUM) { add("9") },
                    Triple("×", KeyKind.OP) { add("*") },
                ),
                listOf(
                    Triple("4", KeyKind.NUM) { add("4") },
                    Triple("5", KeyKind.NUM) { add("5") },
                    Triple("6", KeyKind.NUM) { add("6") },
                    Triple("−", KeyKind.OP) { add("-") },
                ),
                listOf(
                    Triple("1", KeyKind.NUM) { add("1") },
                    Triple("2", KeyKind.NUM) { add("2") },
                    Triple("3", KeyKind.NUM) { add("3") },
                    Triple("+", KeyKind.OP) { add("+") },
                ),
                listOf(
                    Triple("±", KeyKind.FN) {
                        // 在当前末尾数字前切换正负
                        if (expr.isEmpty()) return@Triple
                        val m = Regex("(-?\\d*\\.?\\d+)$").find(expr)
                        if (m == null) {
                            expr += "-"
                        } else {
                            val num = m.value
                            val head = expr.dropLast(num.length)
                            expr = head + if (num.startsWith("-")) num.drop(1) else "-$num"
                        }
                    },
                    Triple("0", KeyKind.NUM) { add("0") },
                    Triple(".", KeyKind.NUM) {
                        // 防止一个数内多个小数点
                        val m = Regex("\\d*\\.?\\d*$").find(expr)
                        if (m != null && m.value.contains('.')) return@Triple
                        expr += if (expr.isEmpty()) "0." else "."
                    },
                    Triple("=", KeyKind.EQ) { calc() },
                ),
            )
        )
    }
}

/**
 * 科学计算器：三角函数、对数、幂、阶乘、括号。
 */
@Composable
fun SciCalc() {
    var expr by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("0") }
    var err by remember { mutableStateOf(false) }

    fun calc() {
        if (expr.isBlank()) return
        try {
            val v = CalcEngine.calc(expr)
            if (v.isFinite()) {
                result = CalcFormat.num(v)
                err = false
            } else {
                result = "无法计算"
                err = true
            }
        } catch (e: Throwable) {
            result = e.message ?: "表达式错误"
            err = true
        }
    }

    fun add(s: String) {
        expr += s
        err = false
    }

    fun fn(name: String) = add("$name(")

    Column(Modifier.fillMaxSize().padding(10.dp)) {
        CalcDisplay(expr = expr, result = result, isError = err)

        Spacer(Modifier.height(10.dp))

        KeyGrid(
            rows = listOf(
                listOf(
                    Triple("AC", KeyKind.DEL) {
                        expr = ""; result = "0"; err = false
                    },
                    Triple("⌫", KeyKind.FN) {
                        if (expr.isNotEmpty()) expr = expr.dropLast(1)
                    },
                    Triple("(", KeyKind.FN) { add("(") },
                    Triple(")", KeyKind.FN) { add(")") },
                    Triple("÷", KeyKind.OP) { add("/") },
                ),
                listOf(
                    Triple("sin", KeyKind.FN) { fn("sin") },
                    Triple("cos", KeyKind.FN) { fn("cos") },
                    Triple("tan", KeyKind.FN) { fn("tan") },
                    Triple("^", KeyKind.OP) { add("^") },
                    Triple("×", KeyKind.OP) { add("*") },
                ),
                listOf(
                    Triple("ln", KeyKind.FN) { fn("ln") },
                    Triple("log", KeyKind.FN) { fn("log") },
                    Triple("√", KeyKind.FN) { fn("sqrt") },
                    Triple("n!", KeyKind.FN) { add("!") },
                    Triple("−", KeyKind.OP) { add("-") },
                ),
                listOf(
                    Triple("π", KeyKind.FN) { add("pi") },
                    Triple("e", KeyKind.FN) { add("e") },
                    Triple("φ", KeyKind.FN) { add("phi") },
                    Triple("1/x", KeyKind.FN) { add("1/(") },
                    Triple("+", KeyKind.OP) { add("+") },
                ),
                listOf(
                    Triple("7", KeyKind.NUM) { add("7") },
                    Triple("8", KeyKind.NUM) { add("8") },
                    Triple("9", KeyKind.NUM) { add("9") },
                    Triple("asin", KeyKind.FN) { fn("asin") },
                    Triple("x²", KeyKind.FN) { add("^2") },
                ),
                listOf(
                    Triple("4", KeyKind.NUM) { add("4") },
                    Triple("5", KeyKind.NUM) { add("5") },
                    Triple("6", KeyKind.NUM) { add("6") },
                    Triple("acos", KeyKind.FN) { fn("acos") },
                    Triple("eˣ", KeyKind.FN) { fn("exp") },
                ),
                listOf(
                    Triple("1", KeyKind.NUM) { add("1") },
                    Triple("2", KeyKind.NUM) { add("2") },
                    Triple("3", KeyKind.NUM) { add("3") },
                    Triple("atan", KeyKind.FN) { fn("atan") },
                    Triple("x³", KeyKind.FN) { add("^3") },
                ),
                // 额外函数行（双曲 / 取整 / 开三次方）
                listOf(
                    Triple("∛", KeyKind.FN) { fn("cbrt") },
                    Triple("sinh", KeyKind.FN) { fn("sinh") },
                    Triple("cosh", KeyKind.FN) { fn("cosh") },
                    Triple("tanh", KeyKind.FN) { fn("tanh") },
                    Triple("|x|", KeyKind.FN) { add("abs(") },
                ),
                listOf(
                    Triple("floor", KeyKind.FN) { fn("floor") },
                    Triple("ceil", KeyKind.FN) { fn("ceil") },
                    Triple("round", KeyKind.FN) { fn("round") },
                    Triple("log₂", KeyKind.FN) { fn("log2") },
                    Triple("10ˣ", KeyKind.FN) { fn("pow10") },
                ),
                listOf(
                    Triple("±", KeyKind.FN) {
                        if (expr.isEmpty()) return@Triple
                        val m = Regex("(-?\\d*\\.?\\d+)$").find(expr)
                        if (m == null) {
                            expr += "-"
                        } else {
                            val num = m.value
                            val head = expr.dropLast(num.length)
                            expr = head + if (num.startsWith("-")) num.drop(1) else "-$num"
                        }
                    },
                    Triple("0", KeyKind.NUM) { add("0") },
                    Triple(".", KeyKind.NUM) {
                        val m = Regex("\\d*\\.?\\d*$").find(expr)
                        if (m != null && m.value.contains('.')) return@Triple
                        expr += if (expr.isEmpty()) "0." else "."
                    },
                    Triple("E", KeyKind.FN) { add("*10^") },
                    Triple("=", KeyKind.EQ) { calc() },
                ),
            )
        )
    }
}
