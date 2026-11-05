package io.github.jingrongx.screenmirror.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.jingrongx.screenmirror.signaling.Protocol

/** 受控端：注册 NSD 服务，让控制端能发现本机 */
class NsdRegistrar(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun register(port: Int, onRegistered: (String) -> Unit, onFailed: (Int) -> Unit) {
        unregister()
        val name = serviceDisplayName()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = Protocol.SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                // 系统可能在重名时自动改名，返回最终名字
                onRegistered(serviceInfo?.serviceName ?: name)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = onFailed(errorCode)
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
        }
        listener = l
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { onFailed(-1) }
    }

    fun unregister() {
        listener?.let { runCatching { nsdManager.unregisterService(it) } }
        listener = null
    }

    companion object {
        fun serviceDisplayName(): String =
            "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Android 设备" }
    }
}
