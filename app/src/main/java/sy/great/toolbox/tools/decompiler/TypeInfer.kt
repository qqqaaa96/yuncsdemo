package sy.great.toolbox.tools.decompiler

/**
 * 类型推断：为 IR 值赋予更具体的 C 类型。
 *
 * IrTranslator 只能根据寄存器宽度给出 int8/16/32/64，
 * 所以输出全是 int64_t。这里补三件事：
 *
 *   1. 指针识别：被当作基址参与访存的值 → 指针
 *   2. 宽度传播：窄类型在运算中保持窄类型
 *   3. 调用返回值：能查到已知函数签名就套用（否则保持原样）
 *
 * 保守原则：拿不准就保持原类型，绝不猜。
 */
object TypeInfer {

    /** 跑完整推断，直接改 IRValue.type */
    fun run(fn: FunctionIR) {
        val defOf = ExprBuilder.indexDefs(fn)

        // ---- 1. 指针识别 ----
        // 出现在 Load/Store 的内存基址位置的值，几乎一定是指针。
        for (b in fn.blocks) {
            for (i in b.instructions) {
                when (i.op) {
                    IROp.Load -> {
                        val base = i.operands.getOrNull(0) ?: continue
                        if (base.kind == ValueKind.Global) continue   // 全局地址不是指针变量
                        markPointer(base)
                    }
                    IROp.Store -> {
                        val base = i.operands.getOrNull(0) ?: continue
                        if (base.kind == ValueKind.Global) continue
                        markPointer(base)
                    }
                    else -> {}
                }
            }
        }

        // ---- 2. 宽度传播 ----
        // 算木结果取参与运算的较宽类型（但不超过 8），
        // 窄宽度不向宽宽度传播，避免把 int 误报成 int64_t。
        for (b in fn.blocks) {
            for (i in b.instructions) {
                val r = i.result ?: continue
                if (r.type == ValueType.Pointer) continue

                when (i.op) {
                    IROp.Add, IROp.Sub, IROp.Mul, IROp.Div, IROp.Rem,
                    IROp.And, IROp.Or, IROp.Xor, IROp.Shl, IROp.Shr, IROp.Sar,
                    IROp.Move -> {
                        val t = i.operands.mapNotNull { it.type }
                            .filter { it != ValueType.Unknown && it != ValueType.Bool }
                            .maxByOrNull { widthOf(it) }
                        if (t != null) {
                            // 指针参与加法（指针运算）结果仍是指针
                            if (i.op == IROp.Add || i.op == IROp.Sub) {
                                if (i.operands.any { it.type == ValueType.Pointer }) {
                                    r.type = ValueType.Pointer
                                    continue
                                }
                            }
                            if (r.accessSize > 0) {
                                r.type = typeForWidth(r.accessSize, isSigned = true)
                            } else {
                                r.type = t
                            }
                        }
                    }

                    IROp.Load -> {
                        // 读出来的值类型由访问宽度决定
                        if (r.kind == ValueKind.Global || r.kind == ValueKind.Local) {
                            if (r.accessSize > 0) r.type = typeForWidth(r.accessSize, true)
                        }
                    }

                    else -> {}
                }
            }
        }

        // ---- 3. 参数类型 ----
        // 参数若在某处被当指针用，就不是整数。
        for (a in fn.args) {
            if (a.type == ValueType.Unknown && a.accessSize > 0) {
                a.type = typeForWidth(a.accessSize, true)
            }
        }

        // ---- 4. 返回值 ----
        // 只有单一 return 且类型一致时才采用；多个 return 保持 Int64 保守。
        val returns = ArrayList<IRValue>()
        for (b in fn.blocks) {
            val last = b.instructions.lastOrNull() ?: continue
            if (last.op == IROp.Return && last.operands.isNotEmpty()) {
                returns.add(last.operands[0])
            }
        }
        if (returns.size == 1) {
            val rv = returns[0]
            if (rv.type != ValueType.Unknown && rv.type != ValueType.Bool) {
                fn.returnValue = rv
            }
        }
    }

    private fun markPointer(v: IRValue) {
        // 栈变量与参数才可以改成指针；临时值改类型可能导致声明与实际不符，
        // 但既然它被当基址用了，声明成指针反而更准确。
        if (v.type == ValueType.Pointer) return
        v.type = ValueType.Pointer
        if (v.accessSize == 0) v.accessSize = 8
    }

    private fun widthOf(t: ValueType): Int = when (t) {
        ValueType.Int8 -> 1
        ValueType.Int16 -> 2
        ValueType.Int32 -> 4
        ValueType.Int64, ValueType.Pointer, ValueType.Double -> 8
        ValueType.Float -> 4
        ValueType.Bool -> 1
        else -> 0
    }

    fun typeForWidth(w: Int, isSigned: Boolean): ValueType = when (w) {
        1 -> if (isSigned) ValueType.Int8 else ValueType.Int8
        2 -> ValueType.Int16
        4 -> ValueType.Int32
        8 -> ValueType.Int64
        else -> ValueType.Unknown
    }
}
