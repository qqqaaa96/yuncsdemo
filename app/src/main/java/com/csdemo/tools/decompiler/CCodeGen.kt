package com.csdemo.tools.decompiler

/**
 * 伪 C 生成器。
 *
 * 输入：函数级 IR（带 CFG）
 * 输出：类 C 文本
 *
 * 关键做法：
 *   · 先做支配关系计算（用于识别循环头、合流点）
 *   · 再按 CFG 拓扑还原 if / else / while
 *   · 表达式直接由 IR 值构建，不是字符串拼接汇编
 */
object CCodeGen {

    /** 生成单个函数的伪 C */
    fun generate(fn: FunctionIR): String {
        val sb = StringBuilder()

        // ---- 变量命名 ----
        val namer = Namer()
        namer.assign(fn)

        // ---- 支配关系 ----
        val dom = Dominator.build(fn)

        // ---- 函数签名 ----
        sb.append("/* ── ").append(fn.name)
            .append(" @ 0x").append(java.lang.Long.toHexString(fn.address))
            .append(" ── ").append(fn.blocks.size).append(" blocks */\n")

        val retType = typeName(fn.returnValue?.type ?: ValueType.Int64)
        sb.append(retType).append(' ').append(sanitize(fn.name)).append('(')
        if (fn.args.isEmpty()) {
            sb.append("void")
        } else {
            sb.append(
                fn.args.joinToString(", ") { a ->
                    typeName(a.type) + " " + namer.nameOf(a)
                }
            )
        }
        sb.append(")\n{\n")

        // ---- 局部变量声明 ----
        if (fn.locals.isNotEmpty() || needsTemps(fn)) {
            val emitted = HashSet<String>()
            for (lv in fn.locals) {
                val n = namer.nameOf(lv)
                if (emitted.add(n)) {
                    sb.append("    ").append(typeName(lv.type)).append(' ')
                        .append(n).append(";\n")
                }
            }
            sb.append('\n')
        }

        // ---- 结构还原 ----
        val ctx = GenCtx(sb, namer, dom, fn)
        ctx.emitFunction()

        sb.append("}\n")
        return sb.toString()
    }

    private fun needsTemps(fn: FunctionIR): Boolean {
        for (b in fn.blocks) {
            for (i in b.instructions) {
                val r = i.result ?: continue
                if (r.kind == ValueKind.Temp) return true
            }
        }
        return false
    }

    // ============================================================
    // 变量命名
    // ============================================================

    class Namer {
        private val names = HashMap<IRValue, String>()

        fun assign(fn: FunctionIR) {
            var argIdx = 0
            for (a in fn.args) {
                names[a] = "arg$argIdx"
                argIdx++
            }
            for (l in fn.locals) {
                val off = l.stackOffset
                names[l] = "local_" + java.lang.Long.toHexString(if (off < 0) -off else off).lowercase()
            }
            // 临时值按 id 编号
            var t = 0
            for (b in fn.blocks) {
                for (i in b.instructions) {
                    val r = i.result ?: continue
                    if (!names.containsKey(r)) {
                        names[r] = when (r.kind) {
                            ValueKind.Global -> "data_" + java.lang.Long.toHexString(r.constant).lowercase()
                            ValueKind.Const -> constForm(r)
                            else -> "v" + (t++)
                        }
                    }
                }
                for (i in b.instructions) {
                    for (o in i.operands) {
                        if (!names.containsKey(o)) {
                            names[o] = when (o.kind) {
                                ValueKind.Global -> "data_" + java.lang.Long.toHexString(o.constant).lowercase()
                                ValueKind.Const -> constForm(o)
                                ValueKind.Arg -> "arg_unknown"
                                ValueKind.Local -> "local_" + java.lang.Long.toHexString(if (o.stackOffset < 0) -o.stackOffset else o.stackOffset).lowercase()
                                else -> "u" + (t++)
                            }
                        }
                    }
                }
            }
        }

        fun nameOf(v: IRValue): String = names[v] ?: ("v" + v.id)
    }

    private fun constForm(v: IRValue): String {
        val c = v.constant
        return when {
            c >= 0 -> "0x" + java.lang.Long.toHexString(c)
            else -> "-0x" + java.lang.Long.toHexString(-c)
        }
    }

    // ============================================================
    // 生成上下文
    // ============================================================

