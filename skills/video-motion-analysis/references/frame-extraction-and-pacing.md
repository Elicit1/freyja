# 抽帧分析与时序预算规范 (Frame Extraction & Temporal Budgeting)

## 1. 客观抽帧与多尺度网格化 (Multi-Scale Grids)

人类视觉系统具有高度的主观连续性补全机制（Gestalt Completion），在直接观看高帧率视频时，容易产生“动作很丰富、很紧凑”的直觉印象。但对于 AI 扩散模型，它是以帧间连续潜变量插值的方式工作的。必须通过客观抽帧，将视频离散化为静态切片进行分析。

### 抽帧建议与工具指令

使用 `ffmpeg` 将视频离散化为图片接触表（Contact Sheet）：

```bash
# 1. 宏观结构表（每 1.0 秒截 1 帧，5x5 排布共 25 秒）
ffmpeg -i input.mp4 -vf "fps=1,scale=320:180,tile=5x5" -frames:v 1 macro_grid.jpg

# 2. 微观精细动作表（每 0.2 秒截 1 帧，分析快速指法与腕部细节）
ffmpeg -i input.mp4 -ss 00:00:05 -to 00:00:10 -vf "fps=5,scale=320:180,tile=5x5" -frames:v 1 micro_finger_grid.jpg
```

---

## 2. 扩散模型时序带宽理论 (Temporal Bandwidth Law)

### 为什么动作多会导致做不完？
在以 MiniMax H3、CogVideoX、Sora 等为代表的时空注意力（Spatio-Temporal Attention）模型中：
- 潜空间（Latent Space）中的时间轴注意力头（Temporal Attention Heads）需要对相邻帧的像素分布进行连续形变；
- 如果两个动作之间的形态差异极大（例如从“双手胸前握拳”突变到“双手在两颊翻腕展开”），模型需要消耗大量的帧数（Frame Budget）来计算关节运动的平滑过渡；
- **动作拥挤（Action Crowding）效应**：如果提示词要求在 1 秒内完成两个或以上的大幅度姿态跃迁，模型的注意力权重会发生竞争冲突，导致后排动作的权重直接被衰减为零，表现为视频中“后面的动作直接被跳过”或“手臂在半空中卡死/畸变”。

---

## 3. 标准时序配比模型 (Action Pacing Guidelines)

对于标准 8 ~ 10 秒的分镜视频，时间预算必须遵循以下**黄金分割原则**：

```
[0.0s - 1.0s] 起势与首个动作展开 (Action 1: Initiation, 1.0-1.5s)
[1.0s - 3.5s] 核心主题动作承接与展开 (Action 2: Core Motif, 2.0-2.5s)
[3.5s - 5.5s] 情绪/节奏次高潮手势 (Action 3: Accent/Climax, 2.0s)
[5.5s - 9.0s] 复杂收势与定格留韵 (Action 4: Complex Finale & Poised Hold, 3.5s)
```

### 关键约束指标：
1. **单动作最小生存时长**：任何被命名的独立动作，在时间轴上的持续时间不得低于 **1.2 秒**；
2. **多周回转手势保护**：任何涉及“翻腕（Wrist Roll）”、“挽花（Lotus Roll）”、“画圆（Circular Tracing）”的手势，耗时不得低于 **2.5 秒**；
3. **尾部定格余量（Hold & Settle）**：视频结束前必须至少留出 **1.0 秒** 的静态姿势维持与眼神注视，绝不能让最后一个大动作卡在视频最后一帧的边缘结束。
