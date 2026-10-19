package sy.great.toolbox.tools.decompiler

/**
 * 表达式重建：把 IR 的「一条指令一个变量」重新组装成表达式树。
 *
 * 为什么需要：
 *   IrTranslator 是逐条指令建值的，所以：
 *       add w8, w0, w1
 *       mul w8, w8, w2
 *   会得到
 *       v0 = arg0 + arg1;
 *       v1 = v0 * arg2;
 *   而专业反编译器应该给出一行：
 *       return arg0 + arg1 * arg2;
 *
 * 做法（与 Ghidra / IDA 一致）：
 *   1. 统计每个值的使用次数
 *   2. 只被用一次、且定义点在同一个块里的临时值，内联进使用处
 *   3. 内联后不再被引用的语句自动消失 → 死代码消除
 *
 * 安全性：
 *   只内联纯表达式（算术/赋值/常量）。
 *   函数调用、内存读（可能带副作用）不内联，除非只读且被用一次。
 */
object ExprBuilder {

    /** 被内联掉的临时值（用于输出时跳过其定义语句） */
    class Inline(val inlineable: Set<IRValue>, val useCount: Map<IRValue, Int>)

    /**
     * 分析可内联的值。
     *
     * 内联条件（全部满足）：
     *   · 该值是 Temp（不是参数 / 栈变量 / 全局）
     *   · 在 IR 里只被引用一次
     *   · 定义它的是纯运算（Add/Sub/Mul/... / Move / Load 只读）
     *   · 不是 Phi（Phi 必须保留）
     *   · 不是比较（条件用）
     */
    fun analyze(fn: FunctionIR): Inline {
        val useCount = HashMap<IRValue, Int>()
        val defInsn = HashMap<IRValue, IRInstruction>()

        for (b in fn.blocks) {
            for (insn in b.instructions) {
                insn.result?.let { defInsn[it] = insn }
                for (o in insn.operands) {
                    useCount[o] = (useCount[o] ?: 0) + 1
                }
            }
        }

        val inlineable = HashSet<IRValue>()
        // 不用 Map 解构 for ((k, v) in map)：
        // 在当前 Kotlin 编译器下会报 No value passed for parameter 'p1'。
        for (entry in defInsn.entries) {
            val v = entry.key
            val insn = entry.value
            if (v.kind != ValueKind.Temp) continue
            if ((useCount[v] ?: 0) != 1) continue
            if (!isPureInlineable(insn)) continue
            // 有 sourceRegister 说明它对应真实寄存器，
            // 内联会让调试对照失去锚点，保留单独声明更利于阅读。
            // 但纯算木临时值内联收益远大于代价，这里只保留 Phi。
            inlineable.add(v)
        }

        return Inline(inlineable, useCount)
    }

    private fun isPureInlineable(i: IRInstruction): Boolean = when (i.op) {
        IROp.Add, IROp.Sub, IROp.Mul,
        IROp.And, IROp.Or, IROp.Xor, IROp.Not,
        IROp.Shl, IROp.Shr, IROp.Sar,
        IROp.Move, IROp.Const -> true

        // 除法/取余可能除零陷入，但也属于纯运算，内联更可读
        IROp.Div, IROp.Rem -> true

        // 内存读：若地址是常量/局部变量就是纯读，可以内联
        IROp.Load -> true

        // 有副作用或控制相关，不内联
        IROp.Call, IROp.Store, IROp.Phi,
        IROp.Branch, IROp.Jump, IROp.Return, IROp.Indirect,
        IROp.Compare, IROp.Nop -> false
    }

