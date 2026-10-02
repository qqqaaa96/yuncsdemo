package com.csdemo.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ELF 解析器（自己实现，不依赖外部库）。
 *
 * 支持 ELF32 / ELF64，小端（Android 上绝大多数是 LE）。
 * 解析内容：文件头、节区表、符号表、字符串表、程序头。
 */
object ElfParser {

    // ---------- 数据结构 ----------

    enum class Arch { ARM64, ARM, X86_64, X86, UNKNOWN }

    data class Section(
        val name: String,
        val nameOff: Int,
        val type: Long,
        val flags: Long,
        val addr: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val info: Int,
        val addralign: Long,
        val entsize: Long
    ) {
        val typeName: String
            get() = when (type) {
                0L -> "NULL"
                1L -> "PROGBITS"
                2L -> "SYMTAB"
                3L -> "STRTAB"
                4L -> "RELA"
                8L -> "NOBITS"
                9L -> "REL"
                11L -> "DYNSYM"
                14L -> "INIT_ARRAY"
                15L -> "FINI_ARRAY"
                0x6ffffff6L -> "GNU_HASH"
                else -> "0x" + java.lang.Long.toHexString(type)
            }
        val perms: String
            get() {
                val w = if (flags and 1L != 0L) "W" else "-"
                val a = if (flags and 2L != 0L) "A" else "-"
                val x = if (flags and 4L != 0L) "X" else "-"
                return "$w$a$x"
            }
    }

    data class Symbol(
        val name: String,
        val value: Long,
        val size: Long,
        val info: Int,
        val other: Int,
        val shndx: Int,
        val isFunc: Boolean,
        val isObject: Boolean,
        val isImport: Boolean,
        val bind: String
    ) {
        val typeName: String
            get() = when (info and 0xF) {
                0 -> "NOTYPE"
                1 -> "OBJECT"
                2 -> "FUNC"
                3 -> "SECTION"
                4 -> "FILE"
                5 -> "COMMON"
                6 -> "TLS"
                else -> "?"
            }
    }

    data class ProgramHeader(
        val type: Long,
        val flags: Long,
        val offset: Long,
        val vaddr: Long,
        val filesz: Long,
        val memsz: Long,
        val align: Long
    ) {
        val typeName: String
            get() = when (type) {
                1L -> "LOAD"
                2L -> "DYNAMIC"
                4L -> "NOTE"
                6L -> "PHDR"
                7L -> "TLS"
                0x6474e550L -> "GNU_EH_FRAME"
                0x6474e551L -> "GNU_STACK"
                0x6474e552L -> "GNU_RELRO"
                else -> "0x" + java.lang.Long.toHexString(type)
            }
    }

