package io.github.jingrongx.screenmirror.host

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.jingrongx.screenmirror.BuildConfig
import io.github.jingrongx.screenmirror.audio.PlaybackAudioCaptor
import io.github.jingrongx.screenmirror.common.NetUtils
import io.github.jingrongx.screenmirror.common.ScreenUtils
import io.github.jingrongx.screenmirror.discovery.NsdRegistrar
import io.github.jingrongx.screenmirror.signaling.FramedSocket
import io.github.jingrongx.screenmirror.signaling.Protocol
import io.github.jingrongx.screenmirror.signaling.SignalingServer
import io.github.jingrongx.screenmirror.signaling.buildMsg
import io.github.jingrongx.screenmirror.signaling.msgType
import io.github.jingrongx.screenmirror.webrtc.ProjectionScreenCapturer
import io.github.jingrongx.screenmirror.webrtc.WebrtcHelper
import io.github.jingrongx.screenmirror.webrtc.createOfferSuspend
import io.github.jingrongx.screenmirror.webrtc.setLocalDescriptionSuspend
import io.github.jingrongx.screenmirror.webrtc.setRemoteDescriptionSuspend
import io.github.jingrongx.screenmirror.webrtc.iceCandidateJson
import io.github.jingrongx.screenmirror.webrtc.EglProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.DataChannel
import org.webrtc.SessionDescription
import org.webrtc.PeerConnection.SignalingState
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.PeerConnection.IceGatheringState

/**
 * 受控端会话（进程级单例，生命周期跟随前台服务）：
 * 投屏采集 -> WebRTC 视频 -> NSD 注册 -> TCP 信令 -> 远程控制指令 -> 音频捕获
 */
object HostSession {

    private const val TAG = "HostSession"

    sealed interface UiState {
        data object Idle : UiState
        data class Waiting(
            val serviceName: String,
            val ip: String?,
            val port: Int,
            val audioAvailable: Boolean,
            val audioEnabled: Boolean,
        ) : UiState

        data class Streaming(
            val clientName: String,
            val audioAvailable: Boolean,
            val audioEnabled: Boolean,
            val touchReady: Boolean,
        ) : UiState

        data class Failed(val message: String) : UiState
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var projection: MediaProjection? = null
    private var capturer: ProjectionScreenCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var server: SignalingServer? = null
    private var nsd: NsdRegistrar? = null
    private var audioCaptor: PlaybackAudioCaptor? = null

    // 当前控制端连接
    private var clientSocket: FramedSocket? = null
    private var clientJob: Job? = null
    private var peer: PeerConnection? = null
    private var clientName: String = ""
    private var serviceName: String = ""

