package sy.great.toolbox.tools

/**
 * ARM64 指令重编码（最小集）。
 *
 * 本项目没有通用汇编器，这里只实现“能确定编码”的几种：
 *
 *   1) b  <target>        无条件跳转（重算 imm26）
 *   2) bl <target>        调用（重算 imm26）
 *   3) b.<cond> <target>  条件跳转（重算 imm19）
 *   4) cbz/cbnz <reg>, <target>（重算 imm19）
 *   5) mov <reg>, #imm    MOVZ/MOVN（立即数）
 *   6) nop                0xD503201F
 *   7) ret                0xD65F03C0
 *
 * 其它指令一律返回失败，并说明原因。
 * 不支持的不能偷偷当成“改好了”。
 */
object Arm64Encoder {

    /** 编码结果 */
    data class Encoded(
        val ok: Boolean,
        /** 编码后的 32 位指令 */
        val word: Int = 0,
        /** 失败原因 / 成功说明 */
        val message: String = ""
    )

    /**
     * 把一行汇编文本重新编码为机器码。
     *
     * @param text     同行原文，如 "b 0x1234" / "mov w0, #1"
     * @param insnAddr 该指令自身的地址（用于算相对偏移）
     */
    fun encode(text: String, insnAddr: Long): Encoded {
        val s = text.substringBefore("//").trim()
        if (s.isEmpty()) return Encoded(false, message = "指令为空")

        val parts = s.split(Regex("\\s+"), limit = 2)
        val mn = parts[0].lowercase()
        val rest = parts.getOrNull(1)?.trim().orEmpty()

        return try {
            when {
                mn == "nop" -> Encoded(true, 0xD503201F.toInt(), "nop")
                mn == "ret" -> Encoded(true, 0xD65F03C0.toInt(), "ret")

                // ---- b / bl ----
                mn == "b" || mn == "bl" -> {
                    val target = parseAddr(rest)
                        ?: return Encoded(false, message = "跳转目标无法解析：" + rest)
                    val offset = target - insnAddr
                    if (offset % 4L != 0L) return Encoded(false, message = "跳转偏移不是 4 字节对齐")
                    val imm26 = (offset / 4).toInt()
                    if (imm26 < -(1 shl 25) || imm26 >= (1 shl 25)) {
                        return Encoded(false, message = "跳转距离超出 ±128MB")
                    }
                    // 注意：0x94000000 超过 Int 范围，Kotlin 会推断为 Long，
                    // 必须 .toInt()，否则与 imm26(Int) 运算时类型不匹配。
                    val op = if (mn == "bl") 0x94000000.toInt() else 0x14000000
                    Encoded(true, op or (imm26 and 0x03FFFFFF), mn + " → 0x" + java.lang.Long.toHexString(target))
                }

                // ---- b.cond ----
                mn.startsWith("b.") -> {
                    val condName = mn.removePrefix("b.")
                    val cond = condCode(condName)
                        ?: return Encoded(false, message = "未知条件码：" + condName)
                    val target = parseAddr(rest)
                        ?: return Encoded(false, message = "跳转目标无法解析：" + rest)
                    val offset = target - insnAddr
                    if (offset % 4L != 0L) return Encoded(false, message = "跳转偏移不是 4 字节对齐")
                    val imm19 = (offset / 4).toInt()
                    if (imm19 < -(1 shl 18) || imm19 >= (1 shl 18)) {
                        return Encoded(false, message = "跳转距离超出 ±1MB")
                    }
                    val w = 0x54000000.toInt() or ((imm19 and 0x7FFFF) shl 5) or cond
                    Encoded(true, w, "b." + condName + " → 0x" + java.lang.Long.toHexString(target))
                }

                // ---- cbz / cbnz ----
                mn == "cbz" || mn == "cbnz" -> {
                    val ops = rest.split(",")
                    if (ops.size < 2) return Encoded(false, message = "cbz/cbnz 需要两个操作数")
                    val reg = ops[0].trim().lowercase()
                    val target = parseAddr(ops[1].trim())
                        ?: return Encoded(false, message = "跳转目标无法解析：" + ops[1])
                    val is64 = reg.startsWith("x")
                    val idx = reg.substring(1).toIntOrNull()
                        ?: return Encoded(false, message = "寄存器无法解析：" + reg)
                    if (idx !in 0..30) return Encoded(false, message = "寄存器编号超出 0..30")
                    val offset = target - insnAddr
                    if (offset % 4L != 0L) return Encoded(false, message = "跳转偏移不是 4 字节对齐")
                    val imm19 = (offset / 4).toInt()
                    if (imm19 < -(1 shl 18) || imm19 >= (1 shl 18)) {
                        return Encoded(false, message = "跳转距离超出 ±1MB")
                    }
                    // cbz: 0x34000000 | sf<<31 | imm19<<5 | Rt
                    // cbnz: 0x35000000 | sf<<31 | imm19<<5 | Rt
                    val base = if (mn == "cbz") 0x34000000 else 0x35000000
                    var w = base or ((imm19 and 0x7FFFF) shl 5) or idx
                    if (is64) w = w or (1 shl 31)
                    Encoded(true, w, mn + " " + reg + " → 0x" + java.lang.Long.toHexString(target))
                }

                // ---- mov reg, #imm ----
                mn == "mov" -> {
                    val ops = rest.split(",")
                    if (ops.size < 2) return Encoded(false, message = "mov 需要两个操作数")
                    val reg = ops[0].trim().lowercase()
                    val immText = ops[1].trim().removePrefix("#")
                    val imm = immText.toLongOrNull()
                        ?: return Encoded(false, message = "立即数无法解析：" + ops[1])
                    val is64 = reg.startsWith("x")
                    val rd = reg.substring(1).toIntOrNull()
                        ?: return Encoded(false, message = "寄存器无法解析：" + reg)
                    if (rd !in 0..30) return Encoded(false, message = "寄存器编号超出 0..30")

                    val bits = if (is64) 64 else 32
                    val mask = if (is64) -1L else 0xFFFFFFFFL
                    val v = imm and mask
                    // 只能是 16 位对齐可表示的立即数（MOVZ / MOVN）
                    val shift = when {
                        (v and 0xFFFFL) == v -> 0
                        (v and 0xFFFF0000L) == v -> 16
                        (v and 0xFFFF00000000L) == v -> 32
                        (v and 0xFFFF000000000000UL.toLong()) == v -> 48
                        else -> -1
                    }
                    if (shift >= 0) {
                        // MOVZ: sf 10 100101 hw(2) imm16 Rd
                        var w = 0x52800000 or ((shift / 16) shl 21) or (((v ushr shift) and 0xFFFF).toInt() shl 5) or rd
                        if (is64) w = w or (1 shl 31)
                        return Encoded(true, w, "MOVZ " + reg + ", #0x" + java.lang.Long.toHexString(v))
                    }
                    // MOVN（取反后能 16 位对齐表示）
                    val nv = (v xor mask) and mask
                    val nshift = when {
                        (nv and 0xFFFFL) == nv -> 0
                        (nv and 0xFFFF0000L) == nv -> 16
                        else -> -1
                    }
                    if (nshift >= 0) {
                        var w = 0x12800000 or ((nshift / 16) shl 21) or (((nv ushr nshift) and 0xFFFF).toInt() shl 5) or rd
                        if (is64) w = w or (1 shl 31)
                        return Encoded(true, w, "MOVN " + reg + ", #0x" + java.lang.Long.toHexString(v))
                    }
                    Encoded(false, message = "该立即数需要 MOVZ/MOVK 组合，当前不支持重编码")
                }

                else -> Encoded(false, message = "暂不支持重编码该指令：" + mn)
            }
        } catch (e: Throwable) {
            Encoded(false, message = "编码异常：" + (e.message ?: "未知"))
        }
    }

    /** 从文本里取目标地址：0x1234 / #0x1234 / 1234 */
    private fun parseAddr(s: String): Long? {
        val t = s.trim().removePrefix("#").removePrefix("=").trim()
        return try {
            when {
                t.startsWith("0x", true) -> java.lang.Long.parseLong(t.substring(2), 16)
                t.startsWith("-0x", true) -> -java.lang.Long.parseLong(t.substring(3), 16)
                else -> t.toLongOrNull()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 条件码 */
    private fun condCode(name: String): Int? = when (name.lowercase()) {
        "eq" -> 0x0; "ne" -> 0x1; "cs", "hs" -> 0x2; "cc", "lo" -> 0x3
        "mi" -> 0x4; "pl" -> 0x5; "vs" -> 0x6; "vc" -> 0x7
        "hi" -> 0x8; "ls" -> 0x9; "ge" -> 0xA; "lt" -> 0xB
        "gt" -> 0xC; "le" -> 0xD; "al" -> 0xE; "nv" -> 0xF
        else -> null
    }
}
