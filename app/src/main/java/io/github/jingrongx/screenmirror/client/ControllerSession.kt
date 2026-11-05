package io.github.jingrongx.screenmirror.client

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.jingrongx.screenmirror.BuildConfig
import io.github.jingrongx.screenmirror.discovery.NsdDiscovery
import io.github.jingrongx.screenmirror.discovery.NsdRegistrar
import io.github.jingrongx.screenmirror.signaling.FramedSocket
import io.github.jingrongx.screenmirror.signaling.Protocol
import io.github.jingrongx.screenmirror.signaling.buildMsg
import io.github.jingrongx.screenmirror.signaling.msgType
import io.github.jingrongx.screenmirror.webrtc.WebrtcHelper
import io.github.jingrongx.screenmirror.webrtc.createAnswerSuspend
import io.github.jingrongx.screenmirror.webrtc.setLocalDescriptionSuspend
import io.github.jingrongx.screenmirror.webrtc.setRemoteDescriptionSuspend
import io.github.jingrongx.screenmirror.webrtc.iceCandidateJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 控制端会话：连接受控端 TCP 端口，交换信令，渲染视频，发送触摸/按键。
 * 一个会话同一时间只处理一台受控端。
 */
class ControllerSession(
    private val appContext: Context,
    private val onVideoTrack: (VideoTrack) -> Unit,
    private val onScreenSize: (Int, Int) -> Unit,
    private val onAudioFrame: (ByteArray) -> Unit,
    private val onClosed: (String?) -> Unit,
) {

    sealed interface UiState {
        data object Idle : UiState
        data class Connecting(val deviceName: String) : UiState
        data class Connected(
            val deviceName: String,
            val audioAvailable: Boolean,
            val touchAvailable: Boolean,
            val audioEnabled: Boolean,
        ) : UiState
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var socket: FramedSocket? = null
    private var peer: PeerConnection? = null

    @Volatile private var closed = false

    // setRemoteDescription 完成前到达的 ICE 候选先入队，避免被静默丢弃
    private val pendingIce = ArrayList<IceCandidate>()
    private var remoteDescSet = false

    var screenWidth = 0
        private set
    var screenHeight = 0
        private set

    // 控制指令（触摸/按键）统一走通道，避免 UI 线程直接碰网络
    private val controlChannel = Channel<JsonObject>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            for (msg in controlChannel) {
                val sock = socket ?: continue
                runCatching { sock.sendJson(msg) }
            }
        }
    }

    suspend fun connect(device: NsdDiscovery.DiscoveredDevice) = withContext(Dispatchers.IO) {
        if (closed) return@withContext
        _state.value = UiState.Connecting(device.name)
        var sock: FramedSocket? = null
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(device.host, device.port), CONNECT_TIMEOUT_MS)
            sock = FramedSocket(s)
            socket = sock

            sock.sendJson(
                buildMsg(
                    Protocol.MSG_HELLO,
                    "deviceName" to NsdRegistrar.serviceDisplayName(),
                    "appVersion" to BuildConfig.VERSION_NAME,
                )
            )
            val welcome = sock.readJson() ?: throw IOException("连接被对方关闭")
            if (welcome.msgType != Protocol.MSG_WELCOME) throw IOException("收到未知协议消息")
            screenWidth = welcome["screenWidth"]?.jsonPrimitive?.intOrNull ?: 0
            screenHeight = welcome["screenHeight"]?.jsonPrimitive?.intOrNull ?: 0
            val audioAvailable = welcome["audioAvailable"]?.jsonPrimitive?.booleanOrNull ?: false
            val touchAvailable = welcome["touchAvailable"]?.jsonPrimitive?.booleanOrNull ?: false
            val audioEnabled = welcome["audioEnabled"]?.jsonPrimitive?.booleanOrNull ?: false
            onScreenSize(screenWidth, screenHeight)
            _state.value = UiState.Connected(device.name, audioAvailable, touchAvailable, audioEnabled)

            setupPeer(sock)

            while (true) {
                val frame = sock.receiveFrame() ?: break
                when (frame.first) {
                    Protocol.FRAME_JSON -> {
                        val obj = runCatching {
                            Protocol.json.parseToJsonElement(frame.second.decodeToString()).jsonObject
                        }.getOrNull() ?: continue
                        handleMessage(sock, obj)
                    }

                    Protocol.FRAME_AUDIO -> onAudioFrame(frame.second)
                }
            }
            close("连接已断开")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            close(e.message ?: "连接失败")
        } finally {
            runCatching { sock?.close() }
            if (socket === sock) socket = null
        }
    }

    /** 主动断开（用户操作） */
    fun disconnect() = close(null)

    fun destroy() {
        close(null)
        controlChannel.close()
        scope.cancel()
        closeScope.cancel()
    }

    fun sendTouch(action: Int, nx: Float, ny: Float, pointerId: Int) {
        controlChannel.trySend(
            buildMsg(
                Protocol.MSG_TOUCH,
                "action" to action,
                "x" to nx,
                "y" to ny,
                "pid" to pointerId,
            )
        )
    }

    fun sendKey(key: String) {
        controlChannel.trySend(buildMsg(Protocol.MSG_KEY, "key" to key))
    }

    fun sendAudioState(enabled: Boolean) {
        controlChannel.trySend(buildMsg(Protocol.MSG_AUDIO_STATE, "enabled" to enabled))
    }

    // ---------- 内部实现 ----------

    private fun setupPeer(sock: FramedSocket) {
        val factory = WebrtcHelper.factory(appContext)
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peer = factory.createPeerConnection(rtcConfig, clientObserver(sock))
    }

    private fun clientObserver(sock: FramedSocket) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.d(TAG, "控制端 ICE 状态: $state")
            if (state == PeerConnection.IceConnectionState.FAILED ||
                state == PeerConnection.IceConnectionState.CLOSED
            ) {
                // 连接级断开：TCP 信令可能还活着，统一由这里收尾
                closeScope.launch { close("视频连接已断开") }
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.d(TAG, "控制端 ICE 收流中: $receiving")
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.d(TAG, "控制端 ICE 收集状态: $state")
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            Log.d(TAG, "控制端发送候选: ${candidate.sdp}")
            runCatching { sock.sendJson(iceCandidateJson(candidate)) }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        override fun onAddStream(stream: MediaStream?) {}

        override fun onRemoveStream(stream: MediaStream?) {}

        override fun onDataChannel(channel: org.webrtc.DataChannel?) {}

        override fun onRenegotiationNeeded() {}

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            Log.d(TAG, "收到远端轨道: ${receiver?.track()?.kind()}")
            val track = receiver?.track()
            if (track is VideoTrack) onVideoTrack(track)
        }

        override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {}
    }

    private fun handleMessage(sock: FramedSocket, obj: JsonObject) {
        when (obj.msgType) {
            Protocol.MSG_OFFER -> {
                val sdp = obj["sdp"]?.jsonPrimitive?.contentOrNull ?: return
                scope.launch {
                    val pc = peer ?: return@launch
                    try {
                        Log.d(TAG, "收到 Offer，开始 setRemoteDescription")
                        pc.setRemoteDescriptionSuspend(SessionDescription(SessionDescription.Type.OFFER, sdp))
                        remoteDescSet = true
                        flushPendingIce(pc)
                        Log.d(TAG, "setRemoteDescription 成功，创建 Answer")
                        val answer = pc.createAnswerSuspend()
                        pc.setLocalDescriptionSuspend(answer)
                        Log.d(TAG, "setLocalDescription 成功，发送 Answer")
                        // 本协程运行在主线程，Socket 写操作必须切到 IO 线程（NetworkOnMainThreadException）
                        withContext(Dispatchers.IO) {
                            sock.sendJson(buildMsg(Protocol.MSG_ANSWER, "sdp" to answer.description))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "媒体协商失败", e)
                        close("媒体协商失败：${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}")
                    }
                }
            }

            Protocol.MSG_ICE -> {
                val candidate = obj["candidate"]?.jsonPrimitive?.contentOrNull ?: return
                val mid = obj["sdpMid"]?.jsonPrimitive?.contentOrNull ?: "0"
                val mline = obj["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0
                val ice = IceCandidate(mid, mline, candidate)
                if (remoteDescSet) {
                    runCatching { peer?.addIceCandidate(ice) }
                } else {
                    synchronized(pendingIce) { pendingIce.add(ice) }
                }
            }

            Protocol.MSG_SCREEN -> {
                val w = obj["width"]?.jsonPrimitive?.intOrNull ?: return
                val h = obj["height"]?.jsonPrimitive?.intOrNull ?: return
                if (w > 0 && h > 0) {
                    screenWidth = w
                    screenHeight = h
                    onScreenSize(w, h)
                }
            }

            Protocol.MSG_BYE -> close("对方已结束共享")
        }
    }

    /** reason 为 null 表示用户主动断开；非 null 表示异常/远端结束 */
    fun close(reason: String?) {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        closeScope.launch {
            runCatching { socket?.sendJson(buildMsg(Protocol.MSG_BYE)) }
            runCatching { socket?.close() }
            runCatching { peer?.close() }
        }
        socket = null
        peer = null
        onClosed(reason)
    }

    private fun flushPendingIce(pc: PeerConnection) {
        val list = synchronized(pendingIce) {
            val l = ArrayList(pendingIce)
            pendingIce.clear()
            l
        }
        for (c in list) runCatching { pc.addIceCandidate(c) }
        Log.d(TAG, "补充应用了 ${list.size} 个先到的 ICE 候选")
    }

    companion object {
        private const val TAG = "ControllerSession"
        private const val CONNECT_TIMEOUT_MS = 6000
    }
}
