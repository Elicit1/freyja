# 上一镜视频尾帧按需提取与首帧引用实施计划

## 1. 目标

在 `FIRST_LAST_FRAME`（首尾帧）模式中增加“从上一镜视频提取尾帧并引用为当前镜首帧”的显式操作。

核心原则：

1. 视频生成完成后只归档视频，不再自动执行 ffmpeg 尾帧提取。
2. 只有创作者主动点击“从上一镜视频提取并引用尾帧”时，后端才执行尾帧提取。
3. 被引用的必须是上一镜**实际生成视频**的尾部帧，不能用上一镜的设计尾帧 `endFrameImageUrl`、首帧 `previewImageUrl` 代替。
4. 后端负责确定上一镜、校验视频版本、抽帧、归档和绑定；前端不得自行猜测上一镜或拼接多个图片字段兜底。
5. 所有前端 Snowflake ID 必须保持为字符串，严禁 `Number(id)`、`parseInt(id)` 或一元 `+id`。

## 2. 本次范围

### 2.1 必须完成

- 移除视频生成成功后的自动尾帧提取。
- 新增上一镜视频尾帧按需提取与引用接口。
- 新增后端上一镜确定逻辑。
- 提取结果上传 MinIO，并缓存到上一镜的 `lastFrameUrl`。
- 将提取结果设置到当前镜的 `previewImageUrl`。
- 记录尾帧缓存对应的视频版本，避免视频重新生成后复用旧尾帧。
- 修改首尾帧模式弹窗及分镜抽屉的交互。
- 删除前端使用 `endFrameImageUrl`、`previewImageUrl` 冒充上一视频尾帧的兜底逻辑。
- 补齐后端单元测试和前端构建验证。
- 更新相关 API 文档和连续镜头组文档。

### 2.2 本次不做

- 不自动跨 ShotGroup 继承尾帧。
- 不在普通单镜视频生成后自动抽帧；原批量渲染和镜头组串行渲染入口已移除。
- 不引入图像内容识别、黑帧语义判断或任何关键字推断。
- 不允许用户提交任意外部视频 URL 让服务器下载抽帧。
- 不重写现有视频生成协议或 FastAPI 网关。

未来如需全自动连续渲染，应单独增加用户显式勾选的 `autoInheritPreviousVideoTail` 能力，不能恢复为默认行为。

## 3. 当前实现与问题

### 3.1 已有能力

- `DramaShot` 已有：
  - `videoUrl`：实际生成的视频。
  - `lastFrameUrl`：从实际视频抽取的尾帧。
  - `previewImageUrl`：当前镜视频首帧。
  - `endFrameImageUrl`：生成视频前设置的设计尾帧。
- `VideoFrameExtractService` 已支持使用 ffmpeg 从视频字节提取尾部帧。
- `AiImageApiServiceImpl.generateAndArchiveVideo()` 已能归档视频到 MinIO。
- 前端已有“引用上一镜尾帧”入口，但当前来源判定不正确。

### 3.2 必须修复的问题

1. `AiImageApiServiceImpl.generateAndArchiveVideo()` 当前在每次视频生成后自动执行 ffmpeg，并返回 `lastFrameUrl`。该行为必须移除。
2. `ShotDrawer.vue` 当前使用：

   ```ts
   prev.endFrameImageUrl || prev.lastFrameUrl || prev.previewImageUrl
   ```

   这会把设计尾帧或首帧误认为真实视频尾帧，必须删除。
3. 前端按 `currentShotNo - 1` 查找上一镜不可靠。镜头重排、镜头编号不连续或同场次多 ShotGroup 时会选错，必须由后端按持久化顺序判定。
4. 原镜头组串行渲染与批量渲染入口已移除。本功能只保留创作者显式触发的单镜尾帧提取与引用，不将组级调度作为实现前提。

## 4. 业务定义

### 4.1 字段语义

| 字段 | 语义 | 是否可作为本功能来源 |
|---|---|---:|
| `videoUrl` | 上一镜实际生成视频 | 是，抽帧源 |
| `lastFrameUrl` | 从 `videoUrl` 按需抽取并归档的尾部稳定帧 | 是，缓存命中时复用 |
| `endFrameImageUrl` | 视频生成前设计/生成的目标尾帧 | 否 |
| `previewImageUrl` | 当前镜视频首帧 | 本功能的写入目标，不是上一镜尾帧来源 |

### 4.2 上一镜定义

