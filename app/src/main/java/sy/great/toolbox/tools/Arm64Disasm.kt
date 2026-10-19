package sy.great.toolbox.tools

/**
 * ARM64 (AArch64) 反汇编器。
 *
 * 自己实现指令解码，ARM64 指令定长 4 字节。
 * 覆盖 A64 常用指令集：分支、访存、算术、逻辑、比较、系统。
 *
 * 不依赖任何外部反汇编库。
 */
object Arm64Disasm {

    /** 一条已解码的指令 */
    data class Insn(
        val addr: Long,
        val word: Int,
        val text: String,
        val mnemonic: String,
        val target: Long?,      // 分支目标（若有）
        val isBranch: Boolean,
        val isCondBranch: Boolean,
        val isCall: Boolean,
        val isReturn: Boolean,
        val isIndirect: Boolean,  // BR/BLR 寄存器跳转
        /**
         * 条件分支的真实谓词（如 "eq" / "ne" / "lt" / "hi"）。
         * 仅当 isCondBranch 为 true 时有值：
         *   b.<cond>  → 该条件名（eq/ne/cs/cc/mi/pl/vs/vc/hi/ls/ge/lt/gt/le/al/nv）
         *   cbz/cbnz  → "eq"（x==0 时跳）/ "ne"（x!=0 时跳）
         *   tbz/tbnz  → "eq"（该位==0 时跳）/ "ne"（该位!=0 时跳）
         * 无条件跳转与其它指令为 null。
         */
        val cond: String? = null
    ) {
        val hex: String get() = String.format("%08X", word)

        /**
         * 无条件跳转（b target，非 bl）。
         *
         * 注意：不要用它表示 ret —— ret 用 isReturn 表示。
         */
        val isUncondBranch: Boolean
            get() = isBranch && !isCondBranch && !isCall && !isIndirect
    }

    // ============================================================
    // 结构化操作数（从已生成的 text 反解析）
    // ============================================================

    /**
     * 一条指令的结构化操作数。
     *
     * 不从 word 里重新解码，而是从 decode 已生成的 text 反解析，
     * 好处：不改动 decode 主流程，不会影响现有反汇编结果。
     *
     * 字段含义：
     *   regs    出现的寄存器号（按书写顺序）；sp=31，xzr/wzr=-2
     *   imm     出现的立即数（第一个）
     *   memBase 内存操作数的基址寄存器（[xN, ...] 里的 N）
     *   memIndex 内存操作数的索引寄存器（[xN, xM, lsl #k] 里的 M）
     *   memScale 索引的移位量（lsl #k 的 k）
     */
    data class Operands(
        val regs: List<Int> = emptyList(),
        val imm: Long? = null,
        val memBase: Int? = null,
        val memIndex: Int? = null,
        val memScale: Int = 0,
        /** 是否是 “:lo12:” 形式的地址低位 */
        val hasLo12: Boolean = false,
    )

    /**
     * 从指令文本解析操作数。
     *
     * 例：
     *   "add x1, x1, #0x10"        → regs=[1,1], imm=16
     *   "ldrsw x2, [x1, x0, lsl #2]" → regs=[2,1,0], memBase=1, memIndex=0, memScale=2
     *   "adrp x1, 0x1000"          → regs=[1], imm=0x1000
     */
    fun parseOperands(insn: Insn): Operands {
        val t = insn.text
        val regs = ArrayList<Int>()
        var imm: Long? = null
        var memBase: Int? = null
        var memIndex: Int? = null
        var memScale = 0
        var hasLo12 = t.contains(":lo12:")

        // ---- 寄存器 ----
        // 匹配 x0-w30 / sp / xzr / wzr
        val regRe = Regex("\\b([xw])(\\d{1,2})\\b|\\b(sp|xzr|wzr|lr|fp)\\b")
        for (m in regRe.findAll(t)) {
            val prefix = m.groupValues[1]
            val num = m.groupValues[2]
            val special = m.groupValues[3]
            if (special.isNotEmpty()) {
                if (special == "sp") regs.add(31)
                else if (special == "lr") regs.add(30)
                else if (special == "fp") regs.add(29)
                else regs.add(-2)   // xzr / wzr
            } else if (num.isNotEmpty()) {
                num.toIntOrNull()?.let { regs.add(it) }
            }
        }

        // ---- 内存操作数 [base, index, lsl #k] ----
        val memRe = Regex("\\[\\s*([xw]\\d{1,2}|sp)\\s*(,\\s*([xw]\\d{1,2})\\s*(,\\s*lsl\\s*#(\\d+))?)?\\s*\\]")
        memRe.find(t)?.let { m ->
            fun regIdx(s: String): Int? = when {
                s == "sp" -> 31
                s.length >= 2 -> s.substring(1).toIntOrNull()
                else -> null
            }
            memBase = regIdx(m.groupValues[1])
            val idx = m.groupValues[3]
            if (idx.isNotEmpty()) memIndex = regIdx(idx)
            val sc = m.groupValues[5]
            if (sc.isNotEmpty()) memScale = sc.toIntOrNull() ?: 0
        }

        // ---- 立即数 ----
        // 优先 #0x.. / #123；其次 0x.. / 纯数字（避开寄存器名中的数字）
        Regex("#(-?0x[0-9a-fA-F]+|-?\\d+)").find(t)?.let { m ->
            imm = parseNum(m.groupValues[1])
        }
        if (imm == null) {
            Regex("(?<!\\[\\s*xw])\\b(0x[0-9a-fA-F]{2,})\\b").find(t)?.let { m ->
                imm = parseNum(m.groupValues[1])
            }
        }

        return Operands(
            regs = regs,
            imm = imm,
            memBase = memBase,
            memIndex = memIndex,
            memScale = memScale,
            hasLo12 = hasLo12
        )
    }

