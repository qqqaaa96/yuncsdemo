package sy.great.toolbox.tools.decompiler

import sy.great.toolbox.tools.Arm64Disasm
import sy.great.toolbox.tools.CfgBuilder
import sy.great.toolbox.tools.ElfParser
import sy.great.toolbox.tools.JumpTableResolver

/**
 * 反编译引擎（第一阶段）。
 *
 * 完整链路：
 *   ELF → 函数发现 → 反汇编 → IR 翻译 → 基本块 → CFG → 支配关系 → 伪 C
 *
 * 与旧版 PseudoCode 的区别：
 *   · 旧版：把汇编文本包进 /* */ 注释
 *   · 新版：寄存器→变量、指令→表达式、CFG→if/else/while
 */
object DecompilerEngine {

    data class Result(
        val text: String,
        val functionCount: Int,
        val blockCount: Int,
        val error: String = ""
    )

    /**
     * 对整份 ELF 生成伪 C。
     */
    fun decompileWhole(
        elf: ElfParser.Elf,
        maxFunctions: Int = 5000,
        maxInsnsPerFunc: Int = 4000,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): Result {
        // 架构检查
        if (elf.machine != 0xB7 && elf.machine != 0x28) {
            return Result(
                "", 0, 0,
                "当前反编译仅支持 AArch64 / ARM。\n该文件架构为 " + elf.machineName
            )
        }

        val funcs = CfgBuilder.discoverFunctions(elf).filter { it.hasValidAddr }
        if (funcs.isEmpty()) {
            return Result("", 0, 0, "未发现任何函数入口")
        }

        val picked = if (funcs.size > maxFunctions) funcs.take(maxFunctions) else funcs
        val nextMap = CfgBuilder.nextAddrMap(funcs)

        val sb = StringBuilder(1 shl 16)
        sb.append("/*\n")
        sb.append(" * ").append(elf.machineName).append("　")
            .append(elf.typeName).append('\n')
        sb.append(" * 入口 0x").append(java.lang.Long.toHexString(elf.entry))
            .append("　函数 ").append(picked.size).append('\n')
        sb.append(" *\n")
        sb.append(" * 由 SSA 重建：寄存器已提升为变量，分支还原为 if / else，循环还原为 while。\n")
        sb.append(" * 类型由宽度与指针用法推断；变量名不代表原始语义。\n")
        sb.append(" */\n\n")

        var produced = 0
        var totalBlocks = 0

        picked.forEachIndexed { idx, f ->
            val code = decompileFunction(elf, f, maxInsnsPerFunc, nextMap[f.addr] ?: 0L)
            if (code != null) {
                sb.append(code)
                sb.append('\n')
                produced++
                // 真实块数：数标签行（L_xxxx:），每个基本块一个。
                totalBlocks += code.countOccurrences("L_")
            }
            onProgress?.invoke(idx + 1, picked.size)
        }

        if (produced == 0) {
            return Result("", 0, 0, "已定位到入口，但未能解出任何函数")
        }

        return Result(sb.toString(), produced, totalBlocks)
    }

