# 分镜视频抽卡历史与历史版本重选实施计划

## 1. 目标

为分镜视频“抽卡 / Re-roll”增加可追溯的候选版本库：

1. 每次分镜视频成功生成并归档后，都保存为一个独立的视频 Take（候选版本）。
2. 创作者可以在当前分镜中查看该分镜的全部历史视频。
3. 创作者可以从历史中重新选择任意可用视频作为当前镜头的视频。
4. 切换历史视频只更新数据库选择关系，不重新生成、不复制 MinIO 对象。
5. 保留 `drama_shot.video_url` 作为当前视频的兼容投影，避免一次性改造全部旧页面。
6. 渲染任务历史和视频候选历史职责分离：`render_task` 记录任务执行，`drama_shot_video_take` 记录可供创作选择的视频资产。

本文档用于直接交给其他 AI 实施。除非本文明确说明，不要扩大到首帧图片历史、尾帧图片历史或通用媒体资产版本系统。

## 2. 产品术语

| 术语 | 定义 |
|---|---|
| 分镜 Shot | `drama_shot` 中的镜头创作单元 |
| 视频 Take | 某个分镜的一次成功视频生成结果，即一次抽卡候选 |
| 当前 Take | 当前被选中用于该镜头的视频版本 |
| 渲染任务 | 一次生成过程及其状态、错误和耗时，记录在 `render_task` |
| 历史视频 | 同一个 `shotId` 下所有未归档且可访问的视频 Take |

界面文案建议使用“历史视频”或“抽卡历史”，代码领域命名统一使用 `VideoTake`。

## 3. 范围

### 3.1 必须实现

- 新增 `drama_shot_video_take` 表。
- `drama_shot` 新增当前 Take 指针 `current_video_take_id`。
- 每次 `SHOT_VIDEO` 成功后创建一条 Take。
- 最新有效渲染任务成功时，自动把新 Take 设为当前视频。
- 并发渲染乱序完成时，旧任务生成的视频进入历史，但不得覆盖更新任务的当前选择。
- 新增分镜视频历史分页接口。
- 新增“选择此版本作为当前视频”接口。
- 在分镜渲染工坊增加历史入口、历史列表和选择操作。
- 选择历史版本后刷新分镜卡片、播放器和当前版本标记。
- 切换当前视频时使旧视频派生的尾帧缓存失效。
- 为现有 `drama_shot.video_url` 数据提供回填方案。
- 更新数据库初始化 SQL、升级 SQL、后端测试、前端类型和 API 文档。

### 3.2 本次不实现

- 不实现首帧图片抽卡历史。
- 不展示失败和取消任务为可选择视频；它们继续只存在于渲染任务历史。
- 不从 `render_task.output_url` 临时拼装视频历史列表。
- 不删除未选中的 MinIO 视频。
- 不实现视频收藏、评分、标签、批量删除或跨分镜复制。
- 不实现通用媒体资产表。
- 不允许通过前端提交任意 `videoUrl` 设为当前视频。
- 不改变 FastAPI / OpenAI 兼容视频生成协议。

## 4. 为什么不能直接复用 `render_task`

现有 `render_task` 是任务调度和审计表，包含成功、失败、取消、图片生成、视频生成和资产生成等多种任务。直接用它作为视频版本库存在以下问题：

1. 没有当前选择指针。
2. 没有每个分镜下稳定的 Take 编号。
3. 没有视频生成输入快照的完整领域结构。
4. 任务可能成功但产物后来不可用，缺少资产状态。
5. 切换当前视频不应该修改历史任务。
6. 同一任务记录的职责不应同时承担运行状态和创作版本资产。

因此使用两层结构：

```text
render_task
    负责：排队、进度、成功/失败、耗时、错误审计
             │ 1 : 0..1
             ▼
drama_shot_video_take
    负责：成功视频资产、生成快照、版本列表、可选状态
             │ N : 1
             ▼
drama_shot.current_video_take_id
    负责：当前选中版本
```

## 5. 数据库设计

项目当前没有 Flyway/Liquibase，实施时必须同时：

