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
class UpdateController(context: Context) {

    private val appContext = context.applicationContext
    private val checker = UpdateChecker(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var ui by mutableStateOf(UpdateUi())
        private set

    private var job: Job? = null

    /** 手动检查（showResult=true 时展示「已是最新/失败」），自动检查只在发现新版本时弹窗 */
    fun check(showResult: Boolean) {
        if (ui.checking || ui.downloading) return
        ui = ui.copy(checking = true, error = null, upToDate = false, showDialog = showResult)
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

    fun download() {
        val manifest = ui.manifest ?: return
        if (ui.downloading) return
        ui = ui.copy(downloading = true, progress = 0f, error = null)
        job = scope.launch {
            try {
                val apk = checker.downloadApk(manifest) { read, total ->
                    val totalOrRead = if (total > 0) total else read
                    val p = if (totalOrRead > 0) read.toFloat() / totalOrRead else 0f
                    if (((p * 100).toInt()) != ((ui.progress * 100).toInt())) {
                        ui = ui.copy(progress = p)
                    }
                }
                ui = ui.copy(downloading = false, progress = 1f, downloadedApk = apk)
                tryInstall()
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

    fun requestInstallPermission() {
        ApkInstaller.requestInstallPermission(appContext)
    }

    fun dismiss() {
        if (ui.forced) return
        ui = UpdateUi()
    }

    companion object {
        val VERSION_CODE: Int = io.github.jingrongx.screenmirror.BuildConfig.VERSION_CODE
        val VERSION_NAME: String = io.github.jingrongx.screenmirror.BuildConfig.VERSION_NAME
        const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

        fun shouldAutoCheck(context: Context): Boolean =
            System.currentTimeMillis() - UpdatePrefs.lastCheckMillis(context) >= AUTO_CHECK_INTERVAL_MS
    }
}