    private fun parseNum(s: String): Long? = try {
        when {
            s.startsWith("-0x", true) -> -java.lang.Long.parseLong(s.substring(3), 16)
            s.startsWith("0x", true) -> java.lang.Long.parseLong(s.substring(2), 16)
            else -> s.toLongOrNull()
        }
    } catch (_: Throwable) {
        null
    }

    private val REGS = arrayOf(
        "x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7",
        "x8", "x9", "x10", "x11", "x12", "x13", "x14", "x15",
        "x16", "x17", "x18", "x19", "x20", "x21", "x22", "x23",
        "x24", "x25", "x26", "x27", "x28", "x29", "x30", "sp"
    )

    private val CREGS = arrayOf(
        "w0", "w1", "w2", "w3", "w4", "w5", "w6", "w7",
        "w8", "w9", "w10", "w11", "w12", "w13", "w14", "w15",
        "w16", "w17", "w18", "w19", "w20", "w21", "w22", "w23",
        "w24", "w25", "w26", "w27", "w28", "w29", "w30", "wsp"
    )

    private fun r(n: Int) = REGS[n and 31]
    private fun rw(n: Int) = CREGS[n and 31]

    /** 解码一句。bytes 为整个代码段，off 为段内偏移，baseAddr 为段虚拟地址 */
    fun decode(bytes: ByteArray, off: Int, baseAddr: Long): Insn? {
        if (off < 0 || off + 4 > bytes.size) return null
        val w = ((bytes[off].toInt() and 0xFF)) or
                ((bytes[off + 1].toInt() and 0xFF) shl 8) or
                ((bytes[off + 2].toInt() and 0xFF) shl 16) or
                ((bytes[off + 3].toInt() and 0xFF) shl 24)
        val addr = baseAddr + off
        return decodeWord(w, addr)
    }

