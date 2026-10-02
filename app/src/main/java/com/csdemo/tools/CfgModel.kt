package com.csdemo.tools

import android.graphics.Paint

/**
 * 把 CfgBuilder 的图转成可绘制的模型。
 *
 * 职责：
 *   1. 按等宽字体测量每行指令，决定节点尺寸
 *   2. 把指令行拆成可高亮的片段（助记符 / 寄存器 / 立即数 / 目标）
 *   3. 调用 CfgLayout 做分层布局
 *   4. 调用 CfgRouter 做正交路由
 */
object CfgModel {

    /** 文本片段类型，用于着色 */
    enum class PieceKind { MNEMONIC, REG, IMM, TARGET, PUNCT, COMMENT }

    data class Piece(val text: String, val kind: PieceKind)

    /** 一行指令 */
    data class Line(
        val addr: Long,
        val hex: String,
        val pieces: List<Piece>,
        val isBranch: Boolean,
        val isCall: Boolean,
        val isReturn: Boolean
    )

    /** 一个基本块 */
    data class Block(
        val id: Int,
        val addr: Long,
        val isEntry: Boolean,
        val isExit: Boolean,
        val lines: List<Line>,
        /** 标题（单函数时为 loc_xxx，整文件时为函数名） */
        val label: String,
        /** 所属函数序号 */
        val group: Int = 0
    )

    /** 最终可绘制模型 */
    data class Graph(
        val blocks: List<Block>,
        val layout: CfgLayout.Result,
        val routes: List<CfgRouter.Route>,
        val entryId: Int,
        /** 本次构建使用的字体度量（绘制阶段必须复用） */
        val metrics: TextMetrics
    )

    // ---------- 度量参数 ----------
    //
    // 重要：不要再用固定行高。
    // Paint.textSize 是 px，通常会乘 density（例如 13 * 3 = 39px），
    // 而固定 21px 的行距会小于字体实际高度，必然造成文字纵向重叠。
    //
    // 统一改为从 Paint.fontMetrics 推导，测量与绘制共用同一套数值。

    private const val PAD_X = 16f            // 左右内边距
    private const val PAD_Y = 10f            // 上下内边距
    private const val MIN_W = 240f           // 最小宽度
    private const val MAX_W = 1600f           // 最大宽度
    private const val GAP_ADDR_CODE = 14f    // 地址列与指令列间距
    private const val TEXT_LEADING = 5f      // 行间呼吸空间（px）
    private const val TITLE_EXTRA = 10f      // 标题栏额外高度
    private const val ADDR_COL_MIN = 112f    // 地址列最小宽度

    /**
     * 字体度量（由实际 Paint 推导）。
     *
     * 测量与绘制必须使用同一个实例，否则会出现
     * “框高对但行重叠”或“行对了但被裁断”。
     */
    data class TextMetrics(
        /** 每行文字占用的垂直高度 */
        val lineHeight: Float,
        /** 标题栏高度 */
        val titleHeight: Float,
        /** 首行 baseline 相对块顶部的偏移 */
        val baselineOffset: Float,
        /** 地址列宽度 */
        val addressWidth: Float,
        /** 地址文字的 baseline 修正（使其与指令基对齐） */
        val addrBaselineDelta: Float
    )

    fun metricsFor(
        textPaint: Paint,
        addrPaint: Paint,
        titlePaint: Paint? = null
    ): TextMetrics {
        val codeFm = textPaint.fontMetrics
        val addrFm = addrPaint.fontMetrics

        val codeH = codeFm.bottom - codeFm.top
        val addrH = addrFm.bottom - addrFm.top

        // 行高取两种字体的较大者，并加呼吸空间
        val lineH = maxOf(codeH, addrH, 12f) + TEXT_LEADING

        // 标题高：标题文字高 + 上下留白
        val titleH = if (titlePaint != null) {
            val tfm = titlePaint.fontMetrics
            (tfm.bottom - tfm.top) + TITLE_EXTRA
        } else {
            codeH + TITLE_EXTRA
        }

        // 首行 baseline：块顶 + 标题高 + 上留白 - 字体 top
        val baselineOff = titleH + PAD_Y - codeFm.top

        // 地址列：按实际文本量一个足够宽的值
        val addrW = maxOf(
            addrPaint.measureText("0x00000000") + GAP_ADDR_CODE,
            ADDR_COL_MIN
        )

        // 地址字体与指令字体大小不同时，两者的 baseline 需要对齐（按 top 差）
        val delta = -(codeFm.top - addrFm.top)

        return TextMetrics(
            lineHeight = lineH,
            titleHeight = titleH,
            baselineOffset = baselineOff,
            addressWidth = addrW,
            addrBaselineDelta = delta
        )
    }

