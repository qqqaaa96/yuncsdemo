package sy.great.toolbox.tools.mcp

import org.json.JSONArray
import org.json.JSONObject
import sy.great.toolbox.tools.Arm64Disasm
import sy.great.toolbox.tools.CfgBuilder
import sy.great.toolbox.tools.ElfParser
import sy.great.toolbox.tools.decompiler.R2Decompiler
import java.io.File
import java.security.MessageDigest

/**
 * MCP 工具集合。
 *
 * 全部基于项目已有能力：
 *   ElfParser       —— ELF 解析
 *   Arm64Disasm     —— 反汇编
 *   CfgBuilder      —— 控制流图
 *   R2Decompiler    —— 伪 C（依赖 radare2 原生库）
 *
 * 设计原则：
 *   1. 只暴露真正能跑的。没依赖的工具（rizin/lief/unidbg）不注册，
 *      而不是注册了却返回“不支持”——那样 AI 会反复试错。
 *   2. 所有路径都走 McpToolContext.resolve()，限制在工作目录内。
 *   3. 返回统一 JSON，字段名固定，方便 AI 解析。
 */
object McpTools {

    /** 工作目录路径（通知栏 / 页面展示用，与 McpService.workDir() 一致） */
    fun workDirPath(): String = "/storage/emulated/0/ELF分析处/mcp/"

    /** 构建全部工具 */
    fun all(): List<McpTool> {
        val list = ArrayList<McpTool>()
        list.addAll(fileTools())
        list.addAll(elfTools())
        list.addAll(disasmTools())
        list.addAll(cfgTools())
        list.addAll(decompileTools())
        return list
    }

    // ============================================================
    // 文件操作
    // ============================================================

