package com.csdemo.tools

/**
 * 控制流图的边路由。
 *
 * 产出正交折线（只含水平与垂直线段），并区分：
 *   · 顺序下落：从父节点底部中点直落子节点顶部中点
 *   · 条件真分支：从父节点左侧引出，走左侧通道下行
 *   · 条件假分支：从父节点右侧引出，走右侧通道下行
 *   · 回边：从父节点右侧引出，绕过右侧回到目标节点右侧
 *
 * 不做复杂的通道避让（那需要全局扫描），
 * 而是用“按索引分层偏移”的方式错开走线，避免重叠。
 */
object CfgRouter {

    data class Point(val x: Float, val y: Float)

    /** 折线路径（至少两个点） */
    data class Route(
        val points: List<Point>,
        val from: Int,
        val to: Int,
        val kind: CfgLayout.Kind,
        val label: String,
        val isBack: Boolean
    ) {
        /** 标签锚点 */
        val labelAt: Point get() = if (points.size >= 2) points[1] else points.first()
    }

    private const val SIDE_MARGIN = 26f     // 侧边通道距离节点边缘的距离
    private const val CORNER = 10f          // 圆角（绘制时使用）

    /**
     * 为所有边计算折线。
     *
     * @param lane 每条边在侧边通道上的车道索引，用于错开平行线
     */
    fun route(
        nodes: List<CfgLayout.Node>,
        edges: List<CfgLayout.Edge>
    ): List<Route> {
        val byId = nodes.associateBy { it.id }
        val out = ArrayList<Route>(edges.size)

        // 统计每个源节点的出边，用于分配车道
        val edgeIndexPerSource = HashMap<Int, Int>()

        for (e in edges) {
            val from = byId[e.from] ?: continue
            val to = byId[e.to] ?: continue
            val idx = edgeIndexPerSource.getOrPut(e.from) { 0 }
            edgeIndexPerSource[e.from] = idx + 1

            // 回边判定：
            //   仅以 CfgLayout 破环阶段的 DFS 结果为准（e.isBack）。
            //
            // 不再用 “to.layer <= from.layer” 兜底：
            //   同层节点（to.layer == from.layer）会被这个条件误判成回边，
            //   把普通前向边/同层边画成虚线绕行。回边只能由 DFS 环切得出。
            val isBackEdge = e.kind == CfgLayout.Kind.BACK || e.isBack

            val r = if (isBackEdge) {
                routeBackEdge(from, to, idx)
            } else {
                when (e.kind) {
                    // 真分支：从左引出，避免与假分支重叠
                    CfgLayout.Kind.TRUE -> routeSideEdge(from, to, idx, left = true)
                    // 假分支 / 顺序下落：从右引出或直接下行
                    CfgLayout.Kind.FALLTHROUGH -> routeSideEdge(from, to, idx, left = false)
                    CfgLayout.Kind.FALSE -> routeSideEdge(from, to, idx, left = false)
                    CfgLayout.Kind.UNCOND -> routeStraightOrSide(from, to, idx)
                    CfgLayout.Kind.CALL -> routeStraightOrSide(from, to, idx)
                    CfgLayout.Kind.BACK -> routeBackEdge(from, to, idx)
                }
            }
            out.add(
                Route(
                    points = r,
                    from = e.from,
                    to = e.to,
                    kind = e.kind,
                    label = e.label,
                    isBack = e.isBack
                )
            )
        }
        return out
    }

    // ---------- 直落 ----------

    private fun routeStraightOrSide(
        from: CfgLayout.Node,
        to: CfgLayout.Node,
        idx: Int
    ): List<Point> {
        val sx = from.x + from.width / 2f
        val sy = from.y + from.height
        val ex = to.x + to.width / 2f
        val ey = to.y

        // 水平中心接近则直线，否则走两段
        if (kotlin.math.abs(sx - ex) < 6f) {
            return listOf(Point(sx, sy), Point(ex, ey))
        }
        val midY = (sy + ey) / 2f
        return listOf(
            Point(sx, sy),
            Point(sx, midY),
            Point(ex, midY),
            Point(ex, ey)
        )
    }

    // ---------- 侧边引出 ----------

    private fun routeSideEdge(
        from: CfgLayout.Node,
        to: CfgLayout.Node,
        idx: Int,
        left: Boolean
    ): List<Point> {
        val sy = from.y + from.height / 2f
        val sy2 = sy
        val laneX = if (left) {
            from.x - SIDE_MARGIN - idx * 12f
        } else {
            from.x + from.width + SIDE_MARGIN + idx * 12f
        }

        val ex = to.x + to.width / 2f
        val ey = to.y

        // 从侧面出来 → 走竖向车道 → 向下 → 进入目标顶部
        return listOf(
            Point(if (left) from.x else from.x + from.width, sy2),
            Point(laneX, sy2),
            Point(laneX, ey - 16f),
            Point(ex, ey - 16f),
            Point(ex, ey)
        )
    }

    // ---------- 回边 ----------

    private fun routeBackEdge(
        from: CfgLayout.Node,
        to: CfgLayout.Node,
        idx: Int
    ): List<Point> {
        // 从右侧出去，绕到目标右侧进入
        val sy = from.y + from.height / 2f
        val outX = from.x + from.width + SIDE_MARGIN + 18f + idx * 14f
        val inY = to.y + to.height / 2f
        val inX = to.x + to.width + 16f

        return listOf(
            Point(from.x + from.width, sy),
            Point(outX, sy),
            Point(outX, inY),
            Point(inX, inY),
            Point(to.x + to.width, inY)
        )
    }

    /** 路径长度（用于调优与 LOD） */
    fun length(r: Route): Float {
        var total = 0f
        for (i in 0 until r.points.size - 1) {
            val a = r.points[i]
            val b = r.points[i + 1]
            total += kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.y - b.y)
        }
        return total
    }
}
