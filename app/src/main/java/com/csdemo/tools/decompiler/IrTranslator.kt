package com.csdemo.tools.decompiler

import com.csdemo.tools.Arm64Disasm

/**
 * AArch64 → IR 翻译器。
 *
 * 核心思路（对应设计文档第 5 节“寄存器状态模拟”）：
 *   维护一个「寄存器 → IRValue」的映射。
 *   读寄存器 = 查映射；写寄存器 = 建立新 IRValue 并更新映射。
 *
 * 这样：
 *     mov w8, w0
 *     add w8, w8, w1
 *   会变成：
 *     v0 = arg0
 *     v1 = v0 + arg1
 *
 *   而不是两条平铺的注释。
 *
 * 栈访问会被识别为栈变量（sp + offset），单独建模。
 */
class IrTranslator(
    private val baseAddr: Long,
    private val funcAddr: Long
) {

    /** 寄存器名 → 当前值 */
    private val regState = HashMap<String, IRValue>()

    /** 栈偏移 → 栈变量值 */
    private val stackVars = HashMap<Long, IRValue>()

    /** 全局地址 → 全局值 */
    private val globalVars = HashMap<Long, IRValue>()

    private var nextId = 0

    /** 已生成的指令 */
    val instructions = ArrayList<IRInstruction>()

    /** 函数参数列表（按发现顺序） */
    val args = ArrayList<IRValue>()

    /** 返回值 */
    var returnValue: IRValue? = null

    // ------------------------------------------------------------

    private fun newValue(
        kind: ValueKind = ValueKind.Temp,
        type: ValueType = ValueType.Unknown,
        register: String = ""
    ): IRValue {
        val v = IRValue(nextId++, type, kind)
        v.sourceRegister = register
        return v
    }

    /** 把寄存器名归一化：w8 与 x8 视为同一寄存器的不同宽度 */
    private fun norm(reg: String): String {
        val r = reg.trim()
        if (r.isEmpty()) return r
        // w0-w30 与 x0-x30 归一成 x0-x30
        if (r.length >= 2 && (r[0] == 'w' || r[0] == 'W')) {
            val n = r.substring(1)
            if (n.all { it.isDigit() }) return "x" + n
        }
        return r.lowercase()
    }

    /** 寄存器位宽（字节） */
    private fun regWidth(reg: String): Int {
        val r = reg.trim().lowercase()
        if (r.isBlank()) return 8
        return when {
            r == "sp" || r == "wsp" -> 8
            r.startsWith("x") -> 8
            r.startsWith("w") -> 4
            r.startsWith("b") -> 1
            r.startsWith("h") -> 2
            r.startsWith("s") -> 4
            r.startsWith("d") -> 8
            else -> 8
        }
    }

    /** 读寄存器。未定义则创建占位值。 */
    private fun readReg(reg: String, addr: Long): IRValue {
        // 防空：名称为空时返回一个未知识别值，不污染 regState
        if (reg.isBlank()) {
            val u = newValue(ValueKind.Unknown, ValueType.Unknown)
            u.uses.add(addr)
            return u
        }
        val key = norm(reg)
        val existing = regState[key]
        if (existing != null) {
            existing.uses.add(addr)
            return existing
        }

        // 未定义：可能是函数参数（x0-x7）或未知输入
        val v = if (isArgReg(key)) {
            val a = newValue(ValueKind.Arg, ValueType.Unknown, key)
            a.accessSize = regWidth(reg)
            a.type = widthToType(regWidth(reg))
            a.definedAt = funcAddr
            args.add(a)
            a
        } else {
            val u = newValue(ValueKind.Unknown, widthToType(regWidth(reg)), key)
            u.accessSize = regWidth(reg)
            u
        }
        regState[key] = v
        v.uses.add(addr)
        return v
    }

    private fun isArgReg(norm: String): Boolean {
        if (!norm.startsWith("x")) return false
        val n = norm.substring(1).toIntOrNull() ?: return false
        return n in 0..7
    }

    private fun widthToType(w: Int): ValueType = when (w) {
        1 -> ValueType.Int8
        2 -> ValueType.Int16
        4 -> ValueType.Int32
        8 -> ValueType.Int64
        else -> ValueType.Unknown
    }

    /** 写寄存器 */
    private fun writeReg(reg: String, value: IRValue) {
        if (reg.isBlank()) return
        val key = norm(reg)
        regState[key] = value
        if (value.accessSize == 0) value.accessSize = regWidth(reg)
    }

    /** 取栈变量（sp + offset） */
    private fun stackVar(offset: Long, size: Int): IRValue {
        val existing = stackVars[offset]
        if (existing != null) {
            if (existing.accessSize == 0) existing.accessSize = size
            return existing
        }
        val v = newValue(ValueKind.Local, widthToType(size))
        v.stackOffset = offset
        v.accessSize = size
        stackVars[offset] = v
        return v
    }

    /** 取全局值 */
    private fun globalVar(addr: Long, size: Int): IRValue {
        val existing = globalVars[addr]
        if (existing != null) return existing
        val v = newValue(ValueKind.Global, widthToType(size))
        v.constant = addr
        v.accessSize = size
        globalVars[addr] = v
        return v
    }

    /** 新建临时值 */
    private fun temp(type: ValueType, size: Int, addr: Long): IRValue {
        val v = newValue(ValueKind.Temp, type)
        v.accessSize = size
        v.definedAt = addr
        return v
    }

    // ------------------------------------------------------------
    // 操作数解析
    // ------------------------------------------------------------

    /**
     * 内存操作数描述：
     *   [base, #offset] / [base, index] / [base], #imm / [base, #imm]!
     */
    private data class MemOperand(
        val base: String,
        val offset: Long,
        val indexReg: String?,   // 寄存器索引（如 [x0, x1]）
        val isSP: Boolean
    )

    /** 解析形如 "[x29, #-0x10]" 的内存操作数 */
    private fun parseMem(text: String): MemOperand? {
        val s = text.trim()
        if (!s.startsWith("[")) return null
        val close = s.indexOf(']')
        if (close < 0) return null
        val inner = s.substring(1, close)
        val parts = inner.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        val base = parts[0]
        var offset = 0L
        var index: String? = null
        for (i in 1 until parts.size) {
            val p = parts[i].removePrefix("#").trim()
            val num = parseImm(p)
            if (num != null) {
                offset += num
            } else {
                // 寄存器索引
                index = p
            }
        }
        return MemOperand(
            base = base,
            offset = offset,
            indexReg = index,
            isSP = base.equals("sp", true) || base.equals("wsp", true)
        )
    }

    /** 解析立即数：支持 0x / 十进制 / 负号 */
    private fun parseImm(sIn: String): Long? {
        var s = sIn.trim().removePrefix("#").trim()
        if (s.isEmpty()) return null
        var neg = false
        if (s.startsWith("-")) {
            neg = true
            s = s.substring(1).trim()
        }
        val v = try {
            when {
                s.startsWith("0x") || s.startsWith("0X") ->
                    s.substring(2).toLongOrNull(16)
                else -> s.toLongOrNull(10)
            }
        } catch (e: Exception) {
            null
        }
        val r = v ?: return null
        return if (neg) -r else r
    }

    /** 拆分操作数（按逗号，但忽略方括号内的逗号） */
    private fun splitOperands(body: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        for (c in body) {
            when (c) {
                '[' -> { depth++; sb.append(c) }
                ']' -> { depth--; sb.append(c) }
                ',' -> {
                    if (depth == 0) {
                        out.add(sb.toString().trim())
                        sb.setLength(0)
                    } else sb.append(c)
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString().trim())
        return out
    }

    // ------------------------------------------------------------
    // 主入口：把一条反汇编指令翻译为 IR
    // ------------------------------------------------------------

    fun translate(insn: Arm64Disasm.Insn) {
        val t = insn.text.trim()
        val mn = insn.mnemonic.lowercase()
        val body = if (t.startsWith(insn.mnemonic)) t.substring(insn.mnemonic.length).trim()
        else t
        val ops = splitOperands(body)

        when (mn) {
            // ---------- 跳过 ----------
            "nop", ".word" -> return

            // ---------- 分支 ----------
            "b" -> {
                val ir = IRInstruction(IROp.Jump, insn.addr, 4)
                ir.target = insn.target ?: 0L
                ir.asmText = t
                instructions.add(ir)
                return
            }
            "bl" -> {
                val ir = IRInstruction(IROp.Call, insn.addr, 4)
                ir.target = insn.target ?: 0L
                ir.asmText = t
                instructions.add(ir)
                return
            }
            "br" -> {
                val ir = IRInstruction(IROp.Indirect, insn.addr, 4)
                if (ops.isNotEmpty()) ir.operands.add(readReg(ops[0], insn.addr))
                ir.asmText = t
                instructions.add(ir)
                return
            }
            "blr" -> {
                val ir = IRInstruction(IROp.Call, insn.addr, 4)
                ir.target = -1L
                if (ops.isNotEmpty()) ir.operands.add(readReg(ops[0], insn.addr))
                ir.asmText = t
                instructions.add(ir)
                return
            }
            "ret" -> {
                val ir = IRInstruction(IROp.Return, insn.addr, 4)
                // 返回值在 x0
                val rv = regState[norm("x0")]
                if (rv != null) {
                    ir.operands.add(rv)
                    returnValue = rv
                }
                ir.asmText = t
                instructions.add(ir)
                return
            }
        }

        // 条件分支
        if (mn.startsWith("b.") || mn == "cbz" || mn == "cbnz" ||
            mn == "tbz" || mn == "tbnz"
        ) {
            val ir = IRInstruction(IROp.Branch, insn.addr, 4)
            ir.target = insn.target ?: 0L
            ir.asmText = t
            ir.condition = condOf(mn, ops, insn.addr, ir)
            instructions.add(ir)
            return
        }

        // ---------- 其他指令 ----------
        translateAlu(mn, ops, insn)
    }

    /** 组装条件表达式所需的操作数 */
    private fun condOf(
        mn: String,
        ops: List<String>,
        addr: Long,
        ir: IRInstruction
    ): String {
        when (mn) {
            "cbz" -> {
                if (ops.isNotEmpty()) ir.operands.add(readReg(ops[0], addr))
                return "eq0"
            }
            "cbnz" -> {
                if (ops.isNotEmpty()) ir.operands.add(readReg(ops[0], addr))
                return "ne0"
            }
            "tbz" -> {
                if (ops.isNotEmpty()) ir.operands.add(readReg(ops[0], addr))
                val bit = parseImm(ops.getOrNull(1) ?: "0") ?: 0L
                return "bit$bit@0"
            }
            "tbnz" -> {
                if (ops.isNotEmpty()) ir.operands.add(readReg(ops[0], addr))
                val bit = parseImm(ops.getOrNull(1) ?: "0") ?: 0L
                return "bit$bit@1"
            }
            else -> {
                // b.eq / b.ne ... 依赖上一条 cmp/subs 的结果
                val cond = mn.removePrefix("b.")
                return "flag:" + cond
            }
        }
    }

    // ------------------------------------------------------------
    // ALU / 访存
    // ------------------------------------------------------------

    private fun translateAlu(mn: String, ops: List<String>, insn: Arm64Disasm.Insn) {
        val addr = insn.addr
        if (ops.isEmpty()) return
        val dst = ops[0]

        // ---------- mov ----------
        if (mn == "mov") {
            val src = ops.getOrNull(1) ?: return
            val imm = parseImm(src)
            val value = if (imm != null) {
                val v = newValue(ValueKind.Const, widthToType(regWidth(dst)))
                v.constant = imm
                v.accessSize = regWidth(dst)
                v
            } else {
                readReg(src, addr)
            }
            writeReg(dst, value)
            emit(IROp.Move, addr, value, listOf(value), insn.text)
            return
        }

        // ---------- 加载 ----------
        if (mn == "ldr" || mn == "ldrb" || mn == "ldrh" || mn == "ldrsb" ||
            mn == "ldrsh" || mn == "ldrsw" || mn == "ldur"
        ) {
            val mem = ops.getOrNull(1)?.let { parseMem(it) }
            if (mem == null) {
                // ldr x0, 0x1234（字面量池）
                val lit = ops.getOrNull(1)?.let { parseImm(it) }
                if (lit != null) {
                    val g = globalVar(lit, regWidth(dst))
                    writeReg(dst, g)
                    emit(IROp.Load, addr, g, listOf(g), insn.text)
                }
                return
            }
            val size = widthOfLoad(mn, dst)
            val value = accessMemory(mem, size, addr, isStore = false)
            if (value != null) {
                writeReg(dst, value)
                emit(IROp.Load, addr, value, listOf(value), insn.text)
            }
            return
        }

        // ---------- 存储 ----------
        if (mn == "str" || mn == "strb" || mn == "strh" || mn == "stur") {
            val src = readReg(dst, addr)
            val mem = ops.getOrNull(1)?.let { parseMem(it) } ?: return
            val size = widthOfStore(mn, dst)
            val target = accessMemory(mem, size, addr, isStore = true)
            if (target != null) {
                emit(IROp.Store, addr, null, listOf(target, src), insn.text)
            }
            return
        }

        // ---------- 栈对 ----------
        if (mn == "stp" || mn == "ldp") {
            val r1 = ops.getOrNull(0)
            val r2 = ops.getOrNull(1)
            val memText = ops.getOrNull(2) ?: return
            val mem = parseMem(memText) ?: return
            val size = 8
            if (mn == "stp") {
                // 需要分别计算两个偏移
                val base1 = accessMemory(mem, size, addr, isStore = true)
                if (base1 != null && r1 != null) {
                    emit(IROp.Store, addr, null, listOf(base1, readReg(r1, addr)), insn.text)
                }
                val mem2 = mem.copy(offset = mem.offset + size)
                val base2 = accessMemory(mem2, size, addr, isStore = true)
                if (base2 != null && r2 != null) {
                    emit(IROp.Store, addr, null, listOf(base2, readReg(r2, addr)), insn.text)
                }
            } else {
                if (r1 != null) {
                    val v1 = accessMemory(mem, size, addr, isStore = false)
                    if (v1 != null) writeReg(r1, v1)
                }
                if (r2 != null) {
                    val v2 = accessMemory(mem.copy(offset = mem.offset + size), size, addr, isStore = false)
                    if (v2 != null) writeReg(r2, v2)
                }
                emit(IROp.Load, addr, null, emptyList(), insn.text)
            }
            return
        }

        // ---------- cmp / tst ----------
        if (mn == "cmp" || mn == "cmn" || mn == "tst") {
            val a = ops.getOrNull(0)?.let { readReg(it, addr) }
            val b = ops.getOrNull(1)?.let { op ->
                val imm = parseImm(op)
                if (imm != null) {
                    val v = newValue(ValueKind.Const, ValueType.Int64)
                    v.constant = imm
                    v
                } else readReg(op, addr)
            }
            val list = ArrayList<IRValue>()
            if (a != null) list.add(a)
            if (b != null) list.add(b)
            emit(IROp.Compare, addr, null, list, insn.text)
            return
        }

        // ---------- adrp / adr ----------
        if (mn == "adrp" || mn == "adr") {
            val imm = insn.target
            val v = if (imm != null) {
                val g = newValue(ValueKind.Const, ValueType.Pointer)
                g.constant = imm
                g.accessSize = 8
                g
            } else {
                // 拿不到目标地址时，退而读第二个操作数
                val src = ops.getOrNull(1)
                if (src.isNullOrBlank()) {
                    val g = newValue(ValueKind.Const, ValueType.Pointer)
                    g.accessSize = 8
                    g
                } else readReg(src, addr)
            }
            writeReg(dst, v)
            emit(IROp.Const, addr, v, listOf(v), insn.text)
            return
        }

        // ---------- 二元运算 ----------
        val op = when (mn) {
            "add", "adds" -> IROp.Add
            "sub", "subs" -> IROp.Sub
            "mul", "madd" -> IROp.Mul
            "sdiv", "udiv" -> IROp.Div
            "and", "ands" -> IROp.And
            "orr" -> IROp.Or
            "eor" -> IROp.Xor
            "lsl" -> IROp.Shl
            "lsr" -> IROp.Shl
            "asr" -> IROp.Sar
            else -> null
        }

        if (op != null) {
            val aRaw = ops.getOrNull(1)
            val bRaw = ops.getOrNull(2)
            if (aRaw == null || bRaw == null) return

            // 特例：add/sub 同时改 sp，视为栈指针调整，不产生数据值
            if (norm(dst) == "sp") {
                val ir = IRInstruction(op, addr, 4)
                ir.asmText = insn.text
                val imm = parseImm(bRaw)
                if (imm != null) {
                    val c = newValue(ValueKind.Const, ValueType.Int64)
                    c.constant = imm
                    ir.operands.add(c)
                } else {
                    ir.operands.add(readReg(bRaw, addr))
                }
                instructions.add(ir)
                return
            }

            val a = if (norm(aRaw) == "sp") {
                val v = newValue(ValueKind.Unknown, ValueType.Pointer, "sp")
                v.accessSize = 8
                v
            } else readReg(aRaw, addr)

            val imm = parseImm(bRaw)
            val b = if (imm != null) {
                val v = newValue(ValueKind.Const, widthToType(regWidth(dst)))
                v.constant = imm
                v.accessSize = regWidth(dst)
                v
            } else if (norm(bRaw) == "sp") {
                val v = newValue(ValueKind.Unknown, ValueType.Pointer, "sp")
                v.accessSize = 8
                v
            } else readReg(bRaw, addr)

            val r = temp(widthToType(regWidth(dst)), regWidth(dst), addr)
            writeReg(dst, r)
            emit(op, addr, r, listOf(a, b), insn.text)
            return
        }

        // ---------- 其他（含条件选择、移位扩展等）----------
        if (ops.size >= 2) {
            val a = readReg(ops[1], addr)
            val r = temp(widthToType(regWidth(dst)), regWidth(dst), addr)
            writeReg(dst, r)
            val b = if (ops.size >= 3) {
                val imm = parseImm(ops[2])
                if (imm != null) {
                    val v = newValue(ValueKind.Const, ValueType.Int64)
                    v.constant = imm
                    v
                } else readReg(ops[2], addr)
            } else null
            val list = ArrayList<IRValue>()
            list.add(a)
            if (b != null) list.add(b)
            emit(IROp.Move, addr, r, list, insn.text)
        }
    }

    private fun widthOfLoad(mn: String, dst: String): Int = when (mn) {
        "ldrb", "ldrsb" -> 1
        "ldrh", "ldrsh" -> 2
        "ldrsw" -> 4
        else -> regWidth(dst)
    }

    private fun widthOfStore(mn: String, src: String): Int = when (mn) {
        "strb" -> 1
        "strh" -> 2
        else -> regWidth(src)
    }

    /**
     * 内存访问解析：
     *   sp + offset  → 栈变量
     *   其他 base     → 取 base 值，偏移作为常量（供后续指针分析）
     */
    private fun accessMemory(
        mem: MemOperand,
        size: Int,
        addr: Long,
        isStore: Boolean
    ): IRValue? {
        // 1) 栈变量
        if (mem.isSP && mem.indexReg == null) {
            val v = stackVar(mem.offset, size)
            if (isStore) v.uses.add(addr)
            return v
        }

        // 2) 其他基址：取基址寄存器值
        val base = if (mem.isSP) {
            val spv = newValue(ValueKind.Unknown, ValueType.Pointer, "sp")
            spv.accessSize = 8
            spv
        } else readReg(mem.base, addr)

        // 如果是常量地址（adrp 出来的），直接当全局变量
        if (base.isConst && mem.offset >= 0) {
            return globalVar(base.constant + mem.offset, size)
        }

        // 其他情况：作为带偏移的内存访问值
        val v = newValue(ValueKind.Unknown, widthToType(size))
        v.accessSize = size
        v.uses.add(addr)
        return v
    }

    private fun emit(
        op: IROp,
        addr: Long,
        result: IRValue?,
        operands: List<IRValue>,
        asm: String
    ) {
        val ir = IRInstruction(op, addr, 4)
        ir.result = result
        ir.operands.addAll(operands)
        ir.asmText = asm
        instructions.add(ir)
    }
}