    private fun fileTools(): List<McpTool> = listOf(
        McpTool(
            name = "mcp_list_files",
            title = "列出文件",
            description = "列出工作目录里的所有 ELF 文件。" +
                "返回文件名、大小、SHA-256 前 16 位。先用它找到要分析的文件。",
            inputSchema = Schema.obj(),
        ) { ctx, _ ->
            val files = ctx.listElfFiles()
            val arr = JSONArray()
            files.forEach { f ->
                arr.put(
                    JSONObject()
                        .put("name", f.name)
                        .put("size", f.length())
                        .put("sha256", sha256Prefix(f))
                )
            }
            JSONObject().put("workDir", ctx.workDir.absolutePath).put("count", files.size).put("files", arr)
        },

        McpTool(
            name = "mcp_read_bytes",
            title = "读取字节",
            description = "读取 ELF 文件指定偏移的原始字节（hex）。" +
                "用于查看某个地址的实际字节内容。单次最多 4096 字节。",
            inputSchema = Schema.obj(
                "file" to Schema.str("文件名（工作目录内的）"),
                "offset" to Schema.int("起始字节偏移，十六进制字符串或十进制整数", 0),
                "length" to Schema.int("读取字节数，1~4096", 256),
            )
        ) { ctx, args ->
            val f = ctx.resolve(args.optString("file"))
            require(f.exists()) { "文件不存在：${f.name}" }

            val offset = parseLong(args.opt("offset"))
            val len = args.optInt("length", 256).coerceIn(1, 4096)

            // 只读指定段，不加载整个文件（大 .so 会 OOM）。
            val slice = readFileAt(f, offset, len)
                ?: return@McpTool JSONObject().put("error", true)
                    .put("message", "偏移超出文件大小 ${f.length()}")
            JSONObject()
                .put("file", f.name)
                .put("offset", offset)
                .put("length", slice.size)
                .put("hex", slice.joinToString("") { "%02X".format(it) })
        },

        McpTool(
            name = "mcp_hash",
            title = "计算哈希",
            description = "计算 ELF 文件的 MD5 / SHA-1 / SHA-256。",
            inputSchema = Schema.obj("file" to Schema.str("文件名")),
        ) { ctx, args ->
            val f = ctx.resolve(args.optString("file"))
            require(f.exists()) { "文件不存在" }
            // 流式计算，不一次性读全文件
            JSONObject()
                .put("file", f.name)
                .put("size", f.length())
                .put("md5", digestFile("MD5", f))
                .put("sha1", digestFile("SHA-1", f))
                .put("sha256", digestFile("SHA-256", f))
        },

        McpTool(
            name = "mcp_write_file",
            title = "写入文件",
            description = "向工作目录写入文件或创建文件夹。" +
                "用它把分析结果、脚本、脱壳产物保存到工作区，方便后续工具链使用。" +
                "path 可以用相对路径（如 out/dump.bin）或子目录名；" +
                "目录不存在时会自动创建。mode=dir 只建文件夹，mode=text 写文本，mode=hex 写十六进制字节。",
            inputSchema = Schema.obj(
                "path" to Schema.str("工作目录内的相对路径，如 out/result.txt 或 logs"),
                "mode" to Schema.oneOf("写入模式：text 写文本 / hex 写字节 / dir 只建目录", "text", "hex", "dir"),
                "content" to Schema.str("内容：mode=text 时为文本；mode=hex 时为十六进制串（偶数位）；mode=dir 时忽略", ""),
                "overwrite" to Schema.bool("已存在时是否覆盖，默认 false", false),
                "append" to Schema.bool("是否追加到文件末尾，默认 false（仅 mode=text/hex 有效）", false),
            )
        ) { ctx, args ->
            val rawPath = args.optString("path")
            require(rawPath.isNotBlank()) { "path 不能为空" }

            val mode = args.optString("mode", "text").lowercase()
            require(mode == "text" || mode == "hex" || mode == "dir") {
                "mode 只能是 text / hex / dir"
            }

            val content = args.optString("content", "")
            val overwrite = args.optBoolean("overwrite", false)
            val append = args.optBoolean("append", false)

            // 路径必須走沙箱校验，禁止 .. 逃逸
            val target = ctx.resolve(rawPath)

            // ---- 建目录 ----
            if (mode == "dir") {
                if (target.exists() && !target.isDirectory) {
                    return@McpTool JSONObject().put("error", true)
                        .put("message", "同名文件已存在，无法建目录：${target.name}")
                }
                val existed = target.exists()
                if (!target.exists() && !target.mkdirs()) {
                    return@McpTool JSONObject().put("error", true)
                        .put("message", "创建目录失败（无权限或路径非法）")
                }
                return@McpTool JSONObject()
                    .put("ok", true)
                    .put("mode", "dir")
                    .put("path", relPath(ctx, target))
                    .put("created", !existed)
            }

            // ---- 写文件 ----
            if (target.isDirectory) {
                return@McpTool JSONObject().put("error", true)
                    .put("message", "目标是已存在的目录，请换一个文件名")
            }

            // 父目录自动创建
            target.parentFile?.let { p ->
                if (!p.exists() && !p.mkdirs()) {
                    return@McpTool JSONObject().put("error", true)
                        .put("message", "创建父目录失败：${p.name}")
                }
            }

            val existed = target.exists()
            if (existed && !overwrite && !append) {
                return@McpTool JSONObject().put("error", true)
                    .put("message", "文件已存在：${target.name}。要覆盖请传 overwrite=true，要追加请传 append=true")
            }

            // 转字节
            val bytes: ByteArray = when (mode) {
                "hex" -> {
                    val clean = content.filter { !it.isWhitespace() }
                    if (clean.length % 2 != 0) {
                        return@McpTool JSONObject().put("error", true)
                            .put("message", "hex 内容长度必须为偶数（每字节两位）")
                    }
                    try {
                        ByteArray(clean.length / 2) { i ->
                            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                        }
                    } catch (_: Throwable) {
                        return@McpTool JSONObject().put("error", true)
                            .put("message", "hex 内容含非法字符，只允许 0-9 A-F")
                    }
                }
                else -> content.toByteArray(Charsets.UTF_8)
            }

            try {
                java.io.FileOutputStream(target, append).use { out ->
                    out.write(bytes)
                }
            } catch (e: Throwable) {
                return@McpTool JSONObject().put("error", true)
                    .put("message", "写入失败：${e.message ?: e.javaClass.simpleName}")
            }

            JSONObject()
                .put("ok", true)
                .put("mode", mode)
                .put("path", relPath(ctx, target))
                .put("bytesWritten", bytes.size)
                .put("totalSize", target.length())
                .put("created", !existed)
                .put("appended", append)
        },
    )

    // ============================================================
    // ELF 结构
    // ============================================================

