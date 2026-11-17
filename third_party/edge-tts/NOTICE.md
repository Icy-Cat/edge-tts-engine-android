# 第三方来源与许可证声明

这份说明只记录已经核实的上游事实，以及本仓库里哪些文件是修改后的移植。它不是法律意见，也不表示这些安排已经满足全部发布义务。

## 上游

- 项目：rany2/edge-tts
- 仓库：https://github.com/rany2/edge-tts
- 对照版本：Git 标签 `7.3.1`，提交 `360fa8e1de354e1dda74c09bd695887d6a44df74`
- 作者信息以上游 `setup.cfg` 为准：`author = rany`，`author_email = ranygh@riseup.net`。被移植的 Python 文件自身没有逐文件版权行。
- 许可证原文：
  - https://github.com/rany2/edge-tts/blob/7.3.1/LICENSE
  - https://github.com/rany2/edge-tts/blob/7.3.1/gpl-3.0.txt
- 上游 `setup.cfg` 的分类是 `License :: OSI Approved :: GNU Lesser General Public License v3 (LGPLv3)`。

`LICENSE` 开头写明：MIT 只用于 `src/edge_tts/srt_composer.py`；其余文件以 LGPLv3 授权。文本没有写 “or any later version” 或 “或任何后续版本”。因此这里把适用范围记为 **GNU LGPLv3 only**（SPDX：`LGPL-3.0-only`），不是 `LGPL-3.0-or-later`。LGPLv3 把 GNU GPL v3 的条款并入，所以 GPL 文本一并附上。

本仓库没有移植 `srt_composer.py`。`LICENSE` 里的 MIT 许可和其中的 Christopher Down / rany 版权行只属于那个文件，不覆盖下面列出的移植代码。

## 本仓库中的许可证文本

这两份文件与上述上游标签中的文件逐字节相同，也与本次从 `master` 读取的文本相同。`gpl-3.0.txt` 还与 https://www.gnu.org/licenses/gpl-3.0.txt 逐字节相同。

| 本仓库路径 | SHA-256 |
| --- | --- |
| `third_party/edge-tts/LICENSE` | `3613045a46a5bce847f80eadbbf013e4260ef19d728ffd061d61e792689ec3a8` |
| `third_party/edge-tts/gpl-3.0.txt` | `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986` |

## 受 LGPLv3 only 约束的移植

下列文件是对上游库的修改移植，不是独立创作。约束它们的是上游已经给出的 LGPLv3 only，本声明不把它们改成别的许可证。

| 本仓库文件 | 上游文件（标签 7.3.1） | 上游 URL |
| --- | --- | --- |
| `edge-protocol/src/main/kotlin/io/github/icycat/edgetts/protocol/SecMsGec.kt` | `src/edge_tts/drm.py` 中的 v2 摘要 | https://github.com/rany2/edge-tts/blob/7.3.1/src/edge_tts/drm.py |
| `edge-protocol/src/main/kotlin/io/github/icycat/edgetts/protocol/Drm.kt` | `src/edge_tts/drm.py` 的时钟偏差 | 同上 |
| `edge-protocol/src/main/kotlin/io/github/icycat/edgetts/protocol/EdgeProtocol.kt` | `constants.py`、`communicate.py`、`exceptions.py`、`data_classes.py` 中的协议、SSML、分句和异常 | https://github.com/rany2/edge-tts/blob/7.3.1/src/edge_tts/constants.py 、 https://github.com/rany2/edge-tts/blob/7.3.1/src/edge_tts/communicate.py 、 https://github.com/rany2/edge-tts/blob/7.3.1/src/edge_tts/exceptions.py 、 https://github.com/rany2/edge-tts/blob/7.3.1/src/edge_tts/data_classes.py |
| `edge-protocol/src/main/kotlin/io/github/icycat/edgetts/protocol/EdgeClient.kt` | `communicate.py` 的流式会话和 403 校正，以及 `voices.py` 的列表重试 | https://github.com/rany2/edge-tts/blob/7.3.1/src/edge_tts/communicate.py 、 https://github.com/rany2/edge-tts/blob/7.3.1/src/edge_tts/voices.py |
| `edge-protocol/src/main/kotlin/io/github/icycat/edgetts/protocol/EdgeVoice.kt` | `voices.py` 使用的语音列表字段的一部分 | https://github.com/rany2/edge-tts/blob/7.3.1/src/edge_tts/voices.py |

核对结果：`communicate.py`、`exceptions.py`、`voices.py` 在标签 `7.2.8` 与 `7.3.1` 上内容相同。`drm.py`、`constants.py`、`data_classes.py` 在 `7.3.1` 才是当前 v2 行为；其中 `drm.py` 与 `constants.py` 也与 2026-10-14 的 `master` 提交 `bd44c1d1aaebdc1db7f47456ee12431b42ec7cac` 相同。该 `master` 提交里的 `communicate.py` 只改了 Python 同步封装的错误传递，本移植没有包含那一处，因为它不实现那条同步封装。

## 修改

移植发生在本仓库，初次提交日期为 2026-10-02，Sec-MS-GEC v2 对齐日期为 2026-11-17。相对上游的实现差异：

- 由 Python/aiohttp 改写为 Kotlin/OkHttp。
- 不手工发送 `Accept-Encoding: br, zstd`，避免 OkHttp 无法解压。
- 中文等无空格长文本按 4096 字节上限再切分。
- 不实现字幕、词边界和 offset 补偿。
- `Sec-MS-GEC` 使用 `7.3.1` 的 v2 算法和 `2-<版本>`，不再使用 `7.2.8` 的纯 SHA-256 与 `1-` 前缀。

## 不是上游移植、但属于本项目的部分

下面这些不是上述 Python 文件的移植：

- `app/` 下的 Android 系统 TTS 服务、设置界面、`MediaCodec` 解码、偏好和语音列表缓存。
- `edge-protocol` 中的 `Prosody.kt`、`Mp3FrameParser.kt`、`SmokeTest.kt`、`SynthesisErrors.kt`，以及各测试文件。
- `app/src/main/assets/voices.json`（接口返回的语音列表快照，不是上游源码）。
- Gradle Wrapper 与构建脚本。

仓库版权人已于 2026-11-17 选择把**整个项目**（包含这些独立部分和上面的移植）以 GNU LGPLv3 only 提供，见仓库根目录的 `LICENSE`。这不是 `LGPL-3.0-or-later`。许可证文本第 6 节里的 “or any later version” 只在版权人写明后续版本也适用时才生效；本项目没有这样写明。

`app/` 与 `edge-protocol` 打进同一个 APK。接收者可以用本仓库或 Release 所附的对应源码归档重新构建。这不是法律意见。上游 `srt_composer.py` 的 MIT 条款仍然不覆盖本仓库代码。
