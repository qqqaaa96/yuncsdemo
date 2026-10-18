package sy.great.toolbox.tools

/**
 * 控制流图构建。
 *
 * 当前实现（可达性扫描，不是线性扫描）：
 *   1. 以函数入口为种子，建立「待分析地址队列」
 *   2. 只解码「从入口可达」的地址：
 *        · 普通指令 → 推进到下一条
 *        · ret / eret → 不推进（控制流终止）
 *        · br Xn（间接） → 不推进（目标未知）
 *        · b target → 只推进到 target
 *        · b.cond → 推进到 target 与下一条
 *        · bl（调用） → 只推进到下一条
 *   3. 解码结果按地址排序，按「leader + 地址连续性」切块
 *   4. 连接边：条件分支分出 taken / fallthrough；向后跳转记为回边
 *
 * 与线性扫描的区别：
 *   线性扫描会把 ret / b 之后的不可达字节也当成基本块，
 *   制造出「看起来真实但实际不可达」的假 CFG。
 *   本实现不推进不可达地址，因此不会产生这类假块。
 *
 * 仍未实现（不可声称已支持）：
 *   · 间接跳转 br Xn 的目标解析 / Jump Table
 *   · NORETURN 函数（abort / exit 等）
 *   · Tail Call（b 到其他函数）
 *   · 真正的递归函数发现（现为：符号表 + entry + 段起点）
 */
object CfgBuilder {

    data class Block(
        val id: Int,
        val start: Long,
        val end: Long,             // 不包含
        val insns: List<Arm64Disasm.Insn>,
        val successors: List<Long>,
        val isEntry: Boolean,
        val isExit: Boolean,
        val label: String,         // 用于图形显示的简短标签
        /** 所属函数序号；单函数构建时全为 0 */
        val funcIndex: Int = 0
    )

    data class Edge(
        val from: Long,
        val to: Long,
        val kind: EdgeKind
    )

    /**
     * 边的语义类型。
     *
     * 命名与语义严格对应：
     *   FALLTHROUGH —— 顺序下落（普通指令 / 调用返回后的下一条）
     *   TAKEN       —— 条件分支的“真”（条件成立，跳向 target）
     *   CALL        —— 函数调用（bl），不是控制流转移，目前不作为边产生
     *   RETURN      —— 保留兼容名；不再用于“向后跳”，仅保留以免外部引用断裂
     *   UNCOND      —— 无条件跳转 b target（不是条件分支，不得标 T/F）
     *   INDIRECT    —— 间接跳转 br Xn / 间接调用 blr Xn（目标未知）
     */
    enum class EdgeKind { FALLTHROUGH, TAKEN, CALL, RETURN, UNCOND, INDIRECT }

    data class Graph(
        val blocks: List<Block>,
        val edges: List<Edge>,
        val entry: Long,
        val truncated: Boolean,
        val insnCount: Int
    )

    /**
     * 从代码段构建函数 CFG。
     *
     * @param bytes 代码段字节
     * @param baseAddr 代码段虚拟地址
     * @param funcAddr 函数起始虚拟地址
     * @param funcSize 函数大小（0 表示未知）
     * @param maxInsns 最多解码多少条指令（安全上限）
     * @param nextFuncAddr 下一个函数的起始地址（0 表示未知）。
     *        当 funcSize 为 0 时，用它作为硬上限，避免把后续函数吞进来。
     */
    /**
     * 已知不会返回的函数名（小写匹配）。
     *
     * 用途：遇到 `bl <noreturn>` 时，不再继续到下一条。
     * 否则 `abort()` 之后的字节会被当成可达代码，把假路径带进图里。
     */
    private val NORETURN_NAMES = setOf(
        "abort", "exit", "_exit", "_exit_group", "__exit",
        "__assert_fail", "__assert2", "__stack_chk_fail",
        "__cxa_pure_virtual", "__cxa_throw", "__stack_chk_fail_local",
        "__fortify_fail", "__chk_fail", "panic", "_abort",
        "longjmp", "_longjmp", "siglongjmp", "pthread_exit"
    )