    data class Elf(
        val is64: Boolean,
        val isLittleEndian: Boolean,
        val arch: Arch,
        val machine: Int,
        val type: Int,
        val entry: Long,
        val phoff: Long,
        val shoff: Long,
        val phentsize: Int,
        val phnum: Int,
        val shentsize: Int,
        val shnum: Int,
        val shstrndx: Int,
        val sections: List<Section>,
        val programs: List<ProgramHeader>,
        val symbols: List<Symbol>,
        val imports: List<Symbol>,
        val bytes: ByteArray
    ) {
        fun section(name: String): Section? = sections.firstOrNull { it.name == name }

        // ============================================================
        // 可执行段与地址换算
        //
        // 关键：Linux 可执行文件的真实可执行区域应以
        // PT_LOAD + PF_X 为准，而不是只依赖节区表。
        // 很多 stripped ELF 的节区表为空或不完整。
        // ============================================================

        /** 一个可执行（可加载）段的描述 */
        data class ExecSegment(
            val vaddr: Long,
            val fileOffset: Long,
            val fileSize: Long,
            val memSize: Long
        ) {
            fun contains(va: Long): Boolean =
                va >= vaddr && va < vaddr + maxOf(memSize, fileSize)
        }

        /**
         * 取所有可执行段（PT_LOAD 且带 PF_X）。
         *
         * 注：PF_X = 1，PF_W = 2，PF_R = 4。
         * const val 不允许写在 data class 内部，所以这里直接内联常量。
         */
        fun executableSegments(): List<ExecSegment> {
            return programs
                .filter { it.type == 1L && (it.flags and 1L) != 0L && it.filesz > 0 }
                .map {
                    ExecSegment(
                        vaddr = it.vaddr,
                        fileOffset = it.offset,
                        fileSize = it.filesz,
                        memSize = it.memsz
                    )
                }
                .sortedBy { it.vaddr }
        }

        /**
         * 虚拟地址 → 文件偏移。
         *
         * 这是 ELF 分析里最关键的一步换算：
         *   fileOffset = p_offset + (VA - p_vaddr)
         *
         * 直接拿 VA 当文件偏移是错的（对非 PIE 可执行文件会完全错位）。
         */
        fun vaToFileOffset(va: Long): Long? {
            for (p in programs) {
                if (p.type != 1L) continue
                if (p.filesz <= 0) continue
                if (va >= p.vaddr && va < p.vaddr + p.filesz) {
                    return p.offset + (va - p.vaddr)
                }
            }
            return null
        }

        /**
         * 尝试用节区表换算（兵底）。
         * 仅用于节区头存在但程序头异常的情况。
         */
        fun vaToFileOffsetBySection(va: Long): Long? {
            for (s in sections) {
                if (s.type == 8L) continue          // SHT_NOBITS 不占文件空间
                if (s.size <= 0) continue
                if (va >= s.addr && va < s.addr + s.size) {
                    return s.offset + (va - s.addr)
                }
            }
            return null
        }

        /** 综合换算：先程序头，再节区表 */
        fun vaToOffset(va: Long): Long? = vaToFileOffset(va) ?: vaToFileOffsetBySection(va)

        val machineName: String
            get() = when (machine) {
                0x28 -> "ARM"
                0xB7 -> "AArch64 (ARM64)"
                0x3E -> "x86-64"
                0x03 -> "x86"
                else -> "0x" + Integer.toHexString(machine)
            }

        val typeName: String
            get() = when (type) {
                1 -> "REL (可重定位)"
                2 -> "EXEC (可执行)"
                3 -> "DYN (共享对象 / PIE)"
                4 -> "CORE"
                else -> "?"
            }
    }

    // ---------- 解析入口 ----------

