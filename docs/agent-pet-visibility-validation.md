# 宠物显示状态修复验收

2026-10-11，分支 `codex/agent-foundation`。已更新 WSL Docker 的 frontend 容器，入口为 http://localhost/system/agent 。

部署前读取的主配置与公开 Profile 均为启用状态，`avatar.visible=true`，默认精灵图地址正常。Chrome 中预置历史 `freyja-agent-collapsed=true` 后，模拟在配置页关闭再开启形象并保存，仍只有 46 × 46 的圆形聊天入口；精灵图组件未渲染，收起状态保持 true。问题来自配置显示开关与浏览器收起偏好缺少同步。

收起偏好现在由 Agent store 管理。显式开启形象、上传 ZIP、恢复默认后保存，或在虚拟形象页签直接保存，会展开已开启的宠物并同步 localStorage。修改形象后切换其他页签保存也生效。平时手动收起、展开和刷新，以及保存其他参数，保留个人偏好。形象关闭或助手停用时不展示无效的展开按钮；重新启用后恢复宠物。展开尺寸保持 224 × 243 CSS 像素、透明无边框，点击继续切换聊天。

TypeScript 与 Vite 生产构建通过，Docker 镜像构建和前端部署通过。已在部署后的 Chrome 页面完成 31 项检查，包含保存响应、历史收起复现、开启后跨页签保存、个人偏好持久化、隐藏/停用及恢复、恢复默认、ZIP 上传后保存、默认精灵图加载、图片失效回退、宠物点击开关聊天，以及无浏览器异常。默认精灵图实际解码尺寸为 1536 × 1872。

浏览器脚本隔离配置写入、ZIP 上传和会话/通知列表，以验证页面交互，未新增任务或改动已读状态；配置校验使用本机真实接口。上传检查覆盖前端导入与保存后的展开行为，本次未重复测试后端 ZIP 归档。验证前后实时主配置逐字段相同，仍为 v10；模型、宠物、会话和制作结果保持原状，未调用 AI。

日志、脚本与截图全部保留在忽略的 `logs/`：`agent-pet-collapse-before.json`、`agent-pet-collapse-before.png`、`agent-pet-collapse-checks.json`、`agent-pet-collapse-after.png`、`agent-pet-collapse-verify.cjs`、`agent-pet-collapse-verify.log`、`agent-pet-collapse-build.log`、`agent-pet-collapse-docker-build.log`。

已有浏览器若仍记着收起偏好，刷新加载新版本后，可点圆形入口下方“展开”，或进入“虚拟形象”页签保存一次。此后再开启形象并保存会自动展开，无需手动清理浏览器存储。
