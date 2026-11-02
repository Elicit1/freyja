# 通用运动学几何建模与消歧指南 (Universal Kinematic Formulation Guide)

## 1. 语义模糊性与模型退化机制

扩散模型（Diffusion Models）是通过文本与视觉潜特征的隐式交叉注意力（Cross-Attention）完成动作生成的。日常自然语言具有先天的**多义性**与**偏置偏差（Prior Bias）**：
- 当使用宏观日常词汇描述动作时，模型往往会默认激活预训练集中最常见、权重最高的统计图例，而非创作者所期望的特定形态；
- 当指令中缺少关键的拓扑或动力学约束时，扩散模型倾向于采用“能量最低”的简化模式，产生肢体僵死、末端假死或形态散架等退化现象（Degeneracy Modes）。

要使 AI 视频生成达到工业级确定性，必须将动作抽象转换为**刚体运动学（Rigid-Body Kinematics）**与**空间几何约束（Spatial Geometric Constraints）**。

---

## 2. 通用四维运动学约束表达范式 (The 4D Kinematic Framework)

无论是舞蹈手势、肢体表演、武术招式还是日常交互，任何动作的精准描述都由以下四大几何约束维度构成：

```
+-------------------------------------------------------------------------------+
|                        通用四维运动学约束表达范式                              |
+-------------------------------------------------------------------------------+
|  1. 接触拓扑 (Contact Topology)   : 闭环/开环、接触面法向、相对距离、非分离性  |
|  2. 末端法向 (End-Effector Normal): 局部坐标系朝向、掌心/拳心/面部主轴矢量   |
|  3. 空间包络 (Spatial Envelope)   : 相对躯干活动半径、振幅边界、相对几何尺度   |
|  4. 末端微动态 (Micro-Kinematics) : 宏观位移下的末端自由度激活与微动态波长     |
+-------------------------------------------------------------------------------+
```

---

### 维度一：接触拓扑与闭环几何约束 (Contact Topology & Closed-Loop Geometry)

#### 核心作用
防止模型在扩散采样过程中将紧密贴合、闭环互锁的肢体结构错误地“拆解分离”或“悬空泛化”。

#### 形式化表达规范
1. **接触关系声明**：
   显式指定两个几何图元之间的接触类型（如面面接触 `planar contact`、线接触 `edge contact`、点对齐 `point coincidence`）；
2. **法向平行与同向/反向约束**：
   指定接触面的法向量关系（如 `palms pressed flatly together with opposing normal vectors`，双掌反向法向对贴）；
3. **不可分离性硬锁（Non-Separation Enforcement）**：
   显式声明二者在运动过程中作为一个刚性统一体（`unbroken unified body`），绝对禁止沿法向或切向产生间隙；
4. **负向词闭环防护**：
   在负向提示词中加入该接触关系的解耦退化词（如 `detached, separated, split apart`）。

---

### 维度二：末端朝向与局部法向矢量约束 (End-Effector Orientation & Normal Vectors)

#### 核心作用
防止模型只计算了末端空间位置的大致坐标，却发生肢体翻转、内外侧混淆或异常扭曲。

#### 形式化表达规范
1. **基准坐标系对齐**：
   为肢体末端（如手掌、足部、头部）建立局部坐标系，并将其主矢量对齐到身体主干（`toward torso / chest`）、镜头平面（`toward camera`）或世界重力轴（`toward floor / ceiling`）；
2. **表面指向显式化**：
   使用大写或明确的主谓结构标明核心功能面（如 `PALMS FACE INWARD TOWARD HER CHEST`，手心完全朝向胸腹内侧）；
3. **轴向旋转（Torsion / Roll）限制**：
   若涉及旋转，明确说明是沿何轴内旋（`inward roll`）或外旋（`outward roll`），杜绝关节逆向翻折。

---

### 维度三：空间包络与相对尺度约束 (Spatial Envelope & Relative Amplitude)

#### 核心作用
取代“幅度稍微大一点”、“手不要抬太高”等模糊的主观修饰词，防止扩散模型过度放大或缩小动作幅度。