    fun parse(bytes: ByteArray): Elf {
        require(bytes.size >= 64) { "文件太小，不是 ELF" }
        require(bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            "不是 ELF 文件（magic 不匹配）"
        }

        val eiClass = bytes[4].toInt() and 0xFF   // 1=32bit 2=64bit
        val eiData = bytes[5].toInt() and 0xFF    // 1=LE 2=BE
        val is64 = eiClass == 2
        val isLE = eiData == 1

        val buf = ByteBuffer.wrap(bytes).order(if (isLE) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)

        val machine: Int
        val type: Int
        val entry: Long
        val phoff: Long
        val shoff: Long
        val phentsize: Int
        val phnum: Int
        val shentsize: Int
        val shnum: Int
        val shstrndx: Int

        if (is64) {
            type = buf.getShort(16).toInt() and 0xFFFF
            machine = buf.getShort(18).toInt() and 0xFFFF
            entry = buf.getLong(24)
            phoff = buf.getLong(32)
            shoff = buf.getLong(40)
            phentsize = buf.getShort(54).toInt() and 0xFFFF
            phnum = buf.getShort(56).toInt() and 0xFFFF
            shentsize = buf.getShort(58).toInt() and 0xFFFF
            shnum = buf.getShort(60).toInt() and 0xFFFF
            shstrndx = buf.getShort(62).toInt() and 0xFFFF
        } else {
            type = buf.getShort(16).toInt() and 0xFFFF
            machine = buf.getShort(18).toInt() and 0xFFFF
            entry = buf.getInt(24).toLong() and 0xFFFFFFFFL
            phoff = buf.getInt(28).toLong() and 0xFFFFFFFFL
            shoff = buf.getInt(32).toLong() and 0xFFFFFFFFL
            phentsize = buf.getShort(42).toInt() and 0xFFFF
            phnum = buf.getShort(44).toInt() and 0xFFFF
            shentsize = buf.getShort(46).toInt() and 0xFFFF
            shnum = buf.getShort(48).toInt() and 0xFFFF
            shstrndx = buf.getShort(50).toInt() and 0xFFFF
        }

        val arch = when (machine) {
            0xB7 -> Arch.ARM64
            0x28 -> Arch.ARM
            0x3E -> Arch.X86_64
            0x03 -> Arch.X86
            else -> Arch.UNKNOWN
        }

        // 节区表
        val rawSections = ArrayList<Section>(shnum)
        for (i in 0 until shnum) {
            val off = (shoff + i.toLong() * shentsize).toInt()
            if (off + shentsize > bytes.size) break
            rawSections.add(readSection(buf, off, is64))
        }

        // 节区名（依赖 shstrtab）
        val shstr = rawSections.getOrNull(shstrndx)
        val shstrBytes = if (shstr != null) slice(bytes, shstr.offset, shstr.size) else ByteArray(0)
        val sections = rawSections.map { s ->
            s.copy(name = readCStr(shstrBytes, s.nameOff))
        }

        // 程序头
        val programs = ArrayList<ProgramHeader>(phnum)
        for (i in 0 until phnum) {
            val off = (phoff + i.toLong() * phentsize).toInt()
            if (off + phentsize > bytes.size) break
            programs.add(readProgramHeader(buf, off, is64))
        }

        // ============================================================
        // 符号表选择
        //
        // 注意：不能写成 firstOrNull { type == 2 || type == 11 }。
        // 那样拿到的只是“节区表中位置靠前”的那个，而实际顺序常常是
        //   #4  .dynsym (SHT_DYNSYM=11)
        //   #17 .symtab (SHT_SYMTAB=2)
        // 于是永远取到 .dynsym。
        //
        // 后果：可执行文件的本地函数（main/add/...）只在 .symtab 里，
        // 取 .dynsym 会导致 functions() 为空，进而报“没有可执行的代码节区”。
        //
        // 正确做法：按类型优先级显式查找，并且优先按节区名匹配。
        // ============================================================
        val symtabPrimary = sections.firstOrNull { it.name == ".symtab" }
            ?: sections.firstOrNull { it.type == 2L }
            ?: sections.firstOrNull { it.name == ".dynsym" }
            ?: sections.firstOrNull { it.type == 11L }

        val symbols = ArrayList<Symbol>()
        if (symtabPrimary != null) {
            val strSec = sections.getOrNull(symtabPrimary.link)
            val strBytes = if (strSec != null) slice(bytes, strSec.offset, strSec.size) else ByteArray(0)
            val ent = if (symtabPrimary.entsize > 0) symtabPrimary.entsize.toInt() else if (is64) 24 else 16
            var n = 0
            var o = symtabPrimary.offset
            while (o + ent <= symtabPrimary.offset + symtabPrimary.size && o + ent <= bytes.size) {
                symbols.add(readSymbol(buf, o.toInt(), is64, strBytes))
                o += ent
                n++
                if (n > 200000) break // 安全上限
            }
        }

        val imports = symbols.filter { it.isImport }

        return Elf(
            is64 = is64,
            isLittleEndian = isLE,
            arch = arch,
            machine = machine,
            type = type,
            entry = entry,
            phoff = phoff,
            shoff = shoff,
            phentsize = phentsize,
            phnum = phnum,
            shentsize = shentsize,
            shnum = shnum,
            shstrndx = shstrndx,
            sections = sections,
            programs = programs,
            symbols = symbols,
            imports = imports,
            bytes = bytes
        )
    }

    // ---------- 低层读取 ----------

