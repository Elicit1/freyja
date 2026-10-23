# 来源、许可与适配边界

本 Skill 是 FREYJA 的中文本地化改编，不是 MiniMax 或 Seedance 官方发布的摄影 Skill。

## Seedance 方法来源

- 上游：[Emily2040/seedance-2.0](https://github.com/Emily2040/seedance-2.0)，`seedance-camera` 6.7.0。
- 固定修订：`4668457e560eee06e95d7fcfdf441c8c0bba802e`。
- [seedance-camera/SKILL.md](https://github.com/Emily2040/seedance-2.0/blob/4668457e560eee06e95d7fcfdf441c8c0bba802e/skills/seedance-camera/SKILL.md)：主导运动、机位与速度、主体关系、端点、冲突和延展状态。
- [cinematography-shot-language.md](https://github.com/Emily2040/seedance-2.0/blob/4668457e560eee06e95d7fcfdf441c8c0bba802e/references/cinematography-shot-language.md)：摄影方案、结尾用途及连续性。
- [directors-read.md](https://github.com/Emily2040/seedance-2.0/blob/4668457e560eee06e95d7fcfdf441c8c0bba802e/references/directors-read.md) 与 [directing-engine.md](https://github.com/Emily2040/seedance-2.0/blob/4668457e560eee06e95d7fcfdf441c8c0bba802e/references/directing-engine.md)：由叙事意图选择摄影与调度，区分叙事和观察/展示任务。
- 上游使用 MIT，版权声明及完整许可随包保留于 `LICENSE`。

## H3 与平台依据

- [MiniMax H3 提示词 Skill](https://github.com/MiniMax-AI/MiniMax-H3/tree/main/skills/h3-prompt-writing)，运行时以平台当前发布的同名 Skill 为准，按需读取基础和参考模式规范。
- `freyja-platform-guide` 的 H3 接入、声音设计、资源限制，以及当前业务 Schema 和工作流配置。

保留了上游“目的先于技术、明确摄影路径、结果或续接端点”的方法。重写为中文制作指导，补充自然拆镜、动作状态交接、FREYJA 字段和 H3 三段/六段示例；移除对 Seedance 主 Skill、`@` 媒体语法、专用编译器、固定十五秒和其余 Seedance 模块的运行依赖。没有宣称任何模型具备精确轨迹控制或自动跨镜一致性保证。

用户锁定与已有剧情优先，技法建议不作为关键词到运镜的硬编码规则；没有因上游示例而新增背景音乐、角色、道具或剧情事件。本包只包含知识，不包含网络调用、自动执行脚本或媒体生成流程。
