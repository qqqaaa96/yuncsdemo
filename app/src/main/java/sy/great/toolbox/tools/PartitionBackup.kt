package sy.great.toolbox.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * 分区（字库 / 基带）全量备份。
 *
 * 设计要点：
 *
 * 1. 分区名每台手机不同
 *    不能写死路径。按优先级依次尝试四种 by-name 位置，
 *    最后一个兑底方案直接读 /proc/partitions 或 find。
 *
 * 2. 需要 root 或 Shizuku
 *    /dev/block/by-name 目录能列出来，但读内容需要提升权限。
 *    root 走 su，Shizuku 走 AdbShell。
 *
 * 3. 直接 dd 到外部存储
 *    Android 11+ 普通 App 写不了 /storage/emulated/0 以外的目录，
 *    但字库基带备份目录是用户可见的，用 MediaStore 或直接方式。
 *    这里用 root 直接写，最稳。
 *
 * 4. 可中断
 *    大分区（如 super 有 8GB）不能一次读入内存。
 *    用 dd 命令分批，每次备份一个分区，
 *    通过检查协程取消状态中断。
 */
object PartitionBackup {

    /** 备份根目录 */
    const val OUT_DIR = "/storage/emulated/0/字库基带备份"

    /** 候选的 by-name 目录，按优先级排序 */
    private val BY_NAME_DIRS = listOf(
        "/dev/block/by-name",
        "/dev/block/bootdevice/by-name",
        "/dev/block/platform/soc/1d84000.ufshc/by-name",
        "/dev/block/platform/soc/1d84000.ufs/by-name",
        "/dev/block/platform/soc/7824900.sdhci/by-name",
        "/dev/block/platform/msm_sdcc.1/by-name",
        "/dev/block/platform/mtk-msdc.0/by-name",
    )

    /**
     * 一个分区。
     *
     * @param name 分区名（如 modem / modemst1 / persist）
     * @param path 实际设备路径
     * @param sizeBytes 大小；-1 表示未知
     * @param tag 分类标签（基带 / 字库 / 其他）
     */
    data class Partition(
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val tag: String,
    ) {
        val sizeText: String
            get() = when {
                sizeBytes < 0 -> "未知"
                sizeBytes >= 1073741824L -> String.format("%.1f GB", sizeBytes / 1073741824.0)
                sizeBytes >= 1048576L -> String.format("%.1f MB", sizeBytes / 1048576.0)
                sizeBytes >= 1024L -> String.format("%d KB", sizeBytes / 1024)
                else -> "$sizeBytes B"
            }
    }

    /** 备份结果 */
    data class ItemResult(
        val partition: Partition,
        val ok: Boolean,
        val message: String = "",
        val outFile: String = "",
        val bytes: Long = 0,
    )

    // ---------------- 分类 ----------------

    /**
     * 按名字判断分区类型。
     *
     * 基带类：modem / modemst1-2 / fsg / fsc / mdm / mbn
     * 字库类：persist / efs / nvdata / nvram / nvitem
     * 其他：boot / dtbo / vbmeta 等
     */
    fun classify(name: String): String {
        val n = name.lowercase()
        return when {
            n.startsWith("modem") || n == "fsg" || n == "fsc" ||
                n.startsWith("mdm") || n.startsWith("mbn") ||
                n.startsWith("mba") || n.contains("baseband") -> "基带"

            n.startsWith("persist") || n.startsWith("efs") ||
                n.startsWith("nvdata") || n.startsWith("nvram") ||
                n.startsWith("nvitem") || n == "param" ||
                n.startsWith("sec") || n.startsWith("proinfo") -> "字库"

            n.startsWith("boot") || n.startsWith("init_boot") ||
                n.startsWith("vendor_boot") || n.startsWith("dtbo") ||
                n.startsWith("vbmeta") || n.startsWith("recovery") ||
                n.startsWith("super") || n.startsWith("system") -> "系统"

            else -> "其他"
        }
    }

    // ---------------- 命令执行 ----------------

