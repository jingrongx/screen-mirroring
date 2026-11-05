package io.github.jingrongx.screenmirror.signaling

import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** 受控端 TCP 信令服务：监听端口等待控制端接入，同一时间只保留一个客户端（由上层处理） */
class SignalingServer(
    private val port: Int = Protocol.PORT,
    private val onClient: (FramedSocket) -> Unit,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var acceptThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        acceptThread = thread(name = "signaling-server") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                while (running) {
                    val client = ss.accept()
                    client.tcpNoDelay = true
                    if (!running) break
                    onClient(FramedSocket(client))
                }
            } catch (e: Exception) {
                // running=false 时为正常关闭；端口占用等错误由上层状态体现
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }
}