    /**
     * 单个函数的完整反编译。
     */
    fun decompileFunction(
        elf: ElfParser.Elf,
        f: CfgBuilder.FuncInfo,
        maxInsns: Int,
        nextFuncAddr: Long
    ): String? {
        // ---- 1. 取代码段 ----
        val segs = elf.executableSegments()
        val seg = segs.firstOrNull { it.contains(f.addr) } ?: return null
        // fileOffset / fileSize 都是 Long，直接 toInt() 可能溢出成负数。
        // 先夹到 elf.bytes 的合法范围再转。
        if (seg.fileOffset < 0 || seg.fileOffset > Int.MAX_VALUE) return null
        val off = seg.fileOffset.toInt()
        if (off >= elf.bytes.size) return null
        val len = minOf(seg.fileSize, (elf.bytes.size - off).toLong()).toInt()
        if (len <= 0) return null
        val bytes = elf.bytes.copyOfRange(off, off + len)

        // ---- 2. 限定扫描范围 ----
        val startOffLong = f.addr - seg.vaddr
        if (startOffLong < 0 || startOffLong > Int.MAX_VALUE) return null
        val startOff = startOffLong.toInt()
        if (startOff >= bytes.size) return null

        var limit = bytes.size
        if (f.size > 0L) {
            // 同样先夹再转，避免 f.size 超大时溢出
            val capSize = minOf(f.size, (bytes.size - startOff).toLong()).toInt()
            val bySize = startOff + capSize
            if (bySize in (startOff + 4)..bytes.size) limit = bySize
        }
        // nextFuncAddr 可能是 Long.MAX_VALUE（表示“到段尾”），
        // 直接减 baseAddr 再 toInt() 会溢出，必须先用 Long 算再判范围。
        if (nextFuncAddr > f.addr && nextFuncAddr != Long.MAX_VALUE) {
            val nextOffLong = nextFuncAddr - seg.vaddr
            if (nextOffLong > startOff && nextOffLong <= Int.MAX_VALUE) {
                val nextOff = nextOffLong.toInt()
                if (nextOff in (startOff + 4)..limit) limit = nextOff
            }
        }
        val cap = startOff + maxInsns * 4
        if (cap < limit) limit = cap

        // ---- 3. 反汇编 + 翻译为 IR ----
        //
        // 修正（治本）：原来这里是线性扫描
        //     var o = startOff; while (o + 4 <= limit) { decode; translate; o += 4 }
        // 后果：
        //   · ret 之后的数据/对齐填充也被当指令翻译 → 伪 C 里出现垃圾语句
        //   · 跳转目标在 limit 之外（向前跳）时整块丢失
        //   · 不可达代码混进结果
        //
        // 现在改成与 CFG 一致的可达性递归下降：
        //   从函数入口出发，只解码“真正可达”的地址，
        //   分支目标入队，ret / 无条件跳转不再自动落下。
        //
        // 注意：IR 必须按地址升序输出（CfgIrBuilder 依赖这个前提切块），
        // 所以先收集再排序。
        val translator = IrTranslator(seg.vaddr, f.addr)

        val reachable = HashMap<Long, Arm64Disasm.Insn>()
        val work = ArrayDeque<Long>()
        val queued = HashSet<Long>()

        fun push(a: Long) {
            val o = (a - seg.vaddr).toInt()
            if (o < 0 || o + 4 > limit) return
            if (o < startOff) return          // 不越到本函数之前
            if (a % 4L != 0L) return          // 指令对齐
            if (queued.add(a)) work.addLast(a)
        }

        push(f.addr)

        var decodedCount = 0
        while (work.isNotEmpty() && decodedCount < maxInsns) {
            val a = work.removeFirst()
            if (reachable.containsKey(a)) continue
            val o = (a - seg.vaddr).toInt()
            if (o < 0 || o + 4 > limit) continue

            val insn = Arm64Disasm.decode(bytes, o, seg.vaddr) ?: continue
            reachable[a] = insn
            decodedCount++

            // 后继规则（与 CfgBuilder 的可达性语义一致）：
            //   ret / br xN          → 无顺序后继
            //   b target（无条件）    → 只走 target
            //   b.cc / cbz / tbz     → target + 顺序下落
            //   其它                 → 顺序下落
            val nextAddr = a + 4
            when {
                insn.isReturn -> { /* 停止 */ }
                insn.isIndirect -> { /* BR/BLR 已分别处理，BR 无后继 */ }
                insn.isCall -> push(nextAddr)
                insn.isCondBranch -> {
                    insn.target?.let { push(it) }
                    push(nextAddr)
                }
                insn.isUncondBranch -> {
                    val tg = insn.target
                    if (tg != null) push(tg) else push(nextAddr)
                }
                else -> push(nextAddr)
            }
        }

        // ---- 3.5 跳转表：把 switch 的各个 case 目标也纳入反汇编 ----
        //
        // 不去解析 br xN 的话，整个 switch 会只剩一行“间接跳转，目标未知”，
        // 所有 case 代码全部丢失。这正是之前 switch 全丢的原因。
        //
        // 做法：
        //   对每个以 br 结尾的可达块，用 JumpTableResolver 试解，
        //   解出的目标当作新的入口继续做可达性展开（最多两轮，避免爆炸）。
        val resolver = JumpTableResolver(elf)
        var switchTargets = 0

        repeat(2) {
            // 收集当前所有以 br 结尾的块（块 = 从一个 leader 到下一个 leader）
            val blockEnds = ArrayList<Long>()
            for (a in reachable.keys.sorted()) {
                val insn = reachable[a] ?: continue
                if (insn.isIndirect && !insn.isCall) blockEnds.add(a)
            }
            if (blockEnds.isEmpty()) return@repeat

            var added = 0
            for (brAddr in blockEnds) {
                // 重建该块：从上一个终止指令之后到 brAddr
                val blockInsns = buildBlockEndingAt(brAddr, reachable, seg.vaddr)
                if (blockInsns.size < 2) continue
                val targets = resolver.resolve(blockInsns) ?: continue
                for (t in targets) {
                    val o = (t - seg.vaddr).toInt()
                    if (o < 0 || o + 4 > limit) continue
                    if (reachable.containsKey(t)) continue
                    // 从该目标再做一次可达性展开
                    val before = reachable.size
                    expandFrom(t, reachable, bytes, seg.vaddr, limit, startOff, maxInsns - reachable.size)
                    if (reachable.size > before) added += reachable.size - before
                }
                if (added > 0) switchTargets++
            }
            if (added == 0) return@repeat
        }

        // 按地址升序翻译，保证 IR 顺序与 CfgIrBuilder 的前提一致
        for (a in reachable.keys.sorted()) {
            translator.translate(reachable[a]!!)
        }

        val ir = translator.instructions
        if (ir.isEmpty()) return null

        // ---- 4. 构建 CFG ----
        val fn = CfgIrBuilder.build(ir, f.addr, f.name)
        if (fn.blocks.isEmpty()) return null

        // ---- 4.5 把解析出的跳转表目标记录到函数上，供 CCodeGen 输出 switch ----
        if (switchTargets > 0) {
            for (b in fn.blocks) {
                val last = b.instructions.lastOrNull() ?: continue
                if (last.op != IROp.Indirect) continue
                val blockInsns = buildBlockEndingAt(
                    last.address, reachable, seg.vaddr
                )
                val ts = resolver.resolve(blockInsns) ?: continue
                // 按目标地址排序后存入 operands 的扩展信息（用 target 字段存首个）
                val sorted = ts.sorted()
                last.target = sorted.firstOrNull() ?: 0L
                // 其余目标用标准 Java List 暂存到 IRInstruction 的 operands 尾部？
                // 不：operands 是 IRValue。这里用全局侧表记录。
                fn.switchTargets[last.address] = sorted
            }
        }

        // ---- 5. 生成伪 C ----
        return CCodeGen.generate(fn)
    }