    fun build(
        bytes: ByteArray,
        baseAddr: Long,
        funcAddr: Long,
        funcSize: Long = 0,
        maxInsns: Int = 4000,
        nextFuncAddr: Long = 0L,
        noreturnAddrs: Set<Long> = emptySet()
    ): Graph {
        val startOff = (funcAddr - baseAddr).toInt()
        if (startOff < 0 || startOff >= bytes.size) {
            return Graph(emptyList(), emptyList(), funcAddr, true, 0)
        }

        // 限制扫描范围
        // 优先级：函数 size > 下一个函数地址 > 代码段末尾，
        // 最后再被 maxInsns 与字节长度双重约束。
        // funcSize 是 Long，直接 toInt() 可能溢出成负数；先夹到合法范围。
        var limitBySize = if (funcSize > 0) {
            val capped = minOf(funcSize, bytes.size.toLong()).toInt()
            startOff + capped
        } else {
            bytes.size
        }
        // nextFuncAddr 是 Long.MAX_VALUE 哨兵值时，直接减 baseAddr 再 toInt() 会溢出。
        // 先算成 Long，确认落在本段范围内再转 Int；超范围就当作“无上限”忽略。
        if (nextFuncAddr > funcAddr && nextFuncAddr != Long.MAX_VALUE) {
            val nextOffLong = nextFuncAddr - baseAddr
            if (nextOffLong > startOff && nextOffLong <= Int.MAX_VALUE) {
                val nextOff = nextOffLong.toInt()
                if (nextOff < limitBySize) {
                    limitBySize = nextOff
                }
            }
        }
        val limit = minOf(limitBySize, bytes.size, startOff + maxInsns * 4)

        // ============================================================
        // 可达性工作队列（Reachable Recursive Descent）
        //
        // 旧实现是线性扫描：从函数头开始每 4 字节 decode，
        // 一路扫到上限。它会把「文件中连续存在但控制流不可达」的
        // 字节也当成基本块，从而制造出假 CFG：
        //
        //   0x1008  mov w1, #1
        //   0x100c  ret          ← 这里是出口
        //   0x1010  ...          ← 可能是另一个分支的代码，也可能是数据
        //
        // 正确做法（与 IDA / Ghidra 一致）：
        //   只解码「从入口可达」的地址。
        //   ret / 无条件跳转 不会把下一条自动加入队列；
        //   只有真实分支目标或真实下落才会。
        //
        // 这样 ret 之后的死字节不会进入 CFG。
        // ============================================================

        val decoded = ArrayList<Arm64Disasm.Insn>()
        val decodedAt = HashMap<Long, Int>()          // 地址 → decoded 下标
        val leaders = HashSet<Long>()                  // 基本块起始地址
        val work = ArrayDeque<Long>()                  // 待分析地址队列
        val queued = HashSet<Long>()                   // 已入过队的地址，防重复
        var truncated = false

        fun push(addr: Long) {
            if (addr < baseAddr) return
            val o = (addr - baseAddr).toInt()
            if (o < 0 || o + 4 > limit) return
            if (addr % 4L != 0L) return                     // 必须指令对齐
            if (queued.add(addr)) work.addLast(addr)
        }

        leaders.add(funcAddr)
        push(funcAddr)

        while (work.isNotEmpty()) {
            if (decoded.size >= maxInsns) {
                truncated = true
                break
            }
            val addr = work.removeFirst()
            if (decodedAt.containsKey(addr)) continue

            val off = (addr - baseAddr).toInt()
            val insn = Arm64Disasm.decode(bytes, off, baseAddr) ?: continue
            decodedAt[addr] = decoded.size
            decoded.add(insn)

            when {
                // ---- 返回：无后继，不推进 ----
                insn.isReturn -> {
                    // 不 push 下一条
                }

                // ---- 函数调用（bl / blr）：保留调用后的返回点 ----
                //
                // 必须放在 isIndirect 之前。
                //
                // 原因：blr（间接调用）同时带 isCall=true 与 isIndirect=true。
                // 若先命中 isIndirect，blr 会被当成“目标未知的跳转”而不推进，
                // 导致调用之后的代码全部不可达 —— 这是真实的 CFG 错误。
                // BLR 会把返回地址写入 LR，调用后正常回到下一条指令。
                insn.isCall -> {
                    // 目标函数（bl 的直接目标）不加入本函数队列：它是另一个函数。
                    //
                    // 但如果该目标是已知的 noreturn（abort / exit 等），
                    // 调用后不会返回，后面的代码不可达，不得继续推进。
                    val t = insn.target ?: -1L
                    val isNoReturn = t > 0 && noreturnAddrs.contains(t)
                    if (!isNoReturn) {
                        push(insn.addr + 4)
                    }
                }

                // ---- 间接跳转（纯 BR）：目标未知，不推进 ----
                //
                // 只处理“非调用”的寄存器跳转（br Xn）。
                // blr 已在上面被 isCall 分支接管，不会走到这里。
                insn.isIndirect -> {
                    // 不 push 下一条
                }

                // ---- 条件分支：真分支 + 假分支 ----
                insn.isCondBranch -> {
                    insn.target?.let {
                        leaders.add(it)
                        push(it)
                    }
                    // 假分支：落到下一条
                    leaders.add(insn.addr + 4)
                    push(insn.addr + 4)
                }

                // ---- 无条件跳转：只跟目标 ----
                insn.isBranch -> {
                    insn.target?.let {
                        leaders.add(it)
                        push(it)
                    }
                    // 不 push 下一条：那是不可达字节
                }

                // ---- 普通指令：顺序下落 ----
                else -> {
                    push(insn.addr + 4)
                }
            }
        }

        // 按地址排序，保证切块顺序与内存顺序一致
        decoded.sortBy { it.addr }

        // 重新建立 地址 → 下标 映射（排序后下标变了）
        decodedAt.clear()
        decoded.forEachIndexed { i, insn -> decodedAt[insn.addr] = i }

        // 只保留「真实可达且是块首」的 leader
        leaders.retainAll(decodedAt.keys)
        leaders.add(funcAddr)

        if (decoded.isEmpty()) {
            return Graph(emptyList(), emptyList(), funcAddr, false, 0)
        }

        val lastAddr = decoded.last().addr

        // 按 leader 切块
        val blocks = ArrayList<Block>()
        var current = ArrayList<Arm64Disasm.Insn>()
        var blockStart = decoded.first().addr
        var id = 0

        for ((idx, insn) in decoded.withIndex()) {
            // 两种情况必须切块：
            //   1) 该地址是 leader（分支目标 / 条件分支下落 / 函数入口）
            //   2) 与上一条指令地址不连续（说明中间有不可达字节被跳过）
            //
            // 第 2 条很关键：可达性扫描后 decoded 里可能存在地址跳空，
            // 如果不检查连续性，两段不相邻的指令会被拼进同一个块。
            val prev = if (idx > 0) decoded[idx - 1] else null
            val contiguous = prev != null && prev.addr + 4 == insn.addr

            val isLeader = (leaders.contains(insn.addr) || !contiguous) && current.isNotEmpty()
            if (isLeader) {
                blocks.add(makeBlock(id++, blockStart, current))
                current = ArrayList()
                blockStart = insn.addr
            }
            current.add(insn)
        }
        if (current.isNotEmpty()) {
            blocks.add(makeBlock(id, blockStart, current))
        }

        // ============================================================
        // 连边（按真实控制流语义）
        //
        // 规则：
        //   ret / eret        —— 无后继（出口）
        //   bl                —— 函数调用，不是控制流转移，不画边
        //   blr               —— 间接调用，同样不画边
        //   br Xn             —— 间接跳转，目标未知，不画边（标记为出口）
        //   b   target        —— 无条件跳转到 target（内部则连，外部则标记外部）
        //   b.cond target     —— TAKEN 到 target，FALLTHROUGH 到下一条
        //   cbz/cbnz/tbz/tbnz —— 同上（条件分支）
        //   普通指令          —— FALLTHROUGH 到下一条
        //
        // 关键：只有目标落在本函数块集合内的边才连。
        // 跳到函数外的目标不画成节点，避免把不相关的块拉进来。
        // ============================================================
        val blockByStart = blocks.associateBy { b -> b.start }
        val edges = ArrayList<Edge>()

        // 用「地址 + 类型」去重，避免同一分支重复连边
        val seen = HashSet<String>()
        fun link(from: Long, to: Long, kind: EdgeKind) {
            val k = from.toString() + "|" + to.toString() + "|" + kind.name
            if (seen.add(k)) edges.add(Edge(from, to, kind))
        }

        for (b in blocks) {
            val last = b.insns.last()
            val nextAddr = b.end

            when {
                // 1) 返回：无后继
                last.isReturn -> {
                }

                // 2) 函数调用（bl / blr）：目标函数不进本图，只保留下落边
                //
                // 必须放在 isIndirect 之前。
                // blr（间接调用）同时是 isCall 与 isIndirect；
                // 调用后正常返回下一条，所以必须连 FALLTHROUGH。
                last.isCall -> {
                    if (blockByStart.containsKey(nextAddr)) {
                        link(b.start, nextAddr, EdgeKind.FALLTHROUGH)
                    }
                }

                // 3) 纯间接跳转 br Xn：目标未知
                //
                // 不产生任何边：
                //   · 不能当成“无后继出口”（那是假出口）；
                //   · 也不能挂自环或具体块（目标未解析，连边就是捏造）。
                // 该块既无出边、又不标 isExit（见下方），如实表现为“去向未解析”。
                //
                // 注意：blr 已在上面被 isCall 接管，不会走到这里。
                last.isIndirect -> {
                }

                // 4) 条件分支：真（跳 target）/ 假（顺序下落）两条边
                last.isCondBranch -> {
                    val t = last.target
                    if (t != null && blockByStart.containsKey(t)) {
                        link(b.start, t, EdgeKind.TAKEN)
                    }
                    if (blockByStart.containsKey(nextAddr)) {
                        link(b.start, nextAddr, EdgeKind.FALLTHROUGH)
                    }
                }

                // 5) 无条件跳转 b target
                //
                // 关键修正：无条件跳转不是条件分支，绝不能标成 TAKEN。
                // 向后跳与向前跳同语义，都是 UNCOND；是否回边由布局层按层号判定。
                last.isBranch -> {
                    val t = last.target
                    if (t != null && blockByStart.containsKey(t)) {
                        link(b.start, t, EdgeKind.UNCOND)
                    }
                    // 目标在函数外：不连边，让该块成为对外出口
                }

                // 6) 普通顺序执行
                else -> {
                    if (blockByStart.containsKey(nextAddr)) {
                        link(b.start, nextAddr, EdgeKind.FALLTHROUGH)
                    }
                }
            }
        }

        // 标记入口 / 出口
        //
        // 出口（真正离开本函数）：
        //   1) 显式 return / eret
        //   2) 无条件跳转 b 到函数外（对外尾调用）
        //   3) 没有任何出边的末尾块
        //
        // 注意：间接跳转 br Xn 的目标未知，它是“去向未解析”，
        // 不是 return 出口，不得标成 isExit（那是假出口）。
        //
        // 判定直接看块末尾指令，不依赖边：间接块不产生任何边，
        // 若用 !hasOut 判定会被误标成出口。
        val hasOut = edges.map { it.from }.toHashSet()

        // 入口兜底：
        //   正常情况下入口块就是 funcAddr 所在的块。
        //   但如果 funcAddr 本身解码失败（越界 / 非法字节），
        //   不会有任何块的 start == funcAddr，整张图就会“没有入口”，
        //   UI 的入口高亮与布局的 entryId 都会失效。
        //   此时把地址最小的可达块作为入口，保证图始终有唯一入口。
        val hasEntryBlock = blocks.any { it.start == funcAddr }
        val fallbackEntry = if (hasEntryBlock) funcAddr
        else (blocks.minByOrNull { it.start }?.start ?: funcAddr)

        val finalBlocks = blocks.map { b ->
            val last = b.insns.last()
            b.copy(
                isEntry = b.start == fallbackEntry,
                isExit = !last.isIndirect &&
                        (last.isReturn || !hasOut.contains(b.start))
            )
        }.map { b ->
            b.copy(label = shortLabel(b))
        }

        return Graph(
            blocks = finalBlocks,
            edges = edges,
            entry = fallbackEntry,
            truncated = truncated,
            insnCount = decoded.size
        )
    }

