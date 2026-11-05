package io.github.jingrongx.screenmirror.signaling

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket

/**
 * 长度前缀分帧的 TCP Socket。
 * 帧格式：[int32 总长度(含类型字段)][int32 帧类型][负载]
 */
class FramedSocket(private val socket: Socket) : Closeable {

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 128 * 1024))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 128 * 1024))
    private val writeLock = Any()

    /** 线程安全：音频采集线程与信令线程共用一条连接 */
    fun sendFrame(type: Int, payload: ByteArray) {
        synchronized(writeLock) {
            output.writeInt(payload.size + 4)
            output.writeInt(type)
            output.write(payload)
            output.flush()
        }
    }

    fun sendJson(obj: JsonObject) =
        sendFrame(Protocol.FRAME_JSON, obj.toString().toByteArray(Charsets.UTF_8))

    /**
     * 阻塞读取一帧；连接关闭/异常返回 null。
     * 最大帧长限制防止恶意数据撑爆内存。
     */
    fun receiveFrame(): Pair<Int, ByteArray>? {
        return try {
            val length = input.readInt()
            if (length < 4 || length > MAX_FRAME_SIZE) return null
            val type = input.readInt()
            val payload = ByteArray(length - 4)
            input.readFully(payload)
            type to payload
        } catch (e: EOFException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    /** 握手阶段读取 JSON 信令（跳过可能存在的音频帧） */
    fun readJson(): JsonObject? {
        while (true) {
            val frame = receiveFrame() ?: return null
            if (frame.first == Protocol.FRAME_JSON) {
                return try {
                    Protocol.json.parseToJsonElement(frame.second.decodeToString()).jsonObject
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val MAX_FRAME_SIZE = 8 * 1024 * 1024
    }
}
