# LanCast

[![Android compatibility build](https://github.com/Lvbey/lancast-android/actions/workflows/android.yml/badge.svg)](https://github.com/Lvbey/lancast-android/actions/workflows/android.yml)

面向 Android TV、投影仪和手机的开源局域网投屏项目。Receiver 支持 Android
4.2–16，Sender 支持 Android 5–16；两个 APK 都是同时兼容 32 位与 64 位设备的
Universal APK。详见 [BUILD-COMPATIBILITY.md](BUILD-COMPATIBILITY.md)。

一个不依赖 Google Cast/Miracast 的 Android 局域网投屏原型：

- `receiver`：安装到投影仪，监听 TCP 53516 并硬解 H.264。
- `sender`：安装到 Android 手机，经系统 `MediaProjection` 授权后硬编码并发送屏幕。
- `receiver` 同时实现 DLNA/UPnP MediaRenderer。优酷、腾讯视频、哔哩哔哩等支持
  “投电视”的应用可以直接搜索 `LanCast`，无需安装 sender。

目标投影仪：LanCast，Android 9 / API 28 / armeabi-v7a。工程完全使用 Android
Framework API，没有 JNI 或 ABI 相关的 `.so`，因此兼容 armeabi-v7a。

receiver 最低支持 Android 4.2 / API 17。API 21 以下使用旧版 `MediaCodec` 缓冲区接口，
并以 1920×1080 作为保守解码能力上限。

receiver 申请 `RECEIVE_BOOT_COMPLETED` 并监听标准及常见 TV 快速启动广播，设备开机后
自动进入接收界面。安装后需至少手动启动一次，系统才会解除应用的 stopped 状态并允许
接收开机广播。

## 构建

需要 JDK 8、Android SDK 33 和 Gradle 6.7.1：

```powershell
gradle :receiver:assembleDebug :sender:assembleDebug
```

产物：

- `receiver/build/outputs/apk/debug/receiver-debug.apk`
- `sender/build/outputs/apk/debug/sender-debug.apk`

## 使用

1. 将 receiver APK 安装到投影仪，启动后记下画面显示的 IP。
2. 将 sender APK 安装到 Android 手机。
3. 保证两台设备位于同一 Wi-Fi，路由器未启用 AP/客户端隔离。
4. 手机会通过 NSD/mDNS 自动发现 `LanCast-xxx`；也可以手动输入 IP。
5. 选择自动、720p、1080p、2K 或 4K，点击“开始投屏”，接受系统录屏授权。

连接建立时双方会先交换 H.264 硬件能力，最终分辨率不会超过手机编码器或投影仪
解码器报告的上限。选择 4K 不代表设备一定能硬编/硬解 4K，不支持时会自动降级。
能力匹配按画面长边/短边判断，因此 `1920×1080` 接收能力也可接受 `1080×1920`
一类竖屏视频尺寸。

首版只发送视频画面，不发送系统音频。Android 9 发送端无法通过公开 API 捕获内部音频；
Android 10+ 可以在后续版本增加 `AudioPlaybackCapture`。

协议 v3 在 Android 10+ 发送端使用 `AudioPlaybackCapture` 捕获允许录制的内部音频，
编码为 AAC-LC 48 kHz 双声道；接收端使用 `MediaCodec` 和 `AudioTrack` 播放。
源应用可以通过系统策略或 DRM 禁止捕获自身音频。

DLNA 模式由投影仪直接请求并播放发送端提供的媒体 URL，支持播放、暂停、停止、Seek
和音量控制。受 DRM、会员鉴权、临时 URL 及 Android 9 `MediaPlayer` 格式能力影响，
并非所有影音 App 或视频都保证可播放。

DLNA 音量使用独立的软件音量，按 5% 步进调整。运行状态不覆盖视频画面，调试信息仅
写入应用内部的 `dlna.log` 与 `lancast.log`。

DLNA 播放会读取媒体的实际宽高并等比例居中显示，竖屏视频保留两侧黑边，不拉伸铺满
投影仪的 16:9 画面。

DLNA 播放期间支持投影仪遥控器：左/右跳转 10 秒，快退/快进跳转 30 秒，OK 或媒体
播放暂停键切换播放状态，音量键按 5% 调整。

## 网络协议

TCP 连接建立后使用大端序：

1. `int32 magic`：`0x4C434153` (`LCAS`)
接收端首先回复：magic、version 2、最大宽、最大高。发送端随后发送：
magic、version 2、实际宽、实际高，再重复视频包：
`int32 length`、`int32 MediaCodec flags`、`int64 ptsUs`、H.264 数据。

这是一套可信局域网原型协议，当前没有加密或设备认证，请不要暴露到公网。
