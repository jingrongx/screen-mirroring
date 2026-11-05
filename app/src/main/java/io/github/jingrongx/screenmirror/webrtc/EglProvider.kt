package io.github.jingrongx.screenmirror.webrtc

import org.webrtc.EglBase

/** 全局共享的 EGL 上下文（采集、编解码、渲染共用，减少内存开销） */
object EglProvider {
    val eglBase: EglBase by lazy { EglBase.create() }
}