    @Volatile private var audioWanted = false
    @Volatile private var screenW = 0
    @Volatile private var screenH = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 用户从系统面板/通知栏停止了投屏
            mainHandler.post { stop() }
        }
    }

    val isRunning: Boolean get() = projection != null

    fun start(context: Context, resultCode: Int, data: Intent, audio: Boolean) {
        appContext = context.applicationContext
        audioWanted = audio && Build.VERSION.SDK_INT >= 29
        val size = ScreenUtils.realDisplaySize(context)
        screenW = size.x
        screenH = size.y
        try {
            val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val projection = pm.getMediaProjection(resultCode, data)
            this.projection = projection
            projection.registerCallback(projectionCallback, mainHandler)

            val factory = WebrtcHelper.factory(context)
            val source = factory.createVideoSource(true /* isScreencast */)
            videoSource = source
            val helper = SurfaceTextureHelper.create("capture-thread", EglProvider.eglBase.eglBaseContext)
            textureHelper = helper
            val capturer = ProjectionScreenCapturer(projection)
            capturer.initialize(helper, context, source.capturerObserver)
            capturer.startCapture(screenW, screenH, 30)
            this.capturer = capturer
            videoTrack = factory.createVideoTrack("host-video", source)

            val server = SignalingServer { sock ->
                scope.launch(Dispatchers.IO) { handleClient(sock) }
            }
            server.start()
            this.server = server

            val nsd = NsdRegistrar(context)
            nsd.register(
                Protocol.PORT,
                onRegistered = { name ->
                    serviceName = name
                    refreshIdleState()
                },
                onFailed = { code ->
                    _state.value = UiState.Failed("局域网服务注册失败（错误码 $code），请检查网络后重试")
                }
            )
            this.nsd = nsd
            if (audioWanted) startAudioCapture()
            refreshIdleState()
        } catch (e: Exception) {
            Log.e(TAG, "启动失败", e)
            stop()
            _state.value = UiState.Failed("启动失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun stop() {
        dropClient()
        stopAudioCapture()
        nsd?.unregister()
        nsd = null
        server?.stop()
        server = null
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        runCatching { textureHelper?.dispose() }
        textureHelper = null
        runCatching { videoTrack?.dispose() }
        videoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        projection?.let { runCatching { it.stop() } }
        projection = null
        screenW = 0
        screenH = 0
        _state.value = UiState.Idle
    }

    fun setAudioEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 29) return
        if (enabled && audioCaptor == null) {
            audioWanted = true
            startAudioCapture()
        } else if (!enabled) {
            audioWanted = false
            stopAudioCapture()
        }
        refreshIdleState()
    }

    // ---------- 内部实现 ----------

    private fun refreshIdleState() {
        if (_state.value is UiState.Streaming) return
        if (projection == null) {
            _state.value = UiState.Idle
            return
        }
        _state.value = waitingState()
    }

    private fun waitingState() = UiState.Waiting(
        serviceName.ifEmpty { NsdRegistrar.serviceDisplayName() },
        NetUtils.localIpAddress(),
        Protocol.PORT,
        audioAvailable = Build.VERSION.SDK_INT >= 29,
        audioEnabled = audioWanted,
    )

    private fun startAudioCapture() {
        if (Build.VERSION.SDK_INT < 29) return
        val projection = projection ?: return
        val captor = PlaybackAudioCaptor(projection)
        audioCaptor = captor
        scope.launch(Dispatchers.IO) {
            val ok = captor.start { pcm ->
                val sock = clientSocket
                if (sock != null) runCatching { sock.sendFrame(Protocol.FRAME_AUDIO, pcm) }
            }
            if (!ok) {
                Log.w(TAG, "音频捕获启动失败")
            }
        }
    }

    private fun stopAudioCapture() {
        val captor = audioCaptor ?: return
        audioCaptor = null
        scope.launch(Dispatchers.IO) { runCatching { captor.stop() } }
    }

    private suspend fun handleClient(sock: FramedSocket) = withContext(Dispatchers.IO) {
        try {
            handleClientInternal(sock)
        } catch (e: Exception) {
            Log.e(TAG, "处理客户端连接异常", e)
            runCatching { sock.close() }
            if (clientSocket === sock) dropClient()
        }
    }

    private suspend fun handleClientInternal(sock: FramedSocket) = withContext(Dispatchers.IO) {
        // 已有连接时踢掉旧客户端
        dropClient()
        val hello = sock.readJson()
        if (hello == null || hello.msgType != Protocol.MSG_HELLO) {
            runCatching { sock.close() }
            return@withContext
        }
        clientName = hello["deviceName"]?.jsonPrimitive?.contentOrNull ?: "控制端"
        clientSocket = sock

        val touchReady = Build.VERSION.SDK_INT >= 24 && GestureAccessibilityService.instance != null
        sock.sendJson(
            buildMsg(
                Protocol.MSG_WELCOME,
                "deviceName" to NsdRegistrar.serviceDisplayName(),
                "screenWidth" to screenW,
                "screenHeight" to screenH,
                "audioAvailable" to (Build.VERSION.SDK_INT >= 29),
                "audioEnabled" to audioWanted,
                "touchAvailable" to touchReady,
                "versionName" to BuildConfig.VERSION_NAME,
            )
        )
        if (audioWanted) startAudioCapture()

        // 建立 PeerConnection 并发起 Offer
        val factory = WebrtcHelper.factory(appContext!!)
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(rtcConfig, hostObserver(sock))
        if (pc == null) {
            runCatching { sock.close() }
            return@withContext
        }
        peer = pc
        val track = videoTrack
        if (track != null) {
            pc.addTrack(track, listOf("screen"))
            pc.senders.firstOrNull()?.let { sender ->
                val params = sender.parameters
                params.encodings.firstOrNull()?.maxBitrateBps = 6_000_000
                runCatching { sender.parameters = params }
            }
        }
        val offer = pc.createOfferSuspend(MediaConstraints())
        pc.setLocalDescriptionSuspend(offer)
        Log.d(TAG, "Offer 已创建并发送（${offer.description.length} 字节）")
        sock.sendJson(buildMsg(Protocol.MSG_OFFER, "sdp" to offer.description))

        // 信令 + 控制指令收包循环
        val job = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val frame = sock.receiveFrame() ?: break
                    if (frame.first != Protocol.FRAME_JSON) continue
                    val obj = runCatching {
                        Protocol.json.parseToJsonElement(frame.second.decodeToString()).jsonObject
                    }.getOrNull() ?: continue
                    handleClientMessage(pc, obj)
                }
            } catch (_: Exception) {
            } finally {
                if (clientSocket === sock) dropClient()
            }
        }
        clientJob = job
        _state.value = UiState.Streaming(clientName, Build.VERSION.SDK_INT >= 29, audioWanted, touchReady)
    }

    private fun hostObserver(sock: FramedSocket) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: SignalingState) {}

        override fun onIceConnectionChange(newState: IceConnectionState) {
            Log.d(TAG, "宿主 ICE 状态: $newState")
            if (newState == IceConnectionState.FAILED || newState == IceConnectionState.CLOSED) {
                scope.launch(Dispatchers.IO) {
                    if (clientSocket === sock) dropClient()
                }
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.d(TAG, "宿主 ICE 收流中: $receiving")
        }

        override fun onIceGatheringChange(state: IceGatheringState) {
            Log.d(TAG, "宿主 ICE 收集状态: $state")
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            Log.d(TAG, "宿主发送候选: ${candidate.sdp}")
            runCatching { sock.sendJson(iceCandidateJson(candidate)) }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        override fun onAddStream(stream: MediaStream?) {}

        override fun onRemoveStream(stream: MediaStream?) {}

        override fun onDataChannel(channel: DataChannel?) {}

        override fun onRenegotiationNeeded() {}

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}

        override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {}
    }

    private suspend fun handleClientMessage(pc: PeerConnection, obj: JsonObject) {
        when (obj.msgType) {
            Protocol.MSG_ANSWER -> {
                val sdp = obj["sdp"]?.jsonPrimitive?.contentOrNull ?: return
                runCatching {
                    pc.setRemoteDescriptionSuspend(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                }.onFailure { Log.w(TAG, "setRemoteDescription 失败", it) }
            }

            Protocol.MSG_ICE -> {
                val candidate = obj["candidate"]?.jsonPrimitive?.contentOrNull ?: return
                val mid = obj["sdpMid"]?.jsonPrimitive?.contentOrNull ?: "0"
                val mline = obj["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0
                runCatching { pc.addIceCandidate(IceCandidate(mid, mline, candidate)) }
            }

            Protocol.MSG_TOUCH -> {
                val service = GestureAccessibilityService.instance
                if (service != null && Build.VERSION.SDK_INT >= 24) {
                    val action = obj["action"]?.jsonPrimitive?.intOrNull ?: return
                    val x = obj["x"]?.jsonPrimitive?.floatOrNull ?: return
                    val y = obj["y"]?.jsonPrimitive?.floatOrNull ?: return
                    val pid = obj["pid"]?.jsonPrimitive?.intOrNull ?: 0
                    service.injectTouch(action, x, y, pid, screenW, screenH)
                }
            }

            Protocol.MSG_KEY -> {
                val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: return
                GestureAccessibilityService.instance?.performGlobal(key)
            }

            Protocol.MSG_AUDIO_STATE -> {
                val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: return
                mainHandler.post { setAudioEnabled(enabled) }
            }

            Protocol.MSG_BYE -> {
                // 关闭当前连接，收包循环退出后在 finally 中统一收尾
                clientSocket?.let { runCatching { it.close() } }
            }
        }
    }

    private fun dropClient() {
        val job = clientJob
        clientJob = null
        val pc = peer
        peer = null
        val sock = clientSocket
        clientSocket = null
        runCatching { sock?.sendJson(buildMsg(Protocol.MSG_BYE)) }
        runCatching { sock?.close() }
        runCatching { pc?.close() }
        // 等待收包循环退出，避免下一次连接时旧循环残留
        if (job != null) {
            scope.launch(Dispatchers.IO) { runCatching { job.cancelAndJoin() } }
        }
        stopAudioCapture()
        // 客户端断开后必须回到等待状态（refreshIdleState 会因 Streaming 提前返回，需直接赋值）
        if (_state.value is UiState.Streaming) _state.value = waitingState()
    }
}
