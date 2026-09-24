# 资源库管理接口文档 (resource-library-api)

> 供人物管理、造型管理、场景环境管理及后续分镜 Prompt 生成调用。基路径默认 `http://127.0.0.1:8080`。
> 所有接口统一返回 `R<T>` 结构。

## 1. 统一返回结构

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

---

## 2. 接口列表

### 2.1 多模态资产上传 (`MediaAssetController`)

#### 1) 上传图片/资产文件
- **URL**: `POST /res/asset/upload`
- **Content-Type**: `multipart/form-data`
- **参数**:
  - `file`: 上传的文件
  - `category`: 类别（`character` / `scene` / `outfit` / `general`）
- **返回示例**:
```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "bucket": "video-assets",
    "objectPath": "character/20260827/uuid.png",
    "url": "http://127.0.0.1:9000/video-assets/character/20260827/uuid.png",
    "originalFilename": "linchen.png",
    "size": 1048576,
    "contentType": "image/png"
  }
}
```

---

### 2.2 人物角色资产管理 (`ResCharacterController`)

#### 1) 分页查询人物列表
- **URL**: `GET /res/character/page`
- **Query 参数**: `current`, `size`, `dramaId`, `name`, `gender`, `roleType`, `status`
- **返回**: 包含各人物基本信息、默认造型及造型数量统计。

#### 2) 获取人物详情
- **URL**: `GET /res/character/{id}`
- **返回**: 人物完整详情及关联的造型列表 `outfits`。

#### 3) 新增人物
- **URL**: `POST /res/character`
- **Body**:
```json
{
  "dramaId": 0,
  "name": "林晨",
  "referenceImageUrl": "http://127.0.0.1:9000/video-assets/character/ref.png",
  "gender": "MALE",
  "ageGroup": "YOUTH",
  "roleType": "PROTAGONIST",
  "personality": "冷静沉稳的创始人",
  "appearanceDesc": "25岁年轻创业者，短黑发，下颚线分明，眼神坚毅深邃",
  "appearancePrompt": "1man, 25yo, handsome, sharp jawline, short black hair, determined look",
  "triggerWords": "linchen, 1man",
  "negativePrompt": "beard, glasses",
  "loraName": "linchen_v1.safetensors",
  "loraWeight": 0.85,
  "defaultOutfitName": "默认日常装",
  "defaultOutfitPrompt": "wearing dark tailored suit, white shirt"
}
```

#### 4) 修改人物
- **URL**: `PUT /res/character`
- **Body**: `ResCharacterDTO`

#### 5) 删除人物
- **URL**: `DELETE /res/character/{id}`
- **说明**: 逻辑删除人物并级联逻辑删除其所属所有造型。

#### 6) 快速引用下拉选项
- **URL**: `GET /res/character/options?dramaId=0`

#### 7) AI 智能衍生角色稳定身份正负向提示词 (普通 / 流式 / 包装)
- **普通响应 URL**: `POST /res/character/derive-visual-prompt`
- **SSE 流式 URL**: `POST /res/character/derive-visual-prompt-stream`
- **前端标准包装 URL**: `POST /res/character/derive-visual-prompt-package`
- **Body**:
```json
{
  "name": "林婉清",
  "gender": "FEMALE",
  "ageGroup": "YOUTH",
  "roleType": "PROTAGONIST",
  "personality": "孤傲冷峻、心思缜密",
  "appearanceDesc": "28岁商界女强人，皮肤极白，冷艳丹凤眼，黑色及腰大波浪长发，右眼角细小泪痣",
  "stylePreset": "cinematic-realism",
  "additionalPrompt": "film lighting"
}
```
- **返回**:
```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "appearancePrompt": "1woman, 28yo, fair skin, phoenix eyes, black long wavy hair reaching waist, subtle beauty mark under right eye, cold aloof expression, highly detailed facial features",
    "negativePrompt": "(worst quality, low quality:1.4), (deformed, bad anatomy:1.3), blurry, watermark, bad hands"
  }
}
```

---

### 2.3 人物造型变体管理 (`ResCharacterOutfitController`)

#### 1) 查询某人物的所有造型
- **URL**: `GET /res/character/outfit/list/{characterId}`

#### 2) 新增人物造型
- **URL**: `POST /res/character/outfit`
- **Body**: `ResCharacterOutfitDTO`
```json
{
  "characterId": "2032095628944588801",
  "name": "宴会晚礼服",
  "lookType": "GOWN",
  "designDesc": "墨绿色丝绸露背吊带长裙，佩戴简约珍珠项链",
  "prompt": "wearing emerald green silk backless slip evening gown, delicate pearl necklace, elegant drape",
  "referenceImageUrl": "http://127.0.0.1:9000/video-assets/outfit/gown.png",
  "isDefault": 0
}
```

#### 3) 修改人物造型
- **URL**: `PUT /res/character/outfit`
- **Body**: `ResCharacterOutfitDTO`

#### 4) 删除造型
- **URL**: `DELETE /res/character/outfit/{id}`

#### 5) 设为默认造型
- **URL**: `PUT /res/character/outfit/{id}/set-default`

#### 6) AI 智能衍生造型提示词 (普通 / 流式 / 包装)
- **普通响应 URL**: `POST /res/character/outfit/derive-prompt`
- **SSE 流式 URL**: `POST /res/character/outfit/derive-prompt-stream`
- **前端标准包装 URL**: `POST /res/character/outfit/derive-prompt-package`
- **Body**:
```json
{
  "characterId": "2032095628944588801",
  "characterName": "林婉清",
  "outfitName": "宴会晚礼服",
  "lookType": "GOWN",
  "designDesc": "墨绿色丝绸露背吊带长裙，佩戴简约珍珠项链",
  "additionalPrompt": "luxurious ballroom lighting"
}
```
- **返回**:
```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "prompt": "wearing emerald green silk backless slip evening gown, delicate pearl necklace, refined haute couture texture",
    "negativePrompt": "casual clothes, t-shirt, jeans, dirty fabrics"
  }
}
```