#### 形式化表达规范
1. **基于身体解剖地标的相对度量**：
   使用人体的自然解剖地标作为位置边界（如 `cheek level` 脸颊高度、`mid-chest` 胸口中心、`waist level` 腰侧）；
2. **定量安全包络范围**：
   给出相对躯干的活动距离范围（如 `natural 5-10 cm separation from torso`），锁死活动走廊；
3. **对称性与开度限制**：
   显式声明双侧对称度（`bilateral symmetry`）或单侧偏置，杜绝手肘过屈、大甩臂或不可控的肢体飞舞。

---

### 维度四：末端自由度激活与微动态约束 (End-Effector DOF & Micro-Kinematic Dynamics)

#### 核心作用
杜绝在躯干或大臂执行宏观移动时，末端小关节（手指、手腕、脚掌）陷入“假死僵木（Sliding Mannequin）”状态。

#### 形式化表达规范
1. **自由度解冻指令（DOF Unfreezing）**：
   在描述肢体位移时，必须同时赋予末端关节独立的动态状态（`active motion`），明确说明微小关节正在进行微幅起伏、屈伸或波动；
2. **多图元独立性（Element Independence）**：
   若末端包含多个子结构（如十指、脚趾），明确要求子结构之间的独立与交替运动（`each individual finger flexing independently`）；
3. **负向僵直词拦截**：
   在负向提示词中精准排除僵直模式（如 `stiff, static, motionless, frozen, rigid, straight`）。

---

## 3. 音画解耦与防对嘴铁律 (The Zero Lip-Sync Protocol)

现代多模态音视频模型具有跨模态注意力融合特性，文字提示词中若出现歌词、台词或带引号的内容，极易被交叉注意力层误判为角色的嘴型驱动指令。

### 防对嘴三道防御工事
1. **指令层剥离（Zero-Lyrics Purity）**：
   提示词中严禁出现任何自然语言台词或歌词原句，动作与时间的关联完全用纯粹的时间戳（`00:00.0-00:XX.X`）和节拍标定（`Beat N`）进行锚定；
2. **身份层声明（Non-Vocal Actor Protocol）**：
   在对象定义中明确声明角色为完全无声、不发音、不唱歌的独立舞者/演员，且全片保持闭唇状态；
3. **负向词全局防御（Negative Enforcement）**：
   在负向提示词中固定注入：
   `lip sync, mouthing words, singing, moving mouth, open mouth, talking, speaking, vocalizing`。

---

## 4. 二次元强先验对抗与空间注意力对称撕裂防御 (Anti-Prior Disambiguation & Symmetry Defense)

在二次元及动漫训练集中，部分经典姿态（如双手托腮、捧脸、剪刀手）具有超高频的统计先验权重，极易在 Cross-Attention 阶段发生拓扑坍塌。

### 核心诱发陷阱与防御规范
1. **Danbooru 毒性标签诱发陷阱（The `head rest on hands` Trap）**：
   - **错误写法**：`head tilts to rest gently against hands` / `hands touch cheeks`；
   - **坍塌现象**：模型自动激活动漫里最常见的 `双手托腮/捧脸 (face-cupping / cheek holding)` 极小值，合十的双臂被强行撕开，两手分别捧住两颊；
   - **标准防御**：**严禁使用任何“头枕/靠在手上”的表达**。手与头必须在几何上解耦描述——手是 `tightly clasped upright prayer hands`（竖直合十掌刀），头是独立的微侧倾（`head tilts softly`），落点锚定在下颌与颈侧（`jawline / neck`），绝不提及 `cheeks` 或 `rest head`。
2. **双侧空间跨注意力对称撕裂（Bilateral Cross-Attention Bleeding）**：
   - **错误写法**：在同一个动作段落中同时密集出现 `left ... right` 并用 `Both hands` 指代；
   - **坍塌现象**：文本编码器缺乏严格的时间先后隔离，将左手分配给左侧、右手分配给右侧，把时序上的“先左后右”压扁成“左右双手同时向两侧展开或托脸”；
   - **标准防御**：将双掌声明为单一不可分刚体图元（`single consolidated vertical unit` / `unified prayer blade`），严禁拆分调用左右手。位移严格遵循单向矢量平移（`first moves to the left jawline at T1, then glides across to the right jawline at T2`）。

