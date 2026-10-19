package sy.great.toolbox.tools.decompiler

/**
 * SSA 构建（Dominance Frontier 算法）。
 *
 * 为什么需要这一层：
 *   原来的 IrTranslator 用一张「寄存器 → IRValue」映射线性往后盖。
 *   一旦出现分支，两条路径各自写同一个寄存器，合流之后
 *   映射里只剩最后写入的那一个 —— 另一条路径的值直接丢了。
 *   表现出来就是伪 C 里变量忽隐忽现、计算链断掉。
 *
 * 标准做法（Cytron 1991）：
 *   1. 算支配树与支配边界（DF）
 *   2. 在 DF 里插入 Phi 节点
 *   3. 沿支配树重命名，每个定义得到唯一版本
 *
 * 这里按「值」而不是「寄存器名」做 SSA：
 *   IrTranslator 已经为每次写入建立了新的 IRValue，
 *   所以真正的任务是把「跨块读到旧版本」这个关系补成 Phi。
 */
object SsaBuilder {

    /**
     * 对函数做 SSA 化：在需要合流的位置插入 Phi。
     *
     * @return 插入的 Phi 指令数（0 表示不需要 / 无法插入）
     */
    fun build(fn: FunctionIR): Int {
        val entry = fn.entry ?: return 0
        if (fn.blocks.size <= 1) return 0

        val dom = Dominators.compute(fn)
        val df = dominanceFrontier(fn, dom)

        // 需要插 Phi 的变量：被多个块定义的量。
        //
        // 关键：这里以「存储位置」为键做分析，而不是以 IRValue 为键。
        // 同一寄存器在不同块写入会产生不同 IRValue，但它们代表同一个变量，
        // 必须合并到同一个 Phi 上。
        val defBlocks = HashMap<String, MutableList<BasicBlock>>()
        val defValue = HashMap<String, IRValue>()

        for (b in fn.blocks) {
            for (insn in b.instructions) {
                val r = insn.result ?: continue
                if (r.kind == ValueKind.Const) continue
                val key = slotKey(r)
                if (key.isEmpty()) continue
                defBlocks.getOrPut(key) { ArrayList() }.add(b)
                defValue[key] = r
            }
        }

        // ---- 真正的 Phi 插入 ----
        //
        // 关键：不能“给每个多前驱块的每个变量都插一个 Phi”，
        // 那样会生成大量 x = phi(x, x) 的垃圾。
        //
        // 正确判据：沿着每个前驱向前找“该变量的最近一次定义”（到达定义），
        //   · 所有前驱得到同一个值 → 不需要 Phi
        //   · 前驱得到不同的值，或某个前驱根本没有定义 → 需要 Phi
        var inserted = 0
        val MAX_PHI = 512

        for (b in fn.blocks) {
            if (b.predecessors.size < 2) continue
            if (b === entry) continue

            for (defEntry in defBlocks.entries) {
                val key = defEntry.key
                val blocks = defEntry.value
                if (inserted >= MAX_PHI) return inserted

                val distinctDefs = blocks.distinct()
                if (distinctDefs.size < 2) continue

                // 本块必须在某个定义块的支配边界里，否则该变量根本流不到这
                val inDf = distinctDefs.any { d -> df[d]?.contains(b) == true }
                if (!inDf) continue

                // 已插入过同一变量的 Phi 就跳过（幂等）
                val already = b.instructions.any {
                    it.op == IROp.Phi && it.result?.let { r -> slotKey(r) } == key
                }
                if (already) continue

                // 收集每个前驱到达本块时该变量的值
                val incoming = ArrayList<IRValue?>()
                for (p in b.predecessors) {
                    incoming.add(reachingDef(p, key, entry, dom))
                }

                // 全部相同（含全为 null）→ 不需要 Phi
                val nonNull = incoming.filterNotNull()
                if (nonNull.isEmpty()) continue
                val first = nonNull.first()
                if (incoming.all { it === first }) continue

                val templ = defValue[key] ?: nonNull.first()
                val phi = IRInstruction(IROp.Phi, b.startAddress, 0)
                val pv = IRValue(templ.id, templ.type, templ.kind)
                pv.accessSize = templ.accessSize
                pv.stackOffset = templ.stackOffset
                pv.constant = templ.constant
                pv.sourceRegister = templ.sourceRegister
                phi.result = pv

                // 操作数按前驱顺序，缺失的那个用自身占位（表示未定义路径）
                for (v in incoming) {
                    phi.operands.add(v ?: pv)
                }

                b.instructions.add(0, phi)
                inserted++
            }
        }

        return inserted
    }

