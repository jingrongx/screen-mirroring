package io.github.jingrongx.screenmirror.ui

import android.app.Activity
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jingrongx.screenmirror.client.ClientViewModel
import io.github.jingrongx.screenmirror.client.ControllerSession
import io.github.jingrongx.screenmirror.webrtc.EglProvider
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import kotlin.math.min

/** 远程画面：
 * 非全屏 = 画面区（上）+ 画面外控制条（下，不遮挡画面）；左上返回。
 * 全屏 = 隐藏系统栏与控制条，仅左上返回、右上退出全屏、左下延迟角标。
 */
@Composable
fun RemoteScreen(vm: ClientViewModel) {
    val context = LocalContext.current
    val activity = context as? Activity

    var fullscreen by remember { mutableStateOf(false) }
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    val connectedForAudio = vm.sessionState as? ControllerSession.UiState.Connected
    var audioOn by remember(connectedForAudio?.deviceName) {
        mutableStateOf(connectedForAudio?.audioEnabled ?: true)
    }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            applyImmersive(activity, false)
        }
    }

    DisposableEffect(fullscreen) {
        applyImmersive(activity, fullscreen)
        onDispose { }
    }

    androidx.activity.compose.BackHandler { vm.disconnect() }

    val sessionUi = vm.sessionState
    val connected = sessionUi as? ControllerSession.UiState.Connected
    val stats = vm.peerStats

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- 画面区（权重占满剩余空间） ----
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            BoxWithConstraints(Modifier.align(Alignment.Center)) {
                val vw = vm.screenSize.first
                val vh = vm.screenSize.second
                if (vw > 0 && vh > 0) {
                    val scale = minOf(maxWidth / vw, maxHeight / vh)
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).apply {
                                init(EglProvider.eglBase.eglBaseContext, null)
                                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                                setEnableHardwareScaler(true)
                                setZOrderOnTop(false)
                                setOnTouchListener { view, event ->
                                    handleTouch(view.width, view.height, vm.screenSize.first, vm.screenSize.second, event, vm)
                                    true
                                }
                                renderer = this
                            }
                        },
                        modifier = Modifier
                            .size(scale * vw, scale * vh)
                            .align(Alignment.Center),
                    )
                } else {
                    Text(
                        "正在连接…",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            val currentRenderer = renderer
            DisposableEffect(vm.videoTrack, currentRenderer) {
                val track = vm.videoTrack
                if (track != null && currentRenderer != null) {
                    track.addSink(currentRenderer)
                }
                onDispose {
                    if (track != null && currentRenderer != null) {
                        track.removeSink(currentRenderer)
                    }
                }
            }

            if (sessionUi is ControllerSession.UiState.Connecting) {
                Text(
                    "正在连接…",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // 左上角：返回（断开连接）
            IconButton(
                onClick = { vm.disconnect() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color(0x4D000000), CircleShape),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
            }

            // 全屏模式：右上角退出全屏
            if (fullscreen) {
                IconButton(
                    onClick = { fullscreen = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color(0x4D000000), CircleShape),
                ) {
                    Icon(Icons.Filled.CloseFullscreen, "退出全屏", tint = Color.White)
                }
            }

            // 延迟角标（左下角小字）
            val st = stats
            if (st != null) {
                val parts = mutableListOf<String>()
                st.rttMs?.let { parts.add("${it}ms") }
                st.fps?.let { if (it > 0) parts.add("${it.toInt()}fps") }
                if (parts.isNotEmpty()) {
                    Text(
                        parts.joinToString(" · "),
                        color = Color(0x8CFFFFFF),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp),
                    )
                }
            }
        }

        // ---- 画面外控制条（非全屏时显示，永不遮挡画面） ----
        if (!fullscreen && connected != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.sendKey("back") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回键", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { vm.sendKey("home") }) {
                        Icon(Icons.Filled.Home, "主页键", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { vm.sendKey("recents") }) {
                        Icon(Icons.Filled.Apps, "最近任务键", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    if (connected.audioAvailable) {
                        IconButton(onClick = {
                            val next = !audioOn
                            audioOn = next
                            vm.setHostAudio(next)
                        }) {
                            if (audioOn) {
                                Icon(Icons.Filled.VolumeUp, "关闭声音", tint = MaterialTheme.colorScheme.onSurface)
                            } else {
                                Icon(Icons.Filled.VolumeOff, "开启声音", tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    IconButton(onClick = { fullscreen = true }) {
                        Icon(Icons.Filled.Fullscreen, "全屏", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/** 沉浸式全屏：隐藏系统栏；API 30+ 用 WindowInsetsController，低版本用 systemUiVisibility */
private fun applyImmersive(activity: Activity?, enable: Boolean) {
    val window = activity?.window ?: return
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(!enable)
        val controller = window.insetsController ?: return
        if (enable) {
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsets.Type.systemBars())
        }
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (enable) {
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}

/** 触摸事件 → 受控端归一化坐标（受控端旋转后随 MSG_SCREEN 更新映射） */
private fun handleTouch(
    viewW: Int,
    viewH: Int,
    videoW: Int,
    videoH: Int,
    event: MotionEvent,
    vm: ClientViewModel,
) {
    if (videoW <= 0 || videoH <= 0) return
    val scale = min(viewW.toFloat() / videoW, viewH.toFloat() / videoH)
    val rectW = videoW * scale
    val rectH = videoH * scale
    val offsetX = (viewW - rectW) / 2f
    val offsetY = (viewH - rectH) / 2f

    fun norm(px: Float, py: Float): Pair<Float, Float> {
        val x = ((px - offsetX) / rectW).coerceIn(0f, 1f)
        val y = ((py - offsetY) / rectH).coerceIn(0f, 1f)
        return x to y
    }

    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            val (x, y) = norm(event.x, event.y)
            vm.sendTouch(MotionEvent.ACTION_DOWN, x, y, event.getPointerId(0))
        }

        MotionEvent.ACTION_POINTER_DOWN -> {
            val i = event.actionIndex
            val (x, y) = norm(event.getX(i), event.getY(i))
            vm.sendTouch(MotionEvent.ACTION_DOWN, x, y, event.getPointerId(i))
        }

        MotionEvent.ACTION_MOVE -> {
            for (i in 0 until event.pointerCount) {
                val (x, y) = norm(event.getX(i), event.getY(i))
                vm.sendTouch(MotionEvent.ACTION_MOVE, x, y, event.getPointerId(i))
            }
        }

        MotionEvent.ACTION_POINTER_UP -> {
            val i = event.actionIndex
            val (x, y) = norm(event.getX(i), event.getY(i))
            vm.sendTouch(MotionEvent.ACTION_UP, x, y, event.getPointerId(i))
        }

        MotionEvent.ACTION_UP -> {
            val (x, y) = norm(event.x, event.y)
            vm.sendTouch(MotionEvent.ACTION_UP, x, y, event.getPointerId(0))
            for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                if (id != event.getPointerId(0)) {
                    vm.sendTouch(MotionEvent.ACTION_CANCEL, x, y, id)
                }
            }
        }

        MotionEvent.ACTION_CANCEL -> {
            for (i in 0 until event.pointerCount) {
                val (x, y) = norm(event.getX(i), event.getY(i))
                vm.sendTouch(MotionEvent.ACTION_CANCEL, x, y, event.getPointerId(i))
            }
        }
    }
}
