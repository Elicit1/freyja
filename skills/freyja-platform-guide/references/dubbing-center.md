# 配音中心与参考音频

配音中心是角色声音的维护入口：角色配音 `/dubbing/characters`，声音特效 `/dubbing/effects`。角色资产只提供跳转按钮。`ResCharacter.voiceSampleUrl/voiceSampleText/voiceDesc` 为基础声音的只读兼容投影，不能通过人物 CRUD 修改。

## 按剧情准备声音

1. 对每个发声角色调用 `dubbing_list_audios`，确认基础声音、已有台词版本、请求 JSON 和特效标签。工具使用授权短剧 `dramaId` 和角色 `characterId`，业务 ID 全部为字符串。
2. 没有基础参考音频时，先查 `list_voice_design_models`，选择已启用的 MiMo VoiceDesign 模型。用 `dubbing_create_audio` 提交 `method=DESIGN`、`modelCode=mimo-v2.5-tts-voicedesign`、明确的 `providerId`、`name`、`modelRequest` JSON。调用前通过 `list_skills` / `read_skill` 读取 `mimo-speech-synthesis`，按其中的声音设计约定组织请求。不要提交旧的 text/voiceDesc/emotion 参数或特效。基础声音必须为清晰的单人干声，建议5至10秒。
3. 已有基础声音时，按当前剧情台词与通话、无线电等明确特效需求检索已有配音。优先复用合适版本；表演要求读取已保存的 `modelRequestJson`，也要读取实际 `text`。
4. 缺少适合当前台词/情绪的版本时，用 `dubbing_create_audio`，`method=CLONE`、`modelCode=mimo-v2.5-tts-voiceclone`，按 `mimo-speech-synthesis` Skill 提交 `modelRequest` JSON，表演标签和台词由请求明确指定。克隆始终使用该角色基础声音。特效从 `dubbing_list_effects` 的已启用模板中选择，使用有序 `effects` 数组。需要修改处理参数时，先用 `dubbing_list_filters` 读取服务器实际可用的滤镜及类型化参数目录。
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

## 特效参数边界

- 首批开放20种处理、86个参数：均衡与音量、压缩/噪声门/限幅/响度、回声/合唱/镶边/颤动、变调/双声/失真、无需外部模型的降噪。可以自由重复添加和排序，不局限于固定搭配。
- 参数支持数字、枚举字符串、布尔和数字数组。严格使用 `dubbing_list_filters` 返回的 `type`、参数键、类型和范围，不自行构造FFmpeg命令或表达式。缺少服务器依赖的处理不可用。
- 每步结构为 `{type,params,enabled}`；`enabled=false` 保留参数、跳过执行。单模板最多12步，最多6个特效、总计24步；数值列表最多4项，同一步对应列表长度必须相同。
- 可调声音处理对现有音频生效；输入输出、采样率、声道、编码和双声内部连接由系统维护。不接受模型或插件路径，不调用外部模型，不生成新的台词或角色表演。`reverb` 是多重短延迟模拟的反射，不能称为真实空间采样混响。
- 参数保存为完整快照。仅调整参数、启用状态或顺序时继续使用克隆原声，不调用TTS；基础参考音频仍禁止特效。

## 自定义上传

`dubbing_upload_base_audio` 支持导入无特效基础参考音频：`audioBase64` 必须是实际文件字节的 Base64，不能传本地路径或 URL。文件最大7 MiB、最长30秒；`text` 填写音频真实台词。上传不会绕过基础声音唯一性；替换需要明确的 `replaceBaseId`，并删除旧基础声音关联克隆。客户端没有读取文件能力时，不声称已上传。

## 分镜选择

- 镜头配置、AI生成提示词和视频渲染的音频新增/替换共用“从配音中心选择参考音频”弹窗。传入当前短剧与可选角色集合；左侧角色分页和人物名称查询，右侧基础声音及按特效筛选的克隆声音分页。每次显式选择，不自动添加基础样音。
- `refAudios` 按提交顺序对应 `<Audio 1>`、`<Audio 2>`。保存 `audioId`、匹配的 `characterId`、当前成品 `audioUrl` 和真实 `duration`。基础、克隆和特效成品都使用相同的四种参考关系，不按平台或来源分类。
- `referenceType` 必填且仅允许：`fully_copy` 完整复用整条音轨；`partially_copy` 只复用明确指定的片段或声音层；`reference` 只参考声线、节奏或风格，不复制音频信号；`weak_reference` 只保留大类或氛围。
- `referenceDescription` 可明确参考内容或复用范围；选择部分复用时应明确片段/声音层。`text` 是原音频转录元数据，不自动成为当前镜头台词；当前台词依据 `scriptContent`。提示词中的关系标记必须与选择一致。
- 不接受音频 `sourceType`、`usageMode` 或旧枚举，不做映射、迁移或默认补值。旧音频配置需要重新选择；图片的 `sourceType` 保留。
- 视频参考音频单段2至15秒，最多3段，总长不超过15秒。配音中心可保存更长台词，不代表视频模型可直接接收。
- 没有台词或明确不使用参考音频时保持 `refAudios=[]`。移除参考不代表静音，不要为角色已有基础声音就自动添加。