首版只允许在同一个 `shotGroupId` 中查找直接前驱镜头：

1. 当前镜必须存在且 `shotGroupId` 非空。
2. 查询同一 `shotGroupId` 的全部有效镜头。
3. 按以下顺序稳定排序：
   - `sortOrder` 升序（空值放最后或统一按 0，必须在代码和测试中固定一种规则）；
   - `shotNo` 升序；
   - `id` 升序。
4. 找到当前镜在排序列表中的直接前一个镜头。
5. 直接前驱不存在时返回业务错误；不得跳过无视频镜头继续向前找。
6. 直接前驱没有 `videoUrl` 时返回“请先生成上一镜视频”；不得用其他图片字段兜底。

建议将顺序查询封装为独立方法，避免 Controller、Service 和前端分别实现不同规则。

### 4.3 尾帧定义

本功能提取“视频结束前一定偏移量处的尾部稳定帧”，不是强求容器中的最后一个可解码帧。

- 默认偏移量：300ms。
- 合法范围：0～2000ms。
- 配置项建议：

  ```yaml
  freyja:
    ffmpeg:
      tail-offset-ms: 300
  ```

- `VideoFrameExtractService` 不应继续硬编码 `-0.5`，改为接收偏移毫秒并安全转换为 `-sseof` 参数。
- 产品文案统一使用“尾部稳定帧”，避免声称一定是编码层最后一帧。

## 5. 数据结构调整

### 5.1 `drama_shot` 新增字段

最少增加以下字段：

```sql
ALTER TABLE `drama_shot`
    ADD COLUMN `last_frame_source_video_url` VARCHAR(512) DEFAULT NULL
        COMMENT 'last_frame_url 对应的视频版本 URL，用于缓存失效判断',
    ADD COLUMN `first_frame_source_type` VARCHAR(32) DEFAULT NULL
        COMMENT '首帧来源: MANUAL_UPLOAD/AI_GENERATED/PREVIOUS_VIDEO_TAIL',
    ADD COLUMN `first_frame_source_shot_id` BIGINT DEFAULT NULL
        COMMENT '首帧来自上一镜视频尾帧时的来源分镜ID',
    ADD COLUMN `first_frame_source_video_url` VARCHAR(512) DEFAULT NULL
        COMMENT '首帧引用时对应的来源视频版本URL';
```

执行要求：

1. 更新 `src/main/resources/sql/init.sql` 中 `drama_shot` 的建表结构。
2. 另行新增现有数据库升级脚本，例如：
   `src/main/resources/sql/upgrade_previous_video_tail.sql`。
3. 升级脚本应注明执行前检查字段是否已存在；MySQL 版本支持时使用 `ADD COLUMN IF NOT EXISTS`，否则提供查询 `information_schema.columns` 的执行说明。
4. 更新 `DramaShot`、`DramaShotDTO`、`DramaShotVO` 和前端 `DramaShot` 类型。

### 5.2 缓存有效性

只有同时满足以下条件才能复用上一镜的 `lastFrameUrl`：

```text
lastFrameUrl 非空
且 lastFrameSourceVideoUrl == videoUrl
```

上一镜生成新视频并成功落库时必须清空：

```java
shot.setLastFrameUrl(null);
shot.setLastFrameSourceVideoUrl(null);
```

注意：只在新视频已经生成并成功归档后清空旧尾帧缓存，生成失败时不能破坏旧视频与旧尾帧的对应关系。

## 6. 后端设计

### 6.1 新增 DTO/VO

建议新增：

```text
dto/drama/PreviousVideoTailRequest.java
dto/drama/PreviousVideoTailVO.java
```

`PreviousVideoTailRequest`：

```java
private Boolean forceExtract; // 默认 false
private Integer tailOffsetMs; // 为空使用配置值，范围 0～2000
```

首版不要暴露 `scope`，固定同 ShotGroup，避免接口表面支持实际未实现的跨组行为。

`PreviousVideoTailVO`：

```java
private Long currentShotId;
private Long sourceShotId;
private Integer sourceShotNo;
private String sourceShotName;
private String sourceVideoUrl;
private String tailFrameUrl;
private Boolean reused;
private Boolean applied;
```

Jackson 会按项目全局配置把 Long 序列化为字符串；不要手工转数字。

### 6.2 新增领域服务

建议新增：

```text
service/ShotFrameContinuityService.java
service/impl/ShotFrameContinuityServiceImpl.java
```

接口：

