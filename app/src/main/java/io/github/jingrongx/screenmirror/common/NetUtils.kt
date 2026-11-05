package io.github.jingrongx.screenmirror.common

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    /** 取本机局域网 IPv4 地址，用于界面展示 */
    fun localIpAddress(): String? = runCatching {
        java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { java.util.Collections.list(it.inetAddresses).asSequence() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()
}

object ScreenUtils {
    /** 真实屏幕尺寸（含导航栏/状态栏区域），用于坐标换算 */
    fun realDisplaySize(context: Context): Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }
}