    /**
     * 构造基本块。
     *
     * successors 固定返回空列表。
     *
     * 原因：控制流的唯一权威数据源是 build() 末尾按真实指令语义生成的 edge 列表。
     * 早期实现在这里"猜"一份 successors，又在 edge 阶段"重建"一份，
     * 两份逻辑不一致时，UI 用不同字段会看到不同结果。
     * 现在统一：只用 edges。
     */
    private fun makeBlock(id: Int, start: Long, insns: List<Arm64Disasm.Insn>): Block {
        val end = insns.last().addr + 4
        val last = insns.last()
        return Block(id, start, end, insns, emptyList(), false, last.isReturn, "")
    }

    /** 生成块标签：首两条指令 */
    private fun shortLabel(b: Block): String {
        val head = b.insns.take(2).joinToString("\n") { it.text }
        return if (b.insns.size > 2) head + "\n..." else head
    }

    // ---------- 函数列表 ----------

    data class FuncInfo(
        val name: String,
        val addr: Long,
        val size: Long,
        val isImport: Boolean,
        val isExport: Boolean,
        /** 批量分析后填入：基本块数（-1 表示未分析） */
        val blockCount: Int = -1,
        /** 指令数 */
        val insnCount: Int = -1,
        /** 边数 */
        val edgeCount: Int = -1
    ) {
        val analyzed: Boolean get() = blockCount >= 0
        /** 地址是否有效（非 0、非导入） */
        val hasValidAddr: Boolean get() = addr != 0L && !isImport
    }

    /**
     * 入口地址是否真的落在可执行段内。
     *
     * 这是比 hasValidAddr（只看 addr != 0 && !isImport）更严的一道门：
     * 符号表里可能存在 addr 非 0、却有指向数据区的“假函数”符号，
     * 拿它去解码只会产出假块。
     */
    private fun isExecutableAddr(elf: ElfParser.Elf, addr: Long): Boolean =
        elf.executableSegments().any { addr >= it.vaddr && addr < it.vaddr + maxOf(it.memSize, it.fileSize) }

    /**
     * 该地址处是否为「填充零字节」。
     *
     * 连续两个 8 字节（16 字节）全零即视为对齐填充，不是函数入口。
     * 只对入口地址做这件事，不去搬构建过程中的单条零，
     * 避免误伤真实的 udf 指令。
     */
    private fun isZeroPaddingAt(elf: ElfParser.Elf, addr: Long): Boolean {
        // 用 memSize 与 fileSize 的较大值做上界：
        // 只拿 fileSize 判会把 memSize > fileSize 的段尾部误判为“不可执行”，
        // 那些地址上的真函数会被整体丢弃。
        val seg = elf.executableSegments().firstOrNull {
            addr >= it.vaddr && addr < it.vaddr + maxOf(it.memSize, it.fileSize)
        } ?: return false
        // 只判定「文件里真实存在」的那部分：
        // 超出 fileSize 的地址对应 .bss 之类的零填充，没有文件字节可读，
        // 这里返回 false，交给上层用“解码失败”去处理，不要凭猜测剔除。
        val delta = addr - seg.vaddr
        if (delta < 0 || delta >= seg.fileSize) return false
        if (delta > Int.MAX_VALUE) return false
        val offLong = seg.fileOffset + delta
        if (offLong < 0 || offLong > Int.MAX_VALUE) return false
        val off = offLong.toInt()
        // 必须要求完整的 16 字节都在本段文件中，否则会读到下一段的数据，
        // 把真函数误判成零填充。
        if (off + 16 > elf.bytes.size) return false
        for (i in 0 until 16) {
            if (elf.bytes[off + i].toInt() != 0) return false
        }
        return true
    }

