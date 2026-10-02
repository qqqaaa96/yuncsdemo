package com.csdemo.tools

import kotlin.math.abs

/**
 * 控制流图分层布局（Sugiyama 方法的裁剪实现）。
 *
 * 流程：
 *   1. cycle break     —— 标记回边，使其不参与分层
 *   2. layering        —— 最长路径分层
 *   3. ordering        —— 层内排序，用重心法减少交叉
 *   4. coordinate      —— 分配坐标（同层居中，尽量对齐父节点）
 *
 * 输出为纯坐标，不含绘制信息，便于替换渲染层。
 */
object CfgLayout {

    /** 输入节点（只需 id 与尺寸） */
    data class Node(
        val id: Int,
        val addr: Long,
        val width: Float,
        val height: Float,
        /** 所属函数序号；单函数布局时全为 0 */
        val group: Int = 0
    ) {
        var x: Float = 0f
        var y: Float = 0f
        var layer: Int = 0
        var order: Int = 0
    }

    /** 输入边 */
    data class Edge(
        val from: Int,
        val to: Int,
        val kind: Kind,
        val label: String
    ) {
        /** 是否为回边（由环切检测得出） */
        var isBack: Boolean = false
    }

    enum class Kind { FALLTHROUGH, TRUE, FALSE, UNCOND, CALL, BACK }

    data class Result(
        val nodes: List<Node>,
        val edges: List<Edge>,
        val width: Float,
        val height: Float,
        val layerCount: Int,
        /** 每个函数分区的垂直范围：group -> (top, bottom) */
        val groupRanges: Map<Int, Pair<Float, Float>> = emptyMap()
    )

    // ---------- 间距参数 ----------
    private const val LAYER_GAP = 96f       // 层间垂直间距
    private const val NODE_GAP = 64f        // 同层水平间距
    private const val MARGIN = 70f          // 画布边距

    /**
     * 分区布局（整个文件的推荐方式）。
     *
     * 每个 group（通常是函数）单独跑一遍分层布局，然后纵向堆叠。
     * 这样每个函数的控制流都是清晰的纵向结构，不会互相干扰。
     */
    fun layoutGrouped(
        nodes: List<Node>,
        edges: List<Edge>,
        groupGap: Float = 70f
    ): Result {
        if (nodes.isEmpty()) {
            return Result(emptyList(), emptyList(), 0f, 0f, 0)
        }

        val byGroup = nodes.groupBy { it.group }.toSortedMap()
        val outNodes = ArrayList<Node>(nodes.size)
        val outEdges = ArrayList<Edge>(edges.size)
        val ranges = HashMap<Int, Pair<Float, Float>>()

        var cursorY = MARGIN + 34f   // 顶部留出总标题区
        var maxW = 0f
        var maxLayerAll = 0

        for ((g, rawList) in byGroup) {
            // 只保留本组的节点与边
            val groupNodes = rawList.toMutableList()
            val ids = groupNodes.map { it.id }.toHashSet()
            val groupEdges = edges.filter { ids.contains(it.from) && ids.contains(it.to) }
                .map { Edge(it.from, it.to, it.kind, it.label).also { e -> e.isBack = it.isBack } }
                .toMutableList()

            // 本组入口：优先层号仍为 0 且无入边的节点
            val hasIncoming = groupEdges.map { it.to }.toHashSet()
            val entry = groupNodes.firstOrNull { !hasIncoming.contains(it.id) }?.id
                ?: groupNodes.first().id

            // 单组布局
            val sub = layout(groupNodes, groupEdges, entry)

            // 平移到堆叠位置
            val top = cursorY
            for (n in sub.nodes) {
                n.y += cursorY - MARGIN
                outNodes.add(n)
            }
            outEdges.addAll(sub.edges)

            val bottom = top + (sub.height - MARGIN * 2) + MARGIN
            ranges[g] = top to bottom
            cursorY = bottom + groupGap

            if (sub.width > maxW) maxW = sub.width
            if (sub.layerCount > maxLayerAll) maxLayerAll = sub.layerCount
        }

        return Result(
            nodes = outNodes,
            edges = outEdges,
            width = maxW,
            height = cursorY + MARGIN,
            layerCount = maxLayerAll,
            groupRanges = ranges
        )
    }

