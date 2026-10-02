package com.csdemo.tools

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 已安装应用扫描，包含签名指纹与权限。
 */
object Apps {

    data class AppItem(
        val label: String,
        val pkg: String,
        val versionName: String,
        val versionCode: Long,
        val isSystem: Boolean,
        val targetSdk: Int,
        val firstInstall: String,
        val lastUpdate: String,
        val apkPath: String,
        val sizeBytes: Long,
        val signerMd5: String,
        val signerSha256: String,
        val permissions: List<String>,
        val activities: Int,
        val services: Int,
        val receivers: Int,
        val providers: Int
    )

    fun scan(ctx: Context): List<AppItem> {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_PERMISSIONS or
                PackageManager.GET_SIGNATURES or
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS

        val list: List<PackageInfo> = pm.getInstalledPackages(flags)
        val out = ArrayList<AppItem>()

        for (p in list) {
            val appInfo = p.applicationInfo ?: continue
            val label = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                p.packageName
            }
            val sys = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val sig = signaturesOf(p)
            val vCode: Long = if (Build.VERSION.SDK_INT >= 28) {
                p.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                val vc = p.versionCode
                vc.toLong()
            }
            out += AppItem(
                label = label,
                pkg = p.packageName,
                versionName = p.versionName ?: "-",
                versionCode = vCode,
                isSystem = sys,
                targetSdk = appInfo.targetSdkVersion,
                firstInstall = time(p.firstInstallTime),
                lastUpdate = time(p.lastUpdateTime),
                apkPath = appInfo.sourceDir,
                sizeBytes = java.io.File(appInfo.sourceDir).length(),
                signerMd5 = sig.first,
                signerSha256 = sig.second,
                permissions = (p.requestedPermissions ?: emptyArray()).toList().sorted(),
                activities = if (p.activities != null) p.activities!!.size else 0,
                services = if (p.services != null) p.services!!.size else 0,
                receivers = if (p.receivers != null) p.receivers!!.size else 0,
                providers = if (p.providers != null) p.providers!!.size else 0
            )
        }
        return out.sortedBy { it.label.lowercase(Locale.US) }
    }

    @Suppress("DEPRECATION")
    private fun signaturesOf(p: PackageInfo): Pair<String, String> {
        return try {
            val sigs = p.signatures
            if (sigs == null || sigs.isEmpty()) return "-" to "-"
            val md5 = digest("MD5", sigs[0].toByteArray())
            val sha = digest("SHA-256", sigs[0].toByteArray())
            md5 to sha
        } catch (e: Exception) {
            "-" to "-"
        }
    }

    private fun digest(algo: String, data: ByteArray): String =
        MessageDigest.getInstance(algo).digest(data).joinToString(":") { "%02X".format(it) }

    private fun time(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    fun permissionLabel(p: String): String = p.substringAfterLast('.')
}