    /**
     * 函数入口发现（不依赖符号表）。
     *
     * 优先级：
     *   1. ELF entry（e_entry）—— 这是最可靠的入口，stripped 文件也有
     *   2. 符号表里带有效地址的函数
     *   3. 从上述入口出发，沿分支目标递归发现
     *
     * 这就是“recursive descent”的种子阶段：
     * 找到若干可信入口后，由 buildWhole 递归展开。
     *
     * 入口筛选（严）：
     *   · addr == 0 或导入符号         —— 当然不要
     *   · addr 不在可执行段内         —— 假函数，会造成假 CFG
     *   · 入口处是连续零填充        —— 不是代码
     */
    fun discoverFunctions(elf: ElfParser.Elf): List<FuncInfo> {
        val out = LinkedHashMap<Long, FuncInfo>()

        // 统一的可信入口判定。
        // 所有来源都走这一道，避免“符号表进来了、entry 进来了却不过滤”。
        fun acceptable(addr: Long): Boolean {
            if (addr == 0L) return false
            if (!isExecutableAddr(elf, addr)) return false
            if (isZeroPaddingAt(elf, addr)) return false
            return true
        }

        // ---- 1. 符号表（可能为空）----
        for (f in functions(elf)) {
            if (!f.hasValidAddr) continue
            if (!acceptable(f.addr)) continue
            if (!out.containsKey(f.addr)) out[f.addr] = f
        }

        // ---- 2. ELF entry ----
        val e = elf.entry
        if (e != 0L && acceptable(e)) {
            if (!out.containsKey(e)) {
                // 看看符号表里有没有对应名字
                val named = elf.symbols.firstOrNull { it.value == e && it.name.isNotBlank() }
                out[e] = FuncInfo(
                    name = named?.name ?: "entry",
                    addr = e,
                    size = named?.size ?: 0L,
                    isImport = false,
                    isExport = true
                )
            }
        }

        // ---- 3. 递归发现（重要）----
        //
        // 发布版 .so 通常被 strip 过：.symtab 没了，只剩 .dynsym（导出函数）。
        // 如果只依赖符号表，一个几 MB 的 so 可能只识别出 5~20 个函数，
        // 内部几百个函数全部丢失 —— 这才是“解析不出控制流”的真正原因。
        //
        // 这里做与 IDA / Ghidra 一致的递归发现：
        //   对已发现的每个函数跑一遍控制流分析，
        //   把所有 bl / b 的直接目标当作候选函数入口，加入队列，
        //   重复直到没有新入口。
        //
        // 注意：这一阶段不依赖 nextAddrMap（它需要“已知全部函数”），
        // 用 maxInsns 限制解码量，避免越界。
        run {
            val segs = elf.executableSegments()
            if (segs.isEmpty()) return@run

            // 已知的 noreturn 地址，避免把 exit/abort 之后当函数
            val noreturn = collectNoReturnAddrs(elf)

            // 已分析过的地址（避免每轮重复跑同一个函数）
            val analyzed = HashSet<Long>()
            // 本轮要处理的种子（第一轮 = 符号表 + entry）
            var seed = ArrayList<FuncInfo>(out.values)

            var round = 0
            // 轮次上限只用于防止异常数据陷入死循环。
            // 正常文件依靠 seed 为空自然收敛，与轮次无关；
            // 6 轮对大型 so 偏少，会静默丢掉还没展开完的函数。
            val MAX_ROUNDS = 32
            val MAX_FUNCS = 20000
            val MAX_INSNS_PER_FUNC = 1200

            while (round < MAX_ROUNDS && out.size < MAX_FUNCS && seed.isNotEmpty()) {
                round++

                // 本轮新发现的函数，作为下一轮种子
                val nextSeed = ArrayList<FuncInfo>()

                // 关键（修复）：每轮重建 nextAddrMap。
                //
                // 之前这里传 nextFuncAddr = 0L，后果是：
                //   strip 过的 .so 里函数 size 通常为 0，
                //   limitBySize 退化成“整段末尾”，
                //   第一个函数就会一路解码到段尾，
                //   后面的函数全被吞进第一张图，表现为“其他函数解析不出控制流”。
                //
                // 用“已发现函数地址”排序后取下一个作为边界，
                // 就能把解码限制在当前函数附近。
                val roundNext = nextAddrMap(out.values.toList())

                // 本轮新发现的函数地址集：用来即时重算边界。
                // 否则“本轮新发现 + 本轮就分析”的函数拿不到自己的下一个函数地址。
                val knownAddrs = java.util.TreeSet<Long>()
                knownAddrs.addAll(out.keys)

                for (f in seed) {
                    if (out.size >= MAX_FUNCS) break
                    if (!analyzed.add(f.addr)) continue   // 已分析过，跳过

                    // 注意：roundNext 是本轮开始时的快照，不含本轮新发现的函数。
                    // 新函数如果在这里才拿到边界，就会解码到段尾并把后面的函数吞掉。
                    // 因此：本轮新发现但快照里没有的函数，直接判到段尾为止，
                    // 同时用 maxInsns 兜底，不再拿一个错误的上限去截。
                    val boundary = if (f.size > 0) {
                        0L
                    } else {
                        // 实时向后的第一个已知函数，优先用快照里的（已排序更可靠），
                        // 拿不到再问 TreeSet。两者都没有就是段内最后一个函数。
                        roundNext[f.addr]
                            ?: knownAddrs.higher(f.addr)
                            ?: Long.MAX_VALUE
                    }
                    val g = buildForFunc(
                        elf = elf,
                        f = f,
                        maxInsns = MAX_INSNS_PER_FUNC,
                        // size 缺失时用下一个函数地址限定
                        nextFuncAddr = boundary,
                        noreturnAddrs = noreturn
                    )
                    if (g.blocks.isEmpty()) continue

                    // 收集所有直接分支/调用目标
                    for (b in g.blocks) {
                        val last = b.insns.lastOrNull() ?: continue
                        val t = last.target ?: continue

                        // 只接受“可执行段内 + 非零填充”的地址
                        if (t == 0L) continue
                        if (!isExecutableAddr(elf, t)) continue
                        if (isZeroPaddingAt(elf, t)) continue
                        if (out.containsKey(t)) continue

                        // 收集两类目标：
                        //   1) bl 的调用目标      —— 一定是函数入口
                        //   2) b 的跳转目标       —— 尾调用（tail call）时也是函数入口
                        //
                        // 修正（重要）：
                        // 之前只收 bl，导致大量尾调用函数漏掉。
                        // ARM64 上 `return foo(x);` 会被优化成 `b foo`，
                        // 这类目标在 IDA / Ghidra 里都会被识别为函数。
                        //
                        // 为了不把“函数内的普通跳转标签”误判成函数，
                        // 加三道约束：
                        //   · 目标不能落在“当前函数已解析的块内”（本函数内部的跳转不算）
                        //   · 目标不能是当前函数自身的入口
                        //   · 必需是无条件分支（条件分支的目标是基本块，不是函数）
                        // 不再要求“目标 > 当前块末尾”：代码共享会把尾调用编成向低地址的 b。
                        val isCallTarget = last.isCall
                        // 尾调用不限定跳转方向：
                        // ARM64 上 `return shared_helper(x);` 完全可能编成向前的 b，
                        // 也可能因为代码共享跳到更低的地址。
                        // 只限定“不是条件分支 + 不是本函数已知块 + 不是自身入口”。
                        val isTailCall = last.isBranch && !last.isCondBranch &&
                                t != f.addr &&
                                g.blocks.none { it.start == t }

                        if (!isCallTarget && !isTailCall) continue

                        // 命名：能查到符号就用符号名，否则 sub_xxx
                        val named = elf.symbols.firstOrNull {
                            it.value == t && it.name.isNotBlank()
                        }
                        val nf = FuncInfo(
                            name = named?.name ?: ("sub_" + java.lang.Long.toHexString(t)),
                            addr = t,
                            size = named?.size ?: 0L,
                            isImport = false,
                            isExport = false
                        )
                        out[t] = nf
                        // 立即写入实时地址集，让后续同类函数能拿到正确边界
                        knownAddrs.add(t)
                        nextSeed.add(nf)
                    }
                }

                // 下一轮只处理本轮新发现的函数；为空则已收敛
                seed = nextSeed
            }
        }

        return sortForAnalysis(out.values.toList())
    }

