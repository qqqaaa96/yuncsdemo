package com.csdemo.tools

/**
 * 把 CFG 整理成结构化的“类伪代码”视图。
 *
 * ⚠️ 重要说明：
 * 这不是反编译。真正的反编译（Hex-Rays / Ghidra）需要类型推导、
 * 数据流分析、控制结构识别，是数百万行的工程。
 *
 * 这里做的是：把基本块按 CFG 连接关系，用缩进和标签排列，
 * 让指令序列的“形状”更易读。它的作用是辅助阅读汇编，
 * 不能替代反编译器。
 */
object PseudoCode {

    /** 全量分析结果 */
    data class WholeResult(
        val text: String,
        val functionCount: Int,
        val blockCount: Int,
        val error: String = ""
    )

    /**
     * 对整份 ELF 生成伪 C。
     *
     * 流程：
     *   1. 发现所有函数入口
     *   2. 逐个构建 CFG
     *   3. 对每个 CFG 做控制结构还原
     *   4. 拼接为完整文本
     *
     * 还原的目标不是“把汇编翻译成 C”，而是：
     *   · 按分支关系分出 if / else
     *   · 识别向后跳转形成的循环
     *   · 把顺序执行合并到一起
     *   · 给基本块起可读标签
     */
    fun buildWhole(
        elf: ElfParser.Elf,
        maxFunctions: Int = 400,
        maxInsnsPerFunc: Int = 900,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): WholeResult {
        val archOk = elf.machine == 0xB7 || elf.machine == 0x28
        if (!archOk) {
            return WholeResult(
                "", 0, 0,
                "当前只支持 AArch64 / ARM 反汇编，\n该文件架构为 " + elf.machineName
            )
        }

        val funcs = CfgBuilder.discoverFunctions(elf).filter { it.hasValidAddr }
        if (funcs.isEmpty()) {
            return WholeResult("", 0, 0, "未发现任何函数入口")
        }

        val picked = if (funcs.size > maxFunctions) funcs.take(maxFunctions) else funcs
        val nextMap = CfgBuilder.nextAddrMap(funcs)

        val sb = StringBuilder(1 shl 16)
        sb.append("/*\n")
        sb.append(" * ").append(elf.machineName)
            .append("　").append(elf.typeName).append('\n')
        sb.append(" * 入口 0x").append(hex(elf.entry))
            .append("　函数 ").append(picked.size).append('\n')
        sb.append(" *\n")
        sb.append(" * 这是根据汇编还原的结构化伪代码，不是反编译结果。\n")
        sb.append(" * 它保留分支与循环结构，但不做类型推导与变量命名。\n")
        sb.append(" */\n\n")

        var totalBlocks = 0
        var produced = 0

        picked.forEachIndexed { idx, f ->
            val g = CfgBuilder.buildForFunc(
                elf = elf,
                f = f,
                maxInsns = maxInsnsPerFunc,
                nextFuncAddr = nextMap[f.addr] ?: 0L
            )
            if (g.blocks.isNotEmpty()) {
                sb.append(renderFunction(g, f.name))
                sb.append('\n')
                totalBlocks += g.blocks.size
                produced++
            }
            onProgress?.invoke(idx + 1, picked.size)
        }

        if (produced == 0) {
            return WholeResult("", 0, 0, "已定位到入口，但解码未产生指令（可能是数据文件）")
        }

        return WholeResult(sb.toString(), produced, totalBlocks)
    }

    // ============================================================
    // 单函数：控制结构还原
    // ============================================================