    private class GenCtx(
        val sb: StringBuilder,
        val namer: Namer,
        val dom: Map<BasicBlock, Set<BasicBlock>>,
        val fn: FunctionIR
    ) {
        /** 已输出过的块 */
        private val emitted = HashSet<BasicBlock>()

        /** 循环头（需要 while 包裹） */
        private val loopHeaders = HashSet<BasicBlock>()

        /** 需要输出 goto 标签的块 */
        private val needLabel = HashSet<BasicBlock>()

        fun emitFunction() {
            val entry = fn.entry ?: return

            detectLoops()
            detectLabels()

            emitBlock(entry, 1)

            // 未到达的块补在后面（通常是被 goto 引用的）
            for (b in fn.blocks) {
                if (!emitted.contains(b)) {
                    sb.append("    ").append(b.label).append(":\n")
                    emitStatements(b, 2)
                }
            }
        }

        /** 识别回边→循环头 */
        private fun detectLoops() {
            for (b in fn.blocks) {
                for (s in b.successors) {
                    // 回边：后继能支配当前块
                    val doms = dom[b] ?: emptySet()
                    if (doms.contains(s)) {
                        loopHeaders.add(s)
                    }
                }
            }
        }

        /** 前驱多于 1 且非循环头的块，可能需要 goto 标签 */
        private fun detectLabels() {
            for (b in fn.blocks) {
                if (b.predecessors.size > 1 && !loopHeaders.contains(b)) {
                    needLabel.add(b)
                }
            }
        }

        // ------------------------------------------------------------

        private fun emitBlock(b: BasicBlock, indent: Int) {
            if (emitted.contains(b)) {
                // 已经输出过 → 用 goto 回到该块
                if (needLabel.contains(b) || loopHeaders.contains(b)) {
                    sb.append(pad(indent)).append("goto ").append(b.label).append(";\n")
                }
                return
            }
            emitted.add(b)

            // 循环头：外面包 while(1)
            if (loopHeaders.contains(b)) {
                sb.append(pad(indent)).append("while (1) {\n")
                emitStatements(b, indent + 1)
                sb.append(pad(indent)).append("}\n")
                return
            }

            if (needLabel.contains(b)) {
                sb.append(pad(indent)).append(b.label).append(":\n")
            }

            emitStatements(b, indent)
        }

        /** 输出块内语句，并处理块尾分支 */
        private fun emitStatements(b: BasicBlock, indent: Int) {
            val insns = b.instructions
            if (insns.isEmpty()) return

            val last = insns.last()
            val bodyInsns = if (last.isTerminator) insns.dropLast(1) else insns

            // 普通指令
            for (i in bodyInsns) {
                val line = exprOf(i)
                if (line.isNotEmpty()) {
                    sb.append(pad(indent)).append(line).append("\n")
                }
            }

            when (last.op) {
                IROp.Return -> emitReturn(last, indent)
                IROp.Jump -> emitJump(b, last, indent)
                IROp.Branch -> emitBranch(b, last, indent)
                IROp.Indirect -> {
                    sb.append(pad(indent)).append("// 间接跳转，目标未知\n")
                }
                else -> {}
            }
        }

        private fun emitReturn(insn: IRInstruction, indent: Int) {
            val v = insn.operands.getOrNull(0)
            if (v == null) {
                sb.append(pad(indent)).append("return;\n")
            } else {
                sb.append(pad(indent)).append("return ")
                    .append(valueOf(v)).append(";\n")
            }
        }

        private fun emitJump(b: BasicBlock, insn: IRInstruction, indent: Int) {
            val t = fn.blockAt(insn.target)
            if (t == null) {
                sb.append(pad(indent)).append("// jump -> 0x")
                    .append(java.lang.Long.toHexString(insn.target)).append("\n")
                return
            }
            emitBlock(t, indent)
        }

        /**
         * 条件分支：
         *   if (cond) { T } else { F }
         * 其中一侧是合流点时省略 else。
         */
        private fun emitBranch(b: BasicBlock, insn: IRInstruction, indent: Int) {
            val succ = b.successors
            if (succ.size < 2) {
                // 只有一个后继：当作无条件处理
                succ.firstOrNull()?.let { emitBlock(it, indent) }
                return
            }

            val takenBlock = fn.blockAt(insn.target)
            val fallBlock = succ.firstOrNull { it !== takenBlock }

            if (takenBlock == null || fallBlock == null) {
                succ.firstOrNull()?.let { emitBlock(it, indent) }
                return
            }

            val cond = conditionText(insn)

            // 哪一侧是合流点（被多个前驱指向，且不是循环头）
            val takenIsJoin = takenBlock.predecessors.size > 1 && !loopHeaders.contains(takenBlock)
            val fallIsJoin = fallBlock.predecessors.size > 1 && !loopHeaders.contains(fallBlock)

            when {
                // 两侧都是合流点：只输出 if，不展开
                takenIsJoin && fallIsJoin -> {
                    sb.append(pad(indent)).append("if (").append(cond).append(") {\n")
                    sb.append(pad(indent + 1)).append("goto ").append(takenBlock.label).append(";\n")
                    sb.append(pad(indent)).append("}\n")
                }
                // 真分支是合流点：if (cond) goto; 然后继续下落
                takenIsJoin -> {
                    sb.append(pad(indent)).append("if (").append(cond).append(") {\n")
                    sb.append(pad(indent + 1)).append("goto ").append(takenBlock.label).append(";\n")
                    sb.append(pad(indent)).append("}\n")
                    emitBlock(fallBlock, indent)
                }
                // 假分支是合流点：if (cond) { ... } 然后回到合流点
                fallIsJoin -> {
                    sb.append(pad(indent)).append("if (").append(cond).append(") {\n")
                    emitBlock(takenBlock, indent + 1)
                    sb.append(pad(indent)).append("}\n")
                    emitBlock(fallBlock, indent)
                }
                // 两侧都不合流：标准 if / else
                else -> {
                    sb.append(pad(indent)).append("if (").append(cond).append(") {\n")
                    emitBlock(takenBlock, indent + 1)
                    sb.append(pad(indent)).append("} else {\n")
                    emitBlock(fallBlock, indent + 1)
                    sb.append(pad(indent)).append("}\n")
                }
            }
        }

        // ------------------------------------------------------------
        // 表达式
        // ------------------------------------------------------------

        private fun valueOf(v: IRValue): String {
            return when (v.kind) {
                ValueKind.Const -> constForm(v)
                else -> namer.nameOf(v)
            }
        }

        private fun conditionText(insn: IRInstruction): String {
            val c = insn.condition
            val ops = insn.operands

            return when {
                c == "eq0" -> ops.getOrNull(0)?.let { valueOf(it) + " == 0" } ?: "cond"
                c == "ne0" -> ops.getOrNull(0)?.let { valueOf(it) + " != 0" } ?: "cond"
                c.startsWith("bit") -> {
                    val o = ops.getOrNull(0)
                    val isOne = c.endsWith("@1")
                    val bit = c.removePrefix("bit").substringBefore('@')
                    if (o == null) "cond"
                    else {
                        val shifted = "((" + valueOf(o) + " >> " + bit + ") & 1)"
                        if (isOne) "$shifted != 0" else "$shifted == 0"
                    }
                }
                c.startsWith("flag:") -> {
                    val cc = c.removePrefix("flag:")
                    // 用上一条 Compare 的操作数还原比较式
                    val cmp = findPrevCompare(insn)
                    if (cmp != null && cmp.operands.size >= 2) {
                        val a = valueOf(cmp.operands[0])
                        val b = valueOf(cmp.operands[1])
                        val op = when (cc) {
                            "eq" -> "=="
                            "ne" -> "!="
                            "lt" -> "<"
                            "le" -> "<="
                            "gt" -> ">"
                            "ge" -> ">="
                            "hi" -> ">"
                            "hs", "cs" -> ">="
                            "lo", "cc" -> "<"
                            "ls" -> "<="
                            else -> "?"
                        }
                        "$a $op $b"
                    } else {
                        "/* $cc */ flag"
                    }
                }
                else -> "cond"
            }
        }

        private fun findPrevCompare(branch: IRInstruction): IRInstruction? {
            // 在同一个块里往前找最近的 Compare
            for (b in fn.blocks) {
                if (!b.instructions.contains(branch)) continue
                val idx = b.instructions.indexOf(branch)
                for (i in idx - 1 downTo 0) {
                    if (b.instructions[i].op == IROp.Compare) return b.instructions[i]
                }
                return null
            }
            return null
        }

        /** 单条 IR → C 语句 */
        private fun exprOf(i: IRInstruction): String {
            return when (i.op) {
                IROp.Nop -> ""

                IROp.Const -> {
                    val r = i.result ?: return ""
                    // 常量不单独成句，命名时已内联
                    if (r.kind == ValueKind.Const) "" else "${namer.nameOf(r)} = ${constForm(r)};"
                }

                IROp.Move -> {
                    val r = i.result ?: return ""
                    val a = i.operands.getOrNull(0) ?: return ""
                    "${namer.nameOf(r)} = ${valueOf(a)};"
                }

                IROp.Add, IROp.Sub, IROp.Mul, IROp.Div, IROp.Rem,
                IROp.And, IROp.Or, IROp.Xor, IROp.Shl, IROp.Shr, IROp.Sar -> {
                    val r = i.result ?: return ""
                    val a = i.operands.getOrNull(0) ?: return ""
                    val b = i.operands.getOrNull(1) ?: return ""
                    val op = when (i.op) {
                        IROp.Add -> "+"
                        IROp.Sub -> "-"
                        IROp.Mul -> "*"
                        IROp.Div -> "/"
                        IROp.Rem -> "%"
                        IROp.And -> "&"
                        IROp.Or -> "|"
                        IROp.Xor -> "^"
                        IROp.Shl -> "<<"
                        IROp.Shr -> ">>"
                        else -> ">>"
                    }
                    "${namer.nameOf(r)} = ${valueOf(a)} $op ${valueOf(b)};"
                }

                IROp.Not -> {
                    val r = i.result ?: return ""
                    val a = i.operands.getOrNull(0) ?: return ""
                    "${namer.nameOf(r)} = ~${valueOf(a)};"
                }

                IROp.Load -> {
                    val r = i.result ?: return ""
                    val a = i.operands.getOrNull(0) ?: return ""
                    if (r.kind == ValueKind.Local) {
                        "${namer.nameOf(r)} = ${valueOf(a)};"
                    } else {
                        "${namer.nameOf(r)} = *${valueOf(a)};"
                    }
                }

                IROp.Store -> {
                    val dst = i.operands.getOrNull(0) ?: return ""
                    val src = i.operands.getOrNull(1) ?: return ""
                    if (dst.kind == ValueKind.Local) {
                        "${namer.nameOf(dst)} = ${valueOf(src)};"
                    } else {
                        "*${valueOf(dst)} = ${valueOf(src)};"
                    }
                }

                IROp.Compare -> ""

                IROp.Call -> {
                    val t = i.target
                    val name = if (t > 0) "sub_" + java.lang.Long.toHexString(t) else
                        (i.operands.getOrNull(0)?.let { "(fnptr)" } ?: "sub")
                    val r = i.result
                    if (r != null) "${namer.nameOf(r)} = $name();" else "$name();"
                }

                else -> ""
            }
        }

        private fun pad(n: Int): String = "    ".repeat(n)
    }

