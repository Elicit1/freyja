# 分镜当前视频打包下载

已实现。入口位于短剧项目库和剧作工作台顶部的“视频打包下载”，可切换短剧，按分集、场景、镜头组、分镜混选。设计背景见 [设计文档](shot-video-package-export-design.md)。

## 部署与数据库

已有数据库先执行 `src/main/resources/sql/shot-video-export.sql`，再启动更新的后端。脚本为可重复执行的增量建表，不修改已有业务数据。新部署的两份初始化 SQL 已包含这些表；Docker db-upgrade 已登记 `20261016-shot-video-export-v1`，会在后端启动前迁移。

导出复用已配置的 MySQL、Redis、MinIO。私有导出桶默认为 `freyja-video-exports`，首次上传时创建、移除公开桶策略并设置生命周期。不能配置成公开业务媒体桶或 MCP 暂存桶。应用凭据需要对该独立桶具备创建、生命周期设置和对象读写权限。

可通过 Spring 配置覆盖：

```yaml
freyja:
  video-export:
    enabled: true
    bucket: freyja-video-exports
    temp-directory: /tmp/freyja-video-exports
    max-shots: 1000
    max-file-bytes: 4294967296
    max-archive-bytes: 21474836480
    min-free-bytes: 536870912
    read-timeout-seconds: 120
    retention-hours: 24
```

临时目录应使用专属路径，并预留“当前 ZIP + 一份当前视频”的空间。默认目录位于 Java 临时目录下。应用只清理该目录内自己的任务子目录。多实例通过独立 MySQL 会话锁合计执行一个打包任务；连接池需容纳锁连接与业务事务连接。容器默认临时目录在容器文件层，较大导出建议通过配置和挂载独立磁盘扩容。

生成预签名下载链接时使用 `minio.external-endpoint`，需能从用户浏览器访问；默认 MinIO 区域为 `us-east-1`。产物默认逻辑保留 24 小时，存储生命周期按天清理；签名链接最多 10 分钟，且不超过产物逻辑有效期。

## 内容与快照

每个分镜只导出创建任务时 `videoUrl` 指向的当前视频；有 `currentVideoTakeId` 时校验 Take 归属、状态和 URL 一致性，没有 Take 时兼容历史直接绑定。新生成视频尚未替换当前绑定时，导出当前已有视频，不等待渲染任务。

预检读取可信 MinIO 对象元数据，冻结大小、ETag、版本和扩展名。创建在锁定分镜及层级的短事务中重新验证预检摘要，不在事务内访问 MinIO。下载通过版本或 If-Match 条件读取，源视频变化不会偷偷替换文件。

仅支持已配置内外端点对应业务媒体桶中的当前视频，未知来源在预检中阻塞。不会请求用户传入的任意 URL，也不会调用 Comfy Gateway、FFmpeg 或生成接口。

ZIP 保留“短剧 / 分集 / 场景 / 镜头组 / 分镜”的层级，未归组镜头有独立目录。视频使用 STORED 与 ZIP64，原始字节不转码。同源视频绑定到不同镜头时分别保留。包内含 `manifest.json`、UTF-8 BOM 的 `导出清单.csv`，有失败 / 缺失项时还包括 `失败清单.csv`。对外清单不暴露内部源地址或对象键，JSON ID 为字符串。

源视频先落临时文件并完成大小与校验，再写 ZIP，避免断流后留下半个视频。源读取失败只影响单镜；成功至少一镜时可以生成部分成功包。磁盘、ZIP 写入或整体上传失败时整个任务失败，不提供半成品。

## API

前缀 `/drama/shot-video-exports`，沿用 `R<T>`；所有业务 ID 在 JSON 响应中为字符串。

| 方法与路径 | 行为 |
| --- | --- |
| `GET /selection-tree?dramaId=...` | 带 shotCount / videoCount 的分集、场景、组、分镜树 |
| `POST /preview` | 校验当前绑定和来源，返回条目、accepted / missing / blocked、knownBytes、previewHash |
| `POST /` | 校验摘要并幂等创建持久任务，立即返回 QUEUED |
| `GET /?dramaId=...&current=1&size=20` | 分页导出历史，size 最大 100 |
| `GET /{id}` | 阶段、进度、计数、有效期及 downloadAvailable |
| `GET /{id}/items?current=1&size=50` | 分页查看冻结清单与结果，size 最大 200 |
| `POST /{id}/cancel` | 取消排队或进行中的任务，关闭窗口不会取消 |
| `POST /{id}/retry` | `{clientRequestId}`；失败 / 部分成功任务按完整原快照新建打包任务 |
| `POST /{id}/download-link` | 返回 `{url,filename,expiresAt}`，由浏览器原生下载，不使用 Blob 缓存整包 |

预检示例：

```json
{
  "dramaId": "2032095628944588801",
  "shotIds": ["2032095628944588802", "2032095628944588803"],
  "missingVideoPolicy": "SKIP",
  "name": "第 1 集当前视频"
}
```

也可用 `selection={include:[{type:ALL/EPISODE/SCENE/SHOT_GROUP/SHOT,id?:string}],excludeShotIds:[]}` 替代 shotIds，二者互斥。创建时传预检返回的固定 shotIds，附加 `expectedPreviewHash` 和 `clientRequestId`。幂等标识要求 8～80 位字母、数字、下划线或连字符。内容相同返回原任务，内容不同返回 409；预检后更改绑定、名称或顺序同样返回 409。

`missingVideoPolicy=SKIP` 默认跳过无视频的镜头；`FAIL` 遇到缺失就禁止创建。不可读、错误 Take 或非可信来源属于 BLOCKED，需要取消勾选或修复后重新预检。至少一个 READY 且没有 BLOCKED 才能创建。

状态为 QUEUED、PACKAGING、UPLOADING、SUCCESS、PARTIAL_SUCCESS、FAILED、CANCELLED；过期保留已完成状态，返回 downloadAvailable=false。重试保留原绑定及元数据，若希望使用最新视频须重新选择并预检。

## 实时进度与恢复

文件打包使用独立执行器、队列、会话锁和 Redis 通道，不占渲染队列。提交 / 取消 / 重试的提交事件、应用启动和 Redis 重新订阅唤醒消费者，前一任务结束后立即处理下一项；没有定时扫描。

任务中心 `sourceType=SHOT_VIDEO_EXPORT`、`category=FILE_EXPORT`、`resumeAction=SHOT_VIDEO_EXPORT`，通过现有 `/ws/task-center` 的 TASK_UPDATED 推送。PACKAGING / UPLOADING 在任务中心映射为 RUNNING，具体阶段保留在 currentStage。详情只在打开、分页、操作和 WebSocket 事件 / 重连时读取，不轮询。

独立会话锁获取后可恢复残留在途任务：保留原清单，以新 attemptToken 从头打包；条件更新防止旧执行写回，临时目录及旧尝试对象会清理。取消与完成通过数据库条件更新竞争，取消后不发布下载结果。