    private fun elfTools(): List<McpTool> = listOf(
        McpTool(
            name = "elf_header",
            title = "ELF 头部",
            description = "读取 ELF 头部信息：架构、位数、字节序、入口点、程序头/节区表位置。",
            inputSchema = Schema.obj("file" to Schema.str("文件名")),
        ) { ctx, args ->
            val elf = loadElf(ctx, args.optString("file"))
            JSONObject()
                .put("is64", elf.is64)
                .put("littleEndian", elf.isLittleEndian)
                .put("arch", elf.arch.name)
                .put("machine", "0x%X".format(elf.machine))
                .put("machineName", elf.machineName)
                .put("type", elf.type)
                .put("entry", "0x%X".format(elf.entry))
                .put("phoff", elf.phoff)
                .put("shoff", elf.shoff)
                .put("programCount", elf.programs.size)
                .put("sectionCount", elf.sections.size)
        },

        McpTool(
            name = "elf_sections",
            title = "节区表",
            description = "列出 ELF 所有节区（.text / .rodata / .data 等），含地址、大小、偏移。",
            inputSchema = Schema.obj("file" to Schema.str("文件名")),
        ) { ctx, args ->
            val elf = loadElf(ctx, args.optString("file"))
            val arr = JSONArray()
            elf.sections.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("name", s.name)
                        .put("type", s.typeName)
                        .put("addr", "0x%X".format(s.addr))
                        .put("offset", s.offset)
                        .put("size", s.size)
                )
            }
            JSONObject().put("count", elf.sections.size).put("sections", arr)
        },

        McpTool(
            name = "elf_segments",
            title = "程序头",
            description = "列出 ELF 程序头（段），含权限标志（可执行/可写）、虚拟地址、文件大小与内存大小。",
            inputSchema = Schema.obj("file" to Schema.str("文件名")),
        ) { ctx, args ->
            val elf = loadElf(ctx, args.optString("file"))
            val arr = JSONArray()
            elf.programs.forEach { p ->
                // flags: 1=X可执行 2=W可写 4=R可读
                arr.put(
                    JSONObject()
                        .put("type", p.typeName)
                        .put("flags", "0x%X".format(p.flags))
                        .put("vaddr", "0x%X".format(p.vaddr))
                        .put("offset", p.offset)
                        .put("fileSize", p.filesz)
                        .put("memSize", p.memsz)
                        .put("executable", (p.flags and 1L) != 0L)
                        .put("writable", (p.flags and 2L) != 0L)
                )
            }
            JSONObject().put("count", elf.programs.size).put("segments", arr)
        },

        McpTool(
            name = "elf_symbols",
            title = "符号表",
            description = "读取符号表（.symtab / .dynsym）。可筛选：只返回函数或只返回指定名称。",
            inputSchema = Schema.obj(
                "file" to Schema.str("文件名"),
                "limit" to Schema.int("最多返回多少条，默认 500", 500),
                "filter" to Schema.str("名称包含此子串（可选，空=全部）", ""),
                "onlyFunctions" to Schema.bool("只看函数类型符号", false),
            )
        ) { ctx, args ->
            val elf = loadElf(ctx, args.optString("file"))
            val limit = args.optInt("limit", 500).coerceIn(1, 5000)
            val filter = args.optString("filter", "").lowercase()
            val onlyFn = args.optBoolean("onlyFunctions", false)

            val arr = JSONArray()
            var shown = 0
            for (s in elf.symbols) {
                if (onlyFn && !s.isFunc) continue
                if (filter.isNotEmpty() && !s.name.lowercase().contains(filter)) continue
                if (shown >= limit) break
                arr.put(
                    JSONObject()
                        .put("name", s.name)
                        .put("addr", "0x%X".format(s.value))
                        .put("size", s.size)
                        .put("type", s.typeName)
                        .put("bind", s.bind)
                        .put("import", s.isImport)
                )
                shown++
            }
            JSONObject()
                .put("total", elf.symbols.size)
                .put("shown", shown)
                .put("symbols", arr)
        },

        McpTool(
            name = "elf_strings",
            title = "提取字符串",
            description = "提取字符串。默认扫描全文件可打印 ASCII。用于找 URL/路径/错误信息/密钥。",
            inputSchema = Schema.obj(
                "file" to Schema.str("文件名"),
                "minLength" to Schema.int("最短长度，默认 5", 5),
                "limit" to Schema.int("最多返回条数，默认 500", 500),
                "filter" to Schema.str("内容包含此子串（可选）", ""),
            )
        ) { ctx, args ->
            val elf = loadElf(ctx, args.optString("file"))
            val minLen = args.optInt("minLength", 5).coerceIn(2, 64)
            val limit = args.optInt("limit", 500).coerceIn(1, 5000)
            val filter = args.optString("filter", "")

            val all = ElfParser.extractAllStrings(elf, minLen)
            val arr = JSONArray()
            var shown = 0
            // 大小写不敏感，与 elf_symbols 的 filter 行为保持一致
            val filterLower = filter.lowercase()
            for ((addr, s) in all) {
                if (filterLower.isNotEmpty() && !s.lowercase().contains(filterLower)) continue
                if (shown >= limit) break
                arr.put(JSONObject().put("addr", "0x%X".format(addr)).put("text", s))
                shown++
            }
            JSONObject().put("total", all.size).put("shown", shown).put("strings", arr)
        },
    )

    // ============================================================
    // 反汇编
    // ============================================================

    private fun disasmTools(): List<McpTool> = listOf(
        McpTool(
            name = "disasm_range",
            title = "反汇编",
            description = "反汇编指定地址范围。需要提供虚拟地址（可用 elf_symbols 拿）。" +
                "返回每条指令的地址、字节、汇编文本。",
            inputSchema = Schema.obj(
                "file" to Schema.str("文件名"),
                "address" to Schema.str("起始虚拟地址，如 0x1234 或十进制"),
                "count" to Schema.int("指令条数，1~2000，默认 50", 50),
            )
        ) { ctx, args ->
            val elf = loadElf(ctx, args.optString("file"))
            requireArm64(elf)?.let { return@McpTool JSONObject().put("error", true).put("message", it) }
            val addr = parseLong(args.opt("address"))
            val count = args.optInt("count", 50).coerceIn(1, 2000)

            val bytes = readAtVa(elf, ctx, args.optString("file"), addr, count * 4 + 64)
                ?: return@McpTool JSONObject().put("error", true).put("message", "地址不在任何可加载段内")

            val insns = decodeRange(bytes, addr, count)
            val arr = JSONArray()
            insns.forEach { i ->
                arr.put(
                    JSONObject()
                        .put("addr", "0x%X".format(i.addr))
                        .put("word", "0x%08X".format(i.word))
                        .put("text", i.text)
                )
            }
            JSONObject().put("start", "0x%X".format(addr)).put("count", insns.size).put("instructions", arr)
        },

        McpTool(
            name = "disasm_function",
            title = "反汇编函数",
            description = "按符号名反汇编一个函数。比 disasm_range 更方便：直接给函数名。" +
                "会先用符号表找地址。",
            inputSchema = Schema.obj(
                "file" to Schema.str("文件名"),
                "function" to Schema.str("函数名（精确匹配或包含均可）；stripped 文件留空", ""),
                "address" to Schema.str("函数虚拟地址（如 0x1260D0）；function 找不到时用", ""),
                "count" to Schema.int("最多反汇编多少条，默认 200", 200),
            )
        ) { ctx, args ->
            val elf = loadElf(ctx, args.optString("file"))
            requireArm64(elf)?.let { return@McpTool JSONObject().put("error", true).put("message", it) }
            val fn = args.optString("function")
            val count = args.optInt("count", 200).coerceIn(1, 2000)
            val addrArg = args.optString("address", "")

            // 无符号表（stripped）的 ELF：允许直接传地址
            val sym = if (fn.isNotBlank()) {
                elf.symbols.firstOrNull { it.name == fn }
                    ?: elf.symbols.firstOrNull { it.name.contains(fn) && it.isFunc }
            } else null

            val startAddr = sym?.value
                ?: if (addrArg.isNotBlank()) parseLong(addrArg) else null
                ?: return@McpTool JSONObject().put("error", true)
                    .put("message", "找不到函数：$fn。该文件可能已 stripped（无符号表），请改用 address 参数直接传虚拟地址。")

            val bytes = readAtVa(elf, ctx, args.optString("file"), startAddr, count * 4 + 64)
                ?: return@McpTool JSONObject().put("error", true).put("message", "函数地址不在可加载段内")

            val insns = decodeRange(bytes, startAddr, count)
            val arr = JSONArray()
            insns.forEach { i ->
                arr.put(
                    JSONObject()
                        .put("addr", "0x%X".format(i.addr))
                        .put("word", "0x%08X".format(i.word))
                        .put("text", i.text)
                )
            }
            JSONObject()
                .put("function", sym?.name ?: "(按地址)")
                .put("address", "0x%X".format(startAddr))
                .put("declaredSize", sym?.size ?: 0)
                .put("stripped", sym == null)
                .put("count", insns.size)
                .put("instructions", arr)
        },
    )

    // ============================================================
    // 控制流图
    // ============================================================

    private fun cfgTools(): List<McpTool> = listOf(
        McpTool(
            name = "cfg_function",
            title = "控制流图",
            description = "构建指定函数的控制流图。返回基本块列表（地址范围、后继块、指令）。" +
                "用于理解分支/循环结构。",
            inputSchema = Schema.obj(
                "file" to Schema.str("文件名"),
                "function" to Schema.str("函数名；stripped 文件留空", ""),
                "address" to Schema.str("函数虚拟地址（如 0x1260D0）；stripped 文件用这个", ""),
            )
        ) { ctx, args ->
            val elf = loadElf(ctx, args.optString("file"))
            requireArm64(elf)?.let { return@McpTool JSONObject().put("error", true).put("message", it) }
            val fn = args.optString("function")
            val addrArg = args.optString("address", "")

            val sym = if (fn.isNotBlank()) {
                elf.symbols.firstOrNull { it.name == fn }
                    ?: elf.symbols.firstOrNull { it.name.contains(fn) && it.isFunc }
            } else null

            val startAddr = sym?.value
                ?: if (addrArg.isNotBlank()) parseLong(addrArg) else null
                ?: return@McpTool JSONObject().put("error", true)
                    .put("message", "找不到函数：$fn。该文件可能已 stripped，请改用 address 参数传虚拟地址。")

            // 有符号长度就按函数边界扫，否则会越界把后面函数的代码也扫进来
            val cfg = CfgBuilder.buildFromAddress(
                elf = elf,
                addr = startAddr,
                maxInsns = 20000,
                funcSize = sym?.size ?: 0L,
            ) ?: return@McpTool JSONObject().put("error", true)
                .put("message", "CFG 构建失败：地址不在可执行段")

            // FuncGraph 里实际块在 .graph.blocks
            val g = cfg.graph
            val blocks = JSONArray()
            g.blocks.forEach { b ->
                blocks.put(
                    JSONObject()
                        .put("id", b.id)
                        .put("start", "0x%X".format(b.start))
                        .put("end", "0x%X".format(b.end))
                        .put("insnCount", b.insns.size)
                        .put("successors", JSONArray(b.successors.map { "0x%X".format(it) }))
                        .put("isEntry", b.isEntry)
                        .put("isExit", b.isExit)
                )
            }
            JSONObject()
                .put("function", sym?.name ?: "(按地址)")
                .put("address", "0x%X".format(startAddr))
                .put("stripped", sym == null)
                .put("blockCount", cfg.blockCount)
                .put("edgeCount", cfg.edgeCount)
                .put("blocks", blocks)
        },
    )

    // ============================================================
    // 伪 C 反编译
    // ============================================================

    private fun decompileTools(): List<McpTool> = listOf(
        McpTool(
            name = "decompile_file",
            title = "反编译",
            description = "把整个 ELF 反编译成伪 C，返回 Markdown 文本。" +
                "依赖 radare2 原生库。大文件会很慢，建议先用 elf_symbols 定位目标函数。" +
                "注意：maxFunctions 控制最多导出多少个函数，默认 50。",
            inputSchema = Schema.obj(
                "file" to Schema.str("文件名"),
                "maxFunctions" to Schema.int("最多反编译多少个函数，默认 50", 50),
            )
        ) { ctx, args ->
            val f = ctx.resolve(args.optString("file"))
            require(f.exists()) { "文件不存在：${f.name}" }
            val max = args.optInt("maxFunctions", 50).coerceIn(1, 500)

            val r = R2Decompiler.decompileFile(f.absolutePath, max)
            if (r.error.isNotBlank()) {
                return@McpTool JSONObject().put("error", true).put("message", r.error)
            }
            JSONObject()
                .put("file", f.name)
                .put("functionCount", r.functionCount)
                .put("blockCount", r.blockCount)
                .put("markdown", r.text.take(200_000))
        },
    )

    // ============================================================
    // 工具函数
    // ============================================================

    private fun loadElf(ctx: McpToolContext, name: String): ElfParser.Elf {
        val f = ctx.resolve(name)
        require(f.exists()) { "文件不存在：${f.name}。请先用 mcp_list_files 查看工作目录。" }
        return ElfParser.parse(f.readBytes())
    }

    /**
     * 只支持 ARM64 的工具（反汇编 / CFG）先检查架构。
     *
     * 以前不检查：x86_64 的 .so 交给 ARM64 解码器，输出一堆 .word，
     * 工具却不报错 —— 对 AI 是致命误导。现在直接拒绝，告诉 AI 原因。
     */
    private fun requireArm64(elf: ElfParser.Elf): String? {
        return if (elf.machine != 0xB7) {
            "该文件架构为 ${elf.machineName}（machine=0x${Integer.toHexString(elf.machine)}），" +
                "当前反汇编器和 CFG 只支持 AArch64 (ARM64)。" +
                "请换用 ARM64 的 so，或改用 mcp_read_bytes / elf_sections 查看原始结构。"
        } else null
    }

    /**
     * 按虚拟地址读字节。
     *
     * 不读整个文件（大 .so 几百 MB 会直接 OOM），
     * 而是用 RandomAccessFile 只读需要的那一段。
     */
    private fun readAtVa(
        elf: ElfParser.Elf,
        ctx: McpToolContext,
        name: String,
        va: Long,
        len: Int,
    ): ByteArray? {
        val off = elf.vaToOffset(va) ?: return null
        if (off < 0L) return null
        val f = ctx.resolve(name)
        return readFileAt(f, off, len)
    }

    /**
     * 从文件指定偏移读一段字节（不加载整个文件）。
     *
     * offset / len 都用 Long 计算，避免 Int 溢出。
     */
    private fun readFileAt(f: File, offset: Long, len: Int): ByteArray? {
        return try {
            val total = f.length()
            if (offset < 0L || offset >= total) return null
            val realLen = minOf(len.toLong(), total - offset).toInt()
            if (realLen <= 0) return null
            java.io.RandomAccessFile(f, "r").use { raf ->
                val buf = ByteArray(realLen)
                raf.seek(offset)
                var read = 0
                while (read < realLen) {
                    val n = raf.read(buf, read, realLen - read)
                    if (n <= 0) break
                    read += n
                }
                if (read <= 0) null else buf.copyOf(read)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 从字节数组反汇编出一段指令。
     *
     * Arm64Disasm 只提供单条 decode()，这里循环调用。
     */
    private fun decodeRange(bytes: ByteArray, baseAddr: Long, count: Int): List<Arm64Disasm.Insn> {
        val out = ArrayList<Arm64Disasm.Insn>(count)
        var off = 0
        var n = 0
        while (n < count && off + 4 <= bytes.size) {
            val insn = Arm64Disasm.decode(bytes, off, baseAddr) ?: break
            out.add(insn)
            off += 4
            n++
        }
        return out
    }

    private fun parseLong(v: Any?): Long {
        return when (v) {
            is Number -> v.toLong()
            is String -> {
                val s = v.trim()
                if (s.startsWith("0x", ignoreCase = true)) {
                    s.substring(2).toLongOrNull(16) ?: 0L
                } else {
                    s.toLongOrNull() ?: 0L
                }
            }
            else -> 0L
        }
    }

    private fun sha256Prefix(f: File): String {
        return try {
            digestFile("SHA-256", f).take(16)
        } catch (_: Throwable) {
            ""
        }
    }

    /** 转成相对工作目录的路径（展示用） */
    private fun relPath(ctx: McpToolContext, f: File): String {
        return try {
            val base = ctx.workDir.canonicalPath
            val p = f.canonicalPath
            if (p.startsWith(base)) p.substring(base.length).trimStart(File.separatorChar) else p
        } catch (_: Throwable) {
            f.absolutePath
        }
    }

    /** 字节数组哈希 */
    private fun digest(algo: String, bytes: ByteArray): String {
        val md = MessageDigest.getInstance(algo)
        val d = md.digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    /**
     * 文件哈希（流式）。
     *
     * 大 .so 文件几百 MB，readBytes() 会直接 OOM，必须分块流式计。
     */
    private fun digestFile(algo: String, f: File): String {
        return try {
            val md = MessageDigest.getInstance(algo)
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            ""
        }
    }
}
