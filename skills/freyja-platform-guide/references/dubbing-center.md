# 配音中心与参考音频

配音中心是角色声音的维护入口：角色配音 `/dubbing/characters`，声音特效 `/dubbing/effects`。角色资产只提供跳转按钮。`ResCharacter.voiceSampleUrl/voiceSampleText/voiceDesc` 为基础声音的只读兼容投影，不能通过人物 CRUD 修改。

## 按剧情准备声音

1. 对每个发声角色调用 `dubbing_list_audios`，确认基础声音、已有台词版本和情绪/特效标签。工具使用授权短剧 `dramaId` 和角色 `characterId`，业务 ID 全部为字符串。
2. 没有基础参考音频时，先查 `list_voice_design_models`，选择已启用的 MiMo VoiceDesign 模型。用 `dubbing_create_audio` 提交 `method=DESIGN`、`modelCode=mimo-v2.5-tts-voicedesign`、明确的 `providerId`、`name`、短试音 `text` 和 `voiceDesc`。不要提交情绪或特效。基础声音必须为清晰的单人干声，建议5至10秒。
3. 已有基础声音时，按当前剧情所需情绪、通话或无线电等明确特效需求检索已有配音。优先复用合适版本；匹配情绪并不意味着台词也匹配，要读取实际 `text`。
4. 缺少适合当前台词/情绪的版本时，用 `dubbing_create_audio`，`method=CLONE`、`modelCode=mimo-v2.5-tts-voiceclone`，明确传入台词与启用的情绪选项。克隆始终使用该角色基础声音。特效从 `dubbing_list_effects` 的已启用模板中选择，使用有序 `effects` 数组。
5. 调用 `dubbing_get_audio` 检查 `audio.audio.status` 与关联任务；任务未完成时不宣称成品已就绪。返回 `READY` 才能将相应成品用于分镜。不要凭空构造 URL、时长或效果名称。
6. 仅特效需要变化时，用 `dubbing_update_effects`，提交最新 `revision` 和有序效果配置；复用保存的克隆原始音频，由 FFmpeg 重新处理，不调用 TTS。修改台词或情绪需要创建新的克隆表演。
7. 不再需要特效时，提交 `effects=[]`。播放器立即使用保存的克隆原始音频，不调用 FFmpeg 或 TTS。特效失败时原声仍然存在，但不能将原声称为已完成的特效成品。

## 音频关系

- 每个角色最多一个有效基础参考音频，基础声音不能带特效。
- 每条克隆配音保留 `rawAudioUrl`；特效成品保存为独立的 `processedAudioUrl`。切换或修改特效始终以干声重新处理，不能在旧成品上反复叠加。
- 情绪属于 TTS 表演；特效属于 FFmpeg 后处理。后端不根据台词关键词猜测两者。
- 多个特效按选择顺序执行。已生成配音保留模板名称、版本与参数快照；模板编辑或停用不会自动改变旧音频。
- 男女双声是同一段表演的原声与变调声混合，不能当作两名独立演员的台词。
- 用户明确要求删除或替换基础声音后，才提交对应操作。基础声音删除会级联删除所有相关克隆；替换先生成成功再切换。仍被分镜引用的音频须先解除引用。

## 自定义上传

`dubbing_upload_base_audio` 支持导入无特效基础参考音频：`audioBase64` 必须是实际文件字节的 Base64，不能传本地路径或 URL。文件最大7 MiB、最长30秒；`text` 填写音频真实台词。上传不会绕过基础声音唯一性；替换需要明确的 `replaceBaseId`，并删除旧基础声音关联克隆。客户端没有读取文件能力时，不声称已上传。

## 分镜选择

- 分镜通过“引入配音中心音频”按角色、情绪、特效选择，保存当时的音频地址和实际时长。
- `VOICE_TIMBRE` 只参考声线，当前台词由分镜剧本决定；基础样音的旧台词不是当前对白。
- `DIALOGUE_REUSE` 用于复用克隆音频中的实际台词，必须核对 `text` 与镜头内容。
- 视频参考音频单段2至15秒，最多3段，总长不超过15秒。配音中心可保存更长台词，不代表视频模型可直接接收。
- 没有台词的镜头按主指南保持 `refAudios=[]`。不要为了“角色有基础声音”自动给所有镜头添加音频。