    /**
     * 从符号表提取函数列表。
     *
     * 排序规则（便于分析）：
     *   1) 有有效地址的函数按地址升序排在前
     *   2) 地址为 0 或导入符号排在最后
     */
    fun functions(elf: ElfParser.Elf): List<FuncInfo> {
        val out = ArrayList<FuncInfo>()

        // 可执行节区范围，用于判定 NOTYPE 符号是否可能是代码
        val execRanges = elf.sections
            .filter { (it.flags and 4L) != 0L && it.size > 0 }
            .map { it.addr to (it.addr + it.size) }

        fun inExec(addr: Long): Boolean =
            execRanges.any { addr >= it.first && addr < it.second }

        for (s in elf.symbols) {
            if (s.name.isBlank()) continue

            // 跳过编译器生成的本地标签与节区标记符号，
            // 它们不是函数，混进来会干扰列表与统计。
            //
            // 注意：这里必须用 '\$' 转义或单字符比较，
            // 写成 "$d" 会被当作字符串插值，编译报 Unresolved reference。
            val n = s.name
            if (n.startsWith('$')) continue
            if (n.startsWith(".L") || n.startsWith(".l")) continue

            // 收入条件：
            //   1) 显式声明为函数（STT_FUNC）
            //   2) 或类型未知（NOTYPE）但地址落在可执行节区内
            //
            // 第 2 条很关键：部分工具链（尤其 -O0 编译的本地函数）
            // 不会给符号打 STT_FUNC 标记，只按 STT_FUNC 过滤会漏掉
            // main / add 这类函数，最终表现为“没有可执行的代码节区”。
            val type = s.info and 0xF
            val treatAsFunc = when {
                s.isImport -> true
                type == 2 -> true
                type == 0 -> s.value != 0L && inExec(s.value)
                else -> false
            }
            if (!treatAsFunc) continue

            out.add(
                FuncInfo(
                    name = s.name,
                    addr = s.value,
                    size = s.size,
                    isImport = s.isImport,
                    isExport = s.bind == "GLOBAL" && !s.isImport
                )
            )
        }
        return sortForAnalysis(out)
    }

    /**
     * 按“有利于分析”的顺序排序：
     * 有效地址的升序在前，0 地址/导入符号排到最后。
     */
    fun sortForAnalysis(list: List<FuncInfo>): List<FuncInfo> {
        val (valid, invalid) = list.partition { it.hasValidAddr }
        return valid.sortedBy { it.addr } + invalid.sortedBy { it.name }
    }

    /** 按复杂度排序（块数降序），未分析的排最后 */
    fun sortByComplexity(list: List<FuncInfo>): List<FuncInfo> {
        val (analyzed, rest) = list.partition { it.analyzed }
        return analyzed.sortedByDescending { it.blockCount } + sortForAnalysis(rest)
    }

    /**
     * 批量分析。对每个函数构建 CFG，只保留摘要（不保留完整图，省内存）。
     *
     * onProgress 回调：已处理数 / 总数 / 当前函数名
     * shouldStop 回调：返回 true 则提前终止
     */
    fun analyzeAll(
        elf: ElfParser.Elf,
        funcs: List<FuncInfo>,
        maxInsnsPerFunc: Int = 2000,
        onProgress: (done: Int, total: Int, current: String) -> Unit,
        shouldStop: () -> Boolean
    ): List<FuncInfo> {
        val result = ArrayList<FuncInfo>(funcs.size)
        var done = 0
        for (f in funcs) {
            if (shouldStop()) {
                result.add(f)
                continue
            }
            if (!f.hasValidAddr) {
                result.add(f)
                done++
                onProgress(done, funcs.size, f.name)
                continue
            }
            val g = buildForFunc(elf, f, maxInsnsPerFunc)
            result.add(
                f.copy(
                    blockCount = g.blocks.size,
                    insnCount = g.insnCount,
                    edgeCount = g.edges.size
                )
            )
            done++
            onProgress(done, funcs.size, f.name)
        }
        return result
    }

