package io.github.jingrongx.screenmirror.client

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jingrongx.screenmirror.audio.PcmPlayer
import io.github.jingrongx.screenmirror.discovery.NsdDiscovery
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack

/** 控制端 UI 状态：设备发现 + 会话状态 + 视频轨道 */
class ClientViewModel(app: Application) : AndroidViewModel(app) {

    val devices = mutableStateListOf<NsdDiscovery.DiscoveredDevice>()
    var discoveryError by mutableStateOf<String?>(null)
        private set
    var sessionState by mutableStateOf<ControllerSession.UiState>(ControllerSession.UiState.Idle)
        private set
    var videoTrack by mutableStateOf<VideoTrack?>(null)
        private set
    var screenSize by mutableStateOf(0 to 0)
        private set
    var failure by mutableStateOf<String?>(null)
        private set

    var session: ControllerSession? = null
        private set

    private var discovery: NsdDiscovery? = null
    private var stateCollectJob: Job? = null
    private val player = PcmPlayer()
    private var playerStarted = false

    fun startDiscovery() {
        if (discovery != null) return
        devices.clear()
        discoveryError = null
        val d = NsdDiscovery(
            getApplication(),
            onDeviceFound = { dev ->
                if (devices.none { it.host == dev.host && it.port == dev.port }) devices.add(dev)
            },
            onDeviceLost = { name -> devices.removeAll { it.name == name } },
            onError = { msg -> discoveryError = msg },
        )
        discovery = d
        d.start()
    }

    fun stopDiscovery() {
        discovery?.stop()
        discovery = null
    }

    fun connect(device: NsdDiscovery.DiscoveredDevice) {
        if (session != null) return
        failure = null
        stopDiscovery()
        val s = ControllerSession(
            getApplication(),
            onVideoTrack = { track -> videoTrack = track },
            onScreenSize = { w, h -> screenSize = w to h },
            onAudioFrame = { pcm -> player.write(pcm) },
            onClosed = { reason ->
                player.stop()
                playerStarted = false
                videoTrack = null
                session = null
                sessionState = ControllerSession.UiState.Idle
                if (reason != null) failure = reason
            },
        )
        session = s
        stateCollectJob?.cancel()
        stateCollectJob = viewModelScope.launch {
            s.state.collect { st ->
                sessionState = st
                if (st is ControllerSession.UiState.Connected && st.audioAvailable && !playerStarted) {
                    player.start()
                    playerStarted = true
                }
            }
        }
        viewModelScope.launch { s.connect(device) }
    }

    fun disconnect() {
        session?.disconnect()
    }

    fun clearFailure() {
        failure = null
    }

    fun sendTouch(action: Int, x: Float, y: Float, pointerId: Int) {
        session?.sendTouch(action, x, y, pointerId)
    }

    fun sendKey(key: String) {
        session?.sendKey(key)
    }

    fun setHostAudio(enabled: Boolean) {
        session?.sendAudioState(enabled)
    }

    override fun onCleared() {
        stopDiscovery()
        session?.close(null)
        player.stop()
        super.onCleared()
    }
}
