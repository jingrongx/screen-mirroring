# WebRTC（io.getstream:stream-webrtc-android 自带 consumer 规则，此处为兜底）
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# kotlinx.serialization
-keepclassmembers class * implements kotlinx.serialization.KSerializer { *; }
-keepclasseswithmembers class io.github.jingrongx.screenmirror.** {
    public static ** Companion;
}
