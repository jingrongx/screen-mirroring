package io.github.jingrongx.screenmirror.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.jingrongx.screenmirror.signaling.Protocol

/** 控制端：扫描局域网内的受控端设备 */
class NsdDiscovery(
    context: Context,
    private val onDeviceFound: (DiscoveredDevice) -> Unit,
    private val onDeviceLost: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    data class DiscoveredDevice(val name: String, val host: String, val port: Int)

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // Android 13 及以下 NSD 不支持并发 resolve，用队列串行处理
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String?) {}
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
            onError("局域网设备发现失败（错误码 $errorCode）")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
            val info = serviceInfo ?: return
            if (info.serviceType?.startsWith("_screenmirror") != true) return
            synchronized(resolveQueue) {
                resolveQueue.addLast(info)
                tryResolveNext()
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
            serviceInfo?.serviceName?.let(onDeviceLost)
        }

        override fun onDiscoveryStopped(serviceType: String?) {}
        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
            synchronized(resolveQueue) { resolving = false }
            tryResolveNext()
        }

        override fun onServiceResolved(info: NsdServiceInfo?) {
            synchronized(resolveQueue) { resolving = false }
            val resolved = info
            if (resolved != null) {
                val host = hostAddressOf(resolved)
                if (host != null) {
                    onDeviceFound(
                        DiscoveredDevice(
                            resolved.serviceName ?: "未知设备",
                            host,
                            resolved.port
                        )
                    )
                }
            }
            tryResolveNext()
        }
    }

    private fun tryResolveNext() {
        if (resolving) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        runCatching { nsdManager.resolveService(next, resolveListener) }
            .onFailure { resolving = false }
    }

    fun start() {
        runCatching {
            nsdManager.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure { onError("无法启动设备发现：${it.message}") }
    }

    fun stop() {
        synchronized(resolveQueue) { resolveQueue.clear() }
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
    }

    private fun hostAddressOf(info: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull()?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            info.host?.hostAddress
        }
}
