# FREYJA Agent 宠物

参照系统背景的银白长发、紫色眼睛、紫金花饰和白紫裙装，使用 Codex 内置 imagegen 生成 Q 版人物关键帧，再离线组装为 [Codex Pet](https://codex-pet.org/zh/codex-pet-json/) 标准包。运行时使用透明精灵图，不再使用 GIF。

## 使用

进入 **管理 → Agent 配置 → 虚拟形象**。点击 **上传 ZIP** 可导入社区宠物包；点击 **恢复默认** 可重新选择 FREYJA。保存后立即更新悬浮宠物。当前标准下载包位于 `front/public/agent/freyja/freyja-smooth.codex-pet.zip`，可下载后重新上传；原包 `freyja.codex-pet.zip` 保留。

宠物显示为约 **224 × 243 CSS 像素**，没有背景、边框或容器阴影；拖动时播放左右移动动作。点击可打开或关闭聊天窗口。完成、失败、需要回答时显示可点击气泡。

默认 FREYJA 使用新增中间帧，每个动作包含 **16 张不同姿态**（9 行共 144 个画格）。轻动作按 0→15→1 的顺序往返，避免末帧突然切回首帧；左右移动按 16 帧顺序循环。

根据用户再次反馈，默认形象相对上一版**再降低 50% 播放速度**，待机一轮约 **12.8 秒**，其中活动和停顿各 6.4 秒。招手一轮 5.6 秒，思考一轮 8.24 秒。配置预览和悬浮宠物共用播放逻辑。该调整降低姿态切换频率，原画之间的细节差异仍然存在。

社区宠物继续使用标准 8 列及既有 **0.5 倍速度**，待机每轮停顿 1 秒、周期 3.2 秒。前端 `avatarPresets.ts` 的 `petPlaybackRate` 控制社区播放速度；`freyjaAnimationDurationScale` 控制默认形象的额外放慢倍数，数值越大越慢。

播放器按 `requestAnimationFrame` 的实际经过时间采样，不累计定时器漂移；页面隐藏时暂停，恢复后接着播放，系统开启「减弱动态效果」时静止。等待图片加载完成后启动动画；增强图集加载失败时自动回退原始图集。

2026-10-11 已在 WSL Docker 部署后通过 Chrome 对比九种动作及悬浮宠物的实际帧周期：待机由约 1.1 秒变为 3.2 秒，其他动作约为原周期的两倍，无浏览器异常。TypeScript、Vite 与 Docker 构建通过；测量结果保留在 `logs/agent-pet-speed-before.json` 和 `logs/agent-pet-speed-after.json`，未修改助手配置或业务数据。

![九种动作预览](preview.png)

新增帧预览及首轮验证见 [播放验证](smooth-playback-validation.md)，再次减速的实测记录见 [后续减速验证](slower-playback-validation.md)。

## 包格式

ZIP 根目录或一个外层文件夹中放置 `pet.json` 和其 `spritesheetPath` 指定的静态透明 WebP/PNG：

```json
{
  "id": "freyja",
  "displayName": "FREYJA",
  "description": "银白发、紫金花饰的 Q 版创作助手",
  "spritesheetPath": "spritesheet.webp",
  "spriteVersionNumber": 1
}
```

每格 **192 × 208**，8 列；v1 为 9 行（1536 × 1872），v2 为 11 行（1536 × 2288）。缺少版本号时按画布行数识别。v2 最后两行方向帧可保留，当前 FREYJA 使用前九行动作。

内置默认形象额外使用 **16 列 × 9 行**的专用图集 `spritesheet-smooth-v2.webp`（3072 × 1872）；这里的 `v2` 是资源修订名，**不是 Codex Pet v2 格式**。不会将这种画布传入社区 ZIP 上传接口。标准下载 ZIP 使用抽取后的 8 列图集 `spritesheet-smooth-codex.webp`，遵循下表帧数；重新上传后按社区标准播放。仅内置默认 URL 启用完整 16 帧，上传包不会因名称或 ID 相同而改变格式。

| 行 | 标准动作 | 帧数 | FREYJA 状态 |
| --- | --- | --- | --- |
| 0 | idle | 6 | 待机 |
| 1 | running-right | 8 | 向右拖动 |
| 2 | running-left | 8 | 向左拖动 |
| 3 | waving | 4 | 招手预览 |
| 4 | jumping | 5 | 任务完成 |
| 5 | failed | 8 | 任务失败 |
| 6 | waiting | 6 | 等待用户处理 |
| 7 | running | 6 | 工具工作 |
| 8 | review | 6 | 模型思考 |

导入上限 20 MB，解压内容上限 32 MB、64 个条目、64 KB 清单。仅将通过校验的精灵图存入 MinIO，不在磁盘解压 ZIP、不执行包内容。拒绝路径穿越、重复路径、多个清单、外链精灵图、错误画布尺寸和动态 WebP/GIF。

## 复现

`source/` 保存七张原始透明 PNG；`prompts.json` 保存人物生成提示词。使用已安装 Pillow 的 Python 环境运行：

```powershell
python scripts/assemble_agent_avatar.py
```

脚本提取原始人物帧、统一尺寸和脚部位置，输出静态无损 WebP、标准清单和 ZIP；`validation.json` 记录画布、各行非空帧、透明度和 SHA-256。Pillow 仅用于离线制作，没有新增应用运行时依赖。

新增帧的七张源图在 `source/smooth/`，提示词在 `smooth-prompts.json`。离线组装需要 Pillow、NumPy、OpenCV：

```powershell
python scripts/assemble_agent_pet_smooth.py
```

此脚本只裁切、对齐、缩放已有 AI 原画，按主要轮廓去除相邻格碎片，并对齐上部头部中心和地面基线；不重新绘制人物。生成内置图集、标准 ZIP、`smooth-frames-preview.png` 和带各行动作帧数/哈希的 `smooth-validation.json`。源图及旧图集不覆盖；这些 Python 库不是应用运行时依赖。
