package io.github.jingrongx.screenmirror.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

/**
 * 受控端无障碍服务：接收远程触摸/按键，注入到本机。
 *
 * 触摸：把归一化坐标映射到真实屏幕，用 GestureDescription 模拟手势。
 *   - API 26+：使用 continueStroke 平滑延续笔画（滑动体验好）
 *   - API 24-25：每次 MOVE 独立派发短笔画（可用但略有顿挫）
 *   - API 21-23：系统不支持手势注入，只有全局按键可用
 */
class GestureAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val strokes = HashMap<Int, ActiveStroke>()
    private var lastDispatchAt = 0L

    private class ActiveStroke {
        val path = Path()
        var stroke: GestureDescription.StrokeDescription? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun injectTouch(action: Int, nx: Float, ny: Float, pointerId: Int, screenW: Int, screenH: Int) {
        if (Build.VERSION.SDK_INT < 24) return
        mainHandler.post { performInject(action, nx, ny, pointerId, screenW, screenH) }
    }

    @RequiresApi(24)
    private fun performInject(action: Int, nx: Float, ny: Float, pointerId: Int, screenW: Int, screenH: Int) {
        val x = nx * screenW
        val y = ny * screenH
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val s = ActiveStroke()
                s.path.moveTo(x, y)
                strokes[pointerId] = s
                val desc = newStroke(s.path, willContinue = true)
                s.stroke = desc
                dispatch(desc)
            }

            MotionEvent.ACTION_MOVE -> {
                val s = strokes[pointerId] ?: return
                s.path.lineTo(x, y)
                val now = SystemClock.uptimeMillis()
                if (now - lastDispatchAt >= MIN_STROKE_INTERVAL_MS) {
                    val desc = continueStroke(s, willContinue = true)
                    s.stroke = desc
                    dispatch(desc)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val s = strokes.remove(pointerId) ?: return
                s.path.lineTo(x, y)
                dispatch(continueStroke(s, willContinue = false))
            }
        }
    }

    @RequiresApi(24)
    private fun newStroke(path: Path, willContinue: Boolean): GestureDescription.StrokeDescription =
        if (Build.VERSION.SDK_INT >= 26) {
            GestureDescription.StrokeDescription(path, 0, STROKE_DURATION_MS, willContinue)
        } else {
            GestureDescription.StrokeDescription(path, 0, STROKE_DURATION_MS)
        }

    @RequiresApi(24)
    private fun continueStroke(s: ActiveStroke, willContinue: Boolean): GestureDescription.StrokeDescription {
        val prev = s.stroke
        return if (Build.VERSION.SDK_INT >= 26 && prev != null) {
            prev.continueStroke(s.path, 0, STROKE_DURATION_MS, willContinue)
        } else {
            newStroke(s.path, willContinue = false)
        }
    }

    @RequiresApi(24)
    private fun dispatch(desc: GestureDescription.StrokeDescription) {
        lastDispatchAt = SystemClock.uptimeMillis()
        dispatchGesture(GestureDescription.Builder().addStroke(desc).build(), null, null)
    }

    /** 全局按键：back / home / recents / notifications / lock（lock 需 API 28+） */
    fun performGlobal(key: String) {
        mainHandler.post {
            val action = when (key) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return@post
                else -> return@post
            }
            performGlobalAction(action)
            if (key == "home") {
                // 部分 TV ROM 对 GLOBAL_ACTION_HOME 无效，200ms 后兜底发 HOME Intent（幂等）
                mainHandler.postDelayed({
                    runCatching {
                        applicationContext.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_MAIN)
                                .addCategory(android.content.Intent.CATEGORY_HOME)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }, 200)
            }
        }
    }

    companion object {
        @Volatile
        var instance: GestureAccessibilityService? = null
            private set

        private const val STROKE_DURATION_MS = 50L
        private const val MIN_STROKE_INTERVAL_MS = 40L
    }
}
