# 多行 ControlNet 控制视频

普通分镜和接续片段的 ControlNet 均支持最多三行独立控制源。每行从当前镜头或片段已挂载的参考视频中选择，独立设置启用状态、控制类型、输入方式和强度（0～2）。例如第一行使用已制作的骨架视频，第二行使用已制作的深度视频；两个来源不会拼接或混合成一段控制视频。参考视频数量、总时长等仍受所选模型的已有媒体预算约束。

输入方式：

- `RAW`（原始视频，自动提取）：按该行 `controlType` 调用对应预处理器；`GRAY` 生成单独的灰度副本，不改变普通参考源。`LAYOUT`、`PREPARED` 沿用必须已制作好控制视频的约定。
- `PREPROCESSED`（已处理视频，直接使用）：直接使用上传的骨架、深度、边缘或灰度视频，保留声明的控制类型，不再次提取或转灰度。只需控制模型的直入能力，不要求该类型的提取器已安装。

普通镜头保存到 `draft.controlNet`，也可通过普通渲染请求的 `controlNet` 提交：

```json
{
  "enabled": true,
  "controls": [
    {"enabled": true, "referenceId": "pose-video-id", "controlType": "POSE", "inputMode": "PREPROCESSED", "strength": 0.8},
    {"enabled": true, "referenceId": "depth-video-id", "controlType": "DEPTH", "inputMode": "PREPROCESSED", "strength": 0.4}
  ]
}
```

接续片段使用原有总开关 `controlNetEnabled`，多行保存到 `draft.segments[].controlInputs`，每行字段与上例 `controls[]` 相同。每行的 `referenceId` 必须是本段 `referenceVideos[].id`，不能引用素材托盘、其他片段或其他镜头中未挂载的素材。每个启用来源都要覆盖本段真实输出范围；时间轴和画幅应相互对齐。

前端可增删、单独启停每行。移除视频时仅清空并禁用引用该视频的行，其他行保持原配置。删除所有控制行或禁用全部行后不创建控制分支。更改任一控制来源、类型、输入方式或强度都会进入新计划/任务快照。

网关传输为 `h3_options.control_net.controls[]`（行字段使用 snake_case）。为每个启用行创建独立加载/可选提取/ControlNet Apply 节点，按行顺序将 Apply 输出接至下一个 Apply，最后接入所有采样模型使用者。共用一个权重加载节点；各行视频和预处理结果保持独立，不增加视频合成或叠图节点。多个控制分支仍有额外计算及显存开销，实际组合效果需以 GPU 成片验收。

兼容约定：普通镜头旧的 `enabled/referenceId/controlType/strength`、接续片段旧的 `controlVideoId/controlType/controlStrength` 继续作为单行读取。新数组为 `null`/缺失才使用旧字段；显式空数组表示已清空，不能恢复旧控制源。旧任务快照不批量改写；网关幂等指纹会移除旧请求新增的空数组字段，保持旧请求序列化语义。工作流版本变化后，新任务按现有流程重新预览。