    fun decodeWord(w: Int, addr: Long): Insn {
        // ---- 分支类 ----

        // B / BL (imm26)
        if ((w and 0x7C000000) == 0x14000000) {
            val imm = signExtend(w and 0x03FFFFFF, 26) shl 2
            val isLink = (w and 0x80000000.toInt()) != 0
            val tgt = addr + imm
            val mn = if (isLink) "bl" else "b"
            return Insn(addr, w, "$mn 0x${tgt.toString(16)}", mn, tgt, true, false, isLink, false, false)
        }

        // B.cond (imm19, cond)
        if ((w and 0x7E000000) == 0x54000000) {
            val imm = signExtend(w and 0x7FFFF, 19) shl 2
            val cond = w and 0xF
            val condTxt = condName(cond)
            val mn = "b." + condTxt
            val tgt = addr + imm
            // cond == 0b1110 (AL) 或 0b1111 (NV)：在 AArch64 中两者均为无条件编码。
            //
            // 按 Armv8-A 规范，B.cond 对 cond=1110/1111 都按“总是跳转”处理
            // （1111 是保留编码，行为等同 AL，并非“永不成立”）。
            // 因此它没有真实的条件分支：若仍按 isCondBranch 处理，
            // CFG 会拆出 T/F 两条边并标上 T，与实际控制流不符。
            val always = (cond == 14 || cond == 15)
            return Insn(
                addr, w, "$mn 0x${tgt.toString(16)}", mn, tgt,
                true, !always, false, false, false, if (always) null else condTxt
            )
        }

        // CBZ / CBNZ (imm19, Rt)
        if ((w and 0x7E000000) == 0x34000000) {
            val sf = (w ushr 31) and 1
            val imm = signExtend(w and 0x7FFFF, 19) shl 2
            val rt = (w ushr 0) and 31
            val nz = (w and 0x01000000) != 0
            val mn = if (nz) "cbnz" else "cbz"
            val tgt = addr + imm
            val reg = if (sf == 1) r(rt) else rw(rt)
            // cbz  → 等于 0 时跳（eq）；cbnz → 不等于 0 时跳（ne）
            val condTxt = if (nz) "ne" else "eq"
            return Insn(
                addr, w, "$mn $reg, 0x${tgt.toString(16)}", mn, tgt,
                true, true, false, false, false, condTxt
            )
        }

        // TBZ / TBNZ (imm14, bit, Rt)
        if ((w and 0x7E000000) == 0x36000000) {
            val imm = signExtend(w and 0x3FFF, 14) shl 2
            val bitLow = (w ushr 19) and 0x1F
            val bitHigh = (w ushr 31) and 1
            val bit = (bitHigh shl 5) or bitLow
            val rt = w and 31
            val nz = (w and 0x01000000) != 0
            val mn = if (nz) "tbnz" else "tbz"
            val tgt = addr + imm
            // tbz  → 该位为 0 时跳（eq）；tbnz → 该位为 1 时跳（ne）
            val condTxt = if (nz) "ne" else "eq"
            return Insn(
                addr, w, "$mn ${r(rt)}, #$bit, 0x${tgt.toString(16)}", mn, tgt,
                true, true, false, false, false, condTxt
            )
        }

        // BR / BLR / RET 等 (0xD6xxxxxx)
        if ((w and 0xFE000000.toInt()) == 0xD6000000.toInt()) {
            val opc = (w ushr 21) and 0xF
            val rn = (w ushr 5) and 31
            when (opc) {
                0 -> return Insn(addr, w, "br ${r(rn)}", "br", null, true, false, false, false, true)
                1 -> return Insn(addr, w, "blr ${r(rn)}", "blr", null, true, false, true, false, true)
                2 -> return Insn(addr, w, "ret ${r(rn)}", "ret", null, false, false, false, true, false)
                4 -> return Insn(addr, w, "eret", "eret", null, false, false, false, true, false)
                5 -> return Insn(addr, w, "drps", "drps", null, false, false, false, true, false)
            }
        }

        // ---- 算术 / 逻辑（ALU 寄存器与立即数） ----

        // ADD/SUB 立即数 (sf, op, S, sh, imm12, Rn, Rd)
        if ((w and 0x1F000000) == 0x11000000) {
            val sf = (w ushr 31) and 1
            val isSub = (w ushr 30) and 1 == 1
            val setFlags = (w ushr 29) and 1 == 1
            val shift = (w ushr 22) and 3
            val imm12 = (w ushr 10) and 0xFFF
            val rn = (w ushr 5) and 31
            val rd = w and 31
            val imm = if (shift == 1) (imm12 shl 12).toLong() else imm12.toLong()
            val mn = (if (isSub) "sub" else "add") + (if (setFlags) "s" else "")
            val dst = if (sf == 1) r(rd) else rw(rd)
            val src = if (sf == 1) r(rn) else rw(rn)
            return Insn(addr, w, "$mn $dst, $src, #$imm", mn, null, false, false, false, false, false)
        }

        // ADD/SUB 寄存器
        if ((w and 0x1F200000) == 0x0B000000) {
            val sf = (w ushr 31) and 1
            val isSub = (w ushr 30) and 1 == 1
            val setFlags = (w ushr 29) and 1 == 1
            val rm = (w ushr 16) and 31
            val rn = (w ushr 5) and 31
            val rd = w and 31
            val mn = (if (isSub) "sub" else "add") + (if (setFlags) "s" else "")
            val d = if (sf == 1) r(rd) else rw(rd)
            val a = if (sf == 1) r(rn) else rw(rn)
            val b = if (sf == 1) r(rm) else rw(rm)
            return Insn(addr, w, "$mn $d, $a, $b", mn, null, false, false, false, false, false)
        }

        // ADDS/SUBS 带进位 (ADC/SBC)
        if ((w and 0x1FE00000) == 0x1A000000) {
            val sf = (w ushr 31) and 1
            val op = (w ushr 30) and 1
            val s = (w ushr 29) and 1
            val rm = (w ushr 16) and 31
            val rn = (w ushr 5) and 31
            val rd = w and 31
            val mn = when {
                op == 1 && s == 0 -> "adc"
                op == 1 && s == 1 -> "adcs"
                op == 0 && s == 0 -> "sbc"
                else -> "sbcs"
            }
            val d = if (sf == 1) r(rd) else rw(rd)
            val a = if (sf == 1) r(rn) else rw(rn)
            val b = if (sf == 1) r(rm) else rw(rm)
            return Insn(addr, w, "$mn $d, $a, $b", mn, null, false, false, false, false, false)
        }

        // 逻辑移位寄存器 (AND/ORR/EOR/ANDS 等, 简化为常见形式)
        if ((w and 0x1F000000) == 0x0A000000) {
            val sf = (w ushr 31) and 1
            val opc = (w ushr 29) and 3
            val rm = (w ushr 16) and 31
            val rn = (w ushr 5) and 31
            val rd = w and 31
            val mn = when (opc) {
                0 -> "and"
                1 -> "orr"
                2 -> "eor"
                else -> "ands"
            }
            val d = if (sf == 1) r(rd) else rw(rd)
            val a = if (sf == 1) r(rn) else rw(rn)
            val b = if (sf == 1) r(rm) else rw(rm)
            return Insn(addr, w, "$mn $d, $a, $b", mn, null, false, false, false, false, false)
        }

        // ORR/AND/EOR 立即数
        if ((w and 0x1F800000) == 0x32000000) {
            val sf = (w ushr 31) and 1
            val opc = (w ushr 29) and 3
            val n = (w ushr 22) and 1
            val immr = (w ushr 16) and 63
            val imms = (w ushr 10) and 63
            val rn = (w ushr 5) and 31
            val rd = w and 31
            val mn = when (opc) {
                0 -> "and"
                1 -> "orr"
                2 -> "eor"
                else -> "ands"
            }
            val d = if (sf == 1) r(rd) else rw(rd)
            val a = if (sf == 1) r(rn) else rw(rn)
            val valN = decodeBitmask(n, imms, sf)
            return Insn(addr, w, "$mn $d, $a, #0x${java.lang.Long.toHexString(valN)}", mn, null, false, false, false, false, false)
        }
        // MOV (寄存器别名 ORR Rd, XZR, Rm)
        if ((w and 0x7FE0FFE0) == 0x2A0003E0 || (w and 0x7FE0FFE0) == 0x52800000 ||
            (w and 0x7FE0FFE0) == 0x320003E0) {
            val sf = (w ushr 31) and 1
            val rm = (w ushr 16) and 31
            val rd = w and 31
            val d = if (sf == 1) r(rd) else rw(rd)
            val m = if (sf == 1) r(rm) else rw(rm)
            return Insn(addr, w, "mov $d, $m", "mov", null, false, false, false, false, false)
        }

        // MOVZ / MOVN / MOVK
        if ((w and 0x1F800000) == 0x12800000) {
            val sf = (w ushr 31) and 1
            val opc = (w ushr 29) and 3
            val hw = (w ushr 21) and 3
            val imm16 = (w ushr 5) and 0xFFFF
            val rd = w and 31
            val shift = hw * 16
            val mn = when (opc) {
                0 -> "movn"
                2 -> "movz"
                3 -> "movk"
                else -> "mov?"
            }
            val d = if (sf == 1) r(rd) else rw(rd)
            val suffix = if (shift == 0) "" else ", lsl #$shift"
            return Insn(addr, w, "$mn $d, #0x${Integer.toHexString(imm16)}$suffix", mn, null, false, false, false, false, false)
        }

        // CMP (SUBS XZR, Rn, Rm/imm)
        if ((w and 0x7F00001F) == 0x6B00001F) {
            val sf = (w ushr 31) and 1
            val rm = (w ushr 16) and 31
            val rn = (w ushr 5) and 31
            val a = if (sf == 1) r(rn) else rw(rn)
            val b = if (sf == 1) r(rm) else rw(rm)
            return Insn(addr, w, "cmp $a, $b", "cmp", null, false, false, false, false, false)
        }
        if ((w and 0x7F00001F) == 0x7100001F) {
            val sf = (w ushr 31) and 1
            val imm12 = (w ushr 10) and 0xFFF
            val rn = (w ushr 5) and 31
            val a = if (sf == 1) r(rn) else rw(rn)
            return Insn(addr, w, "cmp $a, #$imm12", "cmp", null, false, false, false, false, false)
        }

        // ---- 访存 ----

        // LDR/STR 无偏移 / 后索引 / 前索引 (unsigned offset 形式)
        if ((w and 0x3B000000) == 0x39000000) {
            val size = (w ushr 30) and 3
            val opc = (w ushr 22) and 3
            val imm12 = (w ushr 10) and 0xFFF
            val rn = (w ushr 5) and 31
            val rt = w and 31
            val scale = size
            val off = (imm12.toLong() shl scale)
            val isLoad = opc != 0
            val regName = when (size) {
                0 -> "b"
                1 -> "h"
                2 -> "w"
                else -> "x"
            }
            val mn = (if (isLoad) "ldr" else "str") + regName
            val rtName = if (size == 3) r(rt) else if (size == 2) rw(rt) else rw(rt)
            val mem = if (off == 0L) "[${r(rn)}]" else "[${r(rn)}, #$off]"
            return Insn(addr, w, "$mn $rtName, $mem", mn, null, false, false, false, false, false)
        }

        // LDR/STR 立即数（signed offset，带回写后索引）
        if ((w and 0x3B200000) == 0x38000000) {
            val size = (w ushr 30) and 3
            val opc = (w ushr 22) and 3
            val imm9 = signExtend((w ushr 12) and 0x1FF, 9)
            val mode = (w ushr 10) and 3
            val rn = (w ushr 5) and 31
            val rt = w and 31
            val isLoad = opc != 0
            val regName = when (size) {
                0 -> "b"
                1 -> "h"
                2 -> "w"
                else -> "x"
            }
            val mn = (if (isLoad) "ldr" else "str") + regName
            val rtName = if (size == 3) r(rt) else rw(rt)
            val mem = when (mode) {
                1 -> "[${r(rn)}], #$imm9"      // post-index
                3 -> "[${r(rn)}, #$imm9]!"     // pre-index
                else -> "[${r(rn)}, #$imm9]"
            }
            return Insn(addr, w, "$mn $rtName, $mem", mn, null, false, false, false, false, false)
        }

        // LDR/STR 寄存器偏移
        if ((w and 0x3B200C00) == 0x38200800) {
            val size = (w ushr 30) and 3
            val opc = (w ushr 22) and 3
            val rm = (w ushr 16) and 31
            val rn = (w ushr 5) and 31
            val rt = w and 31
            val isLoad = opc != 0
            val regName = when (size) {
                0 -> "b"
                1 -> "h"
                2 -> "w"
                else -> "x"
            }
            val mn = (if (isLoad) "ldr" else "str") + regName
            val rtName = if (size == 3) r(rt) else rw(rt)
            return Insn(addr, w, "$mn $rtName, [${r(rn)}, ${r(rm)}]", mn, null, false, false, false, false, false)
        }

        // LDR 字面量 (PC 相对)
        if ((w and 0x3B000000) == 0x18000000) {
            val opc = (w ushr 30) and 3
            val imm19 = signExtend((w ushr 5) and 0x7FFFF, 19) shl 2
            val rt = w and 31
            val tgt = addr + imm19
            val mn = when (opc) {
                0 -> "ldr"
                1 -> "ldr"
                2 -> "ldrsw"
                else -> "prfm"
            }
            return Insn(addr, w, "$mn ${r(rt)}, 0x${tgt.toString(16)}", mn, tgt, false, false, false, false, false)
        }

        // LDP / STP (前/后索引)
        if ((w and 0x3A000000) == 0x28000000) {
            val opc = (w ushr 30) and 3
            val isLoad = (opc and 1) != 0
            val imm7 = signExtend((w ushr 15) and 0x7F, 7)
            val rt2 = (w ushr 10) and 31
            val rn = (w ushr 5) and 31
            val rt = w and 31
            val mode = (w ushr 23) and 3
            val scale = if (opc == 2) 3 else 2
            val off = imm7 shl scale
            val mn = if (isLoad) "ldp" else "stp"
            val mem = when (mode) {
                1 -> "[${r(rn)}], #$off"
                3 -> "[${r(rn)}, #$off]!"
                else -> "[${r(rn)}, #$off]"
            }
            return Insn(addr, w, "$mn ${r(rt)}, ${r(rt2)}, $mem", mn, null, false, false, false, false, false)
        }

        // ADRP (PAGE 地址)
        if ((w and 0x9F000000.toInt()) == 0x90000000.toInt()) {
            val immlo = (w ushr 29) and 3
            val immhi = (w ushr 5) and 0x7FFFF
            val rd = w and 31
            val imm = (signExtend((immhi shl 2) or immlo, 21) shl 12)
            val base = (addr and 0xFFFFFFFFFFFFF000uL.toLong()) + imm
            return Insn(addr, w, "adrp ${r(rd)}, 0x${base.toString(16)}", "adrp", base, false, false, false, false, false)
        }

        // ============================================================
        // ADD/SUB (immediate)
        //
        // 编码：sf 0 1 0 1 0 0 1 0 sh imm12 Rn Rd
        //   (w & 0x1F000000) == 0x11000000  → 基础形式
        //   bit30 = 1 → 64 位；bit30 = 0 → 32 位
        //   bit30 同时区分 add / sub
        //
        // 旧实现在这里直接输出 "add (imm)"，丢失了 Rd/Rn/imm，
        // 导致 IrTranslator 拿不到任何操作数，整条 ADD/SUB 被丢弃 ——
        // 这是伪 C 里出现大片未定义 uN 的主因。
        // ============================================================
        if ((w and 0x7F000000) == 0x31000000) {
            val sf = (w ushr 31) and 1                  // 1 = 64 位
            val isSub = ((w ushr 30) and 1) == 1
            val setFlags = ((w ushr 29) and 1) == 1
            val sh = (w ushr 22) and 1
            val imm12 = (w ushr 10) and 0xFFF
            val rn = (w ushr 5) and 31
            val rd = w and 31

            var immVal = imm12.toLong()
            if (sh == 1) immVal = immVal shl 12

            val is64 = sf == 1
            // sp 在 32 位下写作 wsp；Rd/Rn 为 31 时不一定是 sp（取决于上下文），
            // 但 ADD/SUB 立即数里 31 一定是 sp/xzr 语义，这里统一当 sp 处理。
            fun regName(n: Int): String = when {
                n == 31 -> if (is64) "sp" else "wsp"
                is64 -> r(n)
                else -> rw(n)
            }

            val mn = if (isSub) (if (setFlags) "subs" else "sub")
                     else (if (setFlags) "adds" else "add")
            val text = "$mn ${regName(rd)}, ${regName(rn)}, #0x${immVal.toString(16)}"
            return Insn(addr, w, text, mn, null, false, false, false, false, false)
        }

        // ============================================================
        // MOVZ / MOVN / MOVK（宽立即数装载）
        //
        // 编码：sf 10 100101 hw imm16 Rd
        //   opc(bit30:29) = 10 → MOVN，11 → MOVZ，01/00 → 保留
        //   MOVK 见下方单独处理（opc=11 且 hw 复用，靠 bit31..23 区分）
        //
        // 旧实现没有这一组，导致所有 mov w0, #1 / movz x0, #0x1b 落到 .word，
        // 常量全部丢失。
        // ============================================================
        // MOVZ: 0xD2800000 类（64 位） / 0x52800000 类（32 位）
        if ((w and 0x7F800000) == 0x52800000 || (w and 0x7F800000) == 0x12800000) {
            val sf = (w ushr 31) and 1
            val isMoveNot = ((w ushr 30) and 1) == 0     // opc=10 → MOVN
            val hw = (w ushr 21) and 3
            val imm16 = (w ushr 5) and 0xFFFF
            val rd = w and 31
            val shift = hw * 16
            // 注意：不能用 val 作变量名，它是 Kotlin 关键字
            var immValue = imm16.toLong() shl shift
            if (isMoveNot) immValue = immValue.inv()
            if (sf == 0) immValue = immValue and 0xFFFFFFFFL

            val is64 = sf == 1
            val rdName = if (is64) r(rd) else rw(rd)
            // 统一输出最终值，让 IrTranslator 不需要再自己拼。
            // MOVN 输出已经取反后的值（例如 0xffffffffffffffff），
            // 这对伪 C 里的 -1 / 掩码识别更有用。
            val text = if (isMoveNot || shift != 0) {
                val mn = if (isMoveNot) "movn" else "movz"
                "$mn $rdName, #0x${if (immValue < 0) java.lang.Long.toHexString(immValue) else immValue.toString(16)}"
            } else {
                "mov $rdName, #0x${imm16.toString(16)}"
            }
            val mnFinal = if (!isMoveNot && shift == 0) "mov" else if (isMoveNot) "movn" else "movz"
            return Insn(addr, w, text, mnFinal, null, false, false, false, false, false)
        }

        // MOVK: sf 111 100101 hw imm16 Rd  → 0xF2800000 类
        if ((w and 0x7F800000) == 0x72800000) {
            val sf = (w ushr 31) and 1
            val hw = (w ushr 21) and 3
            val imm16 = (w ushr 5) and 0xFFFF
            val rd = w and 31
            val shift = hw * 16
            val rdName = if (sf == 1) r(rd) else rw(rd)
            val text = "movk $rdName, #0x${imm16.toString(16)}" + if (shift != 0) ", lsl #$shift" else ""
            return Insn(addr, w, text, "movk", null, false, false, false, false, false)
        }

        // ============================================================
        // 逻辑立即数：AND / ORR / EOR / ANDS（bitmask immediate）
        // 编码：sf opc 100100 N immr imms Rn Rd
        //   基础形式 (w & 0x1F800000) == 0x12000000
        // ============================================================
        if ((w and 0x1F800000) == 0x12000000) {
            val sf = (w ushr 31) and 1
            val opc = (w ushr 29) and 3
            val n = (w ushr 22) and 1
            val immr = (w ushr 16) and 0x3F
            val imms = (w ushr 10) and 0x3F
            val rn = (w ushr 5) and 31
            val rd = w and 31

            val bitmask = decodeBitmask(n, imms, sf)
            val is64 = sf == 1
            fun regName(x: Int): String = when {
                x == 31 -> if (is64) "sp" else "wsp"
                is64 -> r(x)
                else -> rw(x)
            }
            val mn = when (opc) {
                0 -> "and"
                1 -> "orr"
                2 -> "eor"
                else -> "ands"
            }
            // ORR Rd, XZR, #imm 等价于 mov Rd, #imm
            val text = if (opc == 1 && rn == 31) {
                "mov ${regName(rd)}, #0x${bitmask.toString(16)}"
            } else {
                "$mn ${regName(rd)}, ${regName(rn)}, #0x${bitmask.toString(16)}"
            }
            return Insn(addr, w, text, if (opc == 1 && rn == 31) "mov" else mn,
                null, false, false, false, false, false)
        }

        // ============================================================
        // UBFM / SBFM / BFM（移位与位域操作）
        // 编码：sf opc 100110 N immr imms Rn Rd
        //   基础形式 (w & 0x1F800000) == 0x13000000
        // lsl / lsr / asr 立即数都是 UBFM/SBFM 的伪指令
        // ============================================================
        if ((w and 0x1F800000) == 0x13000000) {
            val sf = (w ushr 31) and 1
            val opc = (w ushr 29) and 3
            val n = (w ushr 22) and 1
            val immr = (w ushr 16) and 0x3F
            val imms = (w ushr 10) and 0x3F
            val rn = (w ushr 5) and 31
            val rd = w and 31

            val is64 = sf == 1
            val bits = if (is64) 64 else 32
            fun regName(x: Int): String = if (is64) r(x) else rw(x)

            val rdName = regName(rd)
            val rnName = regName(rn)

            // 识别 lsl / lsr / asr 伪指令
            if (opc == 2 && n == sf) {
                // UBFM：优先识别 lsl / lsr
                if (imms + 1 == immr) {
                    // lsl #immr
                    val sh = (bits - immr) % bits
                    return Insn(addr, w, "lsl $rdName, $rnName, #$sh", "lsl",
                        null, false, false, false, false, false)
                }
                if (imms == bits - 1) {
                    // lsr #immr
                    return Insn(addr, w, "lsr $rdName, $rnName, #$immr", "lsr",
                        null, false, false, false, false, false)
                }
            }
            if (opc == 0 && n == sf && imms == bits - 1) {
                // SBFM 且 imms 为全 1：asr #immr
                return Insn(addr, w, "asr $rdName, $rnName, #$immr", "asr",
                    null, false, false, false, false, false)
            }

            val mn = when (opc) {
                0 -> "sbfm"
                1 -> "bfm"
                2 -> "ubfm"
                else -> "sbfiz"
            }
            return Insn(addr, w, "$mn $rdName, $rnName, #$immr, #$imms", mn,
                null, false, false, false, false, false)
        }

        // ============================================================
        // ADD / SUB（寄存器形式，可选移位）
        // 编码：sf op S 01011 shift imm6 Rm Rn Rd
        //   基础形式 (w & 0x1F200000) == 0x0B000000
        // ============================================================
        if ((w and 0x1F200000) == 0x0B000000) {
            val sf = (w ushr 31) and 1
            val isSub = ((w ushr 30) and 1) == 1
            val setFlags = ((w ushr 29) and 1) == 1
            val shiftType = (w ushr 22) and 3
            val imm6 = (w ushr 10) and 0x3F
            val rm = (w ushr 16) and 31
            val rn = (w ushr 5) and 31
            val rd = w and 31

            val is64 = sf == 1
            fun regName(x: Int): String = when {
                x == 31 -> if (is64) "sp" else "wsp"
                is64 -> r(x)
                else -> rw(x)
            }
            val mn = if (isSub) (if (setFlags) "subs" else "sub")
                     else (if (setFlags) "adds" else "add")
            val shName = when (shiftType) {
                0 -> ""
                1 -> ", lsr #$imm6"
                2 -> ", asr #$imm6"
                else -> ", ror #$imm6"
            }
            val text = "$mn ${regName(rd)}, ${regName(rn)}, ${regName(rm)}$shName"
            return Insn(addr, w, text, mn, null, false, false, false, false, false)
        }

        // ============================================================
        // MADD / MSUB（乘法累加）
        // 编码：sf 00 11011 000 Rm o0 Ra Rn Rd
        //   基础形式 (w & 0x1F000000) == 0x1B000000
        // ============================================================
        if ((w and 0x1F000000) == 0x1B000000) {
            val sf = (w ushr 31) and 1
            val opcode = (w ushr 21) and 7
            val rm = (w ushr 16) and 31
            val isSubAcc = ((w ushr 15) and 1) == 1
            val ra = (w ushr 10) and 31
            val rn = (w ushr 5) and 31
            val rd = w and 31
            val is64 = sf == 1
            fun regName(x: Int): String = if (is64) r(x) else rw(x)

            // opcode 0 = MADD/MSUB
            if (opcode == 0 || opcode == 1) {
                val mn = if (isSubAcc || opcode == 1) "msub" else "madd"
                val text = "$mn ${regName(rd)}, ${regName(rn)}, ${regName(rm)}, ${regName(ra)}"
                return Insn(addr, w, text, mn, null, false, false, false, false, false)
            }
            // 其他乘法变体（SMULH/UMULH 等）如实输出
            val mn = "mul$opcode"
            val text = "$mn ${regName(rd)}, ${regName(rn)}, ${regName(rm)}"
            return Insn(addr, w, text, mn, null, false, false, false, false, false)
        }

        // ============================================================
        // CSEL / CSINC / CSINV / CSNEG（条件选择）
        // 编码：sf op S 11010100 Rm cond 00 Rn Rd
        //   基础形式 (w & 0x1FE00000) == 0x1A800000
        // ============================================================
        if ((w and 0x1FE00000) == 0x1A800000) {
            val sf = (w ushr 31) and 1
            val op = (w ushr 30) and 1
            val rm = (w ushr 16) and 31
            val cond = (w ushr 12) and 0xF
            val rn = (w ushr 5) and 31
            val rd = w and 31
            val is64 = sf == 1
            fun regName(x: Int): String = if (is64) r(x) else rw(x)
            val mn = if (op == 0) "csel" else "csinc"
            val condName = condName(cond)
            val text = "$mn ${regName(rd)}, ${regName(rn)}, ${regName(rm)}, $condName"
            return Insn(addr, w, text, mn, null, false, false, false, false, false)
        }

        // NOP（真正的 NOP 编码是 0xD503201F；它不跳转、不设标志）
        if (w == 0xD503201F.toInt()) return Insn(addr, w, "nop", "nop", null, false, false, false, false, false)

        // 未知指令，按 4 字节字输出
        return Insn(
            addr, w,
            ".word 0x${String.format("%08X", w)}",
            ".word", null, false, false, false, false, false
        )
    }

