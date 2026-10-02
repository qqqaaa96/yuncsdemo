package com.csdemo.tools

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

        // ADD 立即数到 SP（简写）
        if ((w and 0x7F000000) == 0x31000000) {
            return if ((w and 0x1F000000) == 0x11000000)
                Insn(addr, w, "add (imm)", "add", null, false, false, false, false, false)
            else Insn(addr, w, "sub (imm)", "sub", null, false, false, false, false, false)
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
