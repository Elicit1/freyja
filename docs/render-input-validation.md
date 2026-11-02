# 视频渲染输入预检

单镜、批量及失败重试都在入队前校验最终提示词和服务端冻结的素材清单。执行时在邻镜抽帧完成后再次校验；网关在提交 ComfyUI 前核对实际工作流连接。检查不调用 AI，不推断剧情、角色身份或运镜语义，不自动改词、换图、重排编号或丢弃素材。

## 接口

`POST /drama/shot/{id}/render-precheck` 接收与单镜 `/render` 相同的请求（providerId、workflowTemplateId、prompt、size、已有参考视频 preflightId）。此接口不创建渲染任务、不保存提示词或种子，不启动模型生成；参考视频仍需先走既有预处理流程。

返回 `R<RenderValidationReport>`：

```json
{
  "submissionReady": false,
  "dispatchReady": false,
  "rulesVersion": "h3-render-input/1",
  "inputFingerprint": "...",
  "slots": [{"label":"<Picture 1>","referenceId":"image-1","kind":"IMAGE","role":"REFERENCE_IMAGE","source":"...","pending":false}],
  "issues": [{"code":"REFERENCE_NOT_ATTACHED","shotId":"2032095628944588801","label":"<Picture 2>","field":"prompt","line":8,"message":"提示词引用了 <Picture 2>，但本次未挂载该素材","suggestion":"补充对应素材，或修改提示词引用后重新检查"}]
}
```

正式 `/render` 可携带 `expectedRenderFingerprint`，输入变化返回业务码 409；即使未携带指纹也必须执行校验。校验失败返回业务码 422，`data` 为报告。沿用项目 `R` 约定，业务错误 HTTP 状态为 200。所有实体 ID 对外仍为字符串。

批量 `/drama/video-render-batches/preview` 每项新增 `validation`。保留 `READY / BLOCKED / SKIPPED`：只有本次待渲染集合没有 BLOCKED 时才能创建批次；既定策略跳过的旧视频/活动任务不要求再次校验。创作者可以明确排除错误镜头并重新检查。正式提交仍重新检查并比对 `expectedPreviewHash`，拒绝时不创建任何批次或渲染任务。

原始快照重试也执行相同检查。仅恢复已生成视频的下载、归档不重新生成视频，也不因历史提示词不合规而阻止恢复。

## 协议规则

- 按生成模式检查完整、非空且顺序正确的六段式（REFERENCE_MODE）或三段式（FIRST_LAST_FRAME）正文。首尾帧模式还要求正式对齐声明明确 Picture 1、Picture 2。
- 解析精确 `<Picture N>`、`<Audio N>`、`<Video N>` 和 `<Subject N>`；编号从 1 开始。不允许用 Image/Ref/Frame 等别名、错误大小写、前导零或正文裸编号代替正式标签。首尾帧对齐声明中的裸 Picture 编号按专门语法解析。
- 按集合比较，不按出现次数计数：引用不存在的素材、挂载未引用的素材均阻止提交。Subject 独立于媒体数量，必须在 subject_definitions 中以 `<Subject N> is ...` 或冒号定义，不能重复定义。
- `<d>...</d>`、英文双引号和中文双引号包围的字面文字不声明素材引用；保留原文偏移和行号。带引号的展示文字可以作为段落内容，但不会满足素材绑定。
- 首尾帧模式只消费两个显式帧输入，要求两者均已配置或有有效的邻镜来源；额外参考图片/音频必须移除或切换模式。参考模式不会隐式追加分镜首尾帧。
- 同一文件绑定两个槽位时保留两个槽位，禁止按文件地址或内容去重后改变编号。
- 图片独立从 Picture 1 编号；视频从 Video 1 编号；视频附带原音先占 Audio 槽位，随后才是独立音频。提示词生成时的 ReferenceManifest 使用相同顺序。

`submissionReady=true, dispatchReady=false` 表示邻镜来源计划有效，可以入队但尚不能发送渲染请求。必须等抽帧完成再复核；前序任务失败时停止依赖镜头，不静默改用旧 Take，其余独立任务继续。队列只消费提交快照，不重新解析可变的资产链接。

指纹包含规则版本、模式、提示词、有序槽位、素材配置、时长、尺寸、邻镜来源计划及模型指纹。不包含生成时刻和参考视频预检的随机标识。无需数据库迁移，新字段随现有 JSON 快照保存；旧快照在执行和重试时重新检查。

## 网关契约及部署

Java 的视频请求新增 `render_input_contract`，包含版本、有序槽位的 label/kind/role/referenceId/媒体传输值 SHA-256，以及提示词 SHA-256。网关上传前校验传输清单，组装后检查图片、音频、视频及原音的槽位数、顺序、Load 节点来源、实际提示词，以及条件节点是否连接到视频输出。人脸修复新契约路径的两阶段共享相同素材编号，最终输出音轨仍沿用第一阶段结果。

Java、前端和 FastAPI 网关需要一起更新。声明 `MINIMAX_H3_*_V1` 格式的网关请求必须携带该契约；没有声明此格式的旧通用图片/视频调用保持原协议。网关检查失败不会提交 ComfyUI 或启动 GPU 生成。规则只保证协议和挂载一致，不保证模型最终生成质量。

## 边界

自由文本“三张图”“这个人物”不属于媒体引用语法，程序不会推断其数量或指代。正式标签齐全但素材在提示词生成前后被等量换成人物错误的图片，无法单靠标签判断；提交预检后的变更通过指纹拦截，图片语义一致性仍由 AI/创作者审查。

当前不增加基于关键词的运镜、情绪、动作和衣服推断，也不扩展到对白语义或完整时间线质量审查。