---

### 2.4 场景环境资产管理 (`ResSceneController`)

#### 1) 分页查询场景列表
- **URL**: `GET /res/scene/page`
- **Query 参数**: `current`, `size`, `dramaId`, `name`, `sceneType`, `timeOfDay`, `weatherAtmosphere`, `status`

#### 2) 获取场景详情
- **URL**: `GET /res/scene/{id}`

#### 3) 新增场景
- **URL**: `POST /res/scene`
- **Body**:
```json
{
  "dramaId": 0,
  "name": "顶层总裁办公室",
  "coverUrl": "http://127.0.0.1:9000/video-assets/scene/office.jpg",
  "sceneType": "INDOOR",
  "timeOfDay": "NIGHT",
  "weatherAtmosphere": "RAINY",
  "scenePrompt": "modern luxury office, large floor-to-ceiling windows, rainy city skyline at night",
  "lightingPrompt": "cinematic neon rim lighting, volumetric light",
  "negativePrompt": "daylight, sun",
  "loraName": "cyber_interior.safetensors",
  "loraWeight": 0.80,
  "referenceImageUrl": "http://127.0.0.1:9000/video-assets/scene/ref.jpg"
}
```

#### 4) 修改场景
- **URL**: `PUT /res/scene`

#### 5) 删除场景
- **URL**: `DELETE /res/scene/{id}`

#### 6) 场景下拉选项
- **URL**: `GET /res/scene/options?dramaId=0`

---

### 2.5 分镜 Prompt 动态组装调度 (`PromptAssembleController`)

#### 1) 动态组装分镜 Prompt
- **URL**: `POST /res/prompt/assemble`
- **Body**:
```json
{
  "dramaId": 0,
  "sceneId": 10,
  "stylePreset": "cinematic-realism",
  "shotPrompt": "medium close-up shot, shallow depth of field, 8k resolution",
  "customPositivePrompt": "dramatic mood",
  "customNegativePrompt": "cartoon",
  "characterRefs": [
    {
      "characterId": 100,
      "outfitId": 200,
      "positionTag": "foreground center",
      "actionPrompt": "holding a whiskey glass, looking out the window",
      "emotionPrompt": "calm serious expression"
    }
  ]
}
```
- **返回示例**:
```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "positivePrompt": "masterpiece, best quality, cinematic shot, photorealistic, 8k resolution, raw photo, realistic lighting, film grain, modern luxury office, large floor-to-ceiling windows, rainy city skyline at night, cinematic neon rim lighting, volumetric light, linchen, 1man, 1man, 25yo, handsome, sharp jawline, short black hair, wearing dark tailored suit, white shirt, foreground center, holding a whiskey glass, looking out the window, calm serious expression, medium close-up shot, shallow depth of field, dramatic mood",
    "negativePrompt": "(worst quality, low quality:1.4), (deformed, distorted, disfigured:1.3), poorly drawn, bad anatomy, wrong anatomy, extra limb, missing limb, floating limbs, (mutated hands and fingers:1.4), disconnected limbs, mutation, blurry, watermark, text, signature, daylight, sun, beard, glasses, cartoon",
    "loraList": [
      {
        "loraName": "cyber_interior.safetensors",
        "weight": 0.80,
        "source": "SCENE",
        "assetName": "场景: 顶层总裁办公室"
      },
      {
        "loraName": "linchen_v1.safetensors",
        "weight": 0.85,
        "source": "CHARACTER",
        "assetName": "角色: 林晨"
      }
    ],
    "controlImages": [
      {
        "controlType": "SCENE_REF",
        "imageUrl": "http://127.0.0.1:9000/video-assets/scene/ref.jpg",
        "weight": 0.80,
        "label": "场景参考: 顶层总裁办公室"
      },
      {
        "controlType": "CHARACTER_REF",
        "imageUrl": "http://127.0.0.1:9000/video-assets/outfit/suit.png",
        "weight": 0.85,
        "label": "角色参考: 林晨 (造型: 默认日常装)"
      }
    ],
    "sceneSummary": {},
    "characterSummaries": []
  }
}
```

---

### 2.6 核心道具资产管理 (`ResPropController`)

#### 1) 分页查询道具列表
- **URL**: `GET /res/prop/page`
- **Query 参数**: `current`, `size`, `dramaId`, `name`, `propType`, `status`

#### 2) 获取道具详情
- **URL**: `GET /res/prop/{id}`

#### 3) 新增道具
- **URL**: `POST /res/prop`
- **Body**:
```json
{
  "dramaId": 0,
  "name": "繁复小座钟",
  "propType": "KEY_PROP",
  "description": "古典雕花黄铜座钟，带有发条钥匙与微缩齿轮",
  "propPrompt": "ornate antique brass desk clock with intricate engravings, polished mahogany base, ticking mechanism visible",
  "coverUrl": "http://127.0.0.1:9000/video-assets/prop/clock.jpg",
  "sortOrder": 0,
  "status": 1,
  "remark": "关键叙事线索道具"
}
```

#### 4) 修改道具
- **URL**: `PUT /res/prop`
- **Body**: 包含 `id` 及需更新的道具字段

#### 5) 删除道具
- **URL**: `DELETE /res/prop/{id}`

#### 6) 道具下拉选项
- **URL**: `GET /res/prop/options?dramaId=0`

