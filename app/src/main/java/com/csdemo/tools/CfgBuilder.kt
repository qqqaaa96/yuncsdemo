package com.csdemo.tools

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
        var limitBySize = if (funcSize > 0) startOff + funcSize.toInt() else bytes.size
        if (nextFuncAddr > funcAddr) {
            val nextOff = (nextFuncAddr - baseAddr).toInt()
            if (nextOff > startOff && nextOff < limitBySize) {
                limitBySize = nextOff
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
     * 函数入口发现（不依赖符号表）。
     *
     * 优先级：
     *   1. ELF entry（e_entry）—— 这是最可靠的入口，stripped 文件也有
     *   2. 符号表里带有效地址的函数
     *   3. 从上述入口出发，沿分支目标递归发现
     *
     * 这就是“recursive descent”的种子阶段：
     * 找到若干可信入口后，由 buildWhole 递归展开。
     */
    fun discoverFunctions(elf: ElfParser.Elf): List<FuncInfo> {
        val out = LinkedHashMap<Long, FuncInfo>()

        // ---- 1. 符号表（可能为空）----
        for (f in functions(elf)) {
            if (!f.hasValidAddr) continue
            if (!out.containsKey(f.addr)) out[f.addr] = f
        }

        // ---- 2. ELF entry ----
        val e = elf.entry
        if (e != 0L) {
            val inExec = elf.executableSegments().any { it.contains(e) }
            if (inExec && !out.containsKey(e)) {
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

        // ---- 3. 如果什么都没找到，用可执行段起点作为兵底 ----
        if (out.isEmpty()) {
            val segs = elf.executableSegments()
            for (s in segs) {
                out[s.vaddr] = FuncInfo(
                    name = "seg_" + java.lang.Long.toHexString(s.vaddr),
                    addr = s.vaddr,
                    size = 0L,
                    isImport = false,
                    isExport = false
                )
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
        return map
    }

    /** 找出包含指定地址的函数 */
    fun functionAt(funcs: List<FuncInfo>, addr: Long): FuncInfo? =
        funcs.firstOrNull { it.addr == addr }

    // ---------- 整个文件构建 ----------

    /**
     * 扫描整个 ELF，把所有函数的控制流合并为一张图。
     *
     * 入口发现不再只依赖符号表：
     *   · 符号表（如果有）
     *   · ELF entry（e_entry）
     *   · 可执行段起点（兵底）
     *
     * 这样即使 .symtab 被 strip，也能从 entry 开始分析。
     *
     * 为防止大文件卡死，可限制函数数量与每函数指令数。
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