    /**
     * 把一个函数的 CFG 还原为带 if / while 结构的文本。
     *
     * 采用简化算法（不做完整 CFG 结构分析）：
     *   · 先找回边（向后跳转）→ 识别循环头
     *   · 从入口按 DFS 顺序遍历，按前驱数量判断合流点
     *   · 遇到条件分支且两条边都在函数内 → 输出 if / else
     */
    private fun renderFunction(g: CfgBuilder.Graph, funcName: String): String {
        val sb = StringBuilder()

        val byStart = g.blocks.associateBy { it.start }
        val outEdges = g.edges.groupBy { it.from }
        val inEdges = g.edges.groupBy { it.to }

        // 函数头
        sb.append("// ── ").append(funcName)
            .append(" ──  块 ").append(g.blocks.size)
            .append("　边 ").append(g.edges.size).append('\n')
        sb.append("void ").append(sanitize(funcName)).append("()\n{\n")

        // 找出真正的回边目标（与 CfgLayout 破环使用同一 DFS 算法）
        //
        // 旧实现用 “e.to <= e.from” 判断回边，这是错的：
        //   地址更小的前向跳转不一定是循环回边。
        // 回边只能由 DFS 在“目标仍在当前 DFS 栈中”时得出。
        val backTargets = computeBackTargets(g)

        val emitted = HashSet<Long>()

        // 用实例级渲染上下文，避开局部函数互相引用与 lambda return 的限制
        val rc = RenderCtx(
            sb = sb,
            byStart = byStart,
            outEdges = outEdges,
            inEdges = inEdges,
            backTargets = backTargets,
            emitted = emitted
        )
        rc.render(g.entry, 1, 0)

        // 未被访问到的块（孤立块 / 多个出口）补在后面
        val orphans = g.blocks.filter { !emitted.contains(it.start) }
        if (orphans.isNotEmpty()) {
            sb.append("\n    // 未能归入主流程的块\n")
            for (o in orphans.take(200)) {
                sb.append("    L_").append(hex(o.start)).append(":\n")
                for (insn in o.insns) {
                    val line = toCLine(insn)
                    if (line.isNotEmpty()) sb.append("        ").append(line).append('\n')
                }
            }
        }

        sb.append("}\n")
        return sb.toString()
    }

    /**
     * 渲染上下文。
     *
     * 把递归渲染拆成类内方法，避免：
     *   · 局部函数必须按顺序声明
     *   · lambda 里不能直接 return
     */
    private class RenderCtx(
        val sb: StringBuilder,
        val byStart: Map<Long, CfgBuilder.Block>,
        val outEdges: Map<Long, List<CfgBuilder.Edge>>,
        val inEdges: Map<Long, List<CfgBuilder.Edge>>,
        val backTargets: Set<Long>,
        val emitted: MutableSet<Long>
    ) {
        fun render(addr: Long, indent: Int, depth: Int) {
            if (depth > 60) return
            if (emitted.contains(addr)) return
            val b = byStart[addr] ?: return
            emitted.add(addr)

            val pad = "    ".repeat(indent)

            // 回边目标：只标注循环头，不谎称是 while (1)
            //
            // “存在回边”不等于“while (1)”。
            // 真实结构可能是 while(cond) / do-while / for / switch+loop / goto。
            // 在没有 dominator / natural loop 分析前，直接写 while (1) 是过度推断。
            // 这里只给出可验证的事实：这里是循环头，并用花括号保留范围。
            if (backTargets.contains(addr)) {
                sb.append(pad).append("// loop header\n")
                sb.append(pad).append("{\n")
                renderBody(b, indent + 1, depth)
                sb.append(pad).append("}\n")
                return
            }

            renderBody(b, indent, depth)
        }

        fun renderBody(b: CfgBuilder.Block, indent: Int, depth: Int) {
            val pad = "    ".repeat(indent)
            val outs = outEdges[b.start].orEmpty()

            // 1) 输出本块的指令
            for (insn in b.insns) {
                val line = toCLine(insn)
                if (line.isNotEmpty()) {
                    sb.append(pad).append(line).append('\n')
                }
            }

            val last = b.insns.lastOrNull()

            // 2) 返回：结束
            if (last != null && last.isReturn) {
                sb.append(pad).append("return;\n")
                return
            }

            // 3) 条件分支：两条边都在函数内 → if / else
            val cond = last != null && last.isCondBranch
            if (cond && outs.size >= 2) {
                val taken = outs.firstOrNull { it.kind == CfgBuilder.EdgeKind.TAKEN }
                val fall = outs.firstOrNull { it.kind == CfgBuilder.EdgeKind.FALLTHROUGH }
                if (taken != null && fall != null &&
                    byStart.containsKey(taken.to) && byStart.containsKey(fall.to)
                ) {
                    val condText = condExpr(last!!, b)
                    sb.append(pad).append("if (").append(condText).append(") {\n")
                    render(taken.to, indent + 1, depth + 1)
                    sb.append(pad).append("}")
                    // 下落分支不是合流点时才输出 else
                    val fallIsJoin = (inEdges[fall.to]?.size ?: 0) > 1
                    if (!fallIsJoin) {
                        sb.append(" else {\n")
                        render(fall.to, indent + 1, depth + 1)
                        sb.append(pad).append("}")
                    } else {
                        sb.append('\n')
                        render(fall.to, indent, depth + 1)
                    }
                    return
                }
            }

            // 4) 无条件跳转：交给目标继续
            if (last != null && last.isBranch && !last.isIndirect) {
                val t = outs.firstOrNull()
                if (t != null && byStart.containsKey(t.to)) {
                    render(t.to, indent, depth + 1)
                }
                return
            }

            // 5) 间接跳转 / 调用：不展开
            if (last != null && (last.isIndirect || last.isCall)) {
                if (last.isCall) {
                    sb.append(pad).append("// call -> 0x")
                        .append(hex(last.target ?: 0L)).append('\n')
                } else {
                    sb.append(pad).append("// indirect jump\n")
                }
                // 调用后还有下落
                val fall = outs.firstOrNull { it.kind == CfgBuilder.EdgeKind.FALLTHROUGH }
                if (fall != null && byStart.containsKey(fall.to)) {
                    render(fall.to, indent, depth + 1)
                }
                return
            }

            // 6) 普通顺序执行
            val fall = outs.firstOrNull()
            if (fall != null && byStart.containsKey(fall.to)) {
                render(fall.to, indent, depth + 1)
            }
        }
    }

