# 资产中心独立页面与视频素材

资产中心菜单分为角色 `/assets/characters`、场景 `/assets/scenes`、道具 `/assets/props`、关键图 `/assets/keyframes`、视频素材 `/assets/videos` 五个独立页面，每次仅挂载对应列表。旧 `/assets?assetType=...&assetId=...` 链接会保留查询参数并跳转到对应页面；分镜新建资产后的返回、绑定流程继续可用。

## 视频素材

| 接口 | 用途 |
| --- | --- |
| `GET /res/video/page` | 分页；支持 name、dramaId（0 为公共库）、shotId、sourceType、status |
| `GET /res/video/{id}` | 详情及短剧、分镜名称 |
| `POST /res/video/upload` | multipart：file、可选 name/dramaId/shotId、sourceType（MANUAL_UPLOAD 或 SHOT_UPLOAD） |
| `PUT /res/video` | 编辑 id/name/description/dramaId/shotId/sortOrder/status/remark |
| `DELETE /res/video/{id}` | 逻辑删除素材，并在提交后清理不再被引用的托管文件 |

`availableDramaId` 用于分镜素材选择器，仅返回公共库和指定短剧已启用素材。上传使用 FFprobe 校验真实视频轨及元数据，沿用参考视频源限制（默认 256 MiB、600 秒），支持 MP4/WebM/MOV/MKV；成功后立即登记素材。视频 URL 不可在元数据编辑接口中替换，以保证已有分镜的引用快照稳定。

分镜编辑、AI 提示词生成和参考渲染工坊共用视频编辑器：上传调用 `/res/video/upload` 并显式声明 `SHOT_UPLOAD`，成功即入库；新增「从视频素材库选择」。引用继续使用 `DIRECT_URL` 与不可变 URL，不复制文件，不改变现有历史 Take、预检和任务快照协议。素材描述会作为参考说明初始值，可由创作者改写。

启动时会分页登记旧分镜中仍存在的托管 `reference-video/` 上传引用；同 URL 不重复入库，已删除素材不会复活，外部链接不自动入库。旧记录没有探测数据时显示“待探测”，不编造时长、尺寸或音轨；后续渲染预检仍会探测实际文件。

## MCP 上传参考视频

1. 调用 `prepare_asset_video_upload`，提供稳定的 `requestId`、授权短剧 `dramaId`、`fileName`、真实 `contentType`、字节数 `size` 和客户端计算的 `sha256`；可选同项目 `shotId`、`referenceDescription`。所有业务 ID 保持字符串。
2. 使用返回的 `uploadUrl` 和 `fields` 发起 multipart POST，真实视频文件作为最后一个 `file` 字段。凭证有效期为 15 分钟；不通过 MCP JSON 传文件字节、本地路径或 Base64。
3. 调用 `complete_asset_video_upload`，提供独立稳定的 `requestId`、原 `uploadId` 和 `dramaId`。服务端校验大小、SHA-256、真实容器及视频轨后自动登记视频素材，返回 `videoAssetId`、`url`、元数据和可写入分镜 `refVideos` 的 `reference` 对象。

同一 `uploadId` 重试确认不会重复创建素材，即使更换确认请求的 `requestId` 也会复用已完成结果。上传不会自动覆盖分镜或启动渲染；需要将返回的引用显式保存到分镜。一次上传凭证对应一个文件，可以逐个上传多个文件。

分镜参考数量按模型中心的 `maxVideos` 配置校验，已验证的网关能力可以进一步收紧限制；单段及总片段时长同样在预检和提交时校验。编辑器支持多个独立片段、排序和移除；切换到数量更少的模型会提示超限并阻止渲染，保留已有草稿。参考视频卡片默认折叠，展开后才加载播放器并探测来源信息；提示词使用有序的 `<Video N>` 引用。

## 删除关联图片与文件

角色（含全部造型）、单独造型、场景、道具和关键图删除后，默认清理关联的本系统 MinIO 图片。只处理配置端点及配置桶中的对象；其他资产、分镜、Take、任务或生产快照仍引用该对象时保留文件。数据库事务回滚不删除文件；引用检查失败保留文件并记录日志。外部链接和其他桶的文件不删除。视频素材遵循同样规则。

## 部署

现有库执行可重复的增量脚本 `src/main/resources/sql/asset-video-library.sql`；新安装的两份初始化脚本已同步。Docker 的 `db-upgrade` 自动升级流程已加入该迁移。更新后端及前端镜像，保留现有 MinIO、MySQL 持久化卷。此代码修改未连接或迁移线上数据库，也未删除任何现有文件。

MCP 上传与多段参考更新需要同时更新后端、网关和前端，并重新连接 MCP 以刷新工具列表；除上述视频素材库迁移外，不新增数据库迁移。网关默认 H3 工作流的参考槽位上限按节点声明配置为 3，实际可用数量仍受模型中心配置约束；能力保持 `UNVERIFIED`，需完成实际工作流验证后启用。

## 验证

2026-10-23：105 项相关 Java 测试通过，失败、错误、跳过均为 0；前端 Vue/TypeScript 检查及 Vite 构建通过。另执行实际 Vue 组件 setup 冒烟检查，覆盖旧链接重定向、分镜返回上下文、素材库来源限制、上传自动登记、提示词采纳保存和雪花 ID 字符串保持。删除测试使用真实 H2 事务验证提交后清理、回滚保留、共享引用保护和检查失败时保留文件；未对真实 MinIO 执行删除测试。

MCP 与多段参考更新：132 项相关 Java 测试通过，失败、错误、跳过均为 0；网关 45 项测试通过，前端 Vue/TypeScript 检查及 Vite 构建通过。上传测试包含真实 FFmpeg MP4 经 FFprobe 校验入库、伪装图片拒绝、权限、摘要校验、幂等及事务回滚；组件冒烟检查覆盖模型数量限制、多片段编辑排序、默认折叠及展开后探测。未执行线上数据库、真实 MinIO 删除或 GPU 渲染验证。
