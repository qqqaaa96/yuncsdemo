package com.csdemo.tools.decompiler

import com.csdemo.tools.Arm64Disasm
import com.csdemo.tools.CfgBuilder
import com.csdemo.tools.ElfParser

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
        maxFunctions: Int = 300,
        maxInsnsPerFunc: Int = 1200,
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
        sb.append(" * 由 IR 重建：寄存器已映射为变量，分支已还原为 if / while。\n")
        sb.append(" * 不做类型推导，变量名不代表真实语义。\n")
        sb.append(" */\n\n")

        var produced = 0
        var totalBlocks = 0

        picked.forEachIndexed { idx, f ->
            val code = decompileFunction(elf, f, maxInsnsPerFunc, nextMap[f.addr] ?: 0L)
            if (code != null) {
                sb.append(code)
                sb.append('\n')
                produced++
                totalBlocks += code.countOccurrences("blocks */")
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
        val off = seg.fileOffset.toInt()
        val len = seg.fileSize.toInt().coerceAtMost(elf.bytes.size - off)
        if (off < 0 || len <= 0) return null
        val bytes = elf.bytes.copyOfRange(off, off + len)

        // ---- 2. 限定扫描范围 ----
        val startOff = (f.addr - seg.vaddr).toInt()
        if (startOff < 0 || startOff >= bytes.size) return null

        var limit = bytes.size
        if (f.size > 0L) {
            val bySize = startOff + f.size.toInt()
            if (bySize in (startOff + 4)..bytes.size) limit = bySize
        }
        if (nextFuncAddr > f.addr) {
            val nextOff = (nextFuncAddr - seg.vaddr).toInt()
            if (nextOff in (startOff + 4)..limit) limit = nextOff
        }
        val cap = startOff + maxInsns * 4
        if (cap < limit) limit = cap

        // ---- 3. 反汇编 + 翻译为 IR ----
        val translator = IrTranslator(seg.vaddr, f.addr)
        var o = startOff
        var count = 0
        while (o + 4 <= limit && count < maxInsns) {
            val insn = Arm64Disasm.decode(bytes, o, seg.vaddr) ?: break
            translator.translate(insn)
            count++
            o += 4
        }

        val ir = translator.instructions
        if (ir.isEmpty()) return null

        // ---- 4. 构建 CFG ----
        val fn = CfgIrBuilder.build(ir, f.addr, f.name)
        if (fn.blocks.isEmpty()) return null

        // ---- 5. 生成伪 C ----
        return CCodeGen.generate(fn)
    }

    // ---------- 小工具 ----------

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
