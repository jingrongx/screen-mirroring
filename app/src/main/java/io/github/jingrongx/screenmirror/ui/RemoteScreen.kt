package io.github.jingrongx.screenmirror.ui

import android.app.Activity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
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

/** 远程画面：WebRTC 渲染 + 触摸映射 + 控制条 */
@Composable
fun RemoteScreen(vm: ClientViewModel) {
    val context = LocalContext.current
    val activity = context as? Activity

    // 控制端保持屏幕常亮
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val sessionUi = vm.sessionState
    val connected = sessionUi as? ControllerSession.UiState.Connected
    var audioOn by remember(connected?.deviceName) {
        mutableStateOf(connected?.audioEnabled ?: false)
    }

    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    init(EglProvider.eglBase.eglBaseContext, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(false)
                    setZOrderOnTop(false)
                    setOnTouchListener { view, event ->
                        handleTouch(view.width, view.height, vm.screenSize.first, vm.screenSize.second, event, vm)
                        true
                    }
                    renderer = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 绑定视频轨道
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
                "正在连接 ${((sessionUi) as ControllerSession.UiState.Connecting).deviceName}…",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 远程触摸不可用提示
        if (connected != null && !connected.touchAvailable) {
            Surface(
                color = Color(0xCC000000),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp),
            ) {
                Text(
                    "受控端未开启无障碍服务，只能观看画面",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // 断开按钮
        IconButton(
            onClick = { vm.disconnect() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color(0x66000000), CircleShape),
        ) {
            Icon(Icons.Filled.Close, "断开", tint = Color.White)
        }

        // 底部控制条
        if (connected != null) {
            Surface(
                color = Color(0xB3000000),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.sendKey("back") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                    }
                    IconButton(onClick = { vm.sendKey("home") }) {
                        Icon(Icons.Filled.Home, "主页", tint = Color.White)
                    }
                    IconButton(onClick = { vm.sendKey("recents") }) {
                        Icon(Icons.Filled.Apps, "最近任务", tint = Color.White)
                    }
                    if (connected.audioAvailable) {
                        IconButton(onClick = {
                            val next = !audioOn
                            audioOn = next
                            vm.setHostAudio(next)
                        }) {
                            if (audioOn) {
                                Icon(Icons.Filled.VolumeUp, "关闭声音", tint = Color.White)
                            } else {
                                Icon(Icons.Filled.VolumeOff, "开启声音", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 触摸事件 → 受控端归一化坐标（按 letterbox 实际显示区域换算） */
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
            // 同组其他指针补取消，避免受控端残留笔画
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
