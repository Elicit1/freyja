# 分镜视频批量渲染 API

已实现独立批量弹窗（剧作工作台的“批量视频渲染”）、全局串行队列、双向取帧开关及任务中心进度。设计背景见 [设计文档](shot-video-batch-render-design.md)。所有业务 ID 在 JSON 响应中为字符串。

## 数据库与运行

已有数据库先执行 `src/main/resources/sql/video-render-batch.sql`，再启动新版本。该脚本只创建四张新表，重复执行不会重写业务数据。新部署已包含于 `docker/mysql/init/01_init.sql` 与 `src/main/resources/sql/init.sql`。
Docker 部署的 db-upgrade 已加入 `20261016-video-render-batch-v1` 增量迁移，更新部署脚本与 SQL 后由编排在后端启动前执行；直接运行 JAR / IDE 时需要先手动执行该 SQL。

一个后台消费者同步执行视频生成、归档及落库；单镜提交也创建单项批次，进入同一持久队列，提交响应为 PENDING。首尾帧生图、资产生图及视频后处理也通过统一调度入口，与视频消费者共用 MySQL 会话锁，前序批次执行结束后才下发。连接池需至少容纳调度锁连接和业务事务连接。不能先把全部生成请求推到网关再排队。

`freyja.video-queue.enabled=false` 可以关闭消费者供测试或维护使用；默认开启。队列由提交、取消、归档重试和人工核实的事务提交事件唤醒，启动时执行一次恢复；完成当前任务后直接执行下一项，空闲或待核实时停止。已移除定时扫描，原 scan-delay-ms / initial-delay-ms 参数不再生效。等待中的单次任务通过条件通知唤醒，不定时查库。

多实例通过 Redis Pub/Sub 转发提交和调度释放通知，避免其他实例上的等待任务丢失唤醒；通知不存放生成请求，持久任务与上游占用仍以 MySQL 为准。首尾帧生成不会在排队期间持有业务数据库事务，避免大量等待请求耗尽连接池。

Java 到网关是单次同步生成请求。网关通过 ComfyUI WebSocket 等待任务完成，不再周期查询 history 或断线后循环重试。只在订阅后、完成事件缺少产物或断线时读取一次已完成历史快照；结果仍不能确认则返回异常，由持久视频队列保留待核实占用。

## 选择、预检与创建

前缀：`/drama/video-render-batches`。响应沿用 `R<T>`。

| 方法与路径 | 行为 |
| --- | --- |
| `GET /selection-tree?dramaId=...` | 返回剧集、场景、镜头组、分镜复选树，节点含 key、id、type、label、children |
| `POST /preview` | 无副作用预检，返回候选 ID、可提交 / 阻塞 / 跳过数量、双帧来源、实际尺寸、两阶段顺序、previewHash |
| `POST /` | 校验预检摘要并幂等创建批次，立即返回持久化结果 |
| `GET /?active=true&limit=50` | 活跃 / 历史批次，limit 为 1～200 |
| `GET /{id}` | 汇总、分阶段计数、结构化 options 和完整项目列表 |
| `GET /{id}/items` | 项目列表；每批至多 200 个候选 |

预检和创建载荷示例：

```json
{
  "clientRequestId": "756750ef-bf18-4455-9b55-b9c71e4272cc",
  "name": "第 1 集视频",
  "dramaId": "2109214975269752834",
  "shotIds": ["2109214976012144641", "2109214976012144642"],
  "existingVideoPolicy": "SKIP",
  "aspectRatio": "16:9",
  "modeConfigs": {
    "REFERENCE_MODE": {"providerId": "2109214976012144600", "modelId": "2109214976012144602"},
    "FIRST_LAST_FRAME": {
      "providerId": "2109214976012144600",
      "modelId": "2109214976012144601",
      "autoUsePreviousVideoTailAsFirstFrame": true,
      "autoUseNextVideoHeadAsLastFrame": false
    }
  },
  "expectedPreviewHash": "创建时填写 preview 返回值"
}
```

预检可省略 clientRequestId、expectedPreviewHash。创建时 clientRequestId 必须为 8～80 位字母、数字、下划线或连字符；相同请求内容返回原批次，不重复渲染；同一请求标识内容不同返回 409。预检之后输入发生变化也返回 409，需重新检查。

