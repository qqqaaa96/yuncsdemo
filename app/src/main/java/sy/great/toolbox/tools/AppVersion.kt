package sy.great.toolbox.tools

import android.content.Context
import android.content.pm.PackageManager

/** 当前安装包的版本信息 */
object AppVersion {

    /** versionCode；取不到返回 0 */
    fun code(ctx: Context): Int {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            @Suppress("DEPRECATION")
            pi.versionCode
        } catch (_: Throwable) {
            0
        }
    }

    /** versionName；取不到返回空串 */
    fun name(ctx: Context): String {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            pi.versionName ?: ""
        } catch (_: Throwable) {
            ""
        }
    }
}
