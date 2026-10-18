package sy.great.toolbox.tools.decompiler

/**
 * 伪 C 生成器。
 *
 * 流水线：
 *   FunctionIR
 *     → SsaBuilder（插 Phi，补上分支合流的缺失定义）
 *     → ExprBuilder（表达式内联 + 死代码消除）
 *     → Structurer（自然循环识别）
 *     → 本文（按结构输出 if / else / while）
 *
 * 与旧版本的区别：
 *   · 不再满屏 goto：循环用 while 输出，分支用 if/else
 *   · 不再每步一个变量：单次使用的临时值内联成表达式
 *   · 不再靠“回边启发”猜循环：用支配关系算出的自然循环
 */
object CCodeGen {

    /** 生成单个函数的伪 C */
    fun generate(fn: FunctionIR): String {
        // ---- 1. SSA：补全分支合流处的定义 ----
        SsaBuilder.build(fn)

        // ---- 1.5 类型推断（必须在 SSA 之后，Phi 也参与推断）----
        TypeInfer.run(fn)

        val sb = StringBuilder(4096)

        // ---- 2. 命名 ----
        val namer = Namer()
        namer.assign(fn)

        // ---- 3. 支配关系与循环 ----
        val dom = Dominators.compute(fn)
        val loops = Structurer.findLoops(fn, dom)
        val loopAt = HashMap<BasicBlock, Structurer.Loop>()
        for (l in loops) loopAt[l.header] = l

        // ---- 4. 表达式内联分析 ----
        val inlineInfo = ExprBuilder.analyze(fn)
        val defOf = ExprBuilder.indexDefs(fn)

        // ---- 5. 函数头 ----
        sb.append("/* ").append(fn.name)
            .append(" @ 0x").append(java.lang.Long.toHexString(fn.address))
            .append(" */\n")

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

        // ---- 6. 局部变量声明 ----
        // 只声明真正会被输出的值：
        //   · 被内联掉的临时值不声明
        //   · Phi 必须声明
        //   · 只使用一次的纯临时值已内联，不声明
        val declared = LinkedHashSet<String>()
        for (b in fn.blocks) {
            for (i in b.instructions) {
                val r = i.result ?: continue
                if (r.kind == ValueKind.Const) continue
                if (i.op == IROp.Move) continue                     // 拷贝已在目标处体现
                if (i.op == IROp.Compare) continue                  // 比较不单独声明
                if (i.op == IROp.Store) continue                    // store 无结果
                if (i.op == IROp.Phi) {
                    declared.add(declareLine(namer, r))
                    continue
                }
                if (r.kind == ValueKind.Arg || r.kind == ValueKind.Local) continue
                if (inlineInfo.inlineable.contains(r)) continue
                if (i.op == IROp.Call) {
                    declared.add(declareLine(namer, r))
                    continue
                }
                if (i.op == IROp.Load && r.kind != ValueKind.Temp) continue
                declared.add(declareLine(namer, r))
            }
        }
        if (declared.isNotEmpty()) {
            for (d in declared) sb.append("    ").append(d).append('\n')
            sb.append('\n')
        }

        // ---- 7. 结构输出 ----
        val ctx = Ctx(sb, namer, dom, fn, loopAt, inlineInfo, defOf)
        ctx.emitFunction()

        sb.append("}\n")
        return sb.toString()
    }

    private fun declareLine(namer: Namer, v: IRValue): String =
        typeName(v.type) + " " + namer.nameOf(v) + ";"

    // ============================================================
    // 变量命名
    // ============================================================

    class Namer {
        private val names = HashMap<IRValue, String>()

        fun assign(fn: FunctionIR) {
            // 先用 SSA 重命名分配带版本号的名字
            names.putAll(SsaRename.assign(fn))

            // 补上遗漏的值（常量保持字面形式，其余给个占位名）
            var t = 0
            for (b in fn.blocks) {
                for (i in b.instructions) {
                    val r = i.result
                    if (r != null && !names.containsKey(r)) {
                        names[r] = if (r.kind == ValueKind.Const)
                            ExprBuilder.constForm(r) else "t" + (t++)
                    }
                    for (o in i.operands) {
                        if (names.containsKey(o)) continue
                        names[o] = if (o.kind == ValueKind.Const)
                            ExprBuilder.constForm(o) else "u" + (t++)
                    }
                }
            }
        }

