package sy.great.toolbox.tools.decompiler

import sy.great.toolbox.tools.RadareBridge

/**
 * 基于 Radare2 的反编译器。
 *
 * 与旧 DecompilerEngine 的根本区别：
 *   旧：自研 Arm64Disasm 解码 → 自己建 CFG → IR → C
 *   新：r2 的 pdfj 直接给出 blocks / jump / fail / type → IR → C
 *
 * 为什么这样更准：
 *   · 指令解码由 Capstone 完成，覆盖率接近 100%
 *   · 控制流目标是 r2 分析好的，不是猜的
 *   · 跳转表、尾调用、间接跳转 r2 都已经处理
 */
object R2Decompiler {

    data class Result(
        val text: String,
        val functionCount: Int,
        val blockCount: Int,
        val error: String = ""
    )

    /**
     * 反编译整个 ELF。
     *
     * @param elfPath 目标文件路径
     * @param maxFunctions 最多导出多少个函数
     */
    fun decompileFile(
        elfPath: String,
        maxFunctions: Int = 500,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): Result {
        if (!RadareBridge.load()) {
            return Result("", 0, 0, "native 库加载失败：" + (RadareBridge.error() ?: ""))
        }

        val openRes = RadareBridge.open(java.io.File(elfPath))
        if (RadareBridge.isError(openRes)) {
            return Result("", 0, 0, openRes)
        }

        // 关闭颜色等选项，保证 JSON 干净
        RadareBridge.setup()

        // 分析。aaa 比较慢但对准确性必要
        val anaRes = RadareBridge.analyze()
        if (RadareBridge.isError(anaRes)) {
            return Result("", 0, 0, "分析失败：" + anaRes)
        }

        // 拿函数列表
        val funcAddrs = listFunctions()
        if (funcAddrs.isEmpty()) {
            return Result("", 0, 0, "未发现任何函数")
        }

        val picked = if (funcAddrs.size > maxFunctions) funcAddrs.take(maxFunctions) else funcAddrs

        val sb = StringBuilder(1 shl 16)
        sb.append("/*\n")
        sb.append(" * 由 Radare2 分析，共 ").append(funcAddrs.size).append(" 个函数")
        if (picked.size < funcAddrs.size) sb.append("（导出前 ").append(picked.size).append(" 个）")
        sb.append('\n')
        sb.append(" * 指令解码：r2/Capstone；控制流：r2 分析结果\n")
        sb.append(" */\n\n")

        var produced = 0
        var blocks = 0

        for (idx in picked.indices) {
            val addr = picked[idx]
            val json = RadareBridge.pdfj(addr)
            if (!RadareBridge.isError(json)) {
                val code = decompileFunction(json)
                if (code != null) {
                    sb.append(code).append('\n')
                    produced++
                    blocks += code.countOccurrences("L_")
                }
            } else {
                // pdfj 失败时退回 pdrj（无符号表也能用）
                val alt = RadareBridge.pdrj(addr)
                if (!RadareBridge.isError(alt)) {
                    val code = decompileFunction(alt)
                    if (code != null) {
                        sb.append(code).append('\n')
                        produced++
                        blocks += code.countOccurrences("L_")
                    }
                }
            }
            onProgress?.invoke(idx + 1, picked.size)
        }

        if (produced == 0) {
            return Result("", 0, 0, "r2 已分析，但未能导出任何函数")
        }

        return Result(sb.toString(), produced, blocks)
    }

    /** 从 r2 拿函数列表（aflj） */
    private fun listFunctions(): List<Long> {
        val txt = RadareBridge.functionList()
        if (RadareBridge.isError(txt)) return emptyList()

        val out = ArrayList<Long>()
        // aflj 返回 [{"offset":3456,"name":"sym.foo",...}, ...]
        // 这里只需要 offset，用极简扫描即可
        var i = 0
        val key = "\"offset\""
        while (true) {
            val p = txt.indexOf(key, i)
            if (p < 0) break
            var j = p + key.length
            while (j < txt.length && txt[j] != ':') j++
            j++
            while (j < txt.length && txt[j].isWhitespace()) j++
            val start = j
            while (j < txt.length && (txt[j].isDigit() || txt[j] == '-')) j++
            if (j > start) {
                txt.substring(start, j).toLongOrNull()?.let { out.add(it) }
            }
            i = j
        }
        return out.distinct().sorted()
    }

