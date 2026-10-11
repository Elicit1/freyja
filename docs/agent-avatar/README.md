# FREYJA Agent 宠物

参照系统背景的银白长发、紫色眼睛、紫金花饰和白紫裙装，使用 Codex 内置 imagegen 生成 Q 版人物关键帧，再离线组装为 [Codex Pet](https://codex-pet.org/zh/codex-pet-json/) 标准包。运行时使用透明精灵图，不再使用 GIF。

## 使用

进入 **管理 → Agent 配置 → 虚拟形象**。点击 **上传 ZIP** 可导入社区宠物包；点击 **恢复默认** 可重新选择 FREYJA。保存后立即更新悬浮宠物。默认下载包位于 `front/public/agent/freyja/freyja.codex-pet.zip`，可下载后重新上传。

宠物显示为约 **224 × 243 CSS 像素**，没有背景、边框或容器阴影；拖动时播放左右移动动作。点击可打开或关闭聊天窗口。完成、失败、需要回答时显示可点击气泡。

所有动作按标准帧时长的 **0.5 倍速度**播放，待机每轮结束额外停顿 **1 秒**；待机一轮约 3.2 秒。悬浮宠物、配置预览和上传的社区宠物使用同一播放节奏。前端 `avatarPresets.ts` 的 `petPlaybackRate` 控制整体速度，数值越小越慢，待机停顿由 `loopPauseMs` 控制。

2026-10-11 已在 WSL Docker 部署后通过 Chrome 对比九种动作及悬浮宠物的实际帧周期：待机由约 1.1 秒变为 3.2 秒，其他动作约为原周期的两倍，无浏览器异常。TypeScript、Vite 与 Docker 构建通过；测量结果保留在 `logs/agent-pet-speed-before.json` 和 `logs/agent-pet-speed-after.json`，未修改助手配置或业务数据。

![九种动作预览](preview.png)

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
