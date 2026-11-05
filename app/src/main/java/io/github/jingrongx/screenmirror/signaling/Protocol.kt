package io.github.jingrongx.screenmirror.signaling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 局域网信令协议。
 * 传输层：受控端监听 TCP 端口，控制端连接；帧格式：[4 字节总长度][4 字节类型][负载]
 * 所有信令（SDP/ICE）与控制指令（触摸/按键）复用同一条 TCP 连接；音频 PCM 帧为二进制帧。
 */
object Protocol {
    const val PORT = 40000
    const val SERVICE_TYPE = "_screenmirror._tcp."

    const val FRAME_JSON = 1
    const val FRAME_AUDIO = 2

    const val MSG_HELLO = "hello"           // 控制端 -> 受控端
    const val MSG_WELCOME = "welcome"       // 受控端 -> 控制端
    const val MSG_OFFER = "offer"           // WebRTC SDP Offer
    const val MSG_ANSWER = "answer"         // WebRTC SDP Answer
    const val MSG_ICE = "ice"               // ICE Candidate（双向）
    const val MSG_SCREEN = "screen"         // 受控端屏幕尺寸变化
    const val MSG_TOUCH = "touch"           // 远程触摸（归一化坐标）
    const val MSG_KEY = "key"               // 全局按键 back/home/recents/notifications
    const val MSG_AUDIO_STATE = "audio_state" // 控制端请求开关受控端声音
    const val MSG_BYE = "bye"

    val json = Json { ignoreUnknownKeys = true; isLenient = true }
}

/** 快捷构造信令 JSON：buildMsg(MSG_TOUCH, "x" to 0.5f, "y" to 0.5f) */
fun buildMsg(type: String, vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    put("type", type)
    for ((k, v) in pairs) {
        when (v) {
            null -> {}
            is String -> put(k, v)
            is Int -> put(k, v)
            is Long -> put(k, v)
            is Boolean -> put(k, v)
            is Float -> put(k, v)
            is Double -> put(k, v)
            is JsonObject -> put(k, v)
            else -> put(k, v.toString())
        }
    }
}

val JsonObject.msgType: String? get() = this["type"]?.jsonPrimitive?.contentOrNull
