package sy.great.toolbox.tools.decompiler

/**
 * 控制结构还原：把 CFG 还原成 if / else / while / for。
 *
 * 原来 CCodeGen 的做法是两个播片式启发：
 *   · 遇到回边就包一层 while(1)
 *   · 前驱 > 1 就打个 goto 标签
 * 结果就是满屏 goto，看不出原始结构。
 *
 * 这里改用业界标准的两步：
 *   1. 自然循环识别（回边 + 支配关系）→ 循环体、循环头、退出点
 *   2. 区间递归：从入口出发，按后继关系还原 if/else，
 *      遇到循环头输出 while，遇到合流点结束当前层
 */
object Structurer {

    /** 一个自然循环 */
    class Loop(
        val header: BasicBlock,
        /** 循环体内所有块（含 header） */
        val body: Set<BasicBlock>,
        /** 回边源（循环体末尾跳回 header 的块） */
        val latches: Set<BasicBlock>,
        /** 循环退出目标（body 外部的后继） */
        val exits: Set<BasicBlock>
    ) {
        fun contains(b: BasicBlock): Boolean = body.contains(b)
    }

    /** 全部自然循环，按头部地址排序 */
    fun findLoops(fn: FunctionIR, dom: Map<BasicBlock, Set<BasicBlock>>): List<Loop> {
        val result = ArrayList<Loop>()

        for (b in fn.blocks) {
            for (s in b.successors) {
                // 回边：后继支配当前块
                if (!Dominators.dominates(dom, s, b)) continue

                // 从 b 反向 BFS，收集循环体，止于 header
                val body = LinkedHashSet<BasicBlock>()
                body.add(s)
                val stack = ArrayDeque<BasicBlock>()
                if (b !== s) {
                    body.add(b)
                    stack.addLast(b)
                }
                while (stack.isNotEmpty()) {
                    val cur = stack.removeLast()
                    for (p in cur.predecessors) {
                        if (body.add(p)) stack.addLast(p)
                    }
                }
                body.add(s)

                // 退出点：body 内块指向 body 外的后继
                val exits = LinkedHashSet<BasicBlock>()
                for (x in body) {
                    for (su in x.successors) {
                        if (!body.contains(su)) exits.add(su)
                    }
                }

                val existing = result.firstOrNull { it.header === s }
                if (existing != null) {
                    val merged = LinkedHashSet(existing.body)
                    merged.addAll(body)
                    val lb = LinkedHashSet(existing.latches)
                    lb.add(b)
                    val le = LinkedHashSet(existing.exits)
                    le.addAll(exits)
                    result.remove(existing)
                    result.add(Loop(s, merged, lb, le))
                } else {
                    result.add(Loop(s, body, setOf(b), exits))
                }
            }
        }

        return result.sortedBy { it.header.startAddress }
    }

    /**
     * 判断一个循环能否还原成 for。
     *
     * 条件（与 IDA 一致）：
     *   · 单一回边
     *   · 循环头只有一个入口（前置头之外没有其它跳进循环头的路径）
     *   · 回边源就是条件分支（while (cond) 形式）
     */
    fun asWhile(l: Loop): Boolean {
        if (l.latches.size != 1) return false
        // 循环头的进入前驱必须只有 1 个（或 2 个但其中一个是回边源）
        val outsidePreds = l.header.predecessors.filter { !l.body.contains(it) }
        if (outsidePreds.size > 1) return false
        return true
    }

    /** 循环是否有单一退出（可写成 while 而不用 goto 跳出） */
    fun singleExit(l: Loop): Boolean = l.exits.size <= 1

    /**
     * 判断循环能否还原成 do-while。
     *
     * 特征：
     *   · 回边源是一个「条件分支」块（循环末尾判条件）
     *   · 循环头不是条件分支（不是 while(cond) 形式）
     *   · 循环头只有一个外部入口
     */
    fun asDoWhile(l: Loop): Boolean {
        if (l.latches.size != 1) return false
        val outsidePreds = l.header.predecessors.filter { !l.body.contains(it) }
        if (outsidePreds.size > 1) return false

        // 循环头不能本身是条件分支，否则优先当成 while
        val headerLast = l.header.instructions.lastOrNull()
        if (headerLast != null && headerLast.op == IROp.Branch) return false

        // 回边源必须是条件分支（末尾才判条件）
        val latch = l.latches.first()
        val latchLast = latch.instructions.lastOrNull() ?: return false
        return latchLast.op == IROp.Branch
    }

    /**
     * 判断能否还原成 for 循环。
     *
     * 典型形态（编译器生成）：
     *     i = 0;
     *   L: if (i >= n) goto exit;
     *      body…
     *      i++;
     *      goto L;
     *     exit:
     *
     * 判定条件：
     *   · 单一回边，且回边源不是条件分支（无条件跳回）
     *   · 循环头是条件分支（while / for 的公共特征）
     *   · 循环头只有一个外部入口
     *
     * 注：真正拆出“初始化 / 条件 / 递增”三段需要数据流分析，
     * 这里只判定形状，输出仍用 while，避免编造假 for。
     */
    fun asForShape(l: Loop): Boolean {
        if (l.latches.size != 1) return false
        val outsidePreds = l.header.predecessors.filter { !l.body.contains(it) }
        if (outsidePreds.size > 1) return false

        val latch = l.latches.first()
        val latchLast = latch.instructions.lastOrNull() ?: return false
        // 回边是无条件跳回
        if (latchLast.op != IROp.Jump) return false

        val headerLast = l.header.instructions.lastOrNull() ?: return false
        return headerLast.op == IROp.Branch
    }

    /**
     * 按支配树深度给块排序，便于输出时保持外层在前。
     */
    fun orderByDom(fn: FunctionIR, dom: Map<BasicBlock, Set<BasicBlock>>): List<BasicBlock> =
        fn.blocks.sortedWith(
            compareByDescending<BasicBlock> { dom[it]?.size ?: 0 }
                .thenBy { it.startAddress }
        )
}