1. 更新 `src/main/resources/sql/init.sql`；
2. 新增现有数据库升级脚本，例如：
   `src/main/resources/sql/upgrade_shot_video_take_history.sql`；
3. 不要依赖 `@PostConstruct` 在运行时偷偷建表或改表。

### 5.1 新表 `drama_shot_video_take`

建议结构：

```sql
CREATE TABLE `drama_shot_video_take` (
    `id`                    BIGINT UNSIGNED NOT NULL COMMENT '主键ID（雪花算法）',
    `drama_id`              BIGINT UNSIGNED NOT NULL COMMENT '短剧ID（查询冗余）',
    `episode_id`            BIGINT UNSIGNED DEFAULT NULL COMMENT '剧集ID（查询冗余）',
    `scene_id`              BIGINT UNSIGNED DEFAULT NULL COMMENT '场次ID（查询冗余）',
    `shot_group_id`         BIGINT UNSIGNED DEFAULT NULL COMMENT '生成时镜头组ID快照',
    `shot_id`               BIGINT UNSIGNED NOT NULL COMMENT '所属分镜ID',
    `take_no`               INT NOT NULL COMMENT '该分镜下从1开始递增的候选编号',
    `task_id`               VARCHAR(64) DEFAULT NULL COMMENT '来源渲染任务业务ID',
    `source_type`           VARCHAR(32) NOT NULL DEFAULT 'AI_GENERATED'
                              COMMENT '来源: AI_GENERATED/LEGACY_BACKFILL',
    `status`                VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE'
                              COMMENT '资产状态: AVAILABLE/UNAVAILABLE/ARCHIVED',
    `video_url`             VARCHAR(512) NOT NULL COMMENT 'MinIO视频访问URL',
    `provider_id`           BIGINT UNSIGNED DEFAULT NULL COMMENT 'AI提供商ID快照',
    `provider_name`         VARCHAR(128) DEFAULT NULL COMMENT 'AI提供商名称快照',
    `model_code`            VARCHAR(128) DEFAULT NULL COMMENT '视频模型代码快照',
    `generation_mode`       VARCHAR(32) DEFAULT NULL COMMENT '生成模式快照',
    `seed`                  BIGINT DEFAULT NULL COMMENT '随机种子快照',
    `size`                  VARCHAR(32) DEFAULT NULL COMMENT '生成尺寸快照',
    `duration`              DECIMAL(8,2) DEFAULT NULL COMMENT '目标/实际时长秒数',
    `prompt_snapshot`       LONGTEXT DEFAULT NULL COMMENT '视频Prompt快照',
    `negative_prompt_snapshot` TEXT DEFAULT NULL COMMENT '负向Prompt快照',
    `first_frame_url`       VARCHAR(512) DEFAULT NULL COMMENT '生成时首帧URL快照',
    `end_frame_url`         VARCHAR(512) DEFAULT NULL COMMENT '生成时尾帧URL快照',
    `ref_images_json`       LONGTEXT DEFAULT NULL COMMENT '生成时参考图快照',
    `ref_audios_json`       LONGTEXT DEFAULT NULL COMMENT '生成时参考音频快照',
    `request_snapshot_json` LONGTEXT DEFAULT NULL COMMENT '规范化后的请求参数快照JSON',
    `create_by`             BIGINT DEFAULT 0 COMMENT '创建人',
    `create_time`           DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`             BIGINT DEFAULT 0 COMMENT '更新人',
    `update_time`           DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`               TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `remark`                VARCHAR(500) DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_shot_take_no` (`shot_id`, `take_no`),
    UNIQUE KEY `uk_task_id` (`task_id`),
    KEY `idx_shot_status_time` (`shot_id`, `status`, `create_time`),
    KEY `idx_drama_episode` (`drama_id`, `episode_id`),
    KEY `idx_scene_id` (`scene_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='分镜视频抽卡候选版本表';
```

说明：

- `task_id` 允许为空，以支持历史数据回填；MySQL 唯一索引允许多个 `NULL`。
- `take_no` 只负责用户可读编号，不用作主键。
- 不在 Take 表保存 `is_current`，避免它与 `drama_shot.current_video_take_id` 形成双重事实来源。
- 首版只创建成功的视频 Take。失败/取消信息继续查询 `render_task`。
- `request_snapshot_json` 存储请求 DTO 的规范化快照，不应包含 API Key、Authorization、Base64 媒体或其他秘密。

### 5.2 修改 `drama_shot`

增加：

```sql
ALTER TABLE `drama_shot`
    ADD COLUMN `current_video_take_id` BIGINT UNSIGNED DEFAULT NULL
        COMMENT '当前选中的分镜视频Take ID',
    ADD KEY `idx_current_video_take_id` (`current_video_take_id`);
```

首版不强制添加数据库外键，保持与项目现有 MyBatis-Plus 表结构风格一致；业务服务必须验证 Take 归属。

继续保留：

```text
drama_shot.video_url
```

其语义调整为“当前 Take 的视频 URL 兼容投影”。所有旧页面继续读取它，新实现每次选择 Take 时必须同步更新它。

### 5.3 可选的媒体元数据

本次不要求新增自动 ffprobe。如果现有视频返回中没有可靠元数据，`duration` 可以使用分镜目标时长，宽高和文件大小暂不入库。

不要为了历史列表缩略图重新引入视频生成后的自动 ffmpeg 抽帧。历史卡片的 poster 可以使用 Take 保存的 `firstFrameUrl`。

## 6. 现有数据回填

升级脚本需要把已有 `drama_shot.video_url` 转成一条历史 Take，否则上线后用户会看不到当前旧视频。

可使用分镜 ID 作为回填 Take ID。不同表的主键空间独立，不会与 `drama_shot` 冲突：

```sql
INSERT INTO `drama_shot_video_take` (
    `id`, `drama_id`, `episode_id`, `scene_id`, `shot_group_id`,
    `shot_id`, `take_no`, `task_id`, `source_type`, `status`,
    `video_url`, `generation_mode`, `duration`, `prompt_snapshot`,
    `negative_prompt_snapshot`, `first_frame_url`, `end_frame_url`,
    `ref_images_json`, `ref_audios_json`, `create_time`, `update_time`, `deleted`
)
SELECT
    s.`id`, s.`drama_id`, s.`episode_id`, s.`scene_id`, s.`shot_group_id`,
    s.`id`, 1, NULL, 'LEGACY_BACKFILL', 'AVAILABLE',
    s.`video_url`, s.`generation_mode`, s.`duration`, s.`video_prompt`,
    s.`negative_prompt`, s.`preview_image_url`, s.`end_frame_image_url`,
    s.`ref_images_json`, s.`ref_audios_json`,
    COALESCE(s.`update_time`, NOW()), COALESCE(s.`update_time`, NOW()), 0
FROM `drama_shot` s
WHERE s.`video_url` IS NOT NULL
  AND s.`video_url` <> ''
  AND NOT EXISTS (
      SELECT 1 FROM `drama_shot_video_take` t WHERE t.`shot_id` = s.`id`
  );

UPDATE `drama_shot` s
JOIN `drama_shot_video_take` t
  ON t.`shot_id` = s.`id`
 AND t.`source_type` = 'LEGACY_BACKFILL'
SET s.`current_video_take_id` = t.`id`
WHERE s.`current_video_take_id` IS NULL;
```

要求：

- 升级脚本必须可安全重复执行，使用 `NOT EXISTS` 或等价防重逻辑。
- 如果目标库中已经存在 Take，不得重复回填或覆盖当前选择。
- 执行升级前后分别统计有 `video_url` 的分镜数和成功回填数。

## 7. 后端代码结构

### 7.1 Entity 与 Mapper

新增：

```text
entity/DramaShotVideoTake.java
dao/DramaShotVideoTakeMapper.java
```

`DramaShotVideoTake` 继承 `BaseEntity`，使用：

```java
@TableName("drama_shot_video_take")
```

修改：

```text
entity/DramaShot.java
dto/drama/DramaShotDTO.java
dto/drama/DramaShotVO.java
```

增加 `currentVideoTakeId`。`DramaShotVO` 可增加 `videoTakeCount`，便于卡片显示历史数量，但不要在分镜列表接口中嵌入完整历史列表，避免 N+1 和响应膨胀。

### 7.2 DTO / VO

新增：

```text
dto/drama/ShotVideoTakeQuery.java
dto/drama/ShotVideoTakeVO.java
dto/drama/ShotVideoTakeSelectVO.java
```

`ShotVideoTakeQuery`：

```java
private long current = 1;
private long size = 12;
private String status; // 首版默认 AVAILABLE，不允许前端任意 SQL 值
```

限制：`size` 最大 50。

`ShotVideoTakeVO` 建议字段：

```java
private Long id;
private Long shotId;
private Integer takeNo;
private String taskId;
private String sourceType;
private String status;
private String videoUrl;
private Boolean current;
private Long providerId;
private String providerName;
private String modelCode;
private String generationMode;
private Long seed;
private String size;
private BigDecimal duration;
private String promptSnapshot;
private String negativePromptSnapshot;
private String firstFrameUrl;
private String endFrameUrl;
private LocalDateTime createTime;
```

默认列表不返回 `requestSnapshotJson`、`refImagesJson` 和 `refAudiosJson` 的完整内容，避免响应过大；如果未来需要详细信息，可增加 Take 详情接口。

`ShotVideoTakeSelectVO`：

```java
private Long shotId;
private Long previousTakeId;
private Long currentTakeId;
private String videoUrl;
private Boolean changed;
```

### 7.3 Service

新增：

```text
service/ShotVideoTakeService.java
service/impl/ShotVideoTakeServiceImpl.java
```

建议接口：

```java
Page<ShotVideoTakeVO> pageByShotId(Long shotId, ShotVideoTakeQuery query);

DramaShotVideoTake recordGeneratedTake(
        DramaShot shotSnapshot,
        RenderTask renderTask,
        DramaShotRenderRequestDTO requestSnapshot,
        VideoGenerationResultVO result);

ShotVideoTakeSelectVO selectTake(Long shotId, Long takeId);
```

可增加内部方法：

```java
int allocateNextTakeNo(Long shotId);
boolean shouldAutoSelect(Long shotId, String taskId);
```

### 7.4 Take 编号并发安全

不能简单执行 `count + 1`，并发生成会拿到相同编号。

可选实现：

1. 在短事务内对 `drama_shot` 当前行执行 `SELECT ... FOR UPDATE`；
2. 查询该 `shot_id` 的 `MAX(take_no)`；
3. 插入 `max + 1`；
4. 依靠唯一索引 `uk_shot_take_no` 做最终保护。

如果发生唯一键冲突，最多重试 2 次。不要用 JVM `synchronized` 作为唯一保障，因为未来可能多实例部署。

### 7.5 成功视频写入流程

修改 `DramaShotServiceImpl.executeShotVideoRenderAsync()`。

生成外部视频之前应保留一份生成输入快照，避免异步运行期间用户修改分镜后，历史 Take 保存了错误的 Prompt 或参考素材。

建议流程：

```text
提交任务
  → 保存 render_task
  → latestTaskId = taskId
  → 异步读取并冻结 shotSnapshot / requestSnapshot
  → 调用视频生成
  → 视频归档 MinIO
  → 创建 VideoTake（所有成功结果都创建）
  → 判断 taskId 是否仍等于 drama_shot.latestTaskId
       ├─ 是：自动选择该 Take，更新 videoUrl
       └─ 否：只进入历史，不覆盖当前视频
  → 完成 render_task
```

关键规则：

- 每个成功归档的视频都必须创建 Take，即便它不是最新任务。
- 只有 `taskId == shot.latestTaskId` 的成功任务才允许自动成为当前视频。
- 旧任务晚完成时仍显示在历史中，但不能覆盖后来提交任务的结果。
- Take 插入与“是否自动选中”应在同一个短数据库事务中完成。
- 外部 API 调用和视频下载不能放入数据库事务。
- `render_task.output_url` 继续保存本次任务产物 URL。

### 7.6 自动选择与失败行为

提交新抽卡任务时不要立刻清空当前视频。这样新任务失败时，用户仍能观看之前选中的视频。

成功且为最新任务时：

```java
shot.setCurrentVideoTakeId(take.getId());
shot.setVideoUrl(take.getVideoUrl());
shot.setRenderStatus("SUCCESS");
```

失败时：

- 不创建 Take；
- 不修改 `currentVideoTakeId`；
- 不清空现有 `videoUrl`；
- 渲染状态可设为 `FAILED`，但页面仍应能展示当前视频。

### 7.7 手工选择历史 Take

`selectTake(shotId, takeId)` 必须在事务中：

1. 查询分镜，不存在返回 404。
2. 查询 Take，不存在返回 404。
3. 校验 `take.shotId == shotId`，不匹配返回 400/403，禁止跨分镜绑定。
4. 校验 `take.status == AVAILABLE` 且未逻辑删除。
5. 校验 `videoUrl` 非空。
6. 可通过 MinIO `statObject` 检查对象是否存在；对象不存在则把 Take 标为 `UNAVAILABLE` 并返回业务错误。
7. 当前已经是该 Take 时幂等返回 `changed=false`。
8. 更新：

   ```java
   shot.setCurrentVideoTakeId(take.getId());
   shot.setVideoUrl(take.getVideoUrl());
   ```

9. 清除与旧当前视频绑定的派生尾帧缓存，详见第 10 节。
10. 不修改 Take 的创建时间、生成参数和任务历史。

接口只接收 `takeId`，绝不能接收客户端传来的 `videoUrl` 直接落库。

## 8. API 设计

建议在 `DramaShotController` 下暴露：

### 8.1 查询分镜视频历史

```http
GET /drama/shot/{shotId}/video-takes?current=1&size=12
```

响应：

```json
{
  "records": [
    {
      "id": "2032095628944588801",
      "shotId": "2032095628944588700",
      "takeNo": 4,
      "taskId": "RENDER_1789900000000",
      "sourceType": "AI_GENERATED",
      "status": "AVAILABLE",
      "videoUrl": "http://minio/video-assets/projects/1/shots/2/takes/video_4.mp4",
      "current": true,
      "providerId": "2032000000000000001",
      "providerName": "Local FastAPI",
      "modelCode": "MiniMax-H3-FL2VA",
      "generationMode": "FIRST_LAST_FRAME",
      "seed": 123456,
      "size": "544x960",
      "duration": 5.0,
      "firstFrameUrl": "http://minio/.../first.png",
      "createTime": "2026-09-20T18:00:00"
    }
  ],
  "total": 4,
  "current": 1,
  "size": 12,
  "pages": 1
}
```

默认排序：

```text
create_time DESC, take_no DESC, id DESC
```

### 8.2 选择历史视频

```http
POST /drama/shot/{shotId}/video-takes/{takeId}/select
```

无需请求体。

响应：

```json
{
  "shotId": "2032095628944588700",
  "previousTakeId": "2032095628944588799",
  "currentTakeId": "2032095628944588801",
  "videoUrl": "http://minio/video-assets/projects/1/shots/2/takes/video_4.mp4",
  "changed": true
}
```

接口必须幂等。

### 8.3 可选的 Take 详情

如果前端确实需要查看完整 Prompt 和参考素材，再增加：

```http
GET /drama/shot/{shotId}/video-takes/{takeId}
```

首版历史抽屉能满足需求时不要提前实现。

## 9. 前端设计

### 9.1 类型与 API

在 `front/src/types/drama.d.ts` 新增：

```ts
export interface ShotVideoTake {
  id: string
  shotId: string
  takeNo: number
  taskId?: string
  sourceType: 'AI_GENERATED' | 'LEGACY_BACKFILL' | string
  status: 'AVAILABLE' | 'UNAVAILABLE' | 'ARCHIVED' | string
  videoUrl: string
  current: boolean
  providerId?: string
  providerName?: string
  modelCode?: string
  generationMode?: 'FIRST_LAST_FRAME' | 'REFERENCE_MODE' | string
  seed?: number
  size?: string
  duration?: number
  promptSnapshot?: string
  negativePromptSnapshot?: string
  firstFrameUrl?: string
  endFrameUrl?: string
  createTime?: string
}

export interface ShotVideoTakeSelectResult {
  shotId: string
  previousTakeId?: string
  currentTakeId: string
  videoUrl: string
  changed: boolean
}
```

所有 19 位 ID 必须声明和保持为 `string` 或 `string | number`，请求前严禁 `Number()`、`parseInt()`、一元 `+`。

在 `front/src/api/drama.ts` 的 `shotApi` 增加：

```ts
getVideoTakes(shotId: string | number, params: { current?: number; size?: number })
selectVideoTake(shotId: string | number, takeId: string | number)
```

### 9.2 历史 UI 入口

主要入口放在 `ShotRenderStepModal.vue` 的视频生成步骤，当前视频区域旁增加：

```text
[历史视频（N）]
```

同时可在 `StoryboardGrid.vue` 分镜卡片更多菜单增加“查看视频历史”，但首版可以只完成渲染工坊入口，避免重复维护两个弹窗状态。

建议新增独立组件：

```text
front/src/views/drama/components/ShotVideoHistoryDrawer.vue
```

由 `ShotRenderStepModal` 调用，不要把全部历史逻辑继续堆入现有大组件。

### 9.3 历史抽屉布局

建议使用右侧 Drawer，宽度约 760px：

```text
┌─────────────────────────────────────┐
│ S03 视频抽卡历史              刷新 │
│ 共 6 个成功版本                     │
├─────────────────────────────────────┤
│ [Take #6 · 当前]   [Take #5]        │
│ 视频/首帧封面       视频/首帧封面   │
│ H3 · 544x960 · 5s  H3 · 544x960    │
│ 2026-09-20 18:20   18:13            │
│ [播放]              [设为当前视频]  │
├─────────────────────────────────────┤
│ 分页                                │
└─────────────────────────────────────┘
```

交互要求：

- 当前 Take 显示明显的“当前使用”标记，选择按钮禁用。
- 非当前 Take 提供“预览”和“设为当前视频”。
- 点击选择前弹出确认：
  “将 Take #N 设为当前镜头视频？不会删除其他历史版本。”
- 选择请求期间只禁用对应卡片，防止重复提交。
- 成功后更新抽屉中的 `current` 标记，无需关闭抽屉。
- 向父组件发送：

  ```ts
  emit('selected', { takeId, videoUrl })
  ```

- 父组件立即更新 `currentVideoUrl` 并触发已有 `success` 事件刷新工作台。
- 视频列表使用 `preload="metadata"`，不要一次预加载所有完整视频。
- 卡片 poster 优先使用 `firstFrameUrl`，不要为了缩略图触发尾帧抽取。

### 9.4 当前播放器

选择历史版本后：

- `ShotRenderStepModal.currentVideoUrl` 更新为选择结果的 `videoUrl`；
- `StoryboardGrid` 刷新后继续从 `shot.videoUrl` 播放；
- 如果播放器正在播放旧 URL，应先暂停并重新设置 source，避免浏览器继续展示旧缓存；
- 显示当前 Take 编号（如果 `currentVideoTakeId` 和 Take 摘要可用）。

### 9.5 空状态和异常状态

- 没有历史：显示“尚无成功生成的视频版本”。
- Take 对象丢失：标记“视频文件不可用”，禁用选择。
- 当前 `videoUrl` 存在但 `currentVideoTakeId` 为空：显示“旧版视频”，提示刷新/迁移，不允许前端自行构造 Take。
- 选择接口返回 409/400：刷新历史列表和当前分镜，显示后端提示。

## 10. 与按需尾帧方案的联动

本项目已有计划：

```text
docs/on-demand-previous-video-tail-plan.md
```

实现两个计划时，以 Take ID 作为视频版本标识比 URL 更可靠。建议对该计划做以下兼容调整：

### 10.1 来源镜尾帧缓存

优先使用：

```sql
last_frame_source_take_id BIGINT UNSIGNED DEFAULT NULL
```

替代或补充之前建议的 `last_frame_source_video_url`。

尾帧缓存有效条件改为：

```text
lastFrameUrl 非空
且 lastFrameSourceTakeId == currentVideoTakeId
```

### 10.2 当前镜首帧来源

从上一镜当前视频 Take 提取尾帧作为当前首帧时，建议记录：

```sql
first_frame_source_video_take_id BIGINT UNSIGNED DEFAULT NULL
```

这样即使对象访问 URL 或 MinIO 域名变化，来源关系仍可追溯。

### 10.3 切换当前视频时必须失效

当用户选择另一个历史 Take 作为当前视频时，对该分镜执行：

```java
shot.setLastFrameUrl(null);
shot.setLastFrameSourceTakeId(null);
```

原因：`lastFrameUrl` 是旧当前视频派生的图片，不能继续代表新选择的视频。

这只清理当前分镜自己的派生尾帧缓存，不应自动覆盖下一镜已经引用并可能人工修改过的首帧。下一镜如果记录了来源 Take 且来源已变化，前端可以显示“来源视频版本已变化”的过期提示，但不得静默覆盖。

## 11. 删除、归档与 MinIO 生命周期

首版规则：

- 重新抽卡不删除旧视频。
- 选择历史版本不复制文件。
- 删除分镜时逻辑删除所属 Take 记录；是否物理删除 MinIO 对象另做清理任务，不在 Controller 同步删除。
- 当前 Take 不允许单独归档。
- `UNAVAILABLE` 表示数据库记录存在但对象不可访问。
- `ARCHIVED` 预留给未来用户主动隐藏历史版本，首版不必提供接口。

禁止在本功能中加入“切换后立即删除其他视频”的行为。

## 12. 一致性与异常处理

### 12.1 视频已归档但 Take 插入失败

记录 ERROR 日志，包含 `taskId`、`shotId` 和 `videoUrl`，并将渲染任务标记为失败或“产物归档失败待修复”，不能悄悄丢失历史。

推荐：Take 插入失败时任务不标记 SUCCESS，因为从产品角度视频没有进入可管理资产库。不要删除已上传的 MinIO 对象，可后续修复或清理。

### 12.2 Take 已插入但自动选择失败

Take 保留在历史中。任务可标记成功，但应记录“生成成功，自动选择失败”的明确错误/告警，并允许用户从历史手工选择。

更推荐将 Take 插入和自动选择放在同一短事务中，避免该状态。

### 12.3 选择时对象已丢失

- 不更新分镜；
- 将 Take 标记为 `UNAVAILABLE`；
- 返回明确错误；
- 历史 UI 刷新后禁用该版本。

### 12.4 并发选择

选择接口在更新时锁定 `drama_shot` 行，后到请求可以覆盖先到请求，最终状态以事务提交顺序为准。每次响应返回实际的 `currentTakeId`。

如果产品需要更强的防覆盖，可后续增加 `expectedCurrentTakeId` 做乐观锁；首版不是必须。

## 13. 测试计划

### 13.1 `ShotVideoTakeServiceTest`

至少覆盖：

1. 第一次成功视频生成创建 Take #1 并设为当前。
2. 再次成功生成创建 Take #2，Take #1 保留。
3. 两个任务并发，较旧任务晚完成也不能覆盖最新任务。
4. 较旧任务成功仍进入历史。
5. 失败任务不创建 Take，不清空现有当前视频。
6. 同一 `taskId` 重复回调不重复创建 Take。
7. 并发分配 Take 编号不重复。
8. 历史分页只返回指定 `shotId` 的数据。
9. 历史排序为最新优先。
10. 当前 Take 的 `current=true`，其他为 false。
11. 选择属于本分镜的 AVAILABLE Take 成功。
12. 重复选择当前 Take 幂等，`changed=false`。
13. 拒绝选择其他分镜的 Take。
14. 拒绝选择 UNAVAILABLE/ARCHIVED Take。
15. MinIO 对象不存在时不切换并标记 UNAVAILABLE。
16. 选择新 Take 同步更新 `currentVideoTakeId` 和 `videoUrl`。
17. 选择新 Take 清除旧 `lastFrameUrl` 和尾帧来源版本。

### 13.2 `DramaShotServiceImpl` 回归测试

至少覆盖：

1. 视频成功后先创建 Take，再完成任务。
2. 最新任务自动选择。
3. 非最新任务不覆盖当前视频。
4. 请求快照来自提交/执行时快照，而不是后来修改的分镜。
5. API Key、Base64 媒体不进入 `requestSnapshotJson`。
6. 新任务提交时不清空旧 `videoUrl`。

### 13.3 Controller 测试

- 分页参数和最大 size 限制。
- Snowflake ID 字符串能正确反序列化为 Long。
- 选择接口返回正确结果。
- 越权/跨分镜 Take 被拒绝。

### 13.4 前端验证

1. 有多个 Take 时正确显示数量和当前标记。
2. 历史视频可以播放。
3. 选择后当前播放器立即切换。
4. 分镜卡片刷新后播放新选择的视频。
5. 重复点击选择不会发送多次并发请求。
6. 大于 `Number.MAX_SAFE_INTEGER` 的 ID 原样出现在请求 URL。
7. 新视频生成失败后旧当前视频仍显示。
8. 分页切换正常且不会预加载全部视频文件。
9. 不可用 Take 禁止选择。

## 14. 文档更新

更新：

- `docs/drama-api.md`：增加视频 Take 历史和选择接口。
- `Readme.md`：在分镜工作台能力中增加“视频抽卡历史与版本重选”。
- `docs/on-demand-previous-video-tail-plan.md`：实际实施时将视频版本判断优先改为 Take ID。
- `ShotRenderStepModal.vue`、`DramaShotServiceImpl`、相关 DTO 的注释。

不要把该功能描述为“任务历史恢复”。准确表述为“成功视频候选版本历史”。

## 15. 建议实施顺序

1. 新增升级 SQL，并更新 `init.sql`。
2. 新增 Entity、Mapper、DTO、VO。
3. 实现 `ShotVideoTakeService` 和单元测试。
4. 改造视频生成成功落库流程，处理并发任务乱序。
5. 实现历史分页和选择接口。
6. 回填现有 `videoUrl` 数据并验证数量。
7. 新增前端类型和 API。
8. 新增 `ShotVideoHistoryDrawer.vue`。
9. 接入 `ShotRenderStepModal.vue`，必要时接入 `StoryboardGrid.vue`。
10. 联动按需尾帧缓存失效逻辑。
11. 更新文档，执行后端测试和前端构建。

## 16. 验证命令

后端使用 JDK 26 与 Maven Wrapper：

```powershell
$env:JAVA_HOME = "F:\program_file\jdk26"
.\mvnw.cmd test
```

前端：

```powershell
$env:PATH = "D:\program_file\mvn\nvm\v24.9.0;$env:PATH"
Set-Location front
npm run build
```

数据库升级后建议执行校验查询：

```sql
SELECT COUNT(*) AS shots_with_video
FROM drama_shot
WHERE video_url IS NOT NULL AND video_url <> '' AND deleted = 0;

SELECT COUNT(DISTINCT shot_id) AS shots_with_take
FROM drama_shot_video_take
WHERE deleted = 0;

SELECT COUNT(*) AS broken_current_pointer
FROM drama_shot s
LEFT JOIN drama_shot_video_take t ON t.id = s.current_video_take_id
WHERE s.current_video_take_id IS NOT NULL
  AND (t.id IS NULL OR t.shot_id <> s.id OR t.deleted = 1);
```

`broken_current_pointer` 必须为 0。

## 17. 完成标准

同时满足以下条件才算完成：

- 每次成功视频抽卡都新增且仅新增一条 Take。
- 失败和取消任务不会出现在可选择视频历史中。
- 历史列表只展示当前分镜的视频，不会串到其他镜头。
- 新抽卡成功后默认成为当前视频，但旧任务乱序完成不能覆盖新任务。
- 用户可以把任意可用历史 Take 重新设为当前视频。
- 切换只更新指针和 `videoUrl` 投影，不复制、不重新生成、不删除视频。
- 当前 Take 与 `drama_shot.video_url` 始终一致。
- 切换视频后旧尾帧缓存失效。
- 现有旧视频被正确回填为历史 Take。
- 前端 Snowflake ID 全程无精度损失。
- 后端测试通过，前端生产构建通过。
- API 文档、数据库结构、界面文案和实际行为一致。

