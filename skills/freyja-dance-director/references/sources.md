# 编舞方法来源与适配边界

本包将公开编舞与动作分析方法整理为 FREYJA 创作指导。以下是方法参考，不是运行依赖，也不是 MiniMax 官方规范；不要求安装上游 Skill、切换视频模型或创建额外资产。

## 编舞与动作分析

- [AQA GCSE Dance：Choreography](https://www.aqa.org.uk/subjects/dance/gcse/dance-8236/specification/subject-content/choreography)：动作、动力、空间与人物关系，动作母题及发展、重复与对比，以及生成、选择、发展和修订的编舞过程。本包据此让脚步服务动作与空间任务；清单中的类别不是每句必须出现的动作配额。
- [Laban/Bartenieff Institute of Movement Studies：About LIMS](https://labaninstitute.org/about-lims/) 与 [Movement Analysis Overview](https://labaninternational.org/scope-of-practice/movement-analysis/movement-analysis-overview/)：身体、动力、空间与形态之间的关系。本包将其转成身体参与、动力质感、路线与构形的观察问题；不声称实现完整舞谱、动力学求解或专业分析认证。

## AI 视频动作与连续性

- [Emily2040/seedance-2.0：seedance-motion](https://github.com/Emily2040/seedance-2.0/blob/main/skills/seedance-motion/SKILL.md)（所查正文版本 `6.7.0`）：可见动作及结果、动作相位和运动方向继承、已完成动作不重复。本包仅借鉴这些描述与交接方法，不采用其素材标签、固定时间分段或叙事动作要求来限制舞蹈密度。
- [scenario-labs/skills：scenario-seedance-storyboard](https://github.com/scenario-labs/skills/blob/main/skills/scenario-seedance-storyboard/SKILL.md)：相邻镜头的出入状态相容与关键帧衔接。本包补充动态相位、方向与速度趋势，仍须实际看片；相同端点姿态不证明运动连续。不复制其服务接口、时长、分镜数量或制作流程。

## 本包的具体适配

先依据音乐、舞种与表达发展动作母题、空间路线和构形，再核对支撑与连接；身体可执行性始终是通过条件。动态支撑、固定脚位的选用依据、相邻短句重复检查及教学短句是针对当前工作流编写的指导，不是上述来源对“丁字步问题”的实验证明。

丁字步可因舞种、表达或用户指定保留；不把它或任何其他脚位作为通用起止状态，也不以固定动作数量或持续走位替代创作判断。文字规则能引导规划，实际 H3 动作执行、参考素材影响和镜间连续性需要在真实输入与成片中验证。
