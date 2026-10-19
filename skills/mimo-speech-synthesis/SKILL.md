---
name: mimo-speech-synthesis
description: 编写 Xiaomi MiMo V2.5 语音合成、音色设计与声音克隆的请求 JSON。在 FREYJA 配音中心创建声音、调用 dubbing_create_audio 或控制配音情绪、语气、停顿和音频标签前读取。
---

# MiMo 语音请求

依据 [MiMo 官方使用文档](https://mimo.mi.com/docs/en-US/quick-start/usage-guide/audio/speech-synthesis-v2.5)。在 FREYJA 操作时同时读取 [平台输入约定与示例](references/freyja.md)。

在平台准备视频音色参考时，先查询正确角色的已有配音，按情绪与声音特效需求优先复用；样本旧台词不同不要求重新生成。只有缺少合适样本，或用户明确要求 H3 `fully_copy` / `partially_copy` 而需要当前镜头的新音轨时，才编写以下生成请求。复制音轨须检查生成后的真实音频时长能否放入视频时间窗。

## 消息与标签

- 要说出的台词放在 `messages` 中 `role: assistant` 的 `content`。`user.content` 承载音色描述、场景与表演指导，不作为播报台词。
- 所有风格与音频标签都写在 `assistant.content`。整体风格置于台词最开头，例如 `(愤怒 克制)我已经说得很清楚了。` 多个风格写在同一对括号中；支持 `()`、`（）`、`[]`，也支持自定义风格。
- 局部控制写在对应位置，例如 `(悲伤)我一直在等你。[哽咽]你却没有回来。` 不把标签移到 `user`，也不依赖独立的 `emotion` 参数。
- 可另用 `user.content` 描述语速、气息、停顿与重音。按创作者要求选择表达，不把“愤怒”自动等同于喊叫；保留明确指定的台词及标签。

## 模型选择

- `mimo-v2.5-tts`：使用预置音色。
- `mimo-v2.5-tts-voicedesign`：`user` 必须给出音色设计描述；通常用 `assistant` 给出试音台词。`audio.optimize_text_preview=true` 时可省略 `assistant`，但不得声称知道模型实际生成的台词。
- `mimo-v2.5-tts-voiceclone`：基于参考录音克隆音色，支持上述表演指令和标签。克隆不是声音设计，不改用 VoiceDesign 来完成克隆。

## 调用边界

直接调用 MiMo 时，克隆参考为 `audio.voice` 的 `data:audio/wav;base64,...` 或 MP3 data URI，编码后不超过10 MB；不传文件路径。FREYJA 会绑定角色基础录音，无需在 JSON 提供此字段。

声音设计与克隆不支持唱歌模式。声音设计和克隆的低延迟流式输出尚未提供；FREYJA 使用非流式 WAV 归档。模型能控制表演不代表每次生成都达到预期，检查任务结果并试听后再确认。