    private fun condName(c: Int): String = when (c) {
        0 -> "eq"
        1 -> "ne"
        2 -> "cs"
        3 -> "cc"
        4 -> "mi"
        5 -> "pl"
        6 -> "vs"
        7 -> "vc"
        8 -> "hi"
        9 -> "ls"
        10 -> "ge"
        11 -> "lt"
        12 -> "gt"
        13 -> "le"
        14 -> "al"
        else -> "nv"
    }

    private fun signExtend(value: Int, bits: Int): Long {
        val shift = 64 - bits
        return (value.toLong() shl shift) shr shift
    }

    /**
     * ARM64 逻辑立即数（bitmask immediate）解码。
     * 按 ARM ARM 的 DecodeBitMasks 算法实现。
     */
    private fun decodeBitmask(immN: Int, imms: Int, sf: Int): Long {
        val size = if (sf == 1) 64 else 32
        // len = 最高位连续 1 的位置
        val combined = (immN shl 6) or (imms and 0x3F).inv() and 0x7F
        if (combined == 0) return 0
        val len = 31 - Integer.numberOfLeadingZeros(combined)
        if (len < 1) return 0
        val levels = (1 shl len) - 1
        val s = imms and levels
        val r = (imms ushr len) and 0x3F
        if (s == levels) return 0

        val elemBits = s + 1
        val et = if (elemBits == 64) -1L else (1L shl elemBits) - 1
        val welem0 = et
        val rol = r % elemBits
        // 循环右移
        val rotated = if (rol == 0) welem0 else {
            ((welem0 ushr rol) or (welem0 shl (elemBits - rol))) and et
        }

        // 重复填充到 size 位
        var acc = 0L
        var shift = 0
        while (shift < size) {
            acc = acc or (rotated shl shift)
            shift += elemBits
        }
        return if (size == 64) acc else acc and 0xFFFFFFFFL
    }
}
