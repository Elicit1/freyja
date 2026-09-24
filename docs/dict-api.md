# 字典管理接口文档 (dict-api)

> 供前端及其他 AI 对接使用。基路径默认 `http://127.0.0.1:8080`（以实际后端端口为准）。
> 所有接口统一返回 `R<T>` 结构，详情见下方"统一返回结构"。

## 统一返回结构

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

- `code = 200` 表示成功；非 200 为失败，`msg` 为可展示给用户的错误信息。
- 参数/唯一性校验失败时返回 `code = 500`（`BizException` 默认错误码），`msg` 说明原因。
- 分页接口 `data` 为 MyBatis-Plus `Page` 结构：

```json
{
  "total": 32,
  "size": 10,
  "current": 1,
  "pages": 4,
  "records": []
}
```

## 实体字段

### 字典类型 sys_dict_type
| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| id | Long | 主键（雪花） |
| dictType | String | 字典类型编码，唯一 |
| dictName | String | 字典类型名称 |
| status | Integer | 状态 0-停用 1-启用 |
| remark | String | 备注 |
| createTime / updateTime | LocalDateTime | 创建/更新时间（自动填充） |
| createBy / updateBy | Long | 创建/更新人 ID（自动填充） |

### 字典数据项 sys_dict_data
| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| id | Long | 主键（雪花） |
| dictType | String | 归属字典类型编码 |
| dictLabel | String | 字典标签（展示用） |
| dictValue | String | 字典键值（存储用），同一类型下唯一 |
| sortOrder | Integer | 显示排序，升序 |
| status | Integer | 状态 0-停用 1-启用 |
| remark | String | 备注 |
| createTime / updateTime | LocalDateTime | 创建/更新时间（自动填充） |
| createBy / updateBy | Long | 创建/更新人 ID（自动填充） |

---

## 1. 字典类型管理

### 1.1 分页查询类型

- **GET** `/dict/type/page`
- Query 参数（均可选）：
  - `pageNum`：页码，默认 1
  - `pageSize`：每页条数，默认 10
  - `dictType`：编码模糊匹配
  - `dictName`：名称模糊匹配
  - `status`：状态等值（0/1）
- 返回：`R<Page<SysDictType>>`，按 `createTime` 倒序。

### 1.2 类型详情

- **GET** `/dict/type/{id}`
- 返回：`R<SysDictType>`；不存在时 `msg` 提示"字典类型不存在"。

### 1.3 新增类型

- **POST** `/dict/type`
- Body（JSON）：

```json
{
  "dictType": "common_status",
  "dictName": "通用状态",
  "status": 1,
  "remark": "备注（可选）"
}
```

- 校验：`dictType`、`dictName` 非空；`status` 必填；`dictType` 全局唯一（重复报"字典类型编码已存在"）。
- 返回：`R<Void>`。

### 1.4 修改类型

- **PUT** `/dict/type`
- Body（JSON）：同新增，另需传 `id`：

```json
{
  "id": 1,
  "dictType": "common_status",
  "dictName": "通用状态",
  "status": 1,
  "remark": "备注（可选）"
}
```

- 校验：`id` 必传；唯一性校验排除自身。
- 返回：`R<Void>`。

### 1.5 删除类型

- **DELETE** `/dict/type/{id}`
- 说明：仅删除类型记录（逻辑删除），**不影响**其下数据项（字典为元字典，无级联/拦截）。
- 返回：`R<Void>`。

---

## 2. 字典数据项管理

### 2.1 分页查询数据项

- **GET** `/dict/data/page`
- Query 参数（均可选）：
  - `pageNum`：页码，默认 1
  - `pageSize`：每页条数，默认 10
  - `dictType`：类型编码等值
  - `dictLabel`：标签模糊匹配
  - `status`：状态等值（0/1）
- 返回：`R<Page<SysDictData>>`，按 `dictType`、`sortOrder` 升序。

### 2.2 数据项详情

- **GET** `/dict/data/{id}`
- 返回：`R<SysDictData>`；不存在时 `msg` 提示"字典数据项不存在"。

### 2.3 新增数据项

- **POST** `/dict/data`
- Body（JSON）：

```json
{
  "dictType": "common_status",
  "dictLabel": "启用",
  "dictValue": "1",
  "sortOrder": 1,
  "status": 1,
  "remark": "备注（可选）"
}
```

- 校验：`dictType`、`dictLabel`、`dictValue` 非空；`status` 必填；同一类型下 `dictValue` 唯一（重复报"该类型下字典键值已存在"）。`sortOrder` 缺省为 0。
- 写成功后自动失效该类型的 Redis 缓存。
- 返回：`R<Void>`。

### 2.4 修改数据项

- **PUT** `/dict/data`
- Body（JSON）：同新增，另需传 `id`：

```json
{
  "id": 11,
  "dictType": "common_status",
  "dictLabel": "启用",
  "dictValue": "1",
  "sortOrder": 1,
  "status": 1,
  "remark": "备注（可选）"
}
```

- 校验：`id` 必传；同类型下 `dictValue` 唯一校验排除自身。
- 写成功后自动失效该类型的 Redis 缓存。
- 返回：`R<Void>`。

### 2.5 删除数据项

- **DELETE** `/dict/data/{id}`
- 写成功后自动失效该类型的 Redis 缓存。
- 返回：`R<Void>`。

---

## 3. 查询与缓存

### 3.1 按类型查启用数据项（前端下拉用）

- **GET** `/dict/type/{dictType}/data`
- 说明：返回该类型下 `status=1` 的数据项列表，按 `sortOrder` 升序；优先读 Redis 缓存（Key：`freyja:dict:{dictType}`），未命中查库回填。前端 `<DictSelect>` 应调此接口。
- 返回：`R<List<SysDictData>>`，示例：

```json
{
  "code": 200,
  "msg": "success",
  "data": [
    { "id": 11, "dictType": "common_status", "dictLabel": "启用", "dictValue": "1", "sortOrder": 1, "status": 1 },
    { "id": 12, "dictType": "common_status", "dictLabel": "停用", "dictValue": "0", "sortOrder": 2, "status": 1 }
  ]
}
```

### 3.2 失效指定类型缓存

- **DELETE** `/dict/cache/{dictType}`
- 说明：清除 `freyja:dict:{dictType}` 缓存。一般由后端写操作自动触发；此接口用于手动刷新（如直接改库后）。
- 返回：`R<Void>`。

---

## 前端对接建议

- 下拉框：加载时调 `GET /dict/type/{dictType}/data`，用 `dictLabel` 展示、`dictValue` 作为值。
- 管理页（字典配置中心）：
  - 类型列表：`GET /dict/type/page` + 新增/编辑/删除（`POST`/`PUT`/`DELETE /dict/type`）。
  - 数据项列表：按选中的 `dictType` 调 `GET /dict/data/page?dictType=xxx`，或编辑弹窗内管理。
  - 编辑类型时删除类型需二次确认（会逻辑删除类型记录，不影响数据项）。
- 增删改成功以 `code == 200` 判断；失败弹 `msg` 提示。