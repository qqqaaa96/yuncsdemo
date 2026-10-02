package com.csdemo.tools.decompiler

/**
 * 反编译中间表示（IR）。
 *
 * 设计目标：
 *   不要把“汇编文本替换成 C 文本”，而是先把指令提升为结构化 IR，
 *   后续的 CFG / SSA / 数据流 / 类型恢复全部基于 IR。
 *
 * 这一层不关心输出格式，只描述“算了什么、读写什么、跳到哪”。
 */

/** IR 操作码 */
enum class IROp {
    Nop,

    Const,      // 立即数
    Move,       // 寄存器/变量间拷贝

    Add,
    Sub,
    Mul,
    Div,
    Rem,

    And,
    Or,
    Xor,
    Not,
    Shl,
    Shr,
    Sar,        // 算术右移

    Load,       // 从内存读
    Store,      // 写入内存

    Compare,    // 只设置标志

    Phi,        // SSA 合流

    Call,
    Return,

    Branch,     // 条件分支
    Jump,       // 无条件跳转
    Indirect    // 间接跳转（br Xn / jump table）
}

/** 值类型 */
enum class ValueType {
    Unknown,
    Bool,
    Int8,
    Int16,
    Int32,
    Int64,
    Float,
    Double,
    Pointer
}

/** 值的来源类别，用于后续变量命名与显示 */
enum class ValueKind {
    /** 临时值（t0, t1 ...） */
    Temp,
    /** 函数参数（arg0 ...） */
    Arg,
    /** 栈变量（local_20） */
    Local,
    /** 全局 / 常量地址（data_2010） */
    Global,
    /** 立即数 */
    Const,
    /** 未识别 */
    Unknown
}

/**
 * IR 中的一个值。
 *
 * 注意：这里保留 sourceRegister，使得最终输出可以反向映射回
 * 真实寄存器与机器地址，UI 将来可以做“点伪 C 跳转汇编”。
 */
class IRValue(
    val id: Int,
    var type: ValueType = ValueType.Unknown,
    var kind: ValueKind = ValueKind.Temp
) {
    /** 原始寄存器名，如 w8 / x0；没有则为空 */
    var sourceRegister: String = ""

    /** 常量值（kind == Const 时有效） */
    var constant: Long = 0L

    /** 栈偏移（kind == Local 时有效） */
    var stackOffset: Long = 0L

    /** 访问宽度（字节），用于类型推断 */
    var accessSize: Int = 0

    /** 显示名（由命名器决定） */
    var displayName: String = ""

    /** 定义该值的第一条指令地址 */
    var definedAt: Long = 0L

    /** 使用该值的地址集合（用于引用计数） */
    val uses: MutableList<Long> = ArrayList()

    val isConst: Boolean get() = kind == ValueKind.Const

    override fun toString(): String =
        if (displayName.isNotEmpty()) displayName else "v$id"
}

/** 一条 IR 指令 */
class IRInstruction(
    val op: IROp,
    val address: Long,
    val size: Int
) {
    /** 结果值（可空，如 store / branch 无结果） */
    var result: IRValue? = null

    /** 操作数 */
    val operands: MutableList<IRValue> = ArrayList()

    /** 原始汇编文本，便于对照 */
    var asmText: String = ""

    /** 分支目标（Branch / Jump / Indirect 时有效） */
    var target: Long = 0L

    /** 条件描述（Branch 时有效，如 "eq" / "ne"） */
    var condition: String = ""

    /** 该分支是否取真时走 target */
    var trueToTarget: Boolean = true

    val isTerminator: Boolean
        get() = op == IROp.Branch || op == IROp.Jump ||
                op == IROp.Return || op == IROp.Indirect

    val isBranch: Boolean get() = op == IROp.Branch
    val isJump: Boolean get() = op == IROp.Jump
    val isReturn: Boolean get() = op == IROp.Return

    override fun toString(): String {
        val r = result?.toString() ?: ""
        val ops = operands.joinToString(", ") { it.toString() }
        return when (op) {
            IROp.Store -> "store $ops"
            IROp.Branch -> "branch $ops -> 0x${target.toString(16)}"
            IROp.Jump -> "jump -> 0x${target.toString(16)}"
            IROp.Return -> if (ops.isEmpty()) "return" else "return $ops"
            IROp.Call -> "$r = call $ops"
            IROp.Phi -> "$r = phi($ops)"
            else -> "$r = ${op.name.lowercase()} $ops"
        }
    }
}