    /**
     * 执行一条 shell 命令（root 或 Shizuku）。
     *
     * 为什么不拼成 `su -c "..."`：
     *   分区路径没有空格，但输出目录含中文。
     *   用 `su -c 'cmd'` 时如果 cmd 内含单引号会语法错。
     *   这里统一用双引号包裹并把内部双引号转义。
     */
    private suspend fun sh(ctx: Context, cmd: String, timeoutSec: Int = 60): Shell.Result {
        return withContext(Dispatchers.IO) {
            if (RootState.useRoot()) {
                val r = Shell.runSu("-c", cmd, timeoutMs = timeoutSec * 1000L)
                r.result ?: Shell.Result(-1, "", if (r.timedOut) "su 超时" else "su 未执行")
            } else if (AdbShell.granted()) {
                AdbShell.exec(ctx.applicationContext, cmd)
                    ?: Shell.Result(-1, "", "Shizuku 执行失败")
            } else {
                Shell.Result(-1, "", "需要 Root 或 Shizuku 授权")
            }
        }
    }

    // ---------------- 扫描分区 ----------------

    /**
     * 扫描设备上的所有分区。
     *
     * 返回列表按标签排序：基带 → 字库 → 系统 → 其他。
     */
    suspend fun scan(ctx: Context): List<Partition> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, Partition>()

        // ---- 1. 尝试各个 by-name 目录 ----
        for (dir in BY_NAME_DIRS) {
            ensureActive()

            val r = sh(ctx, "ls -l \"$dir\" 2>/dev/null", 15)
            if (!r.ok || r.out.isBlank()) continue

            for (line in r.out.lines()) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("total")) continue

                // ls -l 输出：lrwxrwxrwx 1 root root 16 2024-01-01 00:00 modem -> /dev/block/sdx
                val arrow = t.lastIndexOf(" -> ")
                if (arrow < 0) continue

                val left = t.substring(0, arrow).trim()
                val target = t.substring(arrow + 4).trim()
                val parts = left.split(Regex("\\s+"))
                if (parts.size < 2) continue

                val name = parts.last()
                if (name.isEmpty()) continue
                if (found.containsKey(name)) continue

                // 归一化路径（有些是相对链接）
                val real = if (target.startsWith("/")) target else "$dir/$target"

