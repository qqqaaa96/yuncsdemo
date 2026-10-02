package com.csdemo.tools

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * 定位并读取设备上的 .so 文件。
 *
 * 三个来源：
 *   1) 已安装应用的 APK 内的 lib 目录中的 so 文件
 *   2) 系统目录（/system/lib64 等，需 root 读部分文件）
 *   3) 用户指定路径
 */
object ElfLoader {

    data class SoEntry(
        val displayName: String,      // 显示名
        val apkPath: String?,         // 若来自 APK
        val zipEntry: String?,        // APK 内路径
        val fsPath: String?,          // 文件系统路径
        val size: Long,
        val abi: String
    )

    /** 从某个 APK 里列出所有 .so */
    fun listInApk(apkPath: String): List<SoEntry> {
        val out = ArrayList<SoEntry>()
        try {
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val n = e.name
                    if (n.startsWith("lib/") && n.endsWith(".so")) {
                        val abi = n.removePrefix("lib/").substringBefore('/')
                        out.add(
                            SoEntry(
                                displayName = n.substringAfterLast('/'),
                                apkPath = apkPath,
                                zipEntry = n,
                                fsPath = null,
                                size = e.size,
                                abi = abi
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // 读不了就忽略
        }
        return out.sortedWith(compareBy({ it.abi }, { it.displayName }))
    }

    /** 从 APK 读出一个 so 的字节 */
    fun readFromApk(apkPath: String, zipEntry: String): ByteArray? {
        return try {
            ZipFile(apkPath).use { zip ->
                val e = zip.getEntry(zipEntry) ?: return null
                zip.getInputStream(e).use { it.readBytes() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 直接读文件系统路径 */
    fun readFile(path: String): ByteArray? {
        return try {
            val f = File(path)
            if (f.exists() && f.canRead()) f.readBytes() else null
        } catch (e: Exception) {
            null
        }
    }

    /** 用 root 读文件（应对 /system 下不可读的文件） */
    fun readFileRoot(path: String): ByteArray? {
        val r = Shell.runSu("-c", "cat " + path, timeoutMs = 20000)
        val out = r.result?.out ?: return null
        if (out.isBlank()) return null
        // 注意：su cat 输出经过文本化，仅适用于小文件且字节无损的场景
        // 对二进制文件不可靠，所以这里只作为兵底，最终仍以直接读为主
        return null
    }

    /** 扫描常见系统目录里的 so */
    fun listSystemLibs(max: Int = 300): List<SoEntry> {
        val out = ArrayList<SoEntry>()
        val dirs = listOf(
            "/system/lib64", "/system/lib",
            "/vendor/lib64", "/vendor/lib",
            "/system_ext/lib64", "/product/lib64",
            "/apex/com.android.runtime/lib64"
        )
        for (d in dirs) {
            val dir = File(d)
            val children = if (dir.exists() && dir.canRead()) dir.listFiles() else null
            if (children == null) continue
            for (f in children) {
                if (f.isFile && f.name.endsWith(".so") && f.canRead()) {
                    out.add(
                        SoEntry(
                            displayName = f.name,
                            apkPath = null,
                            zipEntry = null,
                            fsPath = f.absolutePath,
                            size = f.length(),
                            abi = if (d.contains("64")) "arm64-v8a" else "armeabi-v7a"
                        )
                    )
                }
                if (out.size >= max) return out.sortedBy { it.displayName }
            }
        }
        return out.sortedBy { it.displayName }
    }
}
