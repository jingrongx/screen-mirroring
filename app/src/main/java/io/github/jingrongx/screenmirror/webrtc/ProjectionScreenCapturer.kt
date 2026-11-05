package io.github.jingrongx.screenmirror.webrtc

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import io.github.jingrongx.screenmirror.common.ScreenUtils
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink

/**
 * 接收外部 MediaProjection 的屏幕采集器。
 *
 * 不使用官方 ScreenCapturerAndroid 的原因：Android 14 起投屏授权 token 只能调用一次
 * getMediaProjection()，而系统音频捕获（AudioPlaybackCapture）也需要 MediaProjection 实例，
 * 因此必须由外部创建一次投影后同时供视频与音频使用。
 */
class ProjectionScreenCapturer(
    private val projection: MediaProjection,
    /** 视频/屏幕尺寸变化（含初始值与旋转重建）时回调，用于通知控制端 */
    private val onVideoSizeChanged: ((width: Int, height: Int) -> Unit)? = null,
) : VideoCapturer {

    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var context: Context? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var dpi = 0

    @Volatile private var capturing = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameCount = 0
    private var dropCount = 0
    private var lastForwardTsNs = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || !capturing) return
            val ctx = context ?: return
            val size = ScreenUtils.realDisplaySize(ctx)
            val (tw, th) = targetSize(size.x, size.y)
            if (tw != width || th != height) {
                mainHandler.post { recreateDisplay(size.x, size.y) }
            }
        }
    }

    override fun initialize(helper: SurfaceTextureHelper, context: Context, observer: CapturerObserver) {
        this.helper = helper
        this.context = context
        this.observer = observer
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        val ctx = context ?: return
        val h = helper ?: return
        val size = ScreenUtils.realDisplaySize(ctx)
        val (tw, th) = targetSize(size.x, size.y)
        this.width = tw
        this.height = th
        dpi = ctx.resources.displayMetrics.densityDpi
        capturing = true
        onVideoSizeChanged?.invoke(tw, th)

        h.setTextureSize(tw, th)
        // 关键优化：合成器最高 60fps 出帧，编码器（尤其弱机）跟不上会导致帧队列堆积、
        // 延迟爆炸到秒级。这里按时间戳节流到 ~30fps，未到间隔的帧直接丢弃（不进编码队列）。
        h.startListening(VideoSink { frame ->
            val ts = frame.timestampNs
            if (lastForwardTsNs != 0L && ts - lastForwardTsNs < FRAME_INTERVAL_NS) {
                dropCount++
                if (dropCount % 200 == 0) {
                    android.util.Log.d("ScreenCapturer", "已节流丢弃 $dropCount 帧（编码器限流）")
                }
                return@VideoSink
            }
            lastForwardTsNs = ts
            frameCount++
            if (frameCount == 1 || frameCount % 100 == 0) {
                android.util.Log.d("ScreenCapturer", "采集出帧 #${frameCount}（${frame.buffer.width}x${frame.buffer.height}）")
            }
            observer?.onFrameCaptured(frame)
        })

        // VirtualDisplay 直接按目标分辨率创建，系统层自动缩放，编码负担小、传输流畅
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenMirrorCapture",
            tw, th, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            Surface(h.surfaceTexture),
            null,
            null
        )
        displayManager(ctx).registerDisplayListener(displayListener, mainHandler)
        observer?.onCapturerStarted(true)
    }

    override fun stopCapture() {
        capturing = false
        helper?.stopListening()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        context?.let { runCatching { displayManager(it).unregisterDisplayListener(displayListener) } }
        observer?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) = Unit

    override fun dispose() = Unit

    override fun isScreencast(): Boolean = true

    /** 屏幕旋转/尺寸变化时重建虚拟显示（入参为真实屏幕尺寸，内部换算目标分辨率） */
    private fun recreateDisplay(realW: Int, realH: Int) {
        if (!capturing) return
        val (tw, th) = targetSize(realW, realH)
        width = tw
        height = th
        helper?.setTextureSize(tw, th)
        onVideoSizeChanged?.invoke(tw, th)
        runCatching { virtualDisplay?.release() }
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenMirrorCapture",
            tw, th, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            Surface(helper?.surfaceTexture ?: return),
            null,
            null
        )
    }

    companion object {
        /** 采集目标分辨率：短边上限 720p（流畅优先，降低编码与传输负担） */
        private const val TARGET_SHORT_SIDE = 720

        /** 编码输入节流间隔：30fps（约 33.3ms） */
        private const val FRAME_INTERVAL_NS = 33_300_000L

        fun targetSize(realW: Int, realH: Int): Pair<Int, Int> {
            val short = minOf(realW, realH)
            if (short <= TARGET_SHORT_SIDE || short <= 0) return realW to realH
            val scale = TARGET_SHORT_SIDE.toFloat() / short
            return (realW * scale).toInt() to (realH * scale).toInt()
        }
    }

    private fun displayManager(ctx: Context) =
        ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
}