    /**
     * 为单个函数构建 CFG（供内部与外部共用）。
     *
     * @param nextFuncAddr 下一个函数的起始地址（0 表示未知）。
     *        当符号表里 f.size 为 0 时，用它防止解码跑到别的函数里。
     */
    fun buildForFunc(
        elf: ElfParser.Elf,
        f: FuncInfo,
        maxInsns: Int = 3000,
        nextFuncAddr: Long = 0L,
        noreturnAddrs: Set<Long> = emptySet()
    ): Graph {
        if (f.isImport || f.addr == 0L) {
            return Graph(emptyList(), emptyList(), f.addr, false, 0)
        }

        // ============================================================
        // 定位函数所在的代码段
        //
        // 统一用「可执行段（PT_LOAD + PF_X）」作为分析范围，
        // 并用 vaToOffset 做「虚拟地址 → 文件偏移」换算。
        //
        // 不再依赖节区表，也不再把 VA 直接当文件偏移。
        // ============================================================
        val segs = elf.executableSegments()

        // 必须是「包含本函数地址」的那一段。
        //
        // 早期实现会在找不到时回退到「包含 entry 的段」，这是错的：
        // 那样会用另一个段的字节，又以本函数地址为基准解码，
        // startOff = funcAddr - seg.vaddr 会算出越界值，
        // 结果是“拿错字节、解出错误指令”，直接污染整张图。
        //
        // 所以：找不到包含该地址的段，就如实返回空图，不做任何猜测。
        val seg = segs.firstOrNull { it.contains(f.addr) }
            ?: return Graph(emptyList(), emptyList(), f.addr, false, 0)

        val baseVa = seg.vaddr

        val off = seg.fileOffset.toInt()
        val len = seg.fileSize.toInt().coerceAtMost(elf.bytes.size - off)
        if (off < 0 || len <= 0) {
            return Graph(emptyList(), emptyList(), f.addr, false, 0)
        }

        val bytes = elf.bytes.copyOfRange(off, off + len)
        return build(
            bytes = bytes,
            baseAddr = baseVa,
            funcAddr = f.addr,
            funcSize = if (f.size > 0) f.size else 0,
            maxInsns = maxInsns,
            nextFuncAddr = nextFuncAddr,
            noreturnAddrs = noreturnAddrs
        )
    }

    /**
     * 迭代收敛式构建：在基础 CFG 上解析跳转表，把 br xN 的未知边补回来。
     *
     * 流程（对应业界做法里的“迭代到不动点”）：
     *   1) 先用 build() 得到初始图（含“目标未解析”的 br 块）
     *   2) 对每个以 br 结尾的块，用 JumpTableResolver 尝试解析目标
     *   3) 目标中若有不属于当前图的地址，就对该地址再 build 一次并合并
     *   4) 重复步骤 2~3，直到没有新块加入（或达上限）
     *
     * 关键：解析失败就不动，绝不猜目标。
     *
     * @param maxRounds 最多迭代轮数（防止异常数据导致死循环）
     * @param maxTotalInsns 全图指令数上限
     */
    fun buildWithJumpTables(
        elf: ElfParser.Elf,
        addr: Long,
        maxInsns: Int = 1500,
        maxRounds: Int = 4,
        maxTotalInsns: Int = 6000,
    ): Graph {
        // 上界用 maxOf(memSize, fileSize)，与 contains() 保持一致。
        // 只用 fileSize 会把段尾部的函数误判为“不在本段”。
        val seg = elf.executableSegments().firstOrNull {
            addr >= it.vaddr && addr < it.vaddr + maxOf(it.memSize, it.fileSize)
        } ?: return Graph(emptyList(), emptyList(), addr, false, 0)

        val off = seg.fileOffset.toInt()
        val len = seg.fileSize.toInt().coerceAtMost(elf.bytes.size - off)
        if (off < 0 || len <= 0) return Graph(emptyList(), emptyList(), addr, false, 0)
        val bytes = elf.bytes.copyOfRange(off, off + len)
        val baseVa = seg.vaddr

        val resolver = JumpTableResolver(elf)
        val covered = HashSet<Long>()          // 已分析过的起始地址
        val allBlocks = LinkedHashMap<Long, Block>()
        val allEdges = LinkedHashMap<String, Edge>()
        var truncated = false

        fun addGraph(g: Graph) {
            for (b in g.blocks) {
                if (allBlocks.size >= 2000) { truncated = true; break }
                allBlocks[b.start] = b
            }
            for (e in g.edges) {
                val k = e.from.toString() + "|" + e.to.toString() + "|" + e.kind.name
                allEdges[k] = e
            }
        }

        // 第一轮：起点
        val first = build(
            bytes = bytes, baseAddr = baseVa, funcAddr = addr,
            funcSize = 0, maxInsns = maxInsns, nextFuncAddr = 0L,
            noreturnAddrs = collectNoReturnAddrs(elf)
        )
        addGraph(first)
        covered.add(addr)

        // 迭代：处理 br 块
        var round = 0
        while (round < maxRounds) {
            round++
            val pending = ArrayList<Long>()

            for (b in allBlocks.values.toList()) {
                val last = b.insns.lastOrNull() ?: continue
                if (!last.isIndirect || last.isCall) continue
                // 已经有出边了就跳过（已经被解析或本来就是条件出口）
                if (allEdges.keys.any { it.startsWith(b.start.toString() + "|") }) continue

                val targets = resolver.resolve(b.insns) ?: continue
                for (t in targets) {
                    if (!allBlocks.containsKey(t) && !covered.contains(t)) {
                        pending.add(t)
                    }
                    // 补边（目标在或不在图里都先连上）
                    val k = b.start.toString() + "|" + t.toString() + "|" + EdgeKind.INDIRECT.name
                    allEdges[k] = Edge(b.start, t, EdgeKind.INDIRECT)
                }
            }

            if (pending.isEmpty()) break

            // 对每个新目标再 build 一次
            var totalInsns = allBlocks.values.sumOf { it.insns.size }
            for (t in pending) {
                if (totalInsns >= maxTotalInsns) { truncated = true; break }
                covered.add(t)
                val g = build(
                    bytes = bytes, baseAddr = baseVa, funcAddr = t,
                    funcSize = 0, maxInsns = maxInsns, nextFuncAddr = 0L,
                    noreturnAddrs = collectNoReturnAddrs(elf)
                )
                addGraph(g)
                totalInsns += g.insnCount
            }
        }

        if (allBlocks.isEmpty()) return Graph(emptyList(), emptyList(), addr, false, 0)

        // 重排序 + 重编号（按地址），保证切块与绘制稳定
        val sorted = allBlocks.values.sortedBy { it.start }
        val idMap = HashMap<Long, Int>()
        sorted.forEachIndexed { i, b -> idMap[b.start] = i }
        val blocksFinal = sorted.mapIndexed { i, b ->
            // successors 固定空（权威数据源是 edges），与原有实现一致
            b.copy(id = i, successors = emptyList())
        }
        val edgesFinal = allEdges.values.toList()

        return Graph(
            blocks = blocksFinal,
            edges = edgesFinal,
            entry = blocksFinal.firstOrNull { it.start == addr }?.start ?: blocksFinal.first().start,
            truncated = truncated,
            insnCount = blocksFinal.sumOf { it.insns.size }
        )
    }

