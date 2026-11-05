package io.github.jingrongx.screenmirror.webrtc

import android.content.Context
import io.github.jingrongx.screenmirror.signaling.Protocol
import io.github.jingrongx.screenmirror.signaling.buildMsg
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** PeerConnectionFactory 单例（初始化开销大，进程内复用） */
object WebrtcHelper {

    @Volatile
    private var factory: PeerConnectionFactory? = null

    fun factory(context: Context): PeerConnectionFactory {
        factory?.let { return it }
        synchronized(this) {
            factory?.let { return it }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    // 关闭 mDNS 候选混淆：纯局域网 P2P 场景下避免 .local 候选无法解析导致连接失败
                    .setFieldTrials("WebRTC-EnableMDNSObfuscation/Disabled/")
                    .createInitializationOptions()
            )
            val eglContext: EglBase.Context = EglProvider.eglBase.eglBaseContext
            return PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
                .createPeerConnectionFactory()
                .also { factory = it }
        }
    }
}

// ---------- SDP 协程封装 ----------

suspend fun PeerConnection.createOfferSuspend(
    constraints: MediaConstraints = MediaConstraints()
): SessionDescription = suspendCancellableCoroutine { cont ->
    createOffer(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            if (cont.isActive) cont.resume(sdp)
        }

        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            if (cont.isActive) cont.resumeWithException(IllegalStateException("createOffer 失败: $error"))
        }

        override fun onSetFailure(error: String?) {}
    }, constraints)
}

suspend fun PeerConnection.createAnswerSuspend(
    constraints: MediaConstraints = MediaConstraints()
): SessionDescription = suspendCancellableCoroutine { cont ->
    createAnswer(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            if (cont.isActive) cont.resume(sdp)
        }

        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            if (cont.isActive) cont.resumeWithException(IllegalStateException("createAnswer 失败: $error"))
        }

        override fun onSetFailure(error: String?) {}
    }, constraints)
}

suspend fun PeerConnection.setLocalDescriptionSuspend(sdp: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onSetSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("setLocalDescription 失败: $error"))
            }
        }, sdp)
    }

suspend fun PeerConnection.setRemoteDescriptionSuspend(sdp: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onSetSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("setRemoteDescription 失败: $error"))
            }
        }, sdp)
    }

fun iceCandidateJson(candidate: IceCandidate) = buildMsg(
    Protocol.MSG_ICE,
    "candidate" to candidate.sdp,
    "sdpMid" to (candidate.sdpMid ?: "0"),
    "sdpMLineIndex" to candidate.sdpMLineIndex
)
 