    // ---------- 小工具 ----------

    /**
     * 重建以 endAddr（br 指令）为末尾的基本块指令序列。
     *
     * JumpTableResolver 需要看到完整的块（adrp / add / ldrsw / br），
     * 而反编译管线只保留了“可达指令集合”。
     *
     * 做法：从 endAddr 往前逐条回退，遇到上一个终止指令就停。
     * 之所以可行：可达集合里同一个块内的指令是连续的。
     */
    private fun buildBlockEndingAt(
        endAddr: Long,
        reachable: Map<Long, Arm64Disasm.Insn>,
        baseVa: Long
    ): List<Arm64Disasm.Insn> {
        val out = ArrayList<Arm64Disasm.Insn>()
        var a = endAddr
        var guard = 0
        while (guard++ < 512) {
            val insn = reachable[a] ?: break
            out.add(insn)
            // 遇到前面的终止指令就停（终止指令本身属于上一块）
            val prev = a - 4
            val prevInsn = reachable[prev] ?: break
            if (isCodeTerminator(prevInsn)) break
            a = prev
        }
        out.reverse()
        return out
    }

    /** 一条指令是否终止基本块（反汇编层没有 isTerminator，用现有字段组合） */
    private fun isCodeTerminator(i: Arm64Disasm.Insn): Boolean =
        i.isReturn || i.isIndirect || i.isBranch

    /**
     * 从某地址开始做一次可达性展开（供跳转表目标使用）。
     */
    private fun expandFrom(
        start: Long,
        reachable: HashMap<Long, Arm64Disasm.Insn>,
        bytes: ByteArray,
        baseVa: Long,
        limit: Int,
        startOff: Int,
        budget: Int
    ) {
        if (budget <= 0) return
        val work = ArrayDeque<Long>()
        val queued = HashSet<Long>()

        fun push(a: Long) {
            val o = (a - baseVa).toInt()
            if (o < 0 || o + 4 > limit) return
            if (o < startOff) return
            if (a % 4L != 0L) return
            if (reachable.containsKey(a)) return
            if (queued.add(a)) work.addLast(a)
        }

        push(start)

        var done = 0
        while (work.isNotEmpty() && done < budget) {
            val a = work.removeFirst()
            if (reachable.containsKey(a)) continue
            val o = (a - baseVa).toInt()
            if (o < 0 || o + 4 > limit) continue
            val insn = Arm64Disasm.decode(bytes, o, baseVa) ?: continue
            reachable[a] = insn
            done++

            val next = a + 4
            when {
                insn.isReturn -> {}
                insn.isIndirect -> {}
                insn.isCall -> push(next)
                insn.isCondBranch -> {
                    insn.target?.let { push(it) }
                    push(next)
                }
                insn.isUncondBranch -> {
                    val tg = insn.target
                    if (tg != null) push(tg) else push(next)
                }
                else -> push(next)
            }
        }
    }

    private fun String.countOccurrences(sub: String): Int {
        var c = 0
        var i = indexOf(sub)
        while (i >= 0) {
            c++
            i = indexOf(sub, i + sub.length)
        }
        return c
    }
}
