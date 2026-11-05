package io.github.jingrongx.screenmirror.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

data class UpdateUi(
    val checking: Boolean = false,
    val manifest: UpdateManifest? = null,
    val forced: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val downloadedApk: File? = null,
    val needPermission: Boolean = false,
    val error: String? = null,
    val upToDate: Boolean = false,
    val showDialog: Boolean = false,
)

/** 更新流程控制器（Compose 状态 + 协程），由 UI 层持有 */
class UpdateController(private val context: Context) {

    private val appContext = context.applicationContext
    private val checker = UpdateChecker(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var ui by mutableStateOf(UpdateUi())
        private set

    private var job: Job? = null

    /**
     * 检查更新。
     * 手动检查（showResult=true）展示「已是最新/失败」；自动检查只在发现新版本时提示。
     * 发现新版本后自动开始后台下载，完成后弹窗提示安装（可稍后）。
     */
    fun check(showResult: Boolean) {
        if (ui.checking || ui.downloading) return
        // 已有待安装包：直接提示安装，不再重新检查
        if (ui.downloadedApk != null) {
            ui = ui.copy(showDialog = true, error = null, upToDate = false)
            return
        }
        ui = ui.copy(checking = true, error = null, upToDate = false, showDialog = showResult)
        job?.cancel()
        job = scope.launch {
            try {
                val manifest = checker.fetchLatest()
                UpdatePrefs.markChecked(appContext)
                if (isNewerVersion(VERSION_CODE, VERSION_NAME, manifest)) {
                    ui = ui.copy(
                        checking = false,
                        manifest = manifest,
                        forced = VERSION_CODE in 1 until manifest.minVersionCode,
                        showDialog = true,
                    )
                    // 自动后台下载，无需用户点击
                    download()
                } else {
                    ui = if (showResult) {
                        ui.copy(checking = false, upToDate = true, showDialog = true)
                    } else {
                        ui.copy(checking = false)
                    }
                }
            } catch (e: Exception) {
                ui = if (showResult) {
                    ui.copy(checking = false, error = e.message ?: "未知错误", showDialog = true)
                } else {
                    ui.copy(checking = false)
                }
            }
        }
    }

    /** 后台下载（自动触发或用户手动重试），带进度，完成后提示安装 */
    fun download() {
        val manifest = ui.manifest ?: return
        if (ui.downloading) return
        ui = ui.copy(downloading = true, progress = 0f, error = null)
        job?.cancel()
        job = scope.launch {
            try {
                val apk = checker.downloadApk(manifest) { read, total ->
                    val totalOrRead = if (total > 0) total else read
                    val p = if (totalOrRead > 0) read.toFloat() / totalOrRead else 0f
                    if (((p * 100).toInt()) != ((ui.progress * 100).toInt())) {
                        ui = ui.copy(progress = p)
                    }
                }
                UpdatePrefs.savePendingInstall(appContext, manifest, apk.absolutePath)
                ui = ui.copy(downloading = false, progress = 1f, downloadedApk = apk, showDialog = true)
            } catch (e: Exception) {
                ui = ui.copy(downloading = false, error = e.message ?: "下载失败")
            }
        }
    }

    /** 安装；缺「安装未知应用」权限时提示用户去授权 */
    fun tryInstall() {
        val apk = ui.downloadedApk ?: return
        if (ApkInstaller.canInstall(appContext)) {
            ApkInstaller.install(appContext, apk)
            ui = ui.copy(needPermission = false)
        } else {
            ui = ui.copy(needPermission = true)
        }
    }

    /** 从授权页返回时（ON_RESUME）复查：已授权则直接拉起安装 */
    fun refreshPermissionState() {
        if (ui.needPermission && ui.downloadedApk != null && ApkInstaller.canInstall(appContext)) {
            ui = ui.copy(needPermission = false)
            ApkInstaller.install(appContext, ui.downloadedApk!!)
        }
    }

    fun requestInstallPermission() {
        ApkInstaller.requestInstallPermission(appContext)
    }

    /** 用户选「稍后」：关闭弹窗，保留已下载的更新包，下次启动/手动检查时再提示 */
    fun dismiss() {
        if (ui.forced) return
        ui = ui.copy(showDialog = false, checking = false, error = null, upToDate = false)
    }

    /** 应用启动时恢复「稍后安装」的待装包提示 */
    fun restorePendingInstall() {
        if (ui.downloadedApk != null || ui.downloading || ui.manifest != null) return
        val path = UpdatePrefs.pendingInstallPath(appContext) ?: return
        val code = UpdatePrefs.pendingInstallVersionCode(appContext)
        if (code <= VERSION_CODE) {
            UpdatePrefs.clearPendingInstall(appContext)
            return
        }
        val file = File(path)
        if (!file.exists()) {
            UpdatePrefs.clearPendingInstall(appContext)
            return
        }
        ui = ui.copy(
            downloadedApk = file,
            manifest = UpdateManifest(
                versionCode = code,
                versionName = UpdatePrefs.pendingInstallVersionName(appContext),
                url = UpdatePrefs.pendingInstallUrl(appContext),
            ),
            showDialog = true,
        )
    }

    companion object {
        val VERSION_CODE: Int = io.github.jingrongx.screenmirror.BuildConfig.VERSION_CODE
        val VERSION_NAME: String = io.github.jingrongx.screenmirror.BuildConfig.VERSION_NAME
        const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

        fun shouldAutoCheck(context: Context): Boolean =
            System.currentTimeMillis() - UpdatePrefs.lastCheckMillis(context) >= AUTO_CHECK_INTERVAL_MS
    }
}