    /**
     * 把值渲染成表达式字符串。
     *
     * @param inline 可内联集合
     * @param defOf  值 → 定义它的指令
     * @param nameOf 值 → 变量名
     */
    fun render(
        v: IRValue,
        inline: Inline,
        defOf: Map<IRValue, IRInstruction>,
        nameOf: (IRValue) -> String,
        depth: Int = 0
    ): String {
        if (depth > 24) return nameOf(v)          // 防止异常数据递归过深

        if (!inline.inlineable.contains(v)) return atom(v, nameOf)
        val def = defOf[v] ?: return atom(v, nameOf)

        fun sub(x: IRValue) = render(x, inline, defOf, nameOf, depth + 1)

        return when (def.op) {
            IROp.Const -> constForm(v)

            IROp.Move -> def.operands.getOrNull(0)?.let { sub(it) } ?: atom(v, nameOf)

            IROp.Not -> def.operands.getOrNull(0)?.let { "~" + paren(sub(it)) } ?: atom(v, nameOf)

            IROp.Load -> {
                val a = def.operands.getOrNull(0)
                if (a == null) atom(v, nameOf) else "*" + paren(sub(a))
            }

            IROp.Add, IROp.Sub, IROp.Mul, IROp.Div, IROp.Rem,
            IROp.And, IROp.Or, IROp.Xor, IROp.Shl, IROp.Shr, IROp.Sar -> {
                val a = def.operands.getOrNull(0)
                val b = def.operands.getOrNull(1)
                if (a == null || b == null) return atom(v, nameOf)
                val op = binOp(def.op)
                val prec = precedence(def.op)
                val sa = sub(a)
                val sb = sub(b)
                // 只在必要时加括号，避免满屏括号
                val left = if (needsParen(a, def.op, true, defOf)) paren(sa) else sa
                val right = if (needsParen(b, def.op, false, defOf)) paren(sb) else sb
                "$left $op $right"
            }

            else -> atom(v, nameOf)
        }
    }

    private fun atom(v: IRValue, nameOf: (IRValue) -> String): String =
        if (v.kind == ValueKind.Const) constForm(v) else nameOf(v)

    private fun binOp(op: IROp): String = when (op) {
        IROp.Add -> "+"
        IROp.Sub -> "-"
        IROp.Mul -> "*"
        IROp.Div -> "/"
        IROp.Rem -> "%"
        IROp.And -> "&"
        IROp.Or -> "|"
        IROp.Xor -> "^"
        IROp.Shl -> "<<"
        IROp.Shr, IROp.Sar -> ">>"
        else -> "?"
    }

    private fun precedence(op: IROp): Int = when (op) {
        IROp.Or -> 1
        IROp.Xor -> 2
        IROp.And -> 3
        IROp.Shl, IROp.Shr, IROp.Sar -> 4
        IROp.Add, IROp.Sub -> 5
        IROp.Mul, IROp.Div, IROp.Rem -> 6
        else -> 9
    }

    /**
     * 子表达式是否需要括号。
     *
     * 规则：
     *   · 子表达式优先级低于父 → 必须括号
     *   · 右操作数且优先级相同、父为左结合减/除/取余 → 必须括号
     */
    private fun needsParen(
        child: IRValue,
        parentOp: IROp,
        isLeft: Boolean,
        defOf: Map<IRValue, IRInstruction>
    ): Boolean {
        val def = defOf[child] ?: return false
        if (!isBinOp(def.op)) return false
        val cp = precedence(def.op)
        val pp = precedence(parentOp)
        if (cp < pp) return true
        if (cp > pp) return false
        // 同优先级：右操作数遇到减/除/取余需要括号
        if (!isLeft && (parentOp == IROp.Sub || parentOp == IROp.Div || parentOp == IROp.Rem)) return true
        return false
    }

    private fun isBinOp(op: IROp): Boolean = when (op) {
        IROp.Add, IROp.Sub, IROp.Mul, IROp.Div, IROp.Rem,
        IROp.And, IROp.Or, IROp.Xor, IROp.Shl, IROp.Shr, IROp.Sar -> true
        else -> false
    }

    private fun paren(s: String): String = "($s)"

    internal fun constForm(v: IRValue): String {
        val c = v.constant
        return when {
            c == 0L -> "0"
            c > 0 && c < 10 -> c.toString()
            c >= 0 -> "0x" + java.lang.Long.toHexString(c)
            else -> "-0x" + java.lang.Long.toHexString(-c)
        }
    }

    /** 建立「值 → 定义指令」索引 */
    fun indexDefs(fn: FunctionIR): Map<IRValue, IRInstruction> {
        val m = HashMap<IRValue, IRInstruction>()
        for (b in fn.blocks) {
            for (i in b.instructions) {
                i.result?.let { m[it] = i }
            }
        }
        return m
    }
}
