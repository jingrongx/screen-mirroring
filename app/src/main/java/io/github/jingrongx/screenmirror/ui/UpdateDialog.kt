package io.github.jingrongx.screenmirror.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.jingrongx.screenmirror.update.UpdateConfig
import io.github.jingrongx.screenmirror.update.UpdateController

/** 更新对话框：
 * 自动检测 → 自动后台下载（可稍后）→ 下载完成提示安装（安装/稍后）
 * 手动检查同样流程；下载失败可重试或用浏览器下载（国内代理直链）。
 */
@Composable
fun UpdateDialog(controller: UpdateController) {
    val ui = controller.ui
    if (!ui.showDialog) return
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { controller.dismiss() },
        title = {
            Text(
                when {
                    ui.downloadedApk != null -> "更新已就绪 v${ui.manifest?.versionName}"
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
                            Text(
                                "正在后台下载 ${(ui.progress * 100).toInt()}%，可稍后继续",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else if (ui.downloadedApk != null) {
                            if (ui.needPermission) {
                                Text("需要先授予「安装未知应用」权限，授权后回来点「安装」。")
                            } else {
                                Text("更新包已下载完成，可以安装；也可以选「稍后」，之后会再次提醒。")
                            }
                        } else if (ui.error != null) {
                            Text("下载失败：${ui.error}", style = MaterialTheme.typography.bodySmall)
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
                ui.downloadedApk != null -> {
                    TextButton(onClick = {
                        if (ui.needPermission) controller.requestInstallPermission() else controller.tryInstall()
                    }) {
                        Text(if (ui.needPermission) "去授权" else "安装")
                    }
                }

                ui.manifest != null && ui.downloading -> {
                    // 后台下载中：无需确认按钮
                }

                ui.manifest != null -> {
                    Row {
                        TextButton(onClick = { controller.download() }) { Text("重试") }
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(UpdateConfig.PROXY_PREFIXES.first() + ui.manifest?.url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) { Text("浏览器下载") }
                    }
                }

                ui.error != null -> {
                    TextButton(onClick = { controller.check(showResult = true) }) {
                        Text("重试")
                    }
                }

                else -> {
                    TextButton(onClick = { controller.dismiss() }) { Text("好的") }
                }
            }
        },
        dismissButton = {
            if (!ui.forced) {
                TextButton(onClick = { controller.dismiss() }) {
                    Text(if (ui.manifest != null) "稍后" else "取消")
                }
            }
        },
    )
}