    private fun readSection(buf: ByteBuffer, off: Int, is64: Boolean): Section {
        return if (is64) {
            Section(
                name = "", nameOff = buf.getInt(off),
                type = buf.getInt(off + 4).toLong() and 0xFFFFFFFFL,
                flags = buf.getLong(off + 8),
                addr = buf.getLong(off + 16),
                offset = buf.getLong(off + 24),
                size = buf.getLong(off + 32),
                link = buf.getInt(off + 40),
                info = buf.getInt(off + 44),
                addralign = buf.getLong(off + 48),
                entsize = buf.getLong(off + 56)
            )
        } else {
            Section(
                name = "", nameOff = buf.getInt(off),
                type = buf.getInt(off + 4).toLong() and 0xFFFFFFFFL,
                flags = buf.getInt(off + 8).toLong() and 0xFFFFFFFFL,
                addr = buf.getInt(off + 12).toLong() and 0xFFFFFFFFL,
                offset = buf.getInt(off + 16).toLong() and 0xFFFFFFFFL,
                size = buf.getInt(off + 20).toLong() and 0xFFFFFFFFL,
                link = buf.getInt(off + 24),
                info = buf.getInt(off + 28),
                addralign = buf.getInt(off + 32).toLong() and 0xFFFFFFFFL,
                entsize = buf.getInt(off + 36).toLong() and 0xFFFFFFFFL
            )
        }
    }

    private fun readProgramHeader(buf: ByteBuffer, off: Int, is64: Boolean): ProgramHeader {
        return if (is64) {
            ProgramHeader(
                type = buf.getInt(off).toLong() and 0xFFFFFFFFL,
                flags = buf.getInt(off + 4).toLong() and 0xFFFFFFFFL,
                offset = buf.getLong(off + 8),
                vaddr = buf.getLong(off + 16),
                filesz = buf.getLong(off + 32),
                memsz = buf.getLong(off + 40),
                align = buf.getLong(off + 48)
            )
        } else {
            ProgramHeader(
                type = buf.getInt(off).toLong() and 0xFFFFFFFFL,
                offset = buf.getInt(off + 4).toLong() and 0xFFFFFFFFL,
                vaddr = buf.getInt(off + 8).toLong() and 0xFFFFFFFFL,
                filesz = buf.getInt(off + 16).toLong() and 0xFFFFFFFFL,
                memsz = buf.getInt(off + 20).toLong() and 0xFFFFFFFFL,
                flags = buf.getInt(off + 24).toLong() and 0xFFFFFFFFL,
                align = buf.getInt(off + 28).toLong() and 0xFFFFFFFFL
            )
        }
    }

    private fun readSymbol(buf: ByteBuffer, off: Int, is64: Boolean, strBytes: ByteArray): Symbol {
        val nameOff: Int
        val value: Long
        val size: Long
        val info: Int
        val other: Int
        val shndx: Int
        if (is64) {
            nameOff = buf.getInt(off)
            info = buf.get(off + 4).toInt() and 0xFF
            other = buf.get(off + 5).toInt() and 0xFF
            shndx = buf.getShort(off + 6).toInt() and 0xFFFF
            value = buf.getLong(off + 8)
            size = buf.getLong(off + 16)
        } else {
            nameOff = buf.getInt(off)
            value = buf.getInt(off + 4).toLong() and 0xFFFFFFFFL
            size = buf.getInt(off + 8).toLong() and 0xFFFFFFFFL
            info = buf.get(off + 12).toInt() and 0xFF
            other = buf.get(off + 13).toInt() and 0xFF
            shndx = buf.getShort(off + 14).toInt() and 0xFFFF
        }
        val bindN = info ushr 4
        val bind = when (bindN) {
            0 -> "LOCAL"
            1 -> "GLOBAL"
            2 -> "WEAK"
            else -> "?"
        }
        val nam = readCStr(strBytes, nameOff)
        return Symbol(
            name = nam,
            value = value,
            size = size,
            info = info,
            other = other,
            shndx = shndx,
            isFunc = (info and 0xF) == 2,
            isObject = (info and 0xF) == 1,
            isImport = shndx == 0 && nam.isNotBlank(),
            bind = bind
        )
    }