    /**
     * 把 pdfj/pdrj 的 JSON 转成伪 C。
     */
    fun decompileFunction(json: String): String? {
        val f = R2Json.parseFunc(json) ?: return null
        if (f.blocks.isEmpty()) return null

        val fn = buildIr(f)
        if (fn.blocks.isEmpty()) return null

        return CCodeGen.generate(fn)
    }

    /**
     * r2 的函数数据 → FunctionIR。
     *
     * 直接使用 r2 的块划分和跳转目标，不再自己推断。
     */
    private fun buildIr(f: R2Json.Func): FunctionIR {
        val fn = FunctionIR(f.addr, f.name)

        // 1) 建块
        val blockByAddr = HashMap<Long, BasicBlock>()
        for (idx in f.blocks.indices) {
            val rb = f.blocks[idx]
            val b = BasicBlock(idx, rb.offset)
            b.endAddress = rb.offset + (if (rb.size > 0) rb.size else 4L)
            blockByAddr[rb.offset] = b
            fn.blocks.add(b)
        }

        // 2) 每个块内翻译指令
        for (rb in f.blocks) {
            val b = blockByAddr[rb.offset] ?: continue
            for (op in rb.ops) {
                val insn = toIrInsn(op)
                if (insn != null) b.instructions.add(insn)
            }
            if (b.instructions.isEmpty()) continue
            b.endAddress = b.instructions.last().address + 4
        }

        // 3) 连边：直接用 r2 给的 jump / fail
        for (rb in f.blocks) {
            val b = blockByAddr[rb.offset] ?: continue
            val lastOp = rb.ops.lastOrNull() ?: continue
            val target = lastOp.jump
            val fall = lastOp.fail

            when (lastOp.type) {
                "ret" -> b.isExit = true
                "ujmp" -> {
                    val t = target?.let { blockByAddr[it] }
                    if (t != null) b.connectTo(t) else b.isExit = true
                }
                "cjmp" -> {
                    target?.let { blockByAddr[it] }?.let { b.connectTo(it) }
                    fall?.let { blockByAddr[it] }?.let { b.connectTo(it) }
                    if (b.successors.isEmpty()) b.isExit = true
                }
                "trap", "swi" -> b.isExit = true
                else -> {
                    // 普通块：落到地址相邻的下一个块
                    val next = blockByAddr[b.endAddress]
                        ?: fn.blocks.firstOrNull { it.startAddress > b.startAddress &&
                                it.startAddress == b.endAddress }
                    if (next != null) b.connectTo(next) else b.isExit = true
                }
            }
        }

        // 4) 入口
        fn.entry = blockByAddr[f.addr] ?: fn.blocks.firstOrNull()
        fn.entry?.isEntry = true

        // 5) 参数：x0-x7 首次出现的作为参数
        collectArgs(fn)

        return fn
    }

