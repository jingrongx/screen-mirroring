package io.github.jingrongx.screenmirror.update

import android.content.Context
import io.github.jingrongx.screenmirror.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 更新源指向的 GitHub 仓库与国内加速代理配置 */
object UpdateConfig {
    val repoOwner: String get() = BuildConfig.REPO_OWNER
    val repoName: String get() = BuildConfig.REPO_NAME
    val branch: String get() = BuildConfig.REPO_BRANCH

    /**
     * 国内优先的加速代理（前缀拼接式），依次尝试；空字符串为直连兜底。
     * 更新清单放在仓库 raw 文件（raw + releases 均可被这些代理加速）。
     */
    val PROXY_PREFIXES = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://mirror.ghproxy.com/",
        "",
    )

    val rawManifestUrl: String
        get() = "https://raw.githubusercontent.com/$repoOwner/$repoName/$branch/update/latest.json"

    val apiLatestUrl: String
        get() = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"

    val repoUrl: String
        get() = "https://github.com/$repoOwner/$repoName"
}

/** update/latest.json（由 GitHub Actions 自动维护） */
@Serializable
data class UpdateManifest(
    @SerialName("versionCode") val versionCode: Int = 0,
    @SerialName("versionName") val versionName: String = "",
    @SerialName("minVersionCode") val minVersionCode: Int = 0,
    @SerialName("url") val url: String = "",
    @SerialName("sha256") val sha256: String? = null,
    @SerialName("changelog") val changelog: String? = null,
)

object UpdatePrefs {
    private const val FILE = "update_prefs"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_PENDING_PATH = "pending_path"
    private const val KEY_PENDING_CODE = "pending_code"
    private const val KEY_PENDING_NAME = "pending_name"
    private const val KEY_PENDING_URL = "pending_url"

    fun lastCheckMillis(context: Context): Long =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L)

    fun markChecked(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    /** 已下载待安装的更新包（跨进程持久化，"稍后安装"后仍可恢复提示） */
    fun savePendingInstall(context: Context, manifest: UpdateManifest, apkPath: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING_PATH, apkPath)
            .putInt(KEY_PENDING_CODE, manifest.versionCode)
            .putString(KEY_PENDING_NAME, manifest.versionName)
            .putString(KEY_PENDING_URL, manifest.url)
            .apply()
    }

    fun pendingInstallPath(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PENDING_PATH, null)

    fun pendingInstallVersionCode(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_PENDING_CODE, 0)

    fun pendingInstallVersionName(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_NAME, "") ?: ""

    fun pendingInstallUrl(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_URL, "") ?: ""

    fun clearPendingInstall(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING_PATH).remove(KEY_PENDING_CODE)
            .remove(KEY_PENDING_NAME).remove(KEY_PENDING_URL)
            .apply()
    }
}

/** 版本比较：优先 versionCode，缺失时按语义化版本比较 */
fun isNewerVersion(currentCode: Int, currentName: String, manifest: UpdateManifest): Boolean {
    if (manifest.versionCode > 0 && currentCode > 0) return manifest.versionCode > currentCode
    return compareSemver(manifest.versionName, currentName) > 0
}

fun compareSemver(a: String, b: String): Int {
    fun parts(v: String) = v.split('.').map { seg ->
        seg.filter { it.isDigit() }.toIntOrNull() ?: 0
    }
    val pa = parts(a)
    val pb = parts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return x - y
    }
    return 0
}
