package com.csdemo.tools.decompiler

/**
 * 基本块与函数级 CFG。
 *
 * 与 UI 层那个 CfgBuilder.Graph 不同：
 *   这里保存的是 IR 级别的基本块，并且带完整的前驱/后继关系，
 *   供后续的控制结构还原（if / while）使用。
 */

/** 基本块 */
class BasicBlock(val id: Int, val startAddress: Long) {
    /** 块内 IR 指令 */
    val instructions = ArrayList<IRInstruction>()

    /** 后继 */
    val successors = ArrayList<BasicBlock>()

    /** 前驱 */
    val predecessors = ArrayList<BasicBlock>()

    /** 块结束地址（不包含） */
    var endAddress: Long = startAddress

    var isEntry = false
    var isExit = false

    /** 块首标签（用于 goto） */
    val label: String get() = "L_" + java.lang.Long.toHexString(startAddress)

    /** 是否是终止块（无后继） */
    val isTerminal: Boolean get() = successors.isEmpty()

    fun connectTo(to: BasicBlock) {
        if (!successors.contains(to)) successors.add(to)
        if (!to.predecessors.contains(this)) to.predecessors.add(this)
    }

    override fun toString(): String = label
}

/** 函数级 IR */
class FunctionIR(
    val address: Long,
    val name: String
) {
    val blocks = ArrayList<BasicBlock>()
    var entry: BasicBlock? = null

    /** 函数参数 */
    val args = ArrayList<IRValue>()

    /** 返回值（若有） */
    var returnValue: IRValue? = null

    /** 被调用的外部函数地址集合 */
    val calls = LinkedHashSet<Long>()

    /** 使用到的栈变量 */
    val locals = ArrayList<IRValue>()

    /** 无法解析的间接跳转地址 */
    val unresolvedIndirect = ArrayList<Long>()

    fun blockAt(addr: Long): BasicBlock? = blocks.firstOrNull { it.startAddress == addr }
}

/**
 * 从 IR 序列构建基本块与 CFG。
 */
object CfgIrBuilder {

    /**
     * 构建。
     *
     * @param insns 已翻译的 IR 指令（按地址升序）
     * @param funcAddr 函数入口
     */
    fun build(
        insns: List<IRInstruction>,
        funcAddr: Long,
        funcName: String
    ): FunctionIR {
        val fn = FunctionIR(funcAddr, funcName)
        if (insns.isEmpty()) return fn

        // ---- 1. 找出块首 ----
        val leaders = LinkedHashSet<Long>()
        leaders.add(insns.first().address)

        insns.forEachIndexed { i, insn ->
            if (insn.isTerminator) {
                // 终止指令的下一条也是块首
                val next = insns.getOrNull(i + 1)
                if (next != null) leaders.add(next.address)
                // 分支目标
                if (insn.target > 0) leaders.add(insn.target)
            }
        }

        val addrSet = insns.map { it.address }.toHashSet()
        // 只保留落在函数内的块首
        val validLeaders = leaders.filter { addrSet.contains(it) }.toSortedSet()

        // ---- 2. 切块 ----
        var cur = BasicBlock(0, insns.first().address)
        val list = ArrayList<BasicBlock>()
        for (insn in insns) {
            if (validLeaders.contains(insn.address) && cur.instructions.isNotEmpty()) {
                cur.endAddress = insn.address
                list.add(cur)
                cur = BasicBlock(list.size, insn.address)
            }
            // 记录调用
            if (insn.op == IROp.Call && insn.target > 0) fn.calls.add(insn.target)
            if (insn.op == IROp.Indirect) fn.unresolvedIndirect.add(insn.address)
            cur.instructions.add(insn)
        }
        if (cur.instructions.isNotEmpty()) {
            val last = cur.instructions.last()
            cur.endAddress = last.address + last.size
            list.add(cur)
        }

        // 重新编号，保证连续
        val blocks = list.mapIndexed { i, b ->
            val nb = BasicBlock(i, b.startAddress)
            nb.instructions.addAll(b.instructions)
            nb.endAddress = b.endAddress
            nb
        }

        fn.blocks.addAll(blocks)

        // ---- 3. 连边 ----
        val byAddr = blocks.associateBy { it.startAddress }
        blocks.forEachIndexed { i, b ->
            val last = b.instructions.lastOrNull() ?: return@forEachIndexed
            val nextBlock = blocks.getOrNull(i + 1)

            when (last.op) {
                IROp.Return -> {
                    b.isExit = true
                }
                IROp.Indirect -> {
                    // 间接跳转：目标未知，标记为出口
                    b.isExit = true
                }
                IROp.Jump -> {
                    val t = byAddr[last.target]
                    if (t != null) b.connectTo(t) else b.isExit = true
                }
                IROp.Branch -> {
                    val t = byAddr[last.target]
                    if (t != null) b.connectTo(t)
                    if (nextBlock != null) b.connectTo(nextBlock)
                    if (t == null && nextBlock == null) b.isExit = true
                }
                IROp.Call -> {
                    // 调用不是控制流转移，继续下落
                    if (nextBlock != null) b.connectTo(nextBlock) else b.isExit = true
                }
                else -> {
                    if (nextBlock != null) b.connectTo(nextBlock) else b.isExit = true
                }
            }
        }

        // ---- 4. 标记入口 ----
        fn.entry = byAddr[funcAddr] ?: blocks.firstOrNull()
        fn.entry?.isEntry = true

        // ---- 5. 收集栈变量与参数 ----
        val localSet = LinkedHashSet<IRValue>()
        val argSet = LinkedHashSet<IRValue>()
        for (b in blocks) {
            for (insn in b.instructions) {
                val all = ArrayList<IRValue>()
                insn.result?.let { all.add(it) }
                all.addAll(insn.operands)
                for (v in all) {
                    when (v.kind) {
                        ValueKind.Local -> localSet.add(v)
                        ValueKind.Arg -> argSet.add(v)
                        else -> {}
                    }
                }
            }
        }
        fn.locals.addAll(localSet)
        fn.args.addAll(argSet)

        // 返回值
        for (b in blocks) {
            val last = b.instructions.lastOrNull() ?: continue
            if (last.op == IROp.Return && last.operands.isNotEmpty()) {
                fn.returnValue = last.operands[0]
            }
        }

        return fn
    }
}
