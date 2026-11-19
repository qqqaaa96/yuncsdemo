package sy.great.toolbox.tools

/**
 * 用 Capstone 取代手写 ARM64 解码器。
 *
 * 为什么需要适配层：
 *   CfgBuilder / DecompilerEngine / McpTools 全都依赖
 *   Arm64Disasm.decode(bytes, off, baseAddr) 这个签名。
 *   把 Capstone 的结果缓存到 Map 里，decode() 只查表，
 *   这样上层代码一行都不用改。
 *
 * 缓存策略：
 *   同一段字节只解一次。缓存 key = baseAddr + bytes 长度。
 *   用 WeakReference 避免长时间占内存。
 */
class CapstoneDecoder(
    val baseAddr: Long,
    val decoded: Map<Long, Arm64Disasm.Insn>,
) {

    /** 取某地址的指令；没有则 null */
    fun at(addr: Long): Arm64Disasm.Insn? = decoded[addr]

    companion object {

        /** 解码结果缓存（只保留最近几段，避免占内存） */
        private val cache = object : LinkedHashMap<String, CacheEntry>(8, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CacheEntry>?
            ): Boolean = size > 4
        }

        private class CacheEntry(
            val baseAddr: Long,
            val decoded: Map<Long, Arm64Disasm.Insn>,
        )

        /**
         * 缓存键。
         *
         * 用 identityHashCode + 长度，而不是 contentHashCode：
         * decode() 会被逐条调用，对 1MB 段每次算全内容哈希会让整体变慢到不可用。
         * 同一个 ByteArray 实例的 identityHashCode 是固定的，足够区分。
         */
        private fun cacheKey(baseAddr: Long, bytes: ByteArray): String =
            baseAddr.toString() + ":" + System.identityHashCode(bytes) + ":" + bytes.size

        /**
         * 对一段字节做 Capstone 解码，返回可查表的解码器。
         *
         * Capstone 不可用时返回 null，调用方可回退到旧解码器。
         */
        /**
         * 引擎是否可用。
         *
         * 降级库里 nativeDisasm 永远返回 null，把它当“未启用”，
         * 避免每次 decode 都重复调 JNI、重复建缓存。
         */
        @Volatile
        private var engineUsable: Boolean? = null

        private fun checkEngine(): Boolean {
            engineUsable?.let { return it }
            val ok = CapstoneBridge.version() != null
            engineUsable = ok
            return ok
        }

        fun decode(
            bytes: ByteArray,
            baseAddr: Long,
            maxInsns: Int = 8192,
        ): CapstoneDecoder? {
            if (bytes.isEmpty()) return null
            if (!checkEngine()) return null
            val key = cacheKey(baseAddr, bytes)

            synchronized(cache) {
                cache[key]?.let { e ->
                    return CapstoneDecoder(e.baseAddr, e.decoded)
                }
            }

            val list = CapstoneBridge.disasm(
                arch = CapstoneBridge.Arch.ARM64,
                code = bytes,
                baseAddr = baseAddr,
                count = maxInsns,
            )
            if (list.isEmpty()) return null

            val map = HashMap<Long, Arm64Disasm.Insn>(list.size * 2)
            for (c in list) {
                map[c.addr] = toInsn(c)
            }

            synchronized(cache) {
                cache[key] = CacheEntry(baseAddr, map)
            }
            return CapstoneDecoder(baseAddr, map)
        }

        /**
         * 把 Capstone 指令转成现有 Insn 结构。
         *
         * 尽量还原字段语义，让上层逻辑（CFG / 伪 C）不用感知差异：
         *   isBranch      —— 无条件跳转或条件跳转
         *   isCondBranch  —— 条件跳转
         *   isCall        —— 调用
         *   isReturn      —— 返回
         *   isIndirect    —— br Xn / blr Xn / jmp reg
         *   target        —— 直接分支目标（需从操作数解析）
         */
        private fun toInsn(c: CapstoneBridge.Insn): Arm64Disasm.Insn {
            val isJump = c.isJump
            val isCond = c.isCondJump
            val isCall = c.isCall
            val isRet = c.isRet
            val isIndirect = c.isIndirect

            // 分支目标：操作数形如 "0x1b1a0" 或 "#0x1b1a0"
            val target = if (isJump || isCond || isCall) parseTarget(c.opStr) else null

            return Arm64Disasm.Insn(
                addr = c.addr,
                word = c.word.toInt(),
                text = c.text,
                mnemonic = c.mnemonic,
                target = target,
                isBranch = isJump || isCond,
                isCondBranch = isCond,
                isCall = isCall,
                isReturn = isRet,
                isIndirect = isIndirect,
                cond = if (isCond) conditionOf(c.mnemonic) else null,
            )
        }

        /**
         * 从操作数字符串里取分支目标地址。
         *
         * ARM64 直接分支的 op_str 第一个参数就是目标地址：
         *   "b 0x1b1a0"        → 0x1b1a0
         *   "b.eq 0x1b1a0"     → 0x1b1a0
         *   "cbz x0, 0x1b1a0"  → 0x1b1a0（取最后一个逗号后的）
         *   "bl 0x790"         → 0x790
         */
        private fun parseTarget(opStr: String): Long? {
            if (opStr.isBlank()) return null
            // 取最后一个 "0x" 开头的 token
            var best: Long? = null
            var i = 0
            while (i < opStr.length) {
                if (opStr[i] == '0' && i + 1 < opStr.length &&
                    (opStr[i + 1] == 'x' || opStr[i + 1] == 'X')
                ) {
                    var j = i + 2
                    while (j < opStr.length && isHex(opStr[j])) j++
                    if (j > i + 2) {
                        opStr.substring(i + 2, j).toLongOrNull(16)?.let { best = it }
                    }
                    i = j
                } else {
                    i++
                }
            }
            return best
        }

        private fun isHex(c: Char): Boolean =
            c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        /**
         * 从助记符里取条件后缀。
         *
         * ARM64 条件分支的写法：
         *   b.eq / b.ne / b.lt …     → 后缀就是条件
         *   cbz                       → 等于 0 时跳，按 eq
         *   cbnz                      → 不等于 0，按 ne
         *   tbz / tbnz                → 同上
         */
        private fun conditionOf(mnemonic: String): String? {
            val m = mnemonic.lowercase()
            return when {
                m.startsWith("b.") -> m.substring(2).takeIf { it.isNotBlank() }
                m == "cbz" || m == "tbz" -> "eq"
                m == "cbnz" || m == "tbnz" -> "ne"
                else -> null
            }
        }
    }
}
