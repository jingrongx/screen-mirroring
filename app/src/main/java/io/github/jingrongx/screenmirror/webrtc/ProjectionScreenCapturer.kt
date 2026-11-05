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

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || !capturing) return
            val ctx = context ?: return
            val size = ScreenUtils.realDisplaySize(ctx)
            if (size.x != width || size.y != height) {
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
        this.width = size.x
        this.height = size.y
        dpi = ctx.resources.displayMetrics.densityDpi
        capturing = true

        h.setTextureSize(size.x, size.y)
        // 采集线程产出的 VideoFrame 已带正确的尺寸/矩阵，直接转发给 WebRTC 管线
        h.startListening(VideoSink { frame -> observer?.onFrameCaptured(frame) })

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenMirrorCapture",
            size.x, size.y, dpi,
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

    /** 屏幕旋转/尺寸变化时重建虚拟显示 */
    private fun recreateDisplay(w: Int, h: Int) {
        if (!capturing) return
        width = w
        height = h
        helper?.setTextureSize(w, h)
        runCatching { virtualDisplay?.release() }
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenMirrorCapture",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            Surface(helper?.surfaceTexture ?: return),
            null,
            null
        )
    }

    private fun displayManager(ctx: Context) =
        ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
}
