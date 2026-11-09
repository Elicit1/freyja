# 分镜接续与参考视频控制的 MCP 接入

接续和参考视频控制复用工作台的服务、素材准备和串行渲染队列。外部 MCP 与内部 Agent 均从已发布能力平台发现工具；后端升级启动时增量补充这些能力及两类消费者绑定，保留已有的停用状态、自定义说明和待发布草稿。没有原始工具注册表兜底。

这里的“提取”指参考视频的 ControlNet 预处理：选择深度、骨架、灰度等类型后，在渲染过程中自动提取并应用。没有独立导出控制视频的工具。ControlNet 默认关闭，有参考视频时也可以不启用。

## 工具及调用顺序

| 工具 | 用途 |
| --- | --- |
| `get_shot_continuation_options` | 当前段长档位、启用景别/运镜、配套提供商与视频模型；指定提供商和模型后读取实际节点能力、控制类型、尺寸及画幅 |
| `get_shot_continuation` | 草稿、生成模式、当前 `editFingerprint` |
| `plan_shot_continuation` | `auto=true` 自动按帧分段；`false` 校验手动计划；返回草稿而不保存 |
| `split_shot_reference_video` | 按当前片段时间轴分配参考视频源范围；返回草稿而不保存 |
| `save_shot_continuation` | 按指纹保存完整草稿及片段配置 |
| `preview_shot_continuation` | 校验模型、节点和草稿，返回 `renderable`、`issues`、`planHash` |
| `prepare_shot_continuation` | 为已保存且通过预览的接续计划准备素材 |
| `get_shot_continuation_preparation` | 查询准备状态，必须等到 `READY` |
| `render_shot_continuation` | 使用相同计划、模型、尺寸提交现有渲染队列 |
| `get_shot_continuation_render` | 查询镜头视频任务状态及最终地址，也可查询普通镜头视频任务 |
| `prepare_shot_reference_video` | 普通参考模式的已保存参考视频预检和素材准备 |
| `get_shot_reference_video_preparation` | 查询普通参考视频准备状态 |
| `render_shot_video` | 提交未开启接续的普通镜头，最多 15 秒 |

每次调用均传 `dramaId`、`shotId`。写操作还需稳定的 `requestId` 及最新 `expectedFingerprint`，后者来自读取结果的 `editFingerprint`。同一请求重试沿用原 `requestId` 和完全相同的输入，不重复发起渲染；输入变化使用新请求 ID。写权限和项目范围仍由 MCP 配置及已发布能力规则约束。

接续调用顺序：读取选项和草稿 → 自动规划或手动编辑 → 按需切分参考视频 → 保存 → 预览 → 准备并轮询 `READY` → 渲染 → 查询任务。外部 Agent 根据创作者要求填写片段内容，不通过这些工具再次调用内部 AI 规划。生成操作需要创作者授权。

只修改草稿不需要访问 GPU。关闭接续可以保留长草稿，但普通镜头生成仍受 15 秒限制。重新开启时需校验片段内当前选项。后台素材准备只在保存操作事务提交后派发；事务回滚不启动准备任务。

## 参考视频的处理设置

接续模式按片段配置，例如把以下字段设在相应 `draft.segments[]` 上，且 `referenceVideos` 内必须已有同 ID 的参考视频：

```json
{
  "controlNetEnabled": true,
  "controlVideoId": "video-segment-01",
  "controlType": "DEPTH",
  "controlStrength": 1
}
```

普通镜头在 `draft.controlNet` 保存：

```json
{
  "enabled": true,
  "referenceId": "video-01",
  "controlType": "GRAY",
  "strength": 1
}
```

`referenceId` 必须属于该镜头已保存的参考视频；多视频时必须明确选择控制源。普通模式保存后使用 `prepare_shot_reference_video`，准备完成后把 `preflightId` 传给 `render_shot_video`。普通素材选择仍通过现有镜头编辑工具保存。

协议类型包括 `CANNY`、`DEPTH`、`POSE`、`HED`、`MLSD`、`SCRIBBLE`、`LAYOUT`、`GRAY`、`PREPARED`。指定提供商与模型后返回的 `controlTypes` 才表示当前节点支持的类型。`LAYOUT` 需要预先制作的布局视频；`PREPARED` 表示已处理的控制视频。人工验收标记已移除；旧 UNVERIFIED/VERIFIED 状态不再控制提交。节点离线、缺少所选节点/权重或未支持的组合仍按实际能力检查。

分段视频绑定只作用于指定片段，原始资源托盘不参与渲染。切分保留真实重叠，不拉伸或循环不足的源视频。`targetSegmentIds` 可省略以作用于全部片段；显式传入时必须来自当前草稿且不重复。片段参考图需显式选择 `usageRole`，不通过文字猜用途。

## 选项与身份校验

- 所有业务 ID 使用原始十进制字符串，含返回草稿中的嵌套角色、造型、来源镜头及 Take ID。
- 输入 Schema 拒绝未知字段和未知枚举；景别、运镜字典按调用时的启用选项校验。
- 提供商和模型必须启用、相互匹配并支持镜头当前生成模式；尺寸和画幅遵循模型配置。
- 角色、造型、场景、道具、关键帧及音频必须有效且属于公共库或当前项目；造型须属于所选角色，镜头专属关键帧不能跨镜头使用。
- 参考视频镜头/Take 来源必须属于当前项目，控制源和目标片段不能引用已删除的选项。
- 素材准备 ID 和渲染任务 ID 均需属于当前授权镜头；查询渲染状态不返回内部任务请求快照。

REST 工作台和 MCP 共用上述服务端校验。前端同时清除已失效的提供商/模型选择，并把生成尺寸限制为当前模型允许的下拉选项。
