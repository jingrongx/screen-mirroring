package io.github.jingrongx.screenmirror.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.jingrongx.screenmirror.update.UpdateConfig
import io.github.jingrongx.screenmirror.update.UpdateController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    updateController: UpdateController,
) {
    val context = LocalContext.current
    val ui = updateController.ui

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("版本与更新", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "当前版本 v${UpdateController.VERSION_NAME}（versionCode ${UpdateController.VERSION_CODE}）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { updateController.check(showResult = true) },
                        enabled = !ui.checking && !ui.downloading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (ui.checking) "正在检查…" else "检查更新")
                    }
                    Text(
                        "更新源：GitHub Releases（国内自动走加速代理）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("项目主页", style = MaterialTheme.typography.titleMedium)
                    Text(
                        UpdateConfig.repoUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateConfig.repoUrl)))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("在浏览器打开") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已知限制", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "· 远程触摸需要受控端 Android 7.0+（无障碍手势 API）\n" +
                            "· 声音共享需要受控端 Android 10+（系统音频捕获 API）\n" +
                            "· 视频码率自适应，弱 Wi-Fi 下画质会自动下降\n" +
                            "· 本项目为局域网直连方案，不经过公网服务器",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