    // ============================================================
    // 支配关系
    // ============================================================

    object Dominator {
        /** 块 → 支配该块的集合（含自身） */
        fun build(fn: FunctionIR): Map<BasicBlock, Set<BasicBlock>> {
            val entry = fn.entry ?: return emptyMap()
            val all = fn.blocks.toSet()
            val dom = HashMap<BasicBlock, MutableSet<BasicBlock>>()

            for (b in fn.blocks) {
                if (b === entry) dom[b] = mutableSetOf(b)
                else dom[b] = all.toMutableSet()
            }

            var changed = true
            var guard = 0
            while (changed && guard < 200) {
                changed = false
                guard++
                for (b in fn.blocks) {
                    if (b === entry) continue
                    val preds = b.predecessors
                    if (preds.isEmpty()) continue
                    var inter: Set<BasicBlock>? = null
                    for (p in preds) {
                        val pd = dom[p] ?: continue
                        inter = if (inter == null) pd.toSet() else inter.intersect(pd)
                    }
                    val newSet = (inter ?: emptySet()).toMutableSet()
                    newSet.add(b)
                    val old = dom[b]
                    if (old != null && old.size != newSet.size) {
                        dom[b] = newSet
                        changed = true
                    } else if (old == null || !old.containsAll(newSet)) {
                        dom[b] = newSet
                        changed = true
                    }
                }
            }
            return dom
        }
    }

    // ============================================================

    fun typeName(t: ValueType): String = when (t) {
        ValueType.Int8 -> "int8_t"
        ValueType.Int16 -> "int16_t"
        ValueType.Int32 -> "int32_t"
        ValueType.Int64 -> "int64_t"
        ValueType.Float -> "float"
        ValueType.Double -> "double"
        ValueType.Pointer -> "void*"
        ValueType.Bool -> "bool"
        else -> "int64_t"
    }

    fun sanitize(name: String): String {
        val sb = StringBuilder()
        for (c in name) {
            if (c.isLetterOrDigit() || c == '_') sb.append(c) else sb.append('_')
        }
        var s = sb.toString()
        if (s.isEmpty()) s = "sub"
        if (s[0].isDigit()) s = "f_" + s
        if (s.length > 64) s = s.take(64)
        return s
    }
}