```java
PreviousVideoTailVO inheritPreviousVideoTail(
        Long currentShotId,
        PreviousVideoTailRequest request);
```

服务流程：

1. 查询并校验当前镜。
2. 在同一 ShotGroup 内解析直接前驱镜头。
3. 校验前驱镜头 `videoUrl` 非空。
4. 若未强制提取且缓存版本匹配，则复用 `lastFrameUrl`。
5. 否则从 MinIO 获取前驱视频，执行 ffmpeg 抽帧并上传新图片。
6. 抽帧成功后回写前驱镜头：
   - `lastFrameUrl`；
   - `lastFrameSourceVideoUrl = videoUrl`。
7. 再次读取当前镜，防止抽帧期间镜头已被删除或移动到其他组。
8. 确认当前镜与来源镜仍为直接前后关系。
9. 回写当前镜：
   - `previewImageUrl = tailFrameUrl`；
   - `firstFrameSourceType = PREVIOUS_VIDEO_TAIL`；
   - `firstFrameSourceShotId = sourceShotId`；
   - `firstFrameSourceVideoUrl = sourceVideoUrl`。
10. 返回来源信息与是否命中缓存。

长耗时的视频下载和 ffmpeg 执行不要包在长数据库事务中。数据库更新应使用短事务；在最终绑定前重新校验关系。

### 6.3 视频读取与安全边界

项目生成的视频已经归档到自己的 MinIO，因此本功能首版只读取当前配置 bucket 内的对象：

1. 从 `videoUrl` 解析 bucket 和 object key。
2. bucket 必须等于 `minio.bucket-name`。
3. 对 URL 解码和规范化后，object key 不得为空、不得包含路径穿越。
4. 通过 `MinioClient.getObject()` 读取。
5. 不允许直接下载任意 `http://` 或 `https://` 地址，避免 SSRF。

如果历史数据的视频不在本 MinIO，应返回明确错误，不要静默访问外部 URL。未来需要支持外部对象存储时再增加域名白名单。

### 6.4 抽帧与归档

可以复用 `VideoFrameExtractService`，但建议增加重载：

```java
byte[] extractLastFrame(byte[] videoBytes, String extension, int tailOffsetMs);
```

旧的双参数方法可暂时保留并委托到默认配置，避免影响其他调用方。

MinIO 对象路径建议包含来源视频版本摘要，保证幂等和便于排查：

```text
projects/{dramaId}/shots/{sourceShotId}/frames/video_tail_{versionHash}_{offsetMs}.jpg
```

若不引入摘要，也至少使用时间戳并在数据库中保存最终 URL。不得覆盖用户上传的图片对象。

### 6.5 并发与幂等

同一来源镜头可能被重复点击。首版至少实现 JVM 内按 `sourceShotId` 加锁：

- 锁内再次读取 `videoUrl`、`lastFrameUrl` 和 `lastFrameSourceVideoUrl`。
- 已有有效缓存则直接返回。
- `finally` 中释放锁并清理无用锁对象。

如果未来多实例部署，再替换成 Redis 分布式锁。当前不要为此新增 Redisson 依赖。

### 6.6 Controller 接口

在 `DramaShotController` 新增：

```http
POST /drama/shot/{currentShotId}/inherit-previous-video-tail
```

请求示例：

```json
{
  "forceExtract": false,
  "tailOffsetMs": 300
}
```

成功响应数据：

```json
{
  "currentShotId": "2032095628944588801",
  "sourceShotId": "2032095628944588702",
  "sourceShotNo": 3,
  "sourceShotName": "S01-03",
  "sourceVideoUrl": "http://minio/video-assets/projects/1/shots/2/takes/video_123.mp4",
  "tailFrameUrl": "http://minio/video-assets/projects/1/shots/2/frames/video_tail_xxx_300.jpg",
  "reused": false,
  "applied": true
}
```

业务错误建议：

| 场景 | HTTP/业务码 | 提示 |
|---|---:|---|
| 当前镜不存在 | 404 | 当前分镜不存在 |
| 当前镜没有 ShotGroup | 400 | 当前分镜未归属连续镜头组 |
| 没有直接前驱镜头 | 400 | 当前分镜没有可引用的上一镜 |
| 上一镜没有视频 | 400 | 请先生成上一镜视频 |
| 视频不属于本 MinIO | 400 | 上一镜视频不支持服务端尾帧提取 |
| ffmpeg 不可用/超时/失败 | 500 | 上一镜视频尾帧提取失败，请检查 ffmpeg 配置 |
| 抽帧期间顺序被修改 | 409 | 镜头顺序已变化，请刷新后重试 |