    // ---------- 辅助 ----------

    /**
     * 用 DFS 在 CFG 上求“真正的回边”目标集合。
     *
     * 与 CfgLayout.breakCycles 使用同一判定：
     *   当一条边 (from -> to) 指向“当前仍在 DFS 栈中”的节点时，
     *   to 就是回边目标（自然循环头候选）。
     *
     * 这是结构事实，不是“地址大小”的启发式。
     */
    private fun computeBackTargets(g: CfgBuilder.Graph): Set<Long> {
        // 邻接表（按块起点）
        val out = HashMap<Long, MutableList<Long>>()
        for (e in g.edges) {
            // 只考虑真实可达边；间接未解析边不参与
            out.getOrPut(e.from) { ArrayList() }.add(e.to)
        }

        val state = HashMap<Long, Int>()   // 0=未访问 1=在栈中 2=已完成
        val backTargets = HashSet<Long>()

        // 迭代式 DFS，避免大图递归溢出
        for (b in g.blocks) {
            val start = b.start
            if ((state[start] ?: 0) == 2) continue
            val stack = ArrayList<Long>()
            val iterIdx = HashMap<Long, Int>()
            stack.add(start)
            state[start] = 1
            iterIdx[start] = 0

            while (stack.isNotEmpty()) {
                val cur = stack.last()
                val list = out[cur]
                val idx = iterIdx[cur] ?: 0
                if (list == null || idx >= list.size) {
                    state[cur] = 2
                    stack.removeAt(stack.size - 1)
                    continue
                }
                iterIdx[cur] = idx + 1
                val next = list[idx]
                val st = state[next] ?: 0
                if (st == 1) {
                    // 指向栈中节点 → 真正的回边，next 是回边目标
                    backTargets.add(next)
                } else if (st == 0) {
                    state[next] = 1
                    iterIdx[next] = 0
                    stack.add(next)
                }
            }
        }
        return backTargets
    }

    /**
     * 把条件分支指令转成条件表达式。
     *
     * 优先用 Arm64Disasm 解出的真实谓词（insn.cond），
     * 并回溯本块内最近一条设置标志位的指令（cmp / subs / tst / ands），
     * 还原成 “x0 < x1” / “x0 == 0” 这类直接可读的比较表达式。
     *
     * 找不到可比数据时，退回带明确标注的占位（不假装知道）。
     */
    private fun condExpr(insn: Arm64Disasm.Insn, block: CfgBuilder.Block): String {
        val t = insn.text
        // 形如 "b.eq 0x1234" / "cbz x0, 0x1234"
        when {
            t.startsWith("cbz ") -> {
                val body = t.removePrefix("cbz ").substringBefore(',')
                return "$body == 0"
            }
            t.startsWith("cbnz ") -> {
                val body = t.removePrefix("cbnz ").substringBefore(',')
                return "$body != 0"
            }
            t.startsWith("tbz ") -> {
                val parts = t.removePrefix("tbz ").split(',')
                val r = parts.getOrNull(0)?.trim().orEmpty()
                val bit = parts.getOrNull(1)?.trim().orEmpty()
                return "(($r >> $bit) & 1) == 0"
            }
            t.startsWith("tbnz ") -> {
                val parts = t.removePrefix("tbnz ").split(',')
                val r = parts.getOrNull(0)?.trim().orEmpty()
                val bit = parts.getOrNull(1)?.trim().orEmpty()
                return "(($r >> $bit) & 1) != 0"
            }
        }

        // b.<cond>：用真实谓词 + 回溯标志位来源
        val cond = insn.cond
        if (cond != null) {
            val flagSrc = findFlagSetter(block, insn.addr)
            if (flagSrc != null) {
                val expr = renderFlagCmp(flagSrc, cond)
                if (expr != null) return expr
            }
            // 无法还原为比较表达式时，保留谓词本身（不编造）
            return "/* $cond */ flag"
        }

        return "/* " + insn.mnemonic + " */"
    }

