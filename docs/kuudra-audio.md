# Kuudra 音频能力与提示音 Controller

实现与可运行示例位于独立同级 Reactor `kuudra-audio-plugins`。

官方音频实现沿用“能力宿主—业务 Resource”分层：

```text
actforever/audio-host
  -> owner-scoped AudioPlaybackLease
      -> actforever/audio-player
          -> Controller/audio-player
              -> play / play-random / pause / resume / stop / set-volume
```

## Audio Host

`audio-host` 是普通的跨平台 Java 插件，不发布 ResourceTemplate、不请求 UAC，也不依赖
Windows Native Host。它以内嵌 Java Sound MP3/Vorbis SPI 将 `.wav/.mp3/.ogg` 解码为 PCM，
并在第一次播放时才打开系统默认输出设备。

声明强依赖的下游插件通过 `AudioHost.acquirePlayback(owner)` 获取租约。每个租约只维护一个
当前音轨，新播放将旧操作终止为 `REPLACED`；不同租约可以并行混音。暂停使用引用计数 token，
因此用户暂停、Resource 生命周期暂停和 Session ExecutionControl 暂停不会互相错误恢复。
关闭租约只清理该 owner，关闭 host 会清理所有租约和工作线程。

## Audio Player

`audio-player` 发布 `controller/actforever/audio-player/audio-player`。每个 Resource 初始化时
从自身插件家目录的 `audio/` 建立静态音频库，track ID 是包含扩展名的相对路径。目录只能位于
插件家目录内，扫描会拒绝越界符号链接和忽略大小写后的重复 ID。文件变化在 Resource 或内核
重启后生效。

Resource options：

- `directory: audio`：相对于插件家目录；
- `recursive: true`：是否递归扫描；
- `defaultVolume: 1.0`：`0.0..1.0`。

`play` 接受 `track`，`play-random` 可用 `tracks` 限定候选；两者还接受 `volume`、`loop` 和
`awaitCompletion`。默认在成功启动音频后完成，`awaitCompletion: true` 则保留 Session lease
直到自然结束、停止或替换。等待期间 Session 暂停会暂停音频，Session 取消会停止播放。

音频文件位于：

```text
<home>/plugins/actforever/audio-player/audio/notify.wav
```

加载 host JAR 本身以及激活 audio-player Resource 都不会播放声音；只有事件进入播放 handler
才会打开设备并产生音频输出。