    /** 地址列宽度：根据实际文本长度动态计算，并留余量 */
    private fun addrColWidth(blocks: List<CfgModel.Block>, addrPaint: Paint): Float {
        if (blocks.isEmpty()) return 96f
        var maxW = 0f
        for (b in blocks) {
            for (ln in b.lines) {
                val w = addrPaint.measureText("0x" + java.lang.Long.toHexString(ln.addr))
                if (w > maxW) maxW = w
            }
            val tw = addrPaint.measureText(b.label)
            if (tw > maxW) maxW = tw
        }
        return maxW + 12f
    }

    /**
     * 构建模型。
     *
     * @param textPaint 用于测量文本的 Paint（需已设置等宽字体与字号）
     * @param addrPaint 用于测量地址列的 Paint
     */
    fun build(
        graph: CfgBuilder.Graph,
        textPaint: Paint,
        addrPaint: Paint,
        titlePaint: Paint? = null,
        showBytes: Boolean = true
    ): Graph {
        // 先算字体度量，后续所有尺寸都由它推导
        val metrics = metricsFor(textPaint, addrPaint, titlePaint)

        if (graph.blocks.isEmpty()) {
            return Graph(
                emptyList(),
                CfgLayout.Result(emptyList(), emptyList(), 0f, 0f, 0),
                emptyList(), 0, metrics
            )
        }

        // ---- 1. 转换块与行 ----
        val blocks = ArrayList<Block>(graph.blocks.size)
        val layoutNodes = ArrayList<CfgLayout.Node>(graph.blocks.size)
        val idByAddr = HashMap<Long, Int>()

        graph.blocks.forEachIndexed { i, b ->
            idByAddr[b.start] = i
        }

        graph.blocks.forEachIndexed { i, b ->
            val lines = b.insns.map { insn ->
                Line(
                    addr = insn.addr,
                    hex = if (showBytes) insn.hex else "",
                    pieces = splitPieces(insn.text, insn.mnemonic),
                    isBranch = insn.isBranch,
                    isCall = insn.isCall,
                    isReturn = insn.isReturn
                )
            }

            val title = "loc_" + java.lang.Long.toHexString(b.start).uppercase()

            // ============================================================
            // 宽度计算
            //
            // 各部分必须与绘制端完全一致：
            //   指令起点 = PAD_X + metrics.addressWidth
            //   右边距   = PAD_X
            // 不一致就会出现“右边被裁断”或“文字溢出”。
            // ============================================================
            var maxCodeW = 0f
            for (ln in lines) {
                var codeW = 0f
                for (p in ln.pieces) {
                    codeW += textPaint.measureText(p.text)
                }
                if (codeW > maxCodeW) maxCodeW = codeW
            }

            // 指令行宽 = 左内边距 + 地址列 + 指令 + 右内边距
            val lineW = PAD_X + metrics.addressWidth + maxCodeW + PAD_X
            // 标题行宽（预留入口/出口标签位置）
            val titleW = PAD_X + textPaint.measureText(title) + 56f + PAD_X

            val nodeW = maxOf(lineW, titleW)
                .coerceAtLeast(MIN_W)
                .coerceAtMost(MAX_W)
            // 高度 = 标题栏 + 指令行（真实字体行高）+ 上下内边距
            val nodeH = metrics.titleHeight + lines.size * metrics.lineHeight + PAD_Y * 2

            blocks.add(
                Block(
                    id = i,
                    addr = b.start,
                    isEntry = b.isEntry,
                    isExit = b.isExit,
                    lines = lines,
                    label = title,
                    group = b.funcIndex
                )
            )
            layoutNodes.add(
                CfgLayout.Node(
                    id = i,
                    addr = b.start,
                    width = nodeW,
                    height = nodeH,
                    group = b.funcIndex
                )
            )
        }

        // ---- 2. 转换边 ----
        // 间接跳转（br Xn）没有可连线目标，不生成可绘制边。
        // 它在图上以“该块不再被标为出口”的方式如实体现，而不是画一条自环。
        //
        // 预扫：哪些源块同时拥有 TAKEN 与 FALLTHROUGH。
        // 只有这种情况才是“真正的条件分支”，T/F 标签也只给它们。
        val condSources = HashMap<Long, MutableSet<CfgBuilder.EdgeKind>>()
        for (e in graph.edges) {
            if (e.kind == CfgBuilder.EdgeKind.INDIRECT) continue
            condSources.getOrPut(e.from) { HashSet() }.add(e.kind)
        }

        // 预扫：哪些“源块”的末尾指令是函数调用（bl / blr）。
        //
        // 背景：CfgBuilder 对调用后的下落只产生 FALLTHROUGH 边，
        // 不会单独产生 CALL 边（被调函数不在本图内）。
        // 于是 bl / blr 之后的下落边与普通顺序下落完全同色，图上分不出“这里发生了调用”。
        //
        // 修法：不改 CfgBuilder 的数据，只在“可视化映射”这一层，
        // 把“末尾是调用的块的 FALLTHROUGH 边”映射为 Kind.CALL（图上蓝色，与 UI 现成分支一致）。
        // 注意：这不会影响布局/回边判定，只影响颜色；CALL 仍是一条普通顺序边。
        val callTailStarts = HashSet<Long>()
        for (b in graph.blocks) {
            val last = b.insns.lastOrNull() ?: continue
            if (last.isCall) callTailStarts.add(b.start)
        }

        val edges = ArrayList<CfgLayout.Edge>()
        for (e in graph.edges) {
            if (e.kind == CfgBuilder.EdgeKind.INDIRECT) continue
            val fi = idByAddr[e.from] ?: continue
            val ti = idByAddr[e.to] ?: continue

            // 先判定“是否真正的条件分支”：源块同时拥有 TAKEN 与 FALLTHROUGH。
            val srcKinds = condSources[e.from]
            val isRealCond = srcKinds != null &&
                    srcKinds.contains(CfgBuilder.EdgeKind.TAKEN) &&
                    srcKinds.contains(CfgBuilder.EdgeKind.FALLTHROUGH)

            // 关键区分：
            //   条件分支的“顺序下落”是“条件不成立”的假分支 → Kind.FALSE（图上标红）
            //   调用后的下落 → Kind.CALL（图上蓝色，表示“调用返回”）
            //   普通顺序下落 → Kind.FALLTHROUGH（图上灰色，无标签）
            //
            // 三者语义不同，不能混用同一个 Kind。
            val isCallTail = callTailStarts.contains(e.from)
            val kind = when (e.kind) {
                CfgBuilder.EdgeKind.FALLTHROUGH -> when {
                    isRealCond -> CfgLayout.Kind.FALSE
                    isCallTail -> CfgLayout.Kind.CALL
                    else -> CfgLayout.Kind.FALLTHROUGH
                }
                CfgBuilder.EdgeKind.TAKEN -> CfgLayout.Kind.TRUE
                CfgBuilder.EdgeKind.CALL -> CfgLayout.Kind.CALL
                CfgBuilder.EdgeKind.UNCOND -> CfgLayout.Kind.UNCOND
                // 保留兼容：旧 RETURN 不再产生，不再当作回边
                CfgBuilder.EdgeKind.RETURN -> CfgLayout.Kind.UNCOND
                CfgBuilder.EdgeKind.INDIRECT -> continue
            }

            // 标签：只有真正的条件分支才标 T / F；其余一律不标。
            val label = when {
                !isRealCond -> ""
                e.kind == CfgBuilder.EdgeKind.TAKEN -> "T"
                e.kind == CfgBuilder.EdgeKind.FALLTHROUGH -> "F"
                else -> ""
            }

            edges.add(
                CfgLayout.Edge(
                    from = fi,
                    to = ti,
                    kind = kind,
                    label = label
                )
            )
        }

        // ---- 3. 布局 ----
        val entryId = idByAddr[graph.entry]
            ?: blocks.firstOrNull { it.isEntry }?.id
            ?: 0
        // 多函数（整文件）时按函数分区堆叠；单函数时直接分层
        val multiGroup = blocks.any { it.group != 0 }
        val layoutResult = if (multiGroup) {
            CfgLayout.layoutGrouped(layoutNodes, edges)
        } else {
            CfgLayout.layout(layoutNodes, edges, entryId)
        }

        // ---- 4. 路由 ----
        val routes = CfgRouter.route(layoutResult.nodes, layoutResult.edges)

        return Graph(
            blocks = blocks,
            layout = layoutResult,
            routes = routes,
            entryId = entryId,
            metrics = metrics
        )
    }