    /**
     * 从「任意地址」开始构建控制流图。
     *
     * 用途：控制流图里点某个块的某一行 → “跳转到此”，
     * 以该地址为起点，重新画一张“从这条指令开始的控制流”。
     *
     * 与 buildForFunc 的区别：
     *   · buildForFunc 要求该地址是“函数入口”（有 FuncInfo）；
     *   · 这里只需要“落在某个可执行段内”，不要求是函数入口。
     *
     * 边界：
     *   · 用段的末尾作为扫描上限（不跨段）；
     *   · maxInsns 限制总量，防止大段文件卡死。
     * 找不到包含该地址的段时，如实返回空图。
     */
    fun buildFromAddress(
        elf: ElfParser.Elf,
        addr: Long,
        maxInsns: Int = 1500,
        name: String = "sub_" + java.lang.Long.toHexString(addr),
    ): FuncGraph? {
        val seg = elf.executableSegments().firstOrNull {
            addr >= it.vaddr && addr < it.vaddr + it.fileSize
        } ?: return null

        val off = seg.fileOffset.toInt()
        val len = seg.fileSize.toInt().coerceAtMost(elf.bytes.size - off)
        if (off < 0 || len <= 0) return null

        val bytes = elf.bytes.copyOfRange(off, off + len)
        val g = build(
            bytes = bytes,
            baseAddr = seg.vaddr,
            funcAddr = addr,
            funcSize = 0,                 // 不按函数长度限制，交给 maxInsns
            maxInsns = maxInsns,
            nextFuncAddr = 0L,
            noreturnAddrs = collectNoReturnAddrs(elf)
        )
        if (g.blocks.isEmpty()) return null

        // 统计口径与 analyzeFunctions 保持一致，不造假
        val branchBlocks = g.blocks.count { b ->
            val last = b.insns.last()
            last.isCondBranch || last.isBranch || last.isCall || last.isIndirect
        }
        val blockStarts = g.blocks.mapTo(HashSet()) { it.start }
        val unreachable = g.blocks.count { b ->
            val last = b.insns.last()
            val t = last.target
            if (last.isIndirect) true
            else last.isBranch && t != null && !blockStarts.contains(t)
        }

        return FuncGraph(
            name = name,
            addr = addr,
            graph = g,
            blockCount = g.blocks.size,
            edgeCount = g.edges.size,
            branchBlocks = branchBlocks,
            unreachable = unreachable
        )
    }

    /**
     * 从符号表收集「已知不返回」的函数地址。
     *
     * 依据：符号名（去掉版本后缀与前导下划线后）落在 NORETURN_NAMES 中。
     * 同时也会把导入符号里的这些名字对应的地址收进来。
     */
    fun collectNoReturnAddrs(elf: ElfParser.Elf): Set<Long> {
        val out = HashSet<Long>()
        for (s in elf.symbols) {
            if (s.value == 0L) continue
            if (isNoReturnName(s.name)) out.add(s.value)
        }
        return out
    }

    /** 判定符号名是否为已知 noreturn */
    private fun isNoReturnName(raw: String): Boolean {
        var n = raw.trim().lowercase()
        if (n.isEmpty()) return false
        // 去掉 PLT 后缀与版本后缀：abort@plt / abort@@GLIBC_2.2.5
        n = n.substringBefore('@')
        // 去掉前导下划线：__abort -> abort 也一并检查
        if (NORETURN_NAMES.contains(n)) return true
        val stripped = n.trimStart('_')
        if (stripped.isNotEmpty() && NORETURN_NAMES.contains(stripped)) return true
        return false
    }

    /**
     * 为一组已排序的函数，取每个函数的下一个函数地址。
     * 用于在 size 缺失时提供扫描上限。
     */
    fun nextAddrMap(funcs: List<FuncInfo>): HashMap<Long, Long> {
        // 先按地址去重排序。
        // 同一个地址可能同时来自符号表与 entry，去重避免自我覆盖。
        val sorted = funcs
            .filter { it.hasValidAddr }
            .map { it.addr }
            .distinct()
            .sorted()

        val map = HashMap<Long, Long>()
        for (i in 0 until sorted.size - 1) {
            map[sorted[i]] = sorted[i + 1]
        }
        // 最后一个函数没有“下一个函数”，这里不能用 0 表示未知：
        // 那样它会被当成“无上限”一直解码到段尾，看起来就是“末尾函数解析不出”。
        // 用一个超出任何合法地址的哨兵值，让下游能识别为“到段尾为止”。
        if (sorted.isNotEmpty()) {
            map[sorted.last()] = Long.MAX_VALUE
        }
        return map
    }

    /** 找出包含指定地址的函数 */
    fun functionAt(funcs: List<FuncInfo>, addr: Long): FuncInfo? =
        funcs.firstOrNull { it.addr == addr }

    // ---------- 按函数逐个分析（新） ----------

    /**
     * 单个函数的 CFG 结果。
     *
     * 与旧 buildWhole 的“把所有函数硬拼成一张大图”不同，
     * 这里每个函数保留成一份独立、可单独展示的 CFG。
     *
     * @param branchBlocks 含分支/跳转指令的块数。
     *        用于判定“这个函数是否真的解析出了控制流”。
     * @param unreachable 分析中遇到的“去向来解析”块数
     *        （末尾是 br Xn 或跳转目标在函数外）。
     */
    data class FuncGraph(
        val name: String,
        val addr: Long,
        val graph: Graph,
        val blockCount: Int,
        val edgeCount: Int,
        val branchBlocks: Int,
        val unreachable: Int
    )

