package io.github.jingrongx.screenmirror.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jingrongx.screenmirror.update.UpdateController

/** 更新对话框：检查中 / 已最新 / 新版本（含下载进度）/ 失败重试 / 强制更新 */
@Composable
fun UpdateDialog(controller: UpdateController) {
    val ui = controller.ui
    if (!ui.showDialog) return

    AlertDialog(
        onDismissRequest = { controller.dismiss() },
        title = {
            Text(
                when {
                    ui.manifest != null -> "发现新版本 v${ui.manifest?.versionName}"
                    ui.error != null -> "更新失败"
                    ui.upToDate -> "已是最新版本"
                    else -> "检查更新"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val manifest = ui.manifest
                when {
                    manifest != null -> {
                        manifest.changelog?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (ui.downloading) {
                            LinearProgressIndicator(
                                progress = { ui.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("正在下载 ${(ui.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        } else if (ui.downloadedApk != null) {
                            if (ui.needPermission) {
                                Text("需要先授予「安装未知应用」权限，授权后点击安装。")
                            } else {
                                Text("下载完成，可以安装。")
                            }
                        }
                    }

                    ui.error != null -> Text(ui.error ?: "")

                    ui.upToDate -> Text("当前已是最新版本 v${UpdateController.VERSION_NAME}。")

                    ui.checking -> Text("正在检查更新…")
                }
            }
        },
        confirmButton = {
            when {
                ui.manifest != null && ui.downloadedApk != null -> {
                    TextButton(onClick = {
                        if (ui.needPermission) controller.requestInstallPermission() else controller.tryInstall()
                    }) {
                        Text(if (ui.needPermission) "去授权" else "安装")
                    }
                }

                ui.manifest != null && !ui.downloading -> {
                    TextButton(onClick = { controller.download() }) {
                        Text(if (ui.forced) "立即更新" else "立即更新")
                    }
                }

                ui.error != null -> {
                    TextButton(onClick = { controller.check(showResult = true) }) {
                        Text("重试")
                    }
                }

                ui.upToDate || ui.checking -> {
                    TextButton(onClick = { controller.dismiss() }) { Text("好的") }
                }
            }
        },
        dismissButton = {
            if (!ui.forced && !(ui.downloading)) {
                TextButton(onClick = { controller.dismiss() }) { Text(if (ui.manifest != null) "稍后" else "取消") }
            }
        },
    )
}