        fun nameOf(v: IRValue): String = names[v] ?: ("v" + v.id)
    }

    // ============================================================
    // 输出上下文
    // ============================================================

    private class Ctx(
        val sb: StringBuilder,
        val namer: Namer,
        val dom: Map<BasicBlock, Set<BasicBlock>>,
        val fn: FunctionIR,
        val loopAt: Map<BasicBlock, Structurer.Loop>,
        val inlineInfo: ExprBuilder.Inline,
        val defOf: Map<IRValue, IRInstruction>
    ) {
        private val emitted = HashSet<BasicBlock>()
        /** 已输出过 while 的循环头，避免重复包裹 */
        private val whileEmitted = HashSet<BasicBlock>()
        /** 需要 goto 才能回到的块（无法结构化时的兼底） */
        private val needLabel = HashSet<BasicBlock>()

        fun emitFunction() {
            val entry = fn.entry ?: return
            detectUnstructurable()
            emitBlock(entry, 1)

            // 未被可达路径覆盖的块（通常是不可达残留）
            for (b in fn.blocks) {
                if (emitted.contains(b)) continue
                if (b.instructions.isEmpty()) continue
                sb.append(pad(1)).append(b.label).append(":\n")
                emitStatements(b, 2)
            }
        }

        /**
         * 标记无法结构化的块。
         *
         * 只有循环头、且不是自然循环的多前驱块才需要 goto。
         */
        private fun detectUnstructurable() {
            for (b in fn.blocks) {
                if (b.predecessors.size <= 1) continue
                if (loopAt.containsKey(b)) continue
                needLabel.add(b)
            }
        }

        private fun emitBlock(b: BasicBlock, indent: Int) {
            if (emitted.contains(b)) {
                if (needLabel.contains(b)) {
                    sb.append(pad(indent)).append("goto ").append(b.label).append(";\n")
                }
                return
            }
            emitted.add(b)

            // 循环头：按形状选 while / do-while
            val loop = loopAt[b]
            if (loop != null && !whileEmitted.contains(b)) {
                if (Structurer.asWhile(loop)) {
                    emitWhile(loop, indent)
                    return
                }
                if (Structurer.asDoWhile(loop)) {
                    emitDoWhile(loop, indent)
                    return
                }
            }

            if (needLabel.contains(b)) {
                sb.append(pad(indent)).append(b.label).append(":\n")
            }

            emitStatements(b, indent)
        }

        /** while 循环输出 */
        private fun emitWhile(loop: Structurer.Loop, indent: Int) {
            whileEmitted.add(loop.header)
            emitted.add(loop.header)

            // 循环头的条件：如果以条件分支结尾，直接当 while 条件
            val last = loop.header.instructions.lastOrNull()
            val cond = if (last != null && last.op == IROp.Branch) conditionText(last) else null

            if (cond != null) {
                sb.append(pad(indent)).append("while (").append(cond).append(") {\n")
            } else {
                sb.append(pad(indent)).append("while (1) {\n")
            }

            // 循环体：除循环头（已输出）以外的块
            emitLoopBody(loop, indent + 1)

            sb.append(pad(indent)).append("}\n")

            // 循环之后的第一个退出点
            for (e in loop.exits) {
                if (!emitted.contains(e)) {
                    emitBlock(e, indent)
                    break
                }
            }
        }

        /** do-while 循环输出：条件在末尾 */
        private fun emitDoWhile(loop: Structurer.Loop, indent: Int) {
            whileEmitted.add(loop.header)
            emitted.add(loop.header)

            sb.append(pad(indent)).append("do {\n")

            // 循环体：先输出头块全部语句（无 terminator 就全输）
            val header = loop.header
            val hInsns = header.instructions
            val hLast = hInsns.lastOrNull()
            val hBody = if (hLast != null && hLast.isTerminator) hInsns.dropLast(1) else hInsns
            for (i in hBody) {
                val line = exprOf(i)
                if (line.isNotEmpty()) sb.append(pad(indent + 1)).append(line).append('\n')
            }

            // 其余块（按地址升序）
            for (b in loop.body.sortedBy { it.startAddress }) {
                if (b === header) continue
                if (emitted.contains(b)) continue
                emitted.add(b)
                if (needLabel.contains(b)) {
                    sb.append(pad(indent + 1)).append(b.label).append(":\n")
                }
                emitStatements(b, indent + 1)
            }

            // 末尾条件：来自回边源的条件分支
            val latch = loop.latches.firstOrNull()
            val latchLast = latch?.instructions?.lastOrNull()
            val cond = if (latchLast != null && latchLast.op == IROp.Branch)
                conditionText(latchLast) else null

            if (cond != null) {
                sb.append(pad(indent)).append("} while (").append(cond).append(");\n")
            } else {
                sb.append(pad(indent)).append("} while (1);\n")
            }

            // 循环之后的第一个退出点
            for (e in loop.exits.sortedBy { it.startAddress }) {
                if (!emitted.contains(e)) {
                    emitBlock(e, indent)
                    break
                }
            }
        }

        private fun emitLoopBody(loop: Structurer.Loop, indent: Int) {
            // 循环头里条件分支之前的语句需要先输出（循环条件本身已进 while(...)）
            val header = loop.header
            val insns = header.instructions
            val lastIdx = insns.indexOfLast { it.isTerminator }
            // lastIdx == -1 表示块内没有终止指令，那就全部输出
            val end = if (lastIdx < 0) insns.size else lastIdx
            for (k in 0 until end) {
                val line = exprOf(insns[k])
                if (line.isNotEmpty()) sb.append(pad(indent)).append(line).append('\n')
            }

            // 循环体内其余块。
            // 必须按地址升序输出：loop.body 是 Set，直接迭代顺序不确定，
            // 会导致同一函数每次生成的伪 C 顺序乱跳。
            for (b in loop.body.sortedBy { it.startAddress }) {
                if (b === header) continue
                if (emitted.contains(b)) continue
                emitted.add(b)
                if (needLabel.contains(b)) {
                    sb.append(pad(indent)).append(b.label).append(":\n")
                }
                emitStatements(b, indent)
            }
        }

        /** 输出块内语句 + 块尾控制流 */
        private fun emitStatements(b: BasicBlock, indent: Int) {
            val insns = b.instructions
            if (insns.isEmpty()) return

            val last = insns.last()
            val body = if (last.isTerminator) insns.dropLast(1) else insns

            for (i in body) {
                val line = exprOf(i)
                if (line.isNotEmpty()) sb.append(pad(indent)).append(line).append('\n')
            }

            if (!last.isTerminator) return

            when (last.op) {
                IROp.Return -> emitReturn(last, indent)
                IROp.Jump -> emitJump(last, indent)
                IROp.Branch -> emitBranch(b, last, indent)
                IROp.Indirect -> emitSwitch(b, last, indent)
                else -> {}
            }
        }

        private fun emitReturn(insn: IRInstruction, indent: Int) {
            val v = insn.operands.getOrNull(0)
            if (v == null || v.kind == ValueKind.Unknown) {
                sb.append(pad(indent)).append("return;\n")
            } else {
                sb.append(pad(indent)).append("return ")
                    .append(valueOf(v)).append(";\n")
            }
        }

        private fun emitJump(insn: IRInstruction, indent: Int) {
            val t = fn.blockAt(insn.target)
            if (t == null) {
                sb.append(pad(indent)).append("/* jump -> 0x")
                    .append(java.lang.Long.toHexString(insn.target)).append(" (块外) */\n")
                return
            }
            emitBlock(t, indent)
        }

        /**
         * 条件分支 → if / else。
         *
         * 判断依据改用后支配式思路（简化版）：
         *   · 一侧是另一侧的后继链上的合流点 → 输出 if（无 else）
         *   · 两侧都是独立区域 → 输出 if / else
         */
        private fun emitBranch(b: BasicBlock, insn: IRInstruction, indent: Int) {
            val succ = b.successors
            if (succ.size < 2) {
                succ.firstOrNull()?.let { emitBlock(it, indent) }
                return
            }

            val taken = fn.blockAt(insn.target)
            val fall = succ.firstOrNull { it !== taken }
            if (taken == null || fall == null) {
                succ.firstOrNull()?.let { emitBlock(it, indent) }
                return
            }

            val cond = conditionText(insn)

            // 判断合流：一侧是否是另一侧的子图入口
            val takenIsJoin = isJoinPoint(taken)
            val fallIsJoin = isJoinPoint(fall)

            when {
                takenIsJoin && fallIsJoin -> {
                    sb.append(pad(indent)).append("if (").append(cond).append(") {\n")
                    sb.append(pad(indent + 1)).append("goto ").append(taken.label).append(";\n")
                    sb.append(pad(indent)).append("}\n")
                    emitBlock(fall, indent)
                }
                takenIsJoin -> {
                    sb.append(pad(indent)).append("if (").append(cond).append(") {\n")
                    sb.append(pad(indent + 1)).append("goto ").append(taken.label).append(";\n")
                    sb.append(pad(indent)).append("}\n")
                    emitBlock(fall, indent)
                }
                fallIsJoin -> {
                    sb.append(pad(indent)).append("if (").append(cond).append(") {\n")
                    emitBlock(taken, indent + 1)
                    sb.append(pad(indent)).append("}\n")
                    emitBlock(fall, indent)
                }
                else -> {
                    sb.append(pad(indent)).append("if (").append(cond).append(") {\n")
                    emitBlock(taken, indent + 1)
                    sb.append(pad(indent)).append("} else {\n")
                    emitBlock(fall, indent + 1)
                    sb.append(pad(indent)).append("}\n")
                }
            }
        }

        private fun isJoinPoint(b: BasicBlock): Boolean =
            b.predecessors.size > 1 && !loopAt.containsKey(b)

        /**
         * 间接跳转 → switch。
         *
         * 只有 JumpTableResolver 真的解出了目标才输出 switch；
         * 解不出就如实写注释，绝不编一个假的 case。
         */
        private fun emitSwitch(b: BasicBlock, insn: IRInstruction, indent: Int) {
            val targets = fn.switchTargets[insn.address]
            if (targets.isNullOrEmpty()) {
                sb.append(pad(indent)).append("/* br Xn：跳转表未解析 */\n")
                return
            }

            // 跳转对象：br 的操作数（索引寄存器）
            val idx = insn.operands.firstOrNull()
            val idxText = if (idx != null) valueOf(idx) else "index"

            sb.append(pad(indent)).append("switch (").append(idxText).append(") {\n")

            var caseNo = 0
            for (t in targets) {
                val tb = fn.blockAt(t)
                sb.append(pad(indent + 1)).append("case ").append(caseNo).append(":\n")
                if (tb == null) {
                    sb.append(pad(indent + 2)).append("/* 0x")
                        .append(java.lang.Long.toHexString(t)).append(" 不在本图 */\n")
                    sb.append(pad(indent + 2)).append("break;\n")
                } else if (emitted.contains(tb)) {
                    // 多个 case 跳到同一块（fallthrough 合并），用 goto 复用
                    sb.append(pad(indent + 2)).append("goto ").append(tb.label).append(";\n")
                } else {
                    emitted.add(tb)
                    if (needLabel.contains(tb)) {
                        sb.append(pad(indent + 2)).append(tb.label).append(":\n")
                    }
                    emitStatements(tb, indent + 2)
                }
                caseNo++
            }

            sb.append(pad(indent)).append("}\n")
        }

        // ------------------------------------------------------------
        // 表达式
        // ------------------------------------------------------------

        private fun valueOf(v: IRValue): String =
            ExprBuilder.render(v, inlineInfo, defOf, { x -> namer.nameOf(x) })

        private fun conditionText(insn: IRInstruction): String {
            val c = insn.condition
            val ops = insn.operands

            fun op(i: Int): String = ops.getOrNull(i)?.let { valueOf(it) } ?: "?"

            return when {
                c == "eq0" -> op(0) + " == 0"
                c == "ne0" -> op(0) + " != 0"

                c.startsWith("bit") -> {
                    val isOne = c.endsWith("@1")
                    val bit = c.removePrefix("bit").substringBefore('@')
                    val shifted = "((" + op(0) + " >> " + bit + ") & 1)"
                    if (isOne) "$shifted != 0" else "$shifted == 0"
                }

                c.startsWith("flag:") -> {
                    val cc = c.removePrefix("flag:")
                    val cmp = findPrevCompare(insn)
                    if (cmp != null && cmp.operands.size >= 2) {
                        val a = valueOf(cmp.operands[0])
                        val b = valueOf(cmp.operands[1])
                        val o = when (cc) {
                            "eq" -> "=="
                            "ne" -> "!="
                            "lt" -> "<"
                            "le" -> "<="
                            "gt" -> ">"
                            "ge" -> ">="
                            "hi" -> ">"      // 无符号>
                            "hs", "cs" -> ">="
                            "lo", "cc" -> "<"
                            "ls" -> "<="
                            else -> null
                        }
                        if (o != null) "$a $o $b" else "/* $cc */ (" + a + " ? " + b + ")"
                    } else {
                        "/* $cc */ flag"
                    }
                }

                else -> "cond"
            }
        }

        private fun findPrevCompare(branch: IRInstruction): IRInstruction? {
            for (b in fn.blocks) {
                val idx = b.instructions.indexOfFirst { it === branch }
                if (idx < 0) continue
                for (i in idx - 1 downTo 0) {
                    if (b.instructions[i].op == IROp.Compare) return b.instructions[i]
                }
                return null
            }
            return null
        }

        /** 单条 IR → C 语句 */
        private fun exprOf(i: IRInstruction): String {
            // 被内联的临时值，其定义语句不输出（死代码消除）
            val res = i.result
            if (res != null && inlineInfo.inlineable.contains(res)) {
                if (i.op != IROp.Call && i.op != IROp.Phi) return ""
            }

            return when (i.op) {
                IROp.Nop, IROp.Const, IROp.Compare -> ""

                IROp.Move -> {
                    val r = i.result ?: return ""
                    val a = i.operands.getOrNull(0) ?: return ""
                    val dst = namer.nameOf(r)
                    val src = valueOf(a)
                    if (dst == src) "" else "$dst = $src;"
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
                        else -> ">>"
                    }
                    namer.nameOf(r) + " = " + valueOf(a) + " " + op + " " + valueOf(b) + ";"
                }

                IROp.Not -> {
                    val r = i.result ?: return ""
                    val a = i.operands.getOrNull(0) ?: return ""
                    namer.nameOf(r) + " = ~" + valueOf(a) + ";"
                }

                IROp.Load -> {
                    val r = i.result ?: return ""
                    val a = i.operands.getOrNull(0) ?: return ""
                    if (r.kind == ValueKind.Local) {
                        namer.nameOf(r) + " = " + valueOf(a) + ";"
                    } else {
                        namer.nameOf(r) + " = *" + valueOf(a) + ";"
                    }
                }

                IROp.Store -> {
                    val dst = i.operands.getOrNull(0) ?: return ""
                    val src = i.operands.getOrNull(1) ?: return ""
                    if (dst.kind == ValueKind.Local) {
                        namer.nameOf(dst) + " = " + valueOf(src) + ";"
                    } else {
                        "*" + valueOf(dst) + " = " + valueOf(src) + ";"
                    }
                }

                IROp.Phi -> {
                    val r = i.result ?: return ""
                    val parts = i.operands.joinToString(", ") { valueOf(it) }
                    namer.nameOf(r) + " = phi(" + parts + ");"
                }

                IROp.Call -> {
                    val t = i.target
                    val name = if (t > 0) "sub_" + java.lang.Long.toHexString(t) else "(*fn)"
                    val args = i.operands.drop(if (t > 0) 0 else 1)
                        .joinToString(", ") { valueOf(it) }
                    val r = i.result
                    if (r != null) namer.nameOf(r) + " = " + name + "(" + args + ");"
                    else name + "(" + args + ");"
                }

                else -> ""
            }
        }

        private fun pad(n: Int): String = "    ".repeat(n)
    }

    // ============================================================

    fun typeName(t: ValueType): String = when (t) {
        ValueType.Int8 -> "int8_t"
        ValueType.Int16 -> "int16_t"
        ValueType.Int32 -> "int32_t"
        ValueType.Int64 -> "int64_t"
        ValueType.Float -> "float"
        ValueType.Double -> "double"
        ValueType.Pointer -> "void *"
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