    /**
     * 沿控制流反向 BFS，找从 start 块往前最近的“该变量定义”。
     *
     * 这就是到达定义（reaching definition）的简化版：
     *   停在第一个定义了该变量的块上，返回那个值。
     *
     * 如果遇到支配本块的块还没找到，说明该路径上从未定义，返回 null。
     */
    private fun reachingDef(
        start: BasicBlock,
        key: String,
        entry: BasicBlock,
        dom: Map<BasicBlock, Set<BasicBlock>>
    ): IRValue? {
        val visited = HashSet<BasicBlock>()
        val work = ArrayDeque<BasicBlock>()
        work.addLast(start)
        visited.add(start)

        var guard = 0
        while (work.isNotEmpty() && guard++ < 4096) {
            val b = work.removeFirst()

            // 本块内最后一条定义该变量的指令
            var found: IRValue? = null
            for (insn in b.instructions) {
                val r = insn.result ?: continue
                if (r.kind == ValueKind.Const) continue
                if (slotKey(r) == key) found = r
            }
            if (found != null) return found

            // 到入口了还没找到 → 该路径上未定义
            if (b === entry) return null

            for (p in b.predecessors) {
                if (visited.add(p)) work.addLast(p)
            }
        }
        return null
    }

    /**
     * 变量的身份键。
     *
     * 同一存储位置（同一栈槽 / 同一寄存器 / 同一全局）算同一变量。
     * 用它们而不是 IRValue.id，因为每次写入都会产生新的 IRValue。
     */
    private fun slotKey(v: IRValue): String = when (v.kind) {
        ValueKind.Local -> "stk:" + v.stackOffset
        ValueKind.Global -> "glb:" + v.constant
        ValueKind.Arg -> "arg:" + (v.sourceRegister.ifEmpty { v.id.toString() })
        ValueKind.Temp -> if (v.sourceRegister.isNotEmpty()) "reg:" + v.sourceRegister.lowercase() else ""
        ValueKind.Const -> ""
        ValueKind.Unknown -> if (v.sourceRegister.isNotEmpty()) "reg:" + v.sourceRegister.lowercase() else ""
    }

    /** 支配边界 */
    private fun dominanceFrontier(
        fn: FunctionIR,
        dom: Map<BasicBlock, Set<BasicBlock>>
    ): Map<BasicBlock, Set<BasicBlock>> {
        val df = HashMap<BasicBlock, MutableSet<BasicBlock>>()
        for (b in fn.blocks) df[b] = HashSet()

        for (b in fn.blocks) {
            if (b.predecessors.size < 2) continue
            for (p in b.predecessors) {
                var runner: BasicBlock? = p
                var guard = 0
                while (runner != null && guard++ < 512) {
                    // runner 不能严格支配 b
                    val runnerDoms = dom[runner] ?: emptySet()
                    if (runnerDoms.contains(b)) break
                    df.getOrPut(runner) { HashSet() }.add(b)
                    runner = immediateDominator(runner, dom)
                }
            }
        }
        return df
    }

    /** 直接支配者：支配集合中除自身外最接近的那个 */
    private fun immediateDominator(
        b: BasicBlock,
        dom: Map<BasicBlock, Set<BasicBlock>>
    ): BasicBlock? {
        val ds = dom[b] ?: return null
        var best: BasicBlock? = null
        var bestSize = -1
        for (d in ds) {
            if (d === b) continue
            val size = dom[d]?.size ?: 0
            if (size > bestSize) {
                bestSize = size
                best = d
            }
        }
        return best
    }
}

/** 支配关系计算（独立一份，SSA 与结构还原共用） */
object Dominators {

    /** 块 → 支配该块的集合（含自身） */
    fun compute(fn: FunctionIR): Map<BasicBlock, Set<BasicBlock>> {
        val entry = fn.entry ?: return emptyMap()
        if (fn.blocks.isEmpty()) return emptyMap()

        val all = fn.blocks.toSet()
        val dom = HashMap<BasicBlock, MutableSet<BasicBlock>>()
        for (b in fn.blocks) {
            dom[b] = if (b === entry) mutableSetOf(b) else all.toMutableSet()
        }

        var changed = true
        var guard = 0
        while (changed && guard++ < 500) {
            changed = false
            for (b in fn.blocks) {
                if (b === entry) continue
                val preds = b.predecessors

                // 无前驱的不可达块：支配集只包含自己。
                //
                // 旧写法是 continue 直接跳过，导致它的 dom 永远停在“全集”，
                // 于是 dominates() 对任何块都返回 true，
                // 回边被大量误判，结果是每个函数都被包成 while(1)。
                if (preds.isEmpty()) {
                    val only = mutableSetOf(b)
                    val old = dom[b]
                    if (old == null || old.size != 1 || !old.contains(b)) {
                        dom[b] = only
                        changed = true
                    }
                    continue
                }

                var inter: Set<BasicBlock>? = null
                for (p in preds) {
                    val pd = dom[p] ?: continue
                    inter = if (inter == null) pd.toSet() else inter.intersect(pd)
                }
                val newSet = (inter ?: emptySet()).toMutableSet()
                newSet.add(b)
                val old = dom[b]
                if (old == null || old.size != newSet.size || !old.containsAll(newSet)) {
                    dom[b] = newSet
                    changed = true
                }
            }
        }
        return dom
    }

    /** b 是否支配 c */
    fun dominates(
        dom: Map<BasicBlock, Set<BasicBlock>>,
        b: BasicBlock,
        c: BasicBlock
    ): Boolean = dom[c]?.contains(b) == true
}