                found[name] = Partition(
                    name = name,
                    path = real,
                    sizeBytes = -1L,
                    tag = classify(name),
                )
            }

            if (found.isNotEmpty()) break
        }

        // ---- 2. 兑底：读 /proc/partitions ----
        if (found.isEmpty()) {
            val r = sh(ctx, "cat /proc/partitions", 10)
            if (r.ok) {
                for (line in r.out.lines()) {
                    val p = line.trim().split(Regex("\\s+"))
                    if (p.size < 4) continue
                    val nm = p[3]
                    if (!nm.matches(Regex("^[a-z0-9_]+$"))) continue
                    if (found.containsKey(nm)) continue

                    val kb = p[2].toLongOrNull() ?: -1L
                    found[nm] = Partition(
                        name = nm,
                        path = "/dev/block/$nm",
                        sizeBytes = if (kb >= 0) kb * 1024 else -1L,
                        tag = classify(nm),
                    )
                }
            }
        }

        // ---- 3. 补全分区大小 ----
        val result = ArrayList<Partition>(found.size)
        for (p in found.values) {
            ensureActive()
            val size = if (p.sizeBytes >= 0) p.sizeBytes else blockSize(ctx, p.path)
            result.add(p.copy(sizeBytes = size))
        }

        // 排序：基带 → 字库 → 系统 → 其他，同类按名字
        val order = mapOf("基带" to 0, "字库" to 1, "系统" to 2, "其他" to 3)
        result.sortWith(compareBy({ order[it.tag] ?: 9 }, { it.name }))
        result
    }

    /** 读块设备字节数。失败返回 -1。 */
    private suspend fun blockSize(ctx: Context, path: String): Long {
        // 方法 1：blockdev --getsize64（最准）
        val r = sh(ctx, "blockdev --getsize64 \"$path\" 2>/dev/null", 10)
        if (r.ok) {
            r.out.trim().toLongOrNull()?.let { if (it > 0) return it }
        }

        // 方法 2：/sys/class/block/<name>/size（单位 512 字节扇区）
        val nm = path.substringAfterLast('/')
        val r2 = sh(ctx, "cat /sys/class/block/$nm/size 2>/dev/null", 10)
        if (r2.ok) {
            r2.out.trim().toLongOrNull()?.let { if (it > 0) return it * 512 }
        }

        return -1L
    }

    // ---------------- 备份 ----------------

    /**
     * 备份单个分区。
     *
     * 用 dd 直接写文件，不经过 App 内存：
     *   数据量可能几 GB，用 shell 重定向最省资源。
     */
    suspend fun backupOne(
        ctx: Context,
        p: Partition,
        outDir: String = OUT_DIR,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): ItemResult = withContext(Dispatchers.IO) {
        try {
            // 建目录
            var r = sh(ctx, "mkdir -p \"$outDir\"", 15)
            if (!r.ok) {
                return@withContext ItemResult(p, false, "创建目录失败：${r.text()}")
            }

            val fileName = "${p.tag}_${p.name}.img"
            val outPath = "$outDir/$fileName"

            // 先删旧文件（避免 dd 不覆盖导致新旧混杂）
            sh(ctx, "rm -f \"$outPath\"", 10)

            // dd 备份
            // bs=4M 兼顾速度与内存；conv=fsync 确保落盘
            val cmd = "dd if=\"${p.path}\" of=\"$outPath\" bs=4M conv=fsync 2>&1"
            r = sh(ctx, cmd, timeoutSec = 600)

            if (!r.ok) {
                // 失败时清掉半成品
                sh(ctx, "rm -f \"$outPath\"", 10)
                return@withContext ItemResult(p, false, "dd 失败：${r.text().take(200)}")
            }

            // 校验：文件必须存在且大小与分区一致
            val sizeR = sh(ctx, "stat -c %s \"$outPath\" 2>/dev/null", 10)
            val written = sizeR.out.trim().toLongOrNull() ?: 0L

            if (written <= 0) {
                sh(ctx, "rm -f \"$outPath\"", 10)
                return@withContext ItemResult(p, false, "备份文件为空")
            }

            if (p.sizeBytes > 0 && written != p.sizeBytes) {
                // 大小不符：可能分区正在变化，删掉避免误用
                sh(ctx, "rm -f \"$outPath\"", 10)
                return@withContext ItemResult(
                    p, false,
                    "大小不符（期望 ${p.sizeBytes}，实际 $written）"
                )
            }

            onProgress?.invoke(written, p.sizeBytes)
            ItemResult(p, true, "", outPath, written)
        } catch (e: Throwable) {
            ItemResult(p, false, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 批量备份。
     *
     * @param onlyTags 只备份这些标签；null = 全部
     * @param onItem   每个分区完成时回调
     */
    suspend fun backupAll(
        ctx: Context,
        partitions: List<Partition>,
        onlyTags: Set<String>? = null,
        onItem: ((index: Int, total: Int, result: ItemResult) -> Unit)? = null,
    ): List<ItemResult> = withContext(Dispatchers.IO) {
        val list = if (onlyTags == null) partitions
        else partitions.filter { it.tag in onlyTags }

        val results = ArrayList<ItemResult>(list.size)
        list.forEachIndexed { i, p ->
            ensureActive()
            val r = backupOne(ctx, p)
            results.add(r)
            onItem?.invoke(i, list.size, r)
        }
        results
    }

    /**
     * 生成备份清单（manifest.txt）。
     */
    suspend fun writeManifest(ctx: Context, results: List<ItemResult>): Boolean {
        return try {
            val sb = StringBuilder()
            sb.append("# 字库基带备份清单\n")
            sb.append("# 生成时间：").append(java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
            ).format(java.util.Date())).append('\n')
            sb.append("# 设备：").append(android.os.Build.MANUFACTURER)
                .append(' ').append(android.os.Build.MODEL).append('\n')
            sb.append("# 指纹：").append(android.os.Build.FINGERPRINT).append('\n')
            sb.append('#\n')
            sb.append("# 标签\t分区\t大小\t结果\t文件\n")

            for (r in results) {
                sb.append(r.partition.tag).append('\t')
                    .append(r.partition.name).append('\t')
                    .append(r.bytes).append('\t')
                    .append(if (r.ok) "OK" else "FAIL").append('\t')
                    .append(r.outFile).append('\n')
                if (!r.ok) sb.append('#   失败原因：').append(r.message).append('\n')
            }

            val tmp = File(ctx.cacheDir, "manifest.txt")
            tmp.writeText(sb.toString(), Charsets.UTF_8)

            // 用 root 拷到目标目录
            val r = sh(ctx, "cp \"${tmp.absolutePath}\" \"$OUT_DIR/manifest.txt\"", 15)
            tmp.delete()
            r.ok
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 估算备份总大小（只算存在的分区）。
     */
    fun totalSize(partitions: List<Partition>, onlyTags: Set<String>? = null): Long {
        val list = if (onlyTags == null) partitions else partitions.filter { it.tag in onlyTags }
        return list.filter { it.sizeBytes > 0 }.sumOf { it.sizeBytes }
    }
}