    /**
     * 执行布局（单图模式）。
     * nodes 的顺序需要与边中的索引一致。
     */
    fun layout(nodes: List<Node>, edges: List<Edge>, entryId: Int): Result {
        if (nodes.isEmpty()) {
            return Result(emptyList(), emptyList(), 0f, 0f, 0)
        }

        // ---- 1. 破环：从入口做 DFS，指向已访问祖先的边标记为回边 ----
        breakCycles(nodes, edges, entryId)

        // ---- 2. 分层：只考虑非回边，取最长路径 ----
        assignLayers(nodes, edges)

        // ---- 3. 层内排序 ----
        orderWithinLayers(nodes, edges)

        // ---- 4. 坐标分配 ----
        assignCoordinates(nodes, edges)

        // ---- 计算画布尺寸 ----
        var maxX = 0f
        var maxY = 0f
        for (n in nodes) {
            maxX = maxOf(maxX, n.x + n.width)
            maxY = maxOf(maxY, n.y + n.height)
        }
        val layerCount = (nodes.maxOfOrNull { it.layer } ?: 0) + 1

        return Result(
            nodes = nodes,
            edges = edges,
            width = maxX + MARGIN,
            height = maxY + MARGIN,
            layerCount = layerCount
        )
    }

    // ============================================================
    // 1. 破环
    // ============================================================

    private fun breakCycles(nodes: List<Node>, edges: List<Edge>, entryId: Int) {
        // 邻接表
        val out = HashMap<Int, MutableList<Int>>()
        for (e in edges) {
            out.getOrPut(e.from) { ArrayList() }.add(e.to)
        }

        val state = HashMap<Int, Int>()   // 0=未访问 1=在栈中 2=已完成
        val backPairs = HashSet<Long>()

        // 迭代式 DFS，避免大图递归溢出
        for (start in nodes.map { it.id }) {
            if (state[start] == 2) continue
            val stack = ArrayList<Int>()
            val iterIdx = HashMap<Int, Int>()
            stack.add(start)
            state[start] = 1
            iterIdx[start] = 0

            while (stack.isNotEmpty()) {
                val cur = stack.last()
                val list = out[cur]
                val idx = iterIdx[cur] ?: 0
                if (list == null || idx >= list.size) {
                    state[cur] = 2
                    stack.removeAt(stack.size - 1)
                    continue
                }
                iterIdx[cur] = idx + 1
                val next = list[idx]
                val st = state[next] ?: 0
                if (st == 1) {
                    // 指向栈中节点 → 回边
                    backPairs.add(key(cur, next))
                } else if (st == 0) {
                    state[next] = 1
                    iterIdx[next] = 0
                    stack.add(next)
                }
            }
        }

        for (e in edges) {
            if (backPairs.contains(key(e.from, e.to))) {
                e.isBack = true
            }
        }
    }

    private fun key(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)

    // ============================================================
    // 2. 分层（最长路径）
    // ============================================================

    private fun assignLayers(nodes: List<Node>, edges: List<Edge>) {
        val forward = edges.filter { !it.isBack }
        val incoming = HashMap<Int, MutableList<Int>>()
        val outgoing = HashMap<Int, MutableList<Int>>()
        for (e in forward) {
            incoming.getOrPut(e.to) { ArrayList() }.add(e.from)
            outgoing.getOrPut(e.from) { ArrayList() }.add(e.to)
        }

        // 迭代式拓扑分层：反复松弛，直到稳定（带次数上限防环）
        val layer = HashMap<Int, Int>()
        for (n in nodes) layer[n.id] = 0

        var changed = true
        var guard = 0
        val maxIter = (nodes.size + 8) * 2
        while (changed && guard < maxIter) {
            changed = false
            guard++
            for (e in forward) {
                val lf = layer[e.from] ?: 0
                val lt = layer[e.to] ?: 0
                if (lt < lf + 1) {
                    layer[e.to] = lf + 1
                    changed = true
                }
            }
        }

        for (n in nodes) n.layer = layer[n.id] ?: 0
    }

    // ============================================================
    // 3. 层内排序（重心法）
    // ============================================================