    /**
     * 在同一块内向前找最近一条“设置 NZCV 标志位”的指令：
     * cmp / subs / adds / cmn / tst / ands —— 这是 b.<cond> 的谓词来源。
     *
     * 找不到（比如标志位来自上一块）返回 null，不猜测。
     */
    private fun findFlagSetter(block: CfgBuilder.Block, branchAddr: Long): Arm64Disasm.Insn? {
        val idx = block.insns.indexOfLast { it.addr == branchAddr }
        if (idx <= 0) return null
        for (i in idx - 1 downTo 0) {
            val ins = block.insns[i]
            val m = ins.mnemonic
            // 只认真正设置 NZCV 的“带 S”比较类指令。
            if (m == "cmp" || m == "cmn" || m == "subs" ||
                m == "adds" || m == "tst" || m == "ands"
            ) {
                return ins
            }
            // 遇到新的分支/控制流就别再往前回，避免拿错来源
            if (ins.isBranch || ins.isReturn || ins.isCall) break
        }
        return null
    }

    /**
     * 把 “cmp a, b” + 谓词 cond 渲染成 C 比较表达式。
     *
     * 支持：cmp / cmn / tst / subs（含与 XZR 比较的 cmp）。
     * 无法可靠还原时返回 null。
     */
    private fun renderFlagCmp(flagIns: Arm64Disasm.Insn, cond: String): String? {
        val op = conditionToC(cond) ?: return null
        val t = flagIns.text
        val m = flagIns.mnemonic

        // 取出操作数（去掉助记符）
        val body = t.removePrefix(m).trim()
        val parts = body.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val a = parts[0]
        val b = parts[1].removeSuffix("!").removePrefix("#")
        if (a.isEmpty() || b.isEmpty()) return null

        return when (m) {
            // cmp a, b 设置的是 a - b 的标志位
            "cmp" -> "$a $op $b"
            // cmn a, b 设置的是 a + b 的标志位，不能写成 a == -b
            "cmn" -> "($a + $b) $op 0"
            // tst a, b 设置的是 (a & b) 与 0 的标志位
            "tst" -> "($a & $b) $op 0"
            // adds a, b, c 设置的是 b + c 的标志位
            "adds" -> if (parts.size >= 3) "(${parts[1]} + ${parts[2]}) $op 0" else null
            // subs a, b, c 设置的是 b - c 的标志位
            "subs" -> if (parts.size >= 3) "${parts[1]} $op ${parts[2]}" else null
            // ands a, b, c 设置的是 (b & c) 与 0 的标志位
            "ands" -> if (parts.size >= 3) "(${parts[1]} & ${parts[2]}) $op 0" else null
            else -> null
        }
    }

    /** NZCV 谓词 → C 比较运算符（无符号/有符号按 ARM 语义） */
    private fun conditionToC(cond: String): String? = when (cond) {
        "eq" -> "=="
        "ne" -> "!="
        // 进位置位/清零：无符号比较
        "cs", "hs" -> ">="
        "cc", "lo" -> "<"
        "hi" -> ">"
        "ls" -> "<="
        // 有符号比较
        "ge" -> ">="
        "lt" -> "<"
        "gt" -> ">"
        "le" -> "<="
        else -> null   // mi/pl/vs/vc 等依赖单标志，不强行映射为比较
    }

    /** 单条指令 → C 风格行。返回空串表示不输出。 */
    private fun toCLine(insn: Arm64Disasm.Insn): String {
        // 分支类指令单独处理，不作为语句输出
        when (insn.mnemonic) {
            "b", "bl", "br", "blr", "ret" -> return ""
            "nop" -> return ""
            ".word" -> return "/* " + insn.text + " */"
        }
        if (insn.mnemonic.startsWith("b.")) return ""
        if (insn.mnemonic.startsWith("cbz")) return ""
        if (insn.mnemonic.startsWith("cbnz")) return ""
        if (insn.mnemonic.startsWith("tbz")) return ""
        if (insn.mnemonic.startsWith("tbnz")) return ""

        // 其他指令：用注释形式保留原文，不假装成 C 语句
        return "/* " + insn.text + " */"
    }

