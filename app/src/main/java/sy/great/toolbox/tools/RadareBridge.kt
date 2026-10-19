package sy.great.toolbox.tools

import java.io.File

/**
 * Radare2 JNI 桥接（Kotlin 侧）。
 *
 * 对应 native 实现：app/src/main/cpp/radare_bridge.c
 *
 * 设计：
 *   · 所有方法都返回 **原始字符串**（r2 命令输出），由调用方解析；
 *   · 不在这里做任何“修数据”的事 —— 输出什么就是什么；
 *   · 任何失败都以 "ERR:" 开头，调用方据此判断。
 *
 * 使用流程：
 *   RadareBridge.load()        // 加载 so（通常自动）
 *   RadareBridge.open(path)    // 打开 ELF
 *   RadareBridge.analyze()     // aaa
 *   RadareBridge.cfgJson(addr) // 拿 CFG
 *   RadareBridge.close()       // 释放
 */
object RadareBridge {

    /** 库是否已加载成功 */
    @Volatile
    var loaded: Boolean = false
        private set

    private var loadError: String? = null

    /** 加载 lib（幂等） */
    fun load(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            return try {
                System.loadLibrary("android-reverse-native")
                System.loadLibrary("radare_bridge")
                loaded = true
                loadError = null
                true
            } catch (e: Throwable) {
                loadError = "加载 native 库失败：" + (e.message ?: e.javaClass.simpleName)
                loaded = false
                false
            }
        }
    }

    /** 上次加载失败的原因；成功时为 null */
    fun error(): String? = loadError

    // ---------- native ----------

    private external fun nativeVersion(): String
    private external fun nativeOpen(path: String): String
    private external fun nativeAnalyze(): String
    private external fun nativeCfgJson(addr: Long): String
    private external fun nativePseudoC(addr: Long): String
    private external fun nativeDisasm(addr: Long, count: Int): String
    private external fun nativeSetup(): String
    /** 函数列表 JSON（aflj） */
    private external fun nativeFunctionList(): String
    /** 单个函数的 CFG JSON（pdfj） */
    private external fun nativePdfj(addr: Long): String
    /** 递归反汇编 JSON（pdrj） */
    private external fun nativePdrj(addr: Long): String
    private external fun nativeClose()

    // ---------- 公开 API ----------

    /** 版本号；失败返回 ERR:... */
    fun version(): String {
        if (!load()) return "ERR: " + (loadError ?: "unknown")
        return try { nativeVersion() } catch (e: Throwable) { "ERR: " + e.message }
    }

    /** 打开文件，返回 ’iI’ 摘要；失败返回 ERR:... */
    fun open(file: File): String {
        if (!load()) return "ERR: " + (loadError ?: "unknown")
        if (!file.exists()) return "ERR: 文件不存在：" + file.absolutePath
        return try { nativeOpen(file.absolutePath) } catch (e: Throwable) { "ERR: " + e.message }
    }

    /** 运行 aaa，返回函数数量；失败返回 ERR:... */
    fun analyze(): String {
        if (!load()) return "ERR: " + (loadError ?: "unknown")
        return try { nativeAnalyze().trim() } catch (e: Throwable) { "ERR: " + e.message }
    }

    /** 拿某个函数的 CFG（JSON）；失败返回 ERR:... */
    fun cfgJson(addr: Long): String {
        if (!load()) return "ERR: " + (loadError ?: "unknown")
        return try { nativeCfgJson(addr) } catch (e: Throwable) { "ERR: " + e.message }
    }

    /** 拿某个函数的伪 C；失败返回 ERR:... */
    fun pseudoC(addr: Long): String {
        if (!load()) return "ERR: " + (loadError ?: "unknown")
        return try { nativePseudoC(addr) } catch (e: Throwable) { "ERR: " + e.message }
    }

    /** 反汇编指定地址开始的 count 条 */
    fun disasm(addr: Long, count: Int = 30): String {
        if (!load()) return "ERR: " + (loadError ?: "unknown")
        return try { nativeDisasm(addr, count) } catch (e: Throwable) { "ERR: " + e.message }
    }

    /**
     * 配置 r2 输出选项（关颜色、固定格式）。
     * 必须在 open 之后、取 JSON 之前调一次。
     */
    fun setup(): String {
        if (!load()) return "ERR: " + (loadError ?: "unknown")
        return try { nativeSetup() } catch (e: Throwable) { "ERR: " + e.message }
    }

    /**
     * 取单个函数的完整 CFG（JSON）。
     *
     * 这是反编译的权威数据源：
     *   blocks[].ops[].type    = 指令类别
     *   blocks[].ops[].jump    = 跳转目标
     *   blocks[].ops[].fail    = 条件分支的顺序下一条
     * 比自研解码器准确得多。
     */
    fun pdfj(addr: Long): String {
        if (!load()) return "ERR: " + (loadError ?: "unknown")
        return try { nativePdfj(addr) } catch (e: Throwable) { "ERR: " + e.message }
    }

    /** 函数列表（aflj 的原始 JSON 文本） */
    fun functionList(): String {
        if (!load()) return "ERR: " + (loadError ?: "unknown")
        return try { nativeFunctionList() } catch (e: Throwable) { "ERR: " + e.message }
    }

    /** 递归反汇编（对无符号表的函数也能用） */
    fun pdrj(addr: Long): String {
        if (!load()) return "ERR: " + (loadError ?: "unknown")
        return try { nativePdrj(addr) } catch (e: Throwable) { "ERR: " + e.message }
    }

    /** 释放 RCore */
    fun close() {
        if (!loaded) return
        try { nativeClose() } catch (_: Throwable) { }
    }

    /** 是否是错误输出 */
    fun isError(s: String): Boolean = s.startsWith("ERR:")
}
