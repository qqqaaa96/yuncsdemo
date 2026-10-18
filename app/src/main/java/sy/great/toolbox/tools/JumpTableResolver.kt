package sy.great.toolbox.tools

/**
 * 跳转表解析（switch 的 br xN）。
 *
 * 典型 ARM64 模式：
 *   adrp x1, table          ; x1 = 页基址
 *   add  x1, x1, #:lo12:table ; x1 = 表基址
 *   ldrsw x2, [x1, x0, lsl #2] ; x2 = 表项[x0]（32 位有符号）
 *   br   x2                 ; 跳到 x2
 *
 * 做法（抽象解释）：
 *   不依赖固定寄存器名，而是在一个“寄存器 → 值”映射里逐步推导：
 *     Const(addr)      —— 已知常量地址
 *     TableBase(base)  —— 来自某张表的元素
 *   当遇到 br 时，如果目标寄存器是 TableBase，就读表得到目标列表。
 *
 * 边界：
 *   · 最多读 MAX_ENTRIES 项；
 *   · 目标必须落在可执行段且 4 字节对齐，否则停止（连续不合法立即截断）；
 *   · 解析失败返回 null，而不是猜一个目标。
 */
class JumpTableResolver(private val elf: ElfParser.Elf) {

    companion object {
        /** 最多读多少项（防止把 .rodata 当表读到天荒地老） */
        private const val MAX_ENTRIES = 256
    }

    /** 抽象值 */
    private sealed class Abs {
        data class Const(val v: Long) : Abs()
        /** 表的基址 + 元素大小 + 是否有符号 */
        data class TableBase(val base: Long, val elemSize: Int, val signed: Boolean) : Abs()
    }

    /**
     * 尝试解析某个块末尾的间接跳转。
     *
     * @param blockInsns 该基本块的指令列表（按地址升序）
     * @return 目标地址集合；不是跳转表模式则返回 null
     */
    fun resolve(blockInsns: List<Arm64Disasm.Insn>): Set<Long>? {
        if (blockInsns.isEmpty()) return null
        val last = blockInsns.last()
        if (!last.isIndirect || last.isCall) return null   // blr 不在这里处理

        // 寄存器（号）→ 抽象值
        val st = HashMap<Int, Abs>()
        var pageBase: Long? = null   // 最近一条 adrp 给出的页基址

        for (insn in blockInsns) {
            val op = Arm64Disasm.parseOperands(insn)
            when (insn.mnemonic.lowercase()) {
                "adrp" -> {
                    // adrp xN, 0x12345000 → 页基址
                    val rd = op.regs.firstOrNull() ?: continue
                    val imm = op.imm ?: continue
                    pageBase = imm
                    st[rd] = Abs.Const(imm)
                }

                "add" -> {
                    // add xD, xN, #imm（含 :lo12:）
                    val regs = op.regs
                    if (regs.size < 2) continue
                    val rd = regs[0]
                    val rn = regs[1]
                    val base = (st[rn] as? Abs.Const)?.v
                    if (base != null) {
                        // 优先用 add 自己的立即数；没有则用 lo12 从 adrp 页基址拼
                        val imm = op.imm ?: (if (op.hasLo12) 0L else null)
                        if (imm != null) st[rd] = Abs.Const(base + imm)
                    } else if (pageBase != null && op.hasLo12) {
                        // add xD, xN, #:lo12:sym，且 xN 未知：用页基址 + 立即数
                        val imm = op.imm ?: 0L
                        st[rd] = Abs.Const(pageBase + imm)
                    }
                }

                "ldrsw", "ldr", "ldrb", "ldrh" -> {
                    // ldr* xD, [xBase, xIndex, lsl #k]
                    val rd = op.regs.firstOrNull() ?: continue
                    val baseReg = op.memBase ?: continue
                    val tableBase = (st[baseReg] as? Abs.Const)?.v ?: continue
                    // 必须有索引寄存器，否则是普通加载
                    if (op.memIndex == null) continue
                    val elemSize = when (insn.mnemonic.lowercase()) {
                        "ldrsw", "ldrh" -> if (insn.mnemonic.lowercase() == "ldrsw") 4 else 2
                        "ldrb" -> 1
                        else -> 8
                    }
                    val signed = insn.mnemonic.lowercase() == "ldrsw"
                    // 只有 4/8 字节的表项才能当跳转表用
                    if (elemSize != 4 && elemSize != 8) continue
                    st[rd] = Abs.TableBase(tableBase, elemSize, signed)
                }

                "br" -> {
                    val targetReg = op.regs.firstOrNull() ?: continue
                    val abs = st[targetReg] ?: continue
                    if (abs is Abs.TableBase) {
                        return readTable(abs)
                    }
                }
            }
        }
        return null
    }

    /**
     * 从 .rodata（或其他可读段）里读取跳转表。
     *
     * 两种常见形式都要试：
     *   A) 表项 = 目标绝对地址
     *   B) 表项 = 相对表基址的偏移（ldrsw 常见）
     */
    private fun readTable(t: Abs.TableBase): Set<Long>? {
        val out = LinkedHashSet<Long>()
        for (i in 0 until MAX_ENTRIES) {
            val addr = t.base + i.toLong() * t.elemSize
            val raw = readValue(addr, t.elemSize) ?: break

            val target: Long = if (t.elemSize == 4) {
                if (t.signed) t.base + raw else raw and 0xFFFFFFFFL
            } else raw

            // 两种解释都试一遍
            val candidate = when {
                isCode(target) -> target
                isCode(t.base + raw) -> t.base + raw
                else -> break
            }
            out.add(candidate)
        }
        // 少于 2 项一般不是跳转表（单目标直接 b 就够了）
        return if (out.size >= 2) out else null
    }

    /** 从 ELF 读一个值（小端） */
    private fun readValue(va: Long, size: Int): Long? {
        val off = elf.vaToOffset(va)?.toInt() ?: return null
        if (off < 0 || off + size > elf.bytes.size) return null
        var v = 0L
        for (i in size - 1 downTo 0) {
            v = (v shl 8) or (elf.bytes[off + i].toLong() and 0xFF)
        }
        return v
    }

    /** 是否落在可执行段内且 4 字节对齐 */
    private fun isCode(addr: Long): Boolean {
        if (addr <= 0L) return false
        if (addr and 3L != 0L) return false
        return elf.executableSegments().any { addr >= it.vaddr && addr < it.vaddr + it.fileSize }
    }
}