    /** 把符号名处理成合法 C 标识符 */
    private fun sanitize(name: String): String {
        val sb = StringBuilder()
        for ((i, c) in name.withIndex()) {
            val ok = c.isLetterOrDigit() || c == '_'
            if (ok) {
                sb.append(c)
            } else {
                sb.append('_')
            }
        }
        var s = sb.toString()
        if (s.isEmpty()) s = "sub"
        if (s[0].isDigit()) s = "f_" + s
        if (s.length > 64) s = s.take(64)
        return s
    }

    /**
     * 生成结构化块视图（单函数，保留）。
     */
    fun generate(graph: CfgBuilder.Graph, funcName: String): String {
        if (graph.blocks.isEmpty()) return "（无可用基本块）"

        val sb = StringBuilder()
        sb.append("// 结构化块视图（非反编译，仅供辅助阅读）\n")
        sb.append("// 函数: ").append(funcName).append('\n')
        sb.append("// 块数: ").append(graph.blocks.size)
            .append("  边数: ").append(graph.edges.size)
            .append("  指令: ").append(graph.insnCount).append('\n')
        if (graph.truncated) sb.append("// 注：扫描被上限截断\n")
        sb.append('\n')

        val byStart = graph.blocks.associateBy { it.start }
        val edgeMap = graph.edges.groupBy { it.from }

        for (b in graph.blocks) {
            val tag = when {
                b.isEntry -> " [入口]"
                b.isExit -> " [出口]"
                else -> ""
            }
            sb.append("L_").append(hex(b.start)).append(':').append(tag).append('\n')

            for (insn in b.insns) {
                sb.append("    ").append(hex(insn.addr)).append("  ").append(insn.text).append('\n')
            }

            // 后继标注
            val edges = edgeMap[b.start].orEmpty()
            if (edges.isNotEmpty()) {
                val succ = edges.joinToString(", ") { e ->
                    val k = when (e.kind) {
                        CfgBuilder.EdgeKind.FALLTHROUGH -> "下落"
                        CfgBuilder.EdgeKind.TAKEN -> "跳转"
                        CfgBuilder.EdgeKind.CALL -> "调用"
                        CfgBuilder.EdgeKind.RETURN -> "返回"
                        CfgBuilder.EdgeKind.UNCOND -> "无条件跳转"
                        CfgBuilder.EdgeKind.INDIRECT -> "间接跳转(目标未知)"
                    }
                    "$k->L_" + hex(e.to)
                }
                val last = b.insns.last()
                val via = if (last.isCondBranch) "条件" else if (last.isCall) "调用" else ""
                sb.append("    // ").append(via).append(if (via.isNotEmpty()) " " else "")
                    .append(succ).append('\n')
            } else if (b.isExit) {
                sb.append("    // 终止\n")
            }
            sb.append('\n')
        }

        return sb.toString()
    }

    /**
     * 更紧凑的“伪码风”输出：把常见指令模式映射成 C 风格语句。
     * 仅做文本改写，不做任何语义分析。
     */
    fun pseudoView(graph: CfgBuilder.Graph): String {
        val sb = StringBuilder()
        sb.append("// 指令→C 风格改写（无类型/无数据流，仅助读）\n\n")
        for (b in graph.blocks) {
            sb.append(label(b)).append(":\n")
            for (insn in b.insns) {
                sb.append("  ").append(toC(insn)).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun label(b: CfgBuilder.Block): String = "L_" + hex(b.start)

    /** 把单条指令改写为 C 风格文本（纯文本映射） */
    private fun toC(i: Arm64Disasm.Insn): String {
        val t = i.text
        // 只做常见模式的机械改写，不改语义
        return when (i.mnemonic) {
            "mov" -> t.removePrefix("mov ") + ";"
            "bl" -> "call_" + t.removePrefix("bl ") + ";"
            "b" -> "goto " + t.removePrefix("b ").trim() + ";"
            "ret" -> "return;"
            "cmp" -> "if (" + t.removePrefix("cmp ").replace(", ", " != ") + ")"
            "str", "strb", "strh" -> t + ";"
            "ldr", "ldrb", "ldrh" -> t + ";"
            "cbz" -> "if (" + t.removePrefix("cbz ") + " == 0) goto;"
            "cbnz" -> "if (" + t.removePrefix("cbnz ") + " != 0) goto;"
            "tbz" -> "if (" + t.removePrefix("tbz ") + " == 0) goto;"
            "tbnz" -> "if (" + t.removePrefix("tbnz ") + " != 0) goto;"
            "adrp" -> t + ";"
            ".word" -> t + ";"
            else -> {
                if (t.startsWith("b.")) "if (" + t.substring(2) + ") goto;"
                else t + ";"
            }
        }
    }

    private fun hex(v: Long): String = java.lang.Long.toHexString(v)
}
