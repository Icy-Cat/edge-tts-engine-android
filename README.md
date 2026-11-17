# Edge TTS 引擎（Android）

[![License: LGPL-3.0-only](https://img.shields.io/badge/license-LGPL--3.0--only-blue.svg)](LICENSE)
![minSdk 24](https://img.shields.io/badge/Android-7.0%2B-brightgreen.svg)

把微软 Edge「大声朗读」的神经网络语音（`zh-CN-XiaoxiaoNeural` 等 300+ 种）注册成 **Android 系统 TTS 引擎**。阅读器、导航、屏幕朗读等任何支持系统 TTS 的应用都能直接选用。

> [!WARNING]
> 这是**非官方**客户端，与微软无关，没有服务等级保证。接口随时可能限流、改协议或拒绝连接。本项目不保证这种调用方式符合微软服务条款，请自行判断。

## 为什么有这个项目

`jing332/tts-server-android`、`yynag/edge-tts-android`、`ag2s20150909/TTS` 等旧项目没有实现现行的 `Sec-MS-GEC` 校验，握手直接返回 `403`。本项目对照 [rany2/edge-tts](https://github.com/rany2/edge-tts) 7.3.1 实现了当前的 **Sec-MS-GEC v2**（版本前缀 `2-`）。

## 安装

1. 从 [Releases](../../releases) 下载 APK 安装（Android 7.0+）
2. 打开「Edge TTS 引擎」，点「试听」确认能出声
3. 在系统设置里把它设为首选引擎（见下表）

| 系统 | 路径 |
| --- | --- |
| 原生 Android / Pixel | 设置 → 系统 → 语言和输入法 → 文字转语音输出 → 首选引擎 |
| 小米 / Redmi（MIUI / HyperOS） | 设置 → 更多设置 → 无障碍 → 文字转语音（TTS）输出 → 首选引擎 |
| 三星 | 设置 → 常规管理 → 语言和输入法 → 文字转语音 → 首选 TTS 引擎 |
| 其它 | 在设置里搜索「文字转语音」或「TTS」 |

选好后，引擎旁的齿轮图标会打开本应用设置页。

> [!NOTE]
> - 当前发布的 APK 使用 Android debug 签名，和旧版本不是同一把钥匙，**升级前需先卸载旧版**。
> - MIUI / HyperOS 等 ROM 会杀后台：建议把本应用的省电策略设为「无限制」并开启「自启动」。

## 功能

- 系统 TTS 引擎全套回调：语言查询、语音列表、默认语音、停止；系统的 ISO-3 语言码（`zho/CHN`）与 Edge 的 `zh-CN` 自动互转
- 系统语速 / 音调映射到 SSML，设置页可再叠加偏移
- 设置页：按语言选默认语音（默认 `zh-CN-XiaoxiaoNeural`）、试听、查看最近一次错误
- 流式解码：MP3 边收边用 `MediaCodec` 解成 PCM16 / 24 kHz / 单声道，首包更快
- 语音列表本地缓存，每天最多刷新一次；APK 内置快照，离线也能回答「支不支持这个语言」
- 短文本（≤400 字符）PCM 内存 LRU 缓存（6 MB）
- 网络健壮性：
  - 时钟偏差自适应：403 时按服务器 `Date` 校正一次再重试
  - 429 / 5xx / 连接错误在尚未输出音频前指数退避，最多 3 次
  - 60 秒没有音频帧判超时；停止会立即关闭 WebSocket

## 隐私

- 合成时，**待朗读文本、语音、语速、音调**会发送到 `speech.platform.bing.com`。不支持离线合成。
- TTS 服务按 Android 要求必须导出，设备上**任何应用**都可以把文本交给本引擎，这些文本同样会发往微软。
- 合成结果只缓存在进程内存，不写磁盘；语音列表缓存不含朗读文本。
- 没有账号、统计或广告 SDK，应用数据不参与云备份。

## 构建

需要 JDK 17+ 和 Android SDK（platform 34、build-tools 34.0.0）。

```bash
export ANDROID_HOME=/path/to/android-sdk   # 或在 local.properties 写 sdk.dir=...
./gradlew test                             # JVM 单元测试
./gradlew assembleDebug                    # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew :edge-protocol:smoke             # 可选：真实联网冒烟测试，会向微软发一句中文
```

国内网络访问不了 `dl.google.com` 时，加上阿里云镜像脚本：

```bash
./gradlew -I scripts/cn-mirror.init.gradle assembleDebug
```

### 目录结构

| 模块 | 说明 |
| --- | --- |
| `edge-protocol/` | 纯 JVM Kotlin，无 Android 依赖：协议、Sec-MS-GEC、WebSocket 客户端、MP3 切帧、冒烟测试 |
| `app/` | Android 部分：`EdgeTtsService`、`MediaCodec` 解码、语言匹配、语音列表、设置页 |

## 协议失效时怎么更新

出现持续 403 时，多半是微软改了版本号或算法。常量都在 `edge-protocol/src/main/kotlin/io/github/icycat/edgetts/protocol/`：

| 常量 / 位置 | 对照上游 |
| --- | --- |
| `EdgeProtocol.kt` 的 `CHROMIUM_FULL_VERSION`、`TRUSTED_CLIENT_TOKEN`、URL、请求头 | [`constants.py`](https://github.com/rany2/edge-tts/blob/master/src/edge_tts/constants.py) |
| `SecMsGec.kt` 的 v2 摘要 | [`drm.py`](https://github.com/rany2/edge-tts/blob/master/src/edge_tts/drm.py) 的 `generate_sec_ms_gec_v2` |

步骤：

1. 按上游最新 `constants.py` / `drm.py` 改常量或算法
2. 用上游 `generate_sec_ms_gec_v2(<ts>)` 重新生成 `EdgeProtocolTest` 里的测试向量
3. 跑 `./gradlew test`，再跑 `./gradlew :edge-protocol:smoke`，握手应为 `101`
4. 提升 `app/build.gradle.kts` 的 `versionCode` / `versionName`

与上游的实现差异：不手写 `Accept-Encoding`（交给 OkHttp 处理 gzip）；无空格长文本（如中文）按 4096 字节上限切分；不输出字幕和词边界。

## 已知限制

- 只输出 `audio-24khz-48kbitrate-mono-mp3` 一种格式
- 不支持 SSML 直通（系统传入的文本按纯文本转义）
- 弱网下首包延迟明显

## 更新日志

- **0.1.2**：对齐 Sec-MS-GEC v2；修复停止状态被下一次合成清掉；WebSocket 空闲超时与取消；语音列表原子写入；错误信息不再包含请求 URL。
- **0.1.1**：修复服务创建即崩溃（`onCreate()` 内回调 `onLoadLanguage()` 时引擎尚未初始化）。
- **0.1.0**：首个版本（有上述崩溃，请勿使用）。

## 许可证

[GNU LGPL v3.0 only](LICENSE)（SPDX `LGPL-3.0-only`，不是 or-later）。

`edge-protocol` 中部分文件是 [rany2/edge-tts](https://github.com/rany2/edge-tts) 7.3.1 的 Kotlin 移植，上游同为 LGPLv3。文件对照与署名见 [`third_party/edge-tts/NOTICE.md`](third_party/edge-tts/NOTICE.md)。