    /** 从 r2 的 op 生成 IR 指令 */
    private fun toIrInsn(op: R2Json.Op): IRInstruction? {
        val d = op.disasm.trim()
        if (d.isEmpty()) return null

        val mn = d.substringBefore(' ').lowercase()
        val body = if (mn.length < d.length) d.substring(mn.length).trim() else ""

        val irOp = when (op.type) {
            "ret" -> IROp.Return
            "ujmp" -> IROp.Jump
            "cjmp" -> IROp.Branch
            "ijmp" -> IROp.Indirect
            "call" -> IROp.Call
            "trap", "swi", "ill" -> IROp.Return
            else -> when (mn) {
                "add", "adds" -> IROp.Add
                "sub", "subs" -> IROp.Sub
                "mul", "madd", "smull" -> IROp.Mul
                "sdiv", "udiv" -> IROp.Div
                "and", "ands" -> IROp.And
                "orr", "mov", "movz", "movn" -> IROp.Move
                "eor" -> IROp.Xor
                "lsl" -> IROp.Shl
                "lsr" -> IROp.Shr
                "asr" -> IROp.Sar
                "cmp", "cmn", "tst" -> IROp.Compare
                "ldr", "ldrb", "ldrh", "ldrsw", "ldur" -> IROp.Load
                "str", "strb", "strh", "stur" -> IROp.Store
                "bl", "blr" -> IROp.Call
                "b" -> IROp.Jump
                else -> {
                    if (mn.startsWith("b.") || mn == "cbz" || mn == "cbnz" ||
                        mn == "tbz" || mn == "tbnz"
                    ) IROp.Branch else return null
                }
            }
        }

        val ir = IRInstruction(irOp, op.offset, 4)
        ir.asmText = d
        ir.target = op.jump ?: 0L

        // 条件信息
        if (irOp == IROp.Branch) {
            ir.condition = when {
                mn == "cbz" -> "eq0"
                mn == "cbnz" -> "ne0"
                mn == "tbz" -> "bit0@0"
                mn == "tbnz" -> "bit0@1"
                mn.startsWith("b.") -> "flag:" + mn.removePrefix("b.")
                else -> "flag:eq"
            }
        }

        // 操作数：先把寄存器/立即数拆成 IRValue
        ir.operands.addAll(parseOperands(body, op.offset, mn, irOp))

        return ir
    }

    /**
     * 简单的操作数拆分。
     *
     * r2 已经把指令格式化成标准 GAS 语法（逗号分隔），
     * 这里只区分立即数（#0x…）和寄存器引用。
     */
    private fun parseOperands(body: String, addr: Long, mn: String, op: IROp): List<IRValue> {
        if (body.isEmpty()) return emptyList()
        val out = ArrayList<IRValue>()
        for (part in splitTopLevel(body)) {
            val t = part.trim()
            if (t.isEmpty()) continue
            val imm = parseImm(t)
            if (imm != null) {
                val v = IRValue(nextId++, ValueType.Int64, ValueKind.Const)
                v.constant = imm
                v.accessSize = 8
                out.add(v)
            } else {
                val v = IRValue(nextId++, ValueType.Int64, ValueKind.Temp)
                v.sourceRegister = t
                v.accessSize = 8
                out.add(v)
            }
        }
        return out
    }

    private var nextId = 0

    /** 按顶层逗号拆分（跳过 [] 内部） */
    private fun splitTopLevel(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        for (c in s) {
            when (c) {
                '[', '{', '(' -> { depth++; sb.append(c) }
                ']', '}', ')' -> { depth--; sb.append(c) }
                ',' -> if (depth == 0) { out.add(sb.toString()); sb.setLength(0) } else sb.append(c)
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString())
        return out
    }

    private fun parseImm(s: String): Long? {
        var t = s.trim()
        if (t.startsWith("#")) t = t.substring(1)
        if (t.isEmpty()) return null
        val neg = t.startsWith("-")
        if (neg) t = t.substring(1)
        val v = when {
            t.startsWith("0x") || t.startsWith("0X") -> t.substring(2).toLongOrNull(16)
            t.all { it.isDigit() } -> t.toLongOrNull()
            else -> null
        } ?: return null
        return if (neg) -v else v
    }

    /** 从 x0-x7 首次使用收集参数 */
    private fun collectArgs(fn: FunctionIR) {
        val seen = LinkedHashSet<String>()
        for (b in fn.blocks) {
            for (i in b.instructions) {
                for (o in i.operands) {
                    val r = o.sourceRegister.lowercase()
                    if (r.length >= 2 && (r[0] == 'x' || r[0] == 'w')) {
                        val n = r.substring(1).toIntOrNull()
                        if (n != null && n in 0..7) seen.add(r)
                    }
                }
            }
            if (seen.size >= 8) break
        }
        var idx = 0
        for (r in seen) {
            val v = IRValue(nextId++, ValueType.Int64, ValueKind.Arg)
            v.sourceRegister = r
            v.accessSize = 8
            fn.args.add(v)
            idx++
        }
    }

    private fun String.countOccurrences(sub: String): Int {
        var c = 0
        var i = indexOf(sub)
        while (i >= 0) { c++; i = indexOf(sub, i + sub.length) }
        return c
    }
}
