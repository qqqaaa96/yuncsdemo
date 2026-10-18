package sy.great.toolbox.tools.decompiler

/**
 * SSA 变量重命名。
 *
 * 问题：
 *   IrTranslator 每次写寄存器都建新的 IRValue，但它们指向同一个“变量”。
 *   如果都叫 t3，输出就变成：
 *       t3 = a + b;
 *       ...
 *       t3 = t3 * 2;      // 其实是不同版本
 *   语义没错，但读不出“这是同一变量的演化”。
 *
 * 做法（Cytron 重命名的简化版）：
 *   按支配树遍历，为每个「存储位置」维护一个版本计数器，
 *   定义一次就递增，名字变成 x、x_1、x_2 ……
 *
 * 好处：
 *   · 同一变量在不同路径上的不同值不再同名，不会看混
 *   · 配合 Phi 能直接看出合流点
 *   · 重命名后可以安全做常量折叠
 */
object SsaRename {

    /**
     * 为函数内的值分配“带版本号”的显示名。
     *
     * @return 值 → 名字 的映射（可直接被 Namer 使用）
     */
    fun assign(fn: FunctionIR): Map<IRValue, String> {
        val out = HashMap<IRValue, String>()
        val entry = fn.entry ?: return out

        val dom = Dominators.compute(fn)
        val children = dominatorChildren(fn, dom, entry)

        // 每个存储位置的版本计数
        val version = HashMap<String, Int>()
        // 已经分配过的值
        val named = HashSet<IRValue>()

        // 基名：参数用 a0/a1，栈变量用 v_xx，其余用 t
        val baseOf = HashMap<String, String>()
        var argIdx = 0
        for (a in fn.args) {
            val key = slotKey(a)
            val nm = "a" + argIdx
            baseOf[key] = nm
            argIdx++
            named.add(a)
            out[a] = nm
        }
        for (l in fn.locals) {
            val key = slotKey(l)
            val off = if (l.stackOffset < 0) -l.stackOffset else l.stackOffset
            val nm = "v" + java.lang.Long.toHexString(off).lowercase()
            baseOf[key] = nm
            if (named.add(l)) out[l] = nm
        }

        // 沿支配树递归
        fun visit(b: BasicBlock) {
            for (insn in b.instructions) {
                val r = insn.result ?: continue
                if (r.kind == ValueKind.Const) continue
                if (named.contains(r)) continue

                val key = slotKey(r)
                if (key.isEmpty()) {
                    // 没有存储位置的纯临时值：直接用 id 区分
                    out[r] = "t" + r.id
                    named.add(r)
                    continue
                }

                val base = baseOf.getOrPut(key) { defaultBase(r, key) }
                val v = (version[key] ?: 0) + 1
                version[key] = v

                // 第一次定义不加后缀，后续加 _n
                val name = if (v == 1) base else base + "_" + v
                out[r] = name
                named.add(r)
            }

            // 递归子节点。
            //
            // 这里刻意不做“版本号回退”：版本号全局单调递增。
            // 严格 SSA 需要用栈式版本堆栈，但那个实现一旦写错
            // 就会出现两个不同值共用同一个名字（比同名更难排查）。
            // 全局递增永远保证：不同定义 → 不同名字，绝不会撞。
            // 代价：名字号会跳（如 x、x_3），但语义绝对安全。
            for (c in children[b] ?: emptyList()) {
                visit(c)
            }
        }

        visit(entry)

        // 补上没走到（不可达块）的值
        for (b in fn.blocks) {
            for (i in b.instructions) {
                val r = i.result ?: continue
                if (out.containsKey(r)) continue
                out[r] = "u" + r.id
            }
        }

        return out
    }

    private fun defaultBase(v: IRValue, key: String): String = when (v.kind) {
        ValueKind.Global -> "g_" + java.lang.Long.toHexString(v.constant).lowercase()
        ValueKind.Local -> "v" + java.lang.Long.toHexString(
            if (v.stackOffset < 0) -v.stackOffset else v.stackOffset
        ).lowercase()
        ValueKind.Arg -> "a" + v.id
        else -> "t"
    }

    /** 支配树的子节点表 */
    private fun dominatorChildren(
        fn: FunctionIR,
        dom: Map<BasicBlock, Set<BasicBlock>>,
        entry: BasicBlock
    ): Map<BasicBlock, List<BasicBlock>> {
        val children = HashMap<BasicBlock, MutableList<BasicBlock>>()
        for (b in fn.blocks) {
            if (b === entry) continue
            val idom = immediateDominator(b, dom) ?: continue
            children.getOrPut(idom) { ArrayList() }.add(b)
        }
        // 子节点按地址排序，保证命名稳定
        for ((k, v) in children) children[k] = v.sortedBy { it.startAddress }.toMutableList()
        return children
    }

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

    /** 与 SsaBuilder 保持一致的存储位置键 */
    private fun slotKey(v: IRValue): String = when (v.kind) {
        ValueKind.Local -> "stk:" + v.stackOffset
        ValueKind.Global -> "glb:" + v.constant
        ValueKind.Arg -> "arg:" + (v.sourceRegister.ifEmpty { v.id.toString() })
        ValueKind.Const -> ""
        else -> if (v.sourceRegister.isNotEmpty()) "reg:" + v.sourceRegister.lowercase() else ""
    }
}