也可用 `selection={include:[{type: ALL/EPISODE/SCENE/SHOT_GROUP/SHOT,id?: string}],excludeShotIds:[]}` 代替 shotIds，由后端展开混合选择并去重。创建应使用预检返回的固定 shotIds，避免选择范围变动；同一次幂等重发须保持载荷一致。

统一比例支持 16:9、9:16、1:1、4:3、3:4、3:2、2:3。两种模型分别选择，实际尺寸按视频画幅预设解析并显示；可在模型 paramsJson.videoGeneration 显式配置 supportedAspectRatios、supportedSizes、minDuration、maxDuration 约束。默认既有 H3 视频约束为正时长且不超过 15 秒、32 像素对齐。缺少提示词 / 种子、模型不可用和参考绑定不合法会逐镜阻塞，不调用 AI 补写。

## 顺序与首尾帧

批次内部先完成全部 REF2VA，再执行 FL2VA；每阶段内部按剧作顺序。不同批次按提交顺序执行，不抢占当前批次。

FL2VA 的两个开关互相独立：已配置帧优先；开启相应开关且未配置时，首帧取同镜头组直接上一镜视频尾帧，尾帧取直接下一镜视频首帧。关闭开关不清除手动配置。

来源是本批前序任务时使用该任务的成功产物；失败则使用提交时冻结的旧视频。下一镜若是尚未执行的 FL2VA，只取旧视频，不等待未来任务。无邻镜或无来源视频则省略相应帧。抽帧失败属于单项失败，不静默降级。自动取帧不覆盖分镜手动首尾帧，网关保留仅尾帧输入的角色。

## 取消、重试与恢复

- `POST /{id}/cancel`：只有整批取消这一种语义。保留已成功视频及 Take，取消全部未执行项，对唯一在途任务立即发送精准取消并中断本地执行；不进入下一阶段。任务中心及原子任务取消入口统一路由到所属批次。
- 原始 / 当前输入重试：向 preview、create 增加 `retryOfBatchId` 和 `inputSource=ORIGINAL_SNAPSHOT / CURRENT_SHOT`，shotIds 只允许来源批次中明确失败且非归档待恢复的镜头，existingVideoPolicy 使用 CREATE_NEW_TAKE。原始快照重试保留原比例、模型及已解析帧，不重新抽卡成功镜头。
- `POST /{id}/retry-archive`，body `{taskId}`：只允许具有可恢复产物地址的归档 / 完成落库失败项，复用同一任务，仅重新归档或完成落库，不调用生成接口。已取消批次不能恢复。
- `POST /{id}/reconcile`，body `{taskId,evidence}`：人工确认远端已停止后释放待核实占用，核实依据保留在项目错误说明中；不会自动发起生成。不可用超时长度推断远端停止。

请求超时、断线、不可判定的网关异常和进程中断会保留全局槽位，进入 NEEDS_REVIEW，避免同一 GPU 实际运行两个任务。明确的请求拒绝和提交前校验失败可直接失败并继续下一项。上游停止未确认时，本批未执行项仍全部取消，但全局派发继续暂停，等待核实。

## 任务中心

sourceType 为 VIDEO_RENDER_BATCH，使用现有 `/ws/task-center` 的 TASK_UPDATED 推送父摘要。子渲染状态更新折叠为同一父摘要，原 RENDER_EVENT 仍供单镜界面刷新，避免父子重复计数。详情抽屉在打开、事件通知及 WebSocket 重连时读取详情；单镜窗口按 taskId 消费渲染事件，并在开始等待与重连时读取一次快照，不再定时轮询或按分镜旧视频判断完成。

状态：QUEUED、RUNNING、CANCELLING、SUCCESS、PARTIAL_FAILED、FAILED、CANCELLED、NEEDS_REVIEW。项目另有 DISPATCHING、RENDERING、SKIPPED。

总体进度为 `(成功 + 失败 + 取消) / 实际接受数量`，跳过项不计入分母，待核实不计完成。详情另展示 REF2VA、FL2VA 各自计数和当前镜头；阶段切换不会将总进度归零。取消批次即使保留成功视频，结果仍为 CANCELLED。