    private fun readCStr(bytes: ByteArray, off: Int): String {
        if (off < 0 || off >= bytes.size) return ""
        var end = off
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, off, end - off, Charsets.UTF_8)
    }

    private fun slice(bytes: ByteArray, offset: Long, size: Long): ByteArray {
        val o = offset.toInt()
        val s = size.toInt()
        if (o < 0 || o >= bytes.size) return ByteArray(0)
        val end = minOf(o + s, bytes.size)
        return bytes.copyOfRange(o, end)
    }

    /**
     * 带定位信息的字符串。
     * addr: 虚拟地址；fileOff: 文件内偏移；maxLen: 可用槽位长度（不含结尾 NUL）；
     * section: 所属节区名
     */
    data class LocString(
        val addr: Long,
        val fileOff: Int,
        val text: String,
        val maxLen: Int,
        val section: String
    )

    /**
     * 提取字符串并附带文件偏移，用于后续原地修改。
     * 只扫可写的非符号表节区（一般为 .rodata / .data 等）。
     */
    fun extractLocatableStrings(elf: Elf, minLen: Int = 4, limit: Int = 8000): List<LocString> {
        val out = ArrayList<LocString>()
        for (sec in elf.sections) {
            if (sec.type == 2L || sec.type == 11L || sec.type == 3L) continue // 跳过符号/字符串表
            if (sec.size <= 0 || sec.size > 16L * 1024 * 1024) continue
            val secOff = sec.offset.toInt()
            if (secOff < 0 || secOff >= elf.bytes.size) continue
            val secLen = sec.size.toInt().coerceAtMost(elf.bytes.size - secOff)
            if (secLen <= 0) continue

            var i = 0
            while (i < secLen) {
                val c = elf.bytes[secOff + i].toInt() and 0xFF
                if (c in 32..126) {
                    var j = i
                    while (j < secLen) {
                        val d = elf.bytes[secOff + j].toInt() and 0xFF
                        if (d in 32..126) j++ else break
                    }
                    val len = j - i
                    // 必须是 NUL 结尾（或到节区末尾），且长度达标
                    val nulTerminated = (j < secLen && elf.bytes[secOff + j].toInt() == 0) || j == secLen
                    if (len >= minLen && nulTerminated) {
                        val text = String(elf.bytes, secOff + i, len, Charsets.UTF_8)
                        out.add(
                            LocString(
                                addr = sec.addr + i,
                                fileOff = secOff + i,
                                text = text,
                                maxLen = len,
                                section = sec.name
                            )
                        )
                        if (out.size >= limit) return out
                    }
                    i = j + 1
                } else {
                    i++
                }
            }
        }
        return out
    }

    // ---------- 字符串提取 ----------

    /** 从指定节区提取可打印字符串 */
    fun extractStrings(elf: Elf, sectionName: String = ".rodata", minLen: Int = 4): List<Pair<Long, String>> {
        val sec = elf.section(sectionName) ?: return emptyList()
        val data = slice(elf.bytes, sec.offset, sec.size)
        val out = ArrayList<Pair<Long, String>>()
        val sb = StringBuilder()
        var start = 0
        for (i in data.indices) {
            val c = data[i].toInt() and 0xFF
            val printable = c in 32..126 || c == 9
            if (printable) {
                if (sb.isEmpty()) start = i
                sb.append(c.toChar())
            } else {
                if (sb.length >= minLen) out.add((sec.addr + start) to sb.toString())
                sb.setLength(0)
            }
            if (out.size > 4000) break
        }
        if (sb.length >= minLen) out.add((sec.addr + start) to sb.toString())
        return out
    }

    /** 所有节区里的字符串（排除符号表类） */
    fun extractAllStrings(elf: Elf, minLen: Int = 5): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        for (sec in elf.sections) {
            if (sec.type == 2L || sec.type == 11L || sec.type == 3L) continue // 跳过符号/字符串表
            if (sec.size <= 0 || sec.size > 8L * 1024 * 1024) continue
            val data = slice(elf.bytes, sec.offset, sec.size)
            val sb = StringBuilder()
            var start = 0
            for (i in data.indices) {
                val c = data[i].toInt() and 0xFF
                if (c in 32..126) {
                    if (sb.isEmpty()) start = i
                    sb.append(c.toChar())
                } else {
                    if (sb.length >= minLen) out.add((sec.addr + start) to sb.toString())
                    sb.setLength(0)
                }
                if (out.size > 6000) return out
            }
            if (sb.length >= minLen) out.add((sec.addr + start) to sb.toString())
        }
        return out
    }
}
