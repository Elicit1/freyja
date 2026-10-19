# FREYJA 配音 JSON

使用 `list_skills` / `read_skill` 读取本技能；需要本文时用 `read_skill_file`。使用 `dubbing_list_audios` 检查角色基础声音与已有音频；通过模型查询选取已启用的提供商和模型，所有业务 ID 保持字符串。

`dubbing_create_audio` 的业务参数为 `dramaId,characterId,requestId,name,providerId,modelCode,method,modelRequest,effects,replaceBaseId?`。`modelRequest` 是 JSON 对象，不是被再次转义的 JSON 字符串。页面 JSON 输入框填写的正是这个对象。

平台原样传递消息和其他模型参数，不解析标签、不拼接情绪、不查询情绪字典。所选 `modelCode` 决定模型；系统绑定 WAV、非流式归档及克隆基础音频。不要提交 `audio.voice`、`stream=true` 或非 WAV 格式；如填写 `model`，必须与所选模型一致。

## 创建基础音色

选择 `method=DESIGN`、`modelCode=mimo-v2.5-tts-voicedesign`、`effects=[]`，填写：

```json
{
  "messages": [
    {"role": "user", "content": "一位青年女性，声线清亮，吐字清晰，使用自然的普通话。单人干声。"},
    {"role": "assistant", "content": "我已经准备好了，等你回来，我们一起出发。"}
  ]
}
```

基础录音应适合复用为角色音色参考。已有基础录音时，只有创作者明确要求替换后才提交 `replaceBaseId`；替换会删除关联克隆。

## 创建带情绪的克隆

已有基础录音后，选择 `method=CLONE`、`modelCode=mimo-v2.5-tts-voiceclone`，填写：

```json
{
  "messages": [
    {"role": "user", "content": "在“回来”后停顿，最后一句轻声说出。"},
    {"role": "assistant", "content": "(悲伤)你终于回来了。[哽咽]我以为再也见不到你了。"}
  ]
}
```

按剧情更换开头风格、局部标签和台词，不必复用示例。情绪要求保存在 `modelRequest`；不要提交旧的顶层 `emotion`、`voiceDesc` 或 `text` 字段。音频返回的 `text` 是最后一条 assistant 消息的原文投影，包含标签，不能假装是清洗后的转录。

`effects` 为独立 FFmpeg 后处理选择。编辑特效复用已归档原声；修改请求 JSON 生成新记录。使用 `dubbing_get_audio` 查询任务至 READY，核对声音后再用于分镜。请求 JSON 会保存用于重试和新版本编辑；旧记录无 JSON 时，由创作者或 AI 显式重新填写，系统不猜测迁移情绪。
