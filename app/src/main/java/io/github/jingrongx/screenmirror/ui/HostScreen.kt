package io.github.jingrongx.screenmirror.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.jingrongx.screenmirror.host.GestureAccessibilityService
import io.github.jingrongx.screenmirror.host.HostSession
import io.github.jingrongx.screenmirror.host.MirrorHostService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by HostSession.state.collectAsStateWithLifecycle()
    var audioEnabled by rememberSaveable { mutableStateOf(true) }

    val projectionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                MirrorHostService.start(context, result.resultCode, data, audioEnabled)
            }
        }

    fun launchProjection() {
        val pm =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(pm.createScreenCaptureIntent())
    }

    val notifLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // 通知权限被拒也不影响投屏，只是没有前台通知
            launchProjection()
        }

    // 音频捕获需要 RECORD_AUDIO（危险权限，须运行时请求）；被拒则音频自动降级为仅画面
    val audioPermLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else launchProjection()
        }

    fun startMirror() {
        val wantAudio = audioEnabled && Build.VERSION.SDK_INT >= 29
        val hasAudioPerm = androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (wantAudio && !hasAudioPerm) {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchProjection()
        }
    }

    // 从系统设置返回时（ON_RESUME）刷新无障碍服务状态，避免"已开启但页面检测不到"
    var accessibilityRefreshKey by rememberSaveable { mutableStateOf(0) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) accessibilityRefreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val touchReady = remember(accessibilityRefreshKey) {
        GestureAccessibilityService.instance != null && Build.VERSION.SDK_INT >= 24
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("受控端") },
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
            when (val s = state) {
                is HostSession.UiState.Idle -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("共享设置", style = MaterialTheme.typography.titleMedium)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column {
                                    Text("同时共享声音")
                                    Text(
                                        if (Build.VERSION.SDK_INT >= 29) "捕获系统播放的媒体声音"
                                        else "需要 Android 10 及以上",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = audioEnabled,
                                    onCheckedChange = { audioEnabled = it },
                                    enabled = Build.VERSION.SDK_INT >= 29,
                                )
                            }
                            Button(onClick = { startMirror() }, modifier = Modifier.fillMaxWidth()) {
                                Text("开始共享屏幕")
                            }
                        }
                    }
                }

                is HostSession.UiState.Waiting -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("等待控制端连接…", style = MaterialTheme.typography.titleMedium)
                            Text("设备名称：${s.serviceName}")
                            Text("地址：${s.ip ?: "未知"}:${s.port}")
                            Text(
                                "请让控制端设备连接同一 Wi-Fi 后，在「控制端」页面选择本机。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (s.audioEnabled) {
                                Text("声音共享：已开启", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Button(
                        onClick = { MirrorHostService.stop(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("停止共享") }
                }

                is HostSession.UiState.Streaming -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("正在被 ${s.clientName} 控制", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (s.touchReady) "对方可以进行触摸操作与全局按键"
                                else "未开启无障碍服务，对方只能观看画面",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Button(
                        onClick = { MirrorHostService.stop(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("停止共享") }
                }

                is HostSession.UiState.Failed -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("出错了", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                            Text(s.message)
                        }
                    }
                    Button(
                        onClick = { MirrorHostService.stop(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("返回") }
                }
            }

            // 无障碍服务开关（触摸控制依赖）
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("远程触摸控制", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (touchReady) "已就绪" else "未开启",
                            color = if (touchReady) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "需要在系统设置中开启「屏幕镜像 - 远程控制服务」无障碍权限，对方才能远程点击、滑动本机。开启后请回到本页面重新连接。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!touchReady) {
                        Button(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("去开启无障碍服务") }
                    }
                    if (Build.VERSION.SDK_INT < 24) {
                        Text(
                            "当前系统低于 Android 7.0，不支持模拟触摸手势，只能使用返回/主页等全局按键。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