    private fun orderWithinLayers(nodes: List<Node>, edges: List<Edge>) {
        val maxLayer = nodes.maxOfOrNull { it.layer } ?: 0
        val layers = Array(maxLayer + 1) { ArrayList<Node>() }
        for (n in nodes) layers[n.layer].add(n)

        // 初始顺序：按地址（保证确定性）
        for (l in layers) l.sortBy { it.addr }
        for (l in layers) l.forEachIndexed { i, n -> n.order = i }

        val incoming = HashMap<Int, MutableList<Int>>()
        val outgoing = HashMap<Int, MutableList<Int>>()
        for (e in edges) {
            if (e.isBack) continue
            incoming.getOrPut(e.to) { ArrayList() }.add(e.from)
            outgoing.getOrPut(e.from) { ArrayList() }.add(e.to)
        }

        // id → Node 映射，避免在双层循环里反复线性查找（O(N²)）。
        val byId = HashMap<Int, Node>(nodes.size)
        for (n in nodes) byId[n.id] = n

        // 多次扫描，冲淡局部最优
        repeat(4) {
            // 向下：根据上一层位置排序本层
            for (l in 1..maxLayer) {
                val row = layers[l]
                row.sortBy { n ->
                    val parents = incoming[n.id]
                    if (parents.isNullOrEmpty()) n.order.toFloat()
                    else parents.mapNotNull { p -> byId[p]?.order?.toFloat() }.averageOrZero()
                }
                row.forEachIndexed { i, n -> n.order = i }
            }
            // 向上：根据下一层位置排序本层
            for (l in maxLayer - 1 downTo 0) {
                val row = layers[l]
                row.sortBy { n ->
                    val children = outgoing[n.id]
                    if (children.isNullOrEmpty()) n.order.toFloat()
                    else children.mapNotNull { c -> byId[c]?.order?.toFloat() }.averageOrZero()
                }
                row.forEachIndexed { i, n -> n.order = i }
            }
        }
    }

    private fun List<Float>.averageOrZero(): Float =
        if (isEmpty()) 0f else sum() / size

    // ============================================================
    // 4. 坐标分配
    // ============================================================

    private fun assignCoordinates(nodes: List<Node>, edges: List<Edge>) {
        val maxLayer = nodes.maxOfOrNull { it.layer } ?: 0
        val layers = Array(maxLayer + 1) { ArrayList<Node>() }
        for (n in nodes) layers[n.layer].add(n)
        for (l in layers) l.sortBy { it.order }

        // 各层宽度
        val layerWidth = FloatArray(maxLayer + 1)
        for (l in 0..maxLayer) {
            var w = 0f
            layers[l].forEachIndexed { i, n ->
                w += n.width
                if (i < layers[l].size - 1) w += NODE_GAP
            }
            layerWidth[l] = w
        }
        val canvasWidth = (layerWidth.maxOrNull() ?: 0f)

        // Y：逐层累加
        val layerY = FloatArray(maxLayer + 1)
        var y = MARGIN
        for (l in 0..maxLayer) {
            layerY[l] = y
            val h = layers[l].maxOfOrNull { it.height } ?: 0f
            y += h + LAYER_GAP
        }

        // X：先居中摆放，再向父节点靠拢
        for (l in 0..maxLayer) {
            val row = layers[l]
            var x = MARGIN + (canvasWidth - layerWidth[l]) / 2f
            for (n in row) {
                n.x = x
                n.y = layerY[l]
                x += n.width + NODE_GAP
            }
        }

        // 轻量对齐：让子节点尽量靠近直接父节点的水平中心
        val incoming = HashMap<Int, MutableList<Int>>()
        for (e in edges) {
            if (e.isBack) continue
            incoming.getOrPut(e.to) { ArrayList() }.add(e.from)
        }
        // id → Node 映射，避免逐节点线性查找父节点（O(N²)）。
        val byId = HashMap<Int, Node>(nodes.size)
        for (n in nodes) byId[n.id] = n
        for (l in 1..maxLayer) {
            val row = layers[l]
            // 1) 轻微向父节点中心靠拢
            for (n in row) {
                val parents = incoming[n.id]?.mapNotNull { p -> byId[p] } ?: continue
                if (parents.isEmpty()) continue
                val cx = parents.map { it.x + it.width / 2f }.average().toFloat()
                val target = cx - n.width / 2f
                // 幅度降到 0.25，避免推回重叠
                n.x += (target - n.x) * 0.25f
            }
            // 2) 按 x 排序后从左推到右，保证最小间距
            val sorted = row.sortedBy { it.x }
            var cursor = MARGIN
            for (n in sorted) {
                if (n.x < cursor) n.x = cursor
                cursor = n.x + n.width + NODE_GAP
            }
            // 3) 整层重新居中（消除累计偏移）
            val left = sorted.minOf { it.x }
            val right = sorted.maxOf { it.x + it.width }
            val shift = (canvasWidth - (right - left)) / 2f - left
            if (shift > 0f && kotlin.math.abs(shift) > 1f) {
                for (n in sorted) n.x += shift
            }
        }
    }

    /** 同层从左到右推开重叠（按 x 排序后依次保证最小间距） */
    private fun resolveOverlap(row: List<Node>) {
        if (row.size < 2) return
        // 按当前 x 排序后再推，避免原顺序导致重复移动
        val sorted = row.sortedBy { it.x }
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            val minX = a.x + a.width + NODE_GAP
            if (b.x < minX) b.x = minX
        }
    }
}
