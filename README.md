# 屏幕镜像（Android 局域网屏幕共享 + 远程控制）

两台 Android 设备在**同一 Wi-Fi** 下，无需 ADB、无需 Root、无需公网服务器，即可：

- 📺 **受控端**：共享屏幕画面（WebRTC H264/VP8 硬编），Android 10+ 可同时共享系统声音
- 🎮 **控制端**：实时观看画面，远程触摸点击/滑动、返回/主页/最近任务，播放受控端声音
- 🔄 **自动更新**：应用内检查更新，国内自动走加速代理，GitHub Actions 提交代码自动构建发版

> 技术路线参考了 [halifox/android_rdp_webrtc](https://github.com/halifox/android_rdp_webrtc)（NSD 发现 → TCP 信令 → WebRTC 视频 → 无障碍注入），在其基础上补齐了音频链路、自动更新与 CI 发版。

## 兼容性

| 功能 | 要求 | 说明 |
|---|---|---|
| 屏幕共享（观看画面） | Android 5.0+（minSdk 21） | MediaProjection + WebRTC |
| 远程触摸/滑动 | 受控端 Android 7.0+（API 24） | 无障碍 GestureDescription；API 26+ 笔画更平滑 |
| 全局按键（返回/主页/最近任务） | Android 5.0+ | 无障碍 performGlobalAction |
| **声音共享** | 受控端 Android 10+（API 29） | AudioPlaybackCapture 捕获系统媒体声音，PCM 走 TCP 直传 |
| 自动更新 | Android 5.0+ | GitHub Releases + 国内代理 |

## 使用方法

1. 两台手机安装 APK，连接同一 Wi-Fi
2. **受控端**：首页 →「共享这台设备」→ 开启无障碍服务（用于远程触摸）→ 开始共享（授权投屏，可选开启声音）
3. **控制端**：首页 →「控制其他设备」→ 选择列表中的受控设备（搜不到可手动输入 IP）
4. 控制端底部工具条：返回 / 主页 / 最近任务 / 声音开关；直接在画面上触摸即远程操作

## 架构

```
受控端                                  控制端
┌─────────────────────────┐            ┌─────────────────────────┐
│ MirrorHostService(前台) │            │ ClientViewModel         │
│  ├─ MediaProjection     │            │  ├─ NSD 发现/手动 IP     │
│  │   ├─ 投屏采集(视频)   │            │  └─ RemoteScreen        │
│  │   └─ 音频捕获(29+)   │   局域网    │      ├─ WebRTC 渲染     │
│  ├─ WebRTC 视频(H264)   │ ═══════►  │      ├─ 触摸→归一化坐标  │
│  ├─ TCP 40000 信令/控制  │ ◄═══════  │      └─ 声音播放        │
│  ├─ NSD 注册 _screenmirror._tcp      └─────────────────────────┘
│  └─ 无障碍注入手势/按键  │
└─────────────────────────┘
```

- **发现**：受控端 NSD 注册 `_screenmirror._tcp.` : 40000；控制端扫描（也可手动输 IP）
- **信令与控制**：同一条 TCP 连接，帧格式 `[int32 长度][int32 类型][负载]`，类型 1=JSON 信令、2=PCM 音频；消息有 hello/welcome/offer/answer/ice/screen/touch/key/audio_state/bye
- **视频**：受控端创建 Offer，WebRTC P2P 直连（已关闭 mDNS 候选混淆，纯内网可连）
- **触摸**：控制端把触摸坐标按画面显示区域归一化发送，受控端映射回真实分辨率后用无障碍服务模拟手势
- **音频**：受控端 AudioPlaybackCapture 采集（48kHz 单声道 PCM16），经 TCP 二进制帧直传，控制端 AudioTrack 播放；缓冲满自动丢帧防止延迟累积

## 自动更新机制（国内优先代理）

应用启动每天静默检查一次；设置页可手动检查。

1. 依次尝试以下源读取 `update/latest.json`（仓库 raw 文件，由 CI 自动维护）：
   `ghfast.top` → `gh-proxy.com` → `ghproxy.net` → `mirror.ghproxy.com` → 直连 raw
2. 版本更新则弹窗展示更新日志，下载 APK 同样按上述代理顺序（断点不重试整包，校验 sha256）
3. 全部失败时兜底走 GitHub Releases API
4. 下载完成 → 校验 → 拉起系统安装器（缺权限时引导授权）

代理列表可在 `update/UpdateModels.kt` 的 `PROXY_PREFIXES` 调整。

## 提交代码 → 自动发版（GitHub Actions）

- **ci.yml**：push/PR 时编译 Debug APK 并上传 artifact
- **release.yml**：push 到 main 时编译 Release/Debug；**若 `versionName` 对应的 `v{versionName}` Tag 不存在**，则：
  1. 用提交记录自动生成更新日志
  2. 创建 GitHub Release 并附上 `ScreenMirror-v{version}.apk`
  3. 自动提交更新 `update/latest.json`（App 内检查更新即读到新版本）

**发版流程**：改 `app/build.gradle.kts` 里的 `versionCode`（+1）和 `versionName` → push 到 main → 自动发版。

> versionCode 必须递增，App 才会提示更新。

### 配置正式签名（可选）

默认未配置时 Release 用 debug 签名（可直接安装）。要正式签名：

```bash
# 1. 生成密钥（只做一次）
keytool -genkeypair -v -keystore keystore.jks -alias screenmirror \
  -keyalg RSA -keysize 2048 -validity 10000

# 2. base64 后配置到仓库 Secrets（Settings → Secrets and variables → Actions）
base64 -w0 keystore.jks   # → KEYSTORE_BASE64
# KEYSTORE_PASSWORD=密钥库密码
# KEY_ALIAS=screenmirror
# KEY_PASSWORD=key 密码
```

## 本地构建

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

要求 JDK 17+，Android SDK（`local.properties` 指向 SDK 路径）。仓库配置在 `gradle.properties`（`repo.owner` / `repo.name`），fork 后改成自己的用户名/仓库名，App 内更新就会指向你的 Release。

## 已知限制（诚实声明）

- 触摸控制依赖无障碍手势 API（Android 7.0+），API 24-25 滑动略生涩，API 26+ 平滑
- 多点触控（双指缩放等）为逐指注入，效果依赖系统实现，不保证完美
- 音频走 TCP 可靠传输，网络抖动时为防延迟累积会丢帧，可能偶有杂音
- WebRTC 未上 STUN/TURN，跨网段（如热点隔离的 AP）可能无法直连
- 直连端口 40000 可能被部分路由器/AP 隔离（可用热点模式规避）

## License

MIT