    /**
     * 逐函数分析，只保留「真正解析出控制流」的结果。
     *
     * 与旧 buildWhole 的区别：
     *   1) 不把 400 个函数硬拼成一张图（那样无法判断块属于谁，
     *      也无法在图上区分“真块”与“假块”）；
     *   2) 每个函数做真实性过滤，分析不出控制流的直接剔除；
     *   3) 没有“拿段起点当函数”的兵底（已从 discoverFunctions 移除）。
     *
     * 真实性过滤规则（保守，宁可少列不造假）：
     *   · 块数 < 2 且 边数 == 0
     *     —— 单块、无任何边，说明没有解析出控制流，
     *        通常是数据或对齐填充。
     *   · 所有块都是“单条指令”且总指令数 <= 2
     *     —— 典型的数据/跳转表噪声。
     *   · 可达指令里“可疑指令”占比过高
     *     —— 解码整体失败（拿错字节或不是代码）。
     *
     * 注意：不做“猜测式”修复。分析不出就如实剔除，
     * 否则会在列表里给出一个看起来存在、实际是假的 CFG。
     */
    fun analyzeFunctions(
        elf: ElfParser.Elf,
        maxFunctions: Int = 2000,
        maxInsnsPerFunc: Int = 900,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): List<FuncGraph> {
        val allFuncs = discoverFunctions(elf)
        val funcs = if (allFuncs.size > maxFunctions) allFuncs.take(maxFunctions) else allFuncs
        val nextMap = nextAddrMap(allFuncs)
        val noreturn = collectNoReturnAddrs(elf)

        val out = ArrayList<FuncGraph>()

        funcs.forEachIndexed { idx, f ->
            val g = buildForFunc(
                elf = elf,
                f = f,
                maxInsns = maxInsnsPerFunc,
                nextFuncAddr = nextMap[f.addr] ?: 0L,
                noreturnAddrs = noreturn
            )
            onProgress?.invoke(idx + 1, funcs.size)

            if (g.blocks.isEmpty()) return@forEachIndexed

            // 含分支/跳转的块数（用真实指令语义判定，不看边）
            // 块至少含一条指令，last() 安全。
            val branchBlocks = g.blocks.count { b ->
                val last = b.insns.last()
                last.isCondBranch || last.isBranch || last.isCall || last.isIndirect
            }
            // 去向未解析的块：末尾是间接跳转，或跳转目标不在本图块集合内。
            //
            // 注意：必须把 target 提到局部变量再判空。
            // 直接写 last.target != null && ...it.start == last.target 是编译不过的：
            // 属性访问在 lambda 闭包里不会做智能转换。
            val blockStarts = g.blocks.mapTo(HashSet()) { it.start }
            val unreachable = g.blocks.count { b ->
                val last = b.insns.last()
                val t = last.target
                if (last.isIndirect) true
                else last.isBranch && t != null && !blockStarts.contains(t)
            }

            // ---- 真实性过滤 ----
            //
            // 修正（重要）：
            // 之前用「单块 + 无边」剔除，会把**正常的线性函数**误杀。
            // 例：纯计算函数 add(a,b) / memcpy 类实现，只有 1 个基本块、
            //     没有分支，自然也没有边 —— 但它完全可解析。
            //
            // 同理「块数 >= 3 且无分支」也不成立：多块可以来自
            //   · 地址跳空（中间有数据填充）
            //   · 间接跳转后的新目标
            //
            // 现在只保留两条真正可靠的剔除标准：
            //   A. 指令数太少（噪声 / 对齐填充）
            //   B. 单块 + 少量指令 + 完全无分支
            val insnTotal = g.blocks.sumOf { it.insns.size }

            // A) 指令数 <= 2：基本可确定不是真函数
            if (insnTotal <= 2) return@forEachIndexed

            // B) 单块 + 指令数很少（<= 4）且完全无分支：噪声
            //    注意：只有“少量指令且无分支”才剔除；
            //    指令足够多（例如 > 4）的单块函数要保留。
            if (g.blocks.size == 1 && branchBlocks == 0 && insnTotal <= 4) {
                return@forEachIndexed
            }

            out.add(
                FuncGraph(
                    name = f.name,
                    addr = f.addr,
                    graph = g,
                    blockCount = g.blocks.size,
                    edgeCount = g.edges.size,
                    branchBlocks = branchBlocks,
                    unreachable = unreachable
                )
            )
        }

        // 按“控制流复杂度”降序：块多的、边多的、分支多的排前面。
        // 这两个值恰好也是使用者最关心的。
        return out.sortedWith(
            compareByDescending<FuncGraph> { it.blockCount }
                .thenByDescending { it.edgeCount }
                .thenBy { it.addr }
        )
    }

    /**
     * 扫描整个 ELF，把所有函数的控制流合并为一张图。
     *
     * 保留此接口以兼容旧调用。
     * 新代码建议用 analyzeFunctions()，它按函数独立分析并可单独展示。
     */
    fun buildWhole(
        elf: ElfParser.Elf,
        maxFunctions: Int = 400,
        maxInsnsPerFunc: Int = 900,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): Graph {
        val allFuncs = discoverFunctions(elf).filter { it.hasValidAddr }
        val funcs = if (allFuncs.size > maxFunctions) allFuncs.take(maxFunctions) else allFuncs
        val truncated = allFuncs.size > maxFunctions

        val blocks = ArrayList<Block>()
        val edges = ArrayList<Edge>()
        var insnTotal = 0
        var hitLimit = truncated
        var nextId = 0

        // 每个函数的下一个函数地址（用于 size 缺失时限定扫描范围）
        val nextMap = nextAddrMap(allFuncs)

        // 已知不返回的函数地址（abort / exit / __stack_chk_fail 等）。
        // 遇到 bl 到这些地址时不再继续下落，避免把不可达代码带进图。
        val noreturn = collectNoReturnAddrs(elf)

        // 边去重：同一 from/to/kind 只保留一次。
        // 用结构化字段拼键，不用 hashCode，避免碰撞导致丢边。
        val seenEdges = HashSet<String>()
        fun addEdge(f: Long, t: Long, k: EdgeKind) {
            val key = f.toString() + "|" + t.toString() + "|" + k.name
            if (seenEdges.add(key)) edges.add(Edge(f, t, k))
        }

        funcs.forEachIndexed { idx, f ->
            val g = buildForFunc(
                elf = elf,
                f = f,
                maxInsns = maxInsnsPerFunc,
                nextFuncAddr = nextMap[f.addr] ?: 0L,
                noreturnAddrs = noreturn
            )
            if (g.blocks.isEmpty()) {
                onProgress?.invoke(idx + 1, funcs.size)
                return@forEachIndexed
            }
            if (g.truncated) hitLimit = true

            // 保留原始地址作为 Edge 端点（CfgBuilder.Edge 用的是地址，不是索引）
            for (b in g.blocks) {
                val newId = nextId++
                blocks.add(
                    Block(
                        id = newId,
                        start = b.start,
                        end = b.end,
                        insns = b.insns,
                        successors = b.successors,
                        isEntry = b.isEntry,
                        isExit = b.isExit,
                        label = f.name,
                        funcIndex = idx
                    )
                )
                insnTotal += b.insns.size
            }
            // 搬运地址端点（走去重）
            for (e in g.edges) {
                addEdge(e.from, e.to, e.kind)
            }
            onProgress?.invoke(idx + 1, funcs.size)
        }

        return Graph(
            blocks = blocks,
            edges = edges,
            entry = blocks.firstOrNull()?.start ?: 0L,
            truncated = hitLimit,
            insnCount = insnTotal
        )
    }
}