## 7. 移除自动抽帧

修改 `AiImageApiServiceImpl.generateAndArchiveVideo()`：

1. 保留视频下载和 MinIO 归档。
2. 删除生成成功后的 `videoFrameExtractService.extractLastFrame(...)` 调用。
3. 不再在这里上传尾帧图片。
4. 返回的 `VideoGenerationResultVO.lastFrameUrl` 为 `null`；后续可考虑从该 VO 删除字段，但本次为了兼容前端和任务对象可以保留。
5. 视频成功回写时清理该镜旧的尾帧缓存字段。
6. `RenderTaskService.finishTask()` 的 `lastFrameUrl` 参数传 `null`。

不要删除 `VideoFrameExtractService`，新按需服务仍需使用它。

同时检查 `VideoProcessingServiceImpl` 中对 `extractLastFrame()` 的其他调用：视频后处理产物是否需要尾帧属于另一个业务决策。本次只移除“分镜视频生成后自动抽尾帧”，不要误删与其他独立功能有关的逻辑；如后处理也在无条件抽帧且没有消费者，应单独记录并评估，不在本任务擅自扩大范围。

## 8. 前端设计

### 8.1 API

在 `front/src/api/drama.ts` 增加：

```ts
inheritPreviousVideoTail(
  currentShotId: string | number,
  data?: { forceExtract?: boolean; tailOffsetMs?: number }
): Promise<PreviousVideoTailResult>
```

URL 中使用原始 ID 字符串插值，不进行数字转换。

在 `front/src/types/drama.d.ts` 增加响应类型，并将本次触碰到的 `DramaShot.id`、`shotGroupId` 等 ID 类型至少改为 `string | number`。不得借本任务进行无边界的全项目类型重写，但新代码必须符合 Snowflake ID 规则。

### 8.2 首尾帧渲染弹窗

修改 `ShotFirstEndFrameRenderModal.vue`：

1. `open()` 不再接收或依赖 `prevShotTailUrl`。
2. 按钮名称改为“从上一镜视频提取并引用尾帧”。
3. 点击后直接调用新接口，传当前镜 ID。
4. 请求期间按钮进入 loading，防止重复点击。
5. 成功后：
   - `firstFrameUrl = result.tailFrameUrl`；
   - 显示来源镜编号和名称；
   - `reused=true` 时提示“已引用缓存的上一镜视频尾帧”；
   - `reused=false` 时提示“已从上一镜视频提取尾帧并设为当前首帧”；
   - 触发 `success` 刷新父级数据。
6. 失败时保留原首帧，不清空本地状态。
7. 可提供“重新提取”二级操作，需确认后传 `forceExtract=true`，默认按钮不能强制重抽。

### 8.3 分镜抽屉

修改 `ShotDrawer.vue`：

1. 删除 `loadPreviousShotTail()` 中的前端上一镜查找。
2. 删除以下错误兜底：

   ```ts
   prev.endFrameImageUrl || prev.lastFrameUrl || prev.previewImageUrl
   ```

3. “引用上一镜尾帧”按钮统一调用新接口。
4. 接口成功后更新 `form.previewImageUrl` 以及首帧来源展示。
5. 如果分镜尚未保存、没有 ID，则提示先保存。

### 8.4 生图工坊

检查 `ShotImageGenerateModal.vue` 中 `prevTailUrl` 的使用：

- 不再由父组件猜测并传入上一镜图片。
- 如果仍需要“上一视频尾帧作为生图参考图”，应在用户明确点击时调用同一个后端按需接口，再把返回 URL 加入参考图。
- 打开弹窗本身不得触发抽帧，因为“打开”不等于用户确认引用。

### 8.5 来源状态清理

以下行为会把当前镜首帧改成其他来源，必须同步清理或改写来源字段：

- 用户上传首帧：`MANUAL_UPLOAD`，清空来源镜和来源视频。
- AI 生成首帧：`AI_GENERATED`，清空来源镜和来源视频。
- 用户清除首帧：三个来源字段全部置空。
- 用户从上一视频引用：`PREVIOUS_VIDEO_TAIL`，填写来源镜和来源视频。

这部分应由后端接口负责最终落库，前端只更新展示。

## 9. 尾帧引用行为