    // ============================================================
    // 语法拆分：把指令文本拆成可着色的片段
    // ============================================================

    private val REGEX_REG = Regex("^(x|w|v|q|d|s|b|h|r|pc|sp|lr|xzr|wzr|sp|fp|ip)\\d*$")
    private val REGEX_IMM = Regex("^#?-?(0x[0-9a-fA-F]+|\\d+)$")
    private val REGEX_TARGET = Regex("^0x[0-9a-fA-F]+$|^loc_[0-9a-fA-F]+$")

    /**
     * 拆分指令。
     * 第一段是助记符，后续按逗号/空格切分，再逐个判定类型。
     */
    fun splitPieces(text: String, mnemonic: String): List<Piece> {
        val out = ArrayList<Piece>()

        // 助记符
        val body = if (text.startsWith(mnemonic)) text.substring(mnemonic.length) else text
        out.add(Piece(mnemonic, PieceKind.MNEMONIC))

        if (body.isBlank()) return out
        out.add(Piece(" ", PieceKind.PUNCT))

        // 按逗号拆分操作数
        val parts = body.trim().split(",").map { it.trim() }
        parts.forEachIndexed { pi, part ->
            if (part.isEmpty()) return@forEachIndexed

            // 方括号内存操作数：拆出 [reg, #imm]
            if (part.startsWith("[")) {
                out.add(Piece("[", PieceKind.PUNCT))
                val inner = part.removePrefix("[").removeSuffix("]")
                inner.split(",").map { it.trim() }.forEachIndexed { ii, seg ->
                    if (ii > 0) out.add(Piece(", ", PieceKind.PUNCT))
                    out.add(classify(seg))
                }
                out.add(Piece("]", PieceKind.PUNCT))
            } else {
                // 形如 "d0, [a2, #0x30]" 这种已被逗号切开，不会再出现
                out.add(classify(part))
            }

            if (pi < parts.size - 1) out.add(Piece(", ", PieceKind.PUNCT))
        }
        return out
    }

    private fun classify(token: String): Piece {
        val t = token.trim()
        return when {
            t.isEmpty() -> Piece("", PieceKind.PUNCT)
            REGEX_TARGET.matches(t) -> Piece(t, PieceKind.TARGET)
            REGEX_IMM.matches(t) -> Piece(t, PieceKind.IMM)
            REGEX_REG.matches(t) -> Piece(t, PieceKind.REG)
            t.startsWith("#") -> Piece(t, PieceKind.IMM)
            else -> {
                // 形如 reg! 或其他修饰
                val clean = t.removeSuffix("!")
                when {
                    REGEX_REG.matches(clean) -> Piece(t, PieceKind.REG)
                    t.startsWith("0x") -> Piece(t, PieceKind.IMM)
                    t.firstOrNull()?.isDigit() == true -> Piece(t, PieceKind.IMM)
                    else -> Piece(t, PieceKind.PUNCT)
                }
            }
        }
    }
}
