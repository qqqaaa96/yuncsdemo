package sy.great.toolbox.tools

/**
 * Capstone 反汇编引擎的 Kotlin 封装。
 *
 * 只做两件事：
 *   1. 加载 libcapstone_bridge.so
 *   2. 把 native 返回的 long[] / String[] 还原成 Insn 列表
 *
 * 设计：一次调用反汇编一整段，而不是每条指令一次 JNI。
 */
object CapstoneBridge {

    /** 架构编码，必须与 capstone_bridge.c 里的 ARCH_* 一致 */
    object Arch {
        const val ARM64 = 1
        const val ARM = 2
        const val X86 = 3
        const val X86_64 = 4
    }

    /** 控制流标志，必须与 capstone_bridge.c 里的 F_* 一致 */
    object Flags {
        const val JUMP = 1 shl 0
        const val CJUMP = 1 shl 1
        const val CALL = 1 shl 2
        const val RET = 1 shl 3
        const val INDIRECT = 1 shl 4
        const val INVALID = 1 shl 5
    }

    /** 一条已反汇编的指令 */
    data class Insn(
        val addr: Long,
        val word: Long,
        val size: Int,
        val mnemonic: String,
        val opStr: String,
        val flags: Int,
    ) {
        val text: String
            get() = if (opStr.isBlank()) mnemonic else "$mnemonic $opStr"

        val isJump: Boolean get() = flags and Flags.JUMP != 0
        val isCondJump: Boolean get() = flags and Flags.CJUMP != 0
        val isCall: Boolean get() = flags and Flags.CALL != 0
        val isRet: Boolean get() = flags and Flags.RET != 0
        val isIndirect: Boolean get() = flags and Flags.INDIRECT != 0
    }

    @Volatile
    private var loaded = false

    @Volatile
    private var loadError: String? = null

    /** 加载 native 库；失败返回 false 并记录原因 */
    fun load(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            return try {
                System.loadLibrary("capstone_bridge")
                loaded = true
                loadError = null
                true
            } catch (e: Throwable) {
                loadError = "加载 libcapstone_bridge.so 失败：" +
                    (e.message ?: e.javaClass.simpleName)
                loaded = false
                false
            }
        }
    }

    fun error(): String? = loadError

    /**
     * 引擎版本（如 "5.0"）。
     *
     * 未加载 / 降级空库时返回 null。
     * 降级库里 nativeVersionMajor/Minor 都返回 0，所以 0.0 也算未启用。
     */
    fun version(): String? {
        if (!load()) return null
        return try {
            val major = nativeVersionMajor()
            val minor = nativeVersionMinor()
            if (major <= 0) null else "$major.$minor"
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 反汇编一段字节。
     *
     * @param arch      架构编码（Arch.ARM64 等）
     * @param thumb     仅 ARM32 有意义：true = Thumb，false = ARM
     * @param code      机器码
     * @param baseAddr  这段代码对应的虚拟地址（每条指令的 addr 由它推导）
     * @param count     最多解码多少条指令
     * @return 解码结果；库未加载或解码失败返回空列表
     */
    fun disasm(
        arch: Int,
        thumb: Boolean = false,
        code: ByteArray,
        baseAddr: Long,
        count: Int = 4096,
    ): List<Insn> {
        if (code.isEmpty() || count <= 0) return emptyList()
        if (!load()) return emptyList()

        val raw = try {
            nativeDisasm(arch, if (thumb) 1 else 0, code, baseAddr, count)
        } catch (_: Throwable) {
            null
        } ?: return emptyList()

        if (raw.size < 2) return emptyList()
        val data = raw[0] as? LongArray ?: return emptyList()
        val text = raw[1] as? Array<*> ?: return emptyList()
        if (data.isEmpty()) return emptyList()

        val n = data[0].toInt()
        if (n <= 0) return emptyList()

        val out = ArrayList<Insn>(n)
        for (i in 0 until n) {
            val b = 1 + i * 5
            if (b + 4 >= data.size) break
            val t = i * 2
            val mn = (text.getOrNull(t) as? String).orEmpty()
            val op = (text.getOrNull(t + 1) as? String).orEmpty()
            out.add(
                Insn(
                    addr = data[b],
                    word = data[b + 1],
                    size = data[b + 2].toInt(),
                    mnemonic = mn,
                    opStr = op,
                    flags = data[b + 3].toInt(),
                )
            )
        }
        return out
    }

    // ---------- native ----------

    /**
     * 返回 Object[]{ long[], String[] }
     *   long[0]          = 指令条数
     *   long[1 + i*5 ..] = address / word / size / flags / 预留
     *   String[i*2]      = mnemonic
     *   String[i*2 + 1]  = op_str
     */
    private external fun nativeDisasm(
        arch: Int,
        isThumb: Int,
        code: ByteArray,
        baseAddr: Long,
        count: Int,
    ): Array<Any>?

    private external fun nativeVersionMajor(): Int
    private external fun nativeVersionMinor(): Int
}