- 单镜视频生成不会自动抽取尾帧。
- 创作者可在单镜工作流中显式提取并引用上一镜视频尾帧。
- 原批量渲染与镜头组串行渲染入口及其专用调度服务已移除。


## 10. 测试计划

### 10.1 后端单元测试

新增 `ShotFrameContinuityServiceTest`，至少覆盖：

1. 当前镜不存在。
2. 当前镜无 ShotGroup。
3. 当前镜是组内第一镜。
4. 直接上一镜没有 `videoUrl`。
5. 镜头编号不连续时仍按稳定排序找到直接前驱。
6. 同场次不同 ShotGroup 不得跨组引用。
7. 有 `lastFrameUrl` 且视频版本匹配时直接复用，不调用 ffmpeg。
8. `lastFrameUrl` 存在但视频版本不匹配时重新提取。
9. `forceExtract=true` 时重新提取。
10. ffmpeg 返回空或失败时不修改当前镜首帧。
11. 抽帧成功后正确更新来源镜缓存和当前镜首帧来源。
12. 抽帧期间镜头被重排时返回冲突且不绑定当前镜。
13. 视频 URL 指向非本 MinIO bucket 时拒绝处理。
14. 并发请求只执行一次实际抽帧，其余请求复用结果。

### 10.2 视频生成回归测试

为 `AiImageApiServiceImpl` / `DramaShotServiceImpl` 补充或调整测试：

1. 视频生成成功只写 `videoUrl`。
2. 视频生成过程中不调用 `VideoFrameExtractService`。
3. 新视频成功后清空旧 `lastFrameUrl` 和版本字段。
4. 新视频生成失败时保留旧视频和旧尾帧缓存。
5. 渲染任务成功时不再返回自动尾帧 URL。

### 10.3 前端验证

至少人工或组件测试覆盖：

1. 点击按钮后出现 loading。
2. 成功后首帧立即更新。
3. 错误时原首帧不变。
4. 重复点击命中缓存提示。
5. 上一镜无视频时提示明确。
6. 大于 `Number.MAX_SAFE_INTEGER` 的 ID 请求路径保持原字符串。
7. 页面不再把 `endFrameImageUrl` 或 `previewImageUrl` 当作上一视频尾帧。

## 11. 文档更新

需要同步更新：

- `docs/drama-api.md`：新增按需提取与引用接口。
- `docs/shot-group-continuity-api.md`：删除自动抽尾帧与自动续接的默认描述。
- `Readme.md` 技术栈表：将“视频产物归档时抽取末帧”改为“用户引用上一视频尾帧时按需抽取”。
- 相关 JavaDoc、Vue 注释、按钮 tooltip 和操作提示文案。

文档中统一使用：

- “上一镜视频尾部稳定帧”；
- “用户按需提取”；
- “同一连续镜头组的直接前驱镜头”。

## 12. 建议实施顺序

1. 更新数据库结构、实体、DTO/VO 和前端类型。
2. 修改 `VideoFrameExtractService`，支持可配置偏移量。
3. 实现 MinIO 视频读取和尾帧归档。
4. 实现 `ShotFrameContinuityService` 及单元测试。
5. 暴露 Controller 接口并更新 API 文档。
6. 移除视频生成后的自动抽帧，补回归测试。
7. 改造首尾帧弹窗、分镜抽屉和生图工坊入口。
8. 清理镜头组自动续接的过时注释与 UI 文案。
9. 执行后端测试和前端构建。

## 13. 验证命令

后端必须使用 JDK 26 和 Maven Wrapper：

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

如果全量测试受外部 MySQL、Redis、MinIO 或网关环境影响，至少执行相关服务单测并明确记录未执行项和原因；不能以编译通过代替核心业务测试。

## 14. 完成标准

同时满足以下条件才算完成：

- 生成视频后没有任何自动 ffmpeg 尾帧提取。
- 用户不点击引用按钮时，不新增尾帧图片对象。
- 用户点击后能从同 ShotGroup 直接上一镜的实际视频提取并应用首帧。
- 重复引用同一视频版本不会重复执行 ffmpeg。
- 上一镜重新生成视频后不会复用旧尾帧缓存。
- 任何情况下都不会用 `endFrameImageUrl` 或 `previewImageUrl` 冒充视频尾帧。
- 抽帧失败不会覆盖当前镜原首帧。
- 前端 Snowflake ID 全程保持字符串精度。
- 后端测试通过，前端生产构建通过。
- API、Readme、JavaDoc、前端提示与真实行为一致。

