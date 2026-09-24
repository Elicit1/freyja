# 连续镜头组 (ShotGroup) 与镜头连续性系统 (Continuity System) 接口设计与状态规范

## 1. 模块定位与核心设计理念

传统的短剧分镜系统将场次（Scene）下的多个镜头（Shot）割裂生成，导致镜头间人物站位瞬移、左右手持物突变、动作脱节。
本模块引入 **ShotGroup（连续镜头组）** 这一独立的连续叙事单元，形成四级大纲架构：
$$\text{Drama} \longrightarrow \text{Episode} \longrightarrow \text{Scene} \longrightarrow \text{ShotGroup} \longrightarrow \text{Shot}$$

核心设计原则：
1. **叙事单元独立性**：一个 ShotGroup 承载一个完整的动作链/对白交互/视觉事件，支持配置 `STRICT`（严格连续）、`NORMAL`（普通连续）、`LOOSE`（蒙太奇/跳跃）连续性等级。
2. **状态自动继承链（State Chain Propagation）**：
   $$Shot_{i}.\text{startState} = Shot_{i-1}.\text{endState} \quad (i > 0)$$
   $$Shot_{0}.\text{startState} = ShotGroup.\text{startState}$$
3. **连续性体检与冲突诊断（Continuity Validator）**：对组内分镜进行 5 维连续性体检（左右手道具倒转 `PROP_HAND_MISMATCH`、站位瞬移 `POSITION_JUMP`、服饰突变 `ATTIRE_MISMATCH`、环境跳跃 `ENVIRONMENT_SHIFT`、道具状态冲突 `PROP_STATE_CONFLICT`），并支持一键智能对齐修复（Auto-Fix）。
4. **Prompt 自动注入**：将结构化 `StartState`（站位、朝向、左右手持物、视线）转化为高质量 Stable Diffusion / Flux / MiniMax 提示词 tokens。

---

## 2. 数据结构规范

### 2.1 镜头状态模型 (`ShotStateDTO`)
```json
{
  "characters": [
    {
      "characterId": 101,
      "characterName": "葛明",
      "position": {
        "location": "办公桌左侧",
        "relativeTo": "大门",
        "coordinate": "left-foreground"
      },
      "body": {
        "pose": "站立微前倾",
        "facing": "面向大门"
      },
      "gaze": {
        "target": "门口的林晨",
        "direction": "向前"
      },
      "hands": {
        "right": "紧握黑色公文包",
        "left": "自然下垂"
      },
      "clothing": {
        "outfitId": 201,
        "outfitName": "黑色定制西装",
        "state": "微湿，领带松开"
      },
      "emotion": {
        "primary": "冷峻",
        "intensity": 0.85,
        "expression": "眉头紧锁，眼神锐利"
      }
    }
  ],
  "props": {
    "briefcase_01": {
      "propId": 301,
      "name": "黑色公文包",
      "holder": "葛明",
      "holdingHand": "RIGHT",
      "status": "已打开，露出机密文件角"
    }
  },
  "environment": {
    "timeOfDay": "NIGHT",
    "weather": "雷雨",
    "lighting": "冷色调侧光，闪电忽明忽暗"
  },
  "camera": {
    "focalLength": "50mm",
    "angle": "平视",
    "movement": "SLOW_PUSH_IN"
  }
}
```

---

## 3. 核心 API 接口列表

### 3.1 连续镜头组管理 (`/drama/shot-group`)

| 请求方式 | 接口路径 | 描述 |
| :--- | :--- | :--- |
| `GET` | `/drama/shot-group/list/{sceneId}` | 获取场次下所有连续镜头组（附带连续性体检报告及组内分镜） |
| `GET` | `/drama/shot-group/list-by-episode?episodeId={id}` | 获取剧集下所有镜头组 |
| `GET` | `/drama/shot-group/{id}` | 获取指定镜头组详情 |
| `POST` | `/drama/shot-group` | 创建连续镜头组 |
| `PUT` | `/drama/shot-group` | 修改连续镜头组 |
| `DELETE` | `/drama/shot-group/{id}` | 删除连续镜头组 |
| `POST` | `/drama/shot-group/split` | 从指定分镜拆分连续镜头组 |
| `POST` | `/drama/shot-group/merge` | 合并多个连续镜头组并顺延分镜序号与状态链 |
| `PUT` | `/drama/shot-group/{groupId}/reorder-shots` | 重排组内分镜顺序并触发状态链全量重算 |
| `PUT` | `/drama/shot-group/reorder-groups` | 重排场次内镜头组顺序 |
| `GET` | `/drama/shot-group/{groupId}/validate` | 执行连续性体检并输出冲突诊断清单与健康评分 |
| `POST` | `/drama/shot-group/{groupId}/autofix` | 一键自动对齐并修复组内分镜连续性冲突 |
