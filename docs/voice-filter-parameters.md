# 配音特效参数组件

特效配置页与角色配音的参数编辑共用 `FilterSteps.vue`、`FilterParameter.vue`。参数目录在 `src/main/resources/voice/filter-catalog.json`，由服务端统一提供给页面、MCP 和处理引擎；前端不维护另一份滤镜定义。

## 开放范围

首批20种处理、86个参数，按以下分类检索；滤镜可重复添加和排序。

| 分类 | 处理 type |
|---|---|
| 频率与音量 | `highpass`, `lowpass`, `equalizer`, `volume` |
| 动态与响度 | `compressor`, `gate`, `limiter`, `loudnorm` |
| 回声与调制 | `echo`, `reverb`, `chorus`, `flanger`, `tremolo`, `vibrato` |
| 失真与音高 | `distortion`, `pitch`, `rubberband`, `dual` |
| 降噪修复 | `afftdn`, `deesser` |

`reverb` 为多次短延迟模拟的空间反射，`loudnorm` 为单次动态响度归一化。`dual` 对同一表演进行分流、变调和混合，不代表两名独立演员。输入输出、分流与连接、重采样、最终保护限幅和WAV编码均由系统管理。

不开放任意命令、滤镜图文本、表达式、文件路径、模型加载、视频处理、音频采集、网络输入、分析调试、外部插件、需要脉冲响应或HRTF数据的滤镜。Rubber Band 变调需要服务器编译启用该组件；缺失时仅将相关处理标为不可用，不影响其他滤镜。

## 参数协议

`GET /dubbing/effects/filters` 返回各处理的 `type,label,category,description,ffmpegFilter,parameters,available,missingFilters`。

每个参数包含 `key,label,kind,initial`，以及适用的 `unit,description,min,max,step,options,maxItems,advanced,required`。`option` 是内部 FFmpeg 参数名；页面按 kind 选择组件：

| kind | JSON值 | 组件 |
|---|---|---|
| `number` | 有限数字 | 数字输入、滑块；极小步长仅数字输入 |
| `select` | 目录中列出的字符串 | 单选下拉，不接受自行填写的值 |
| `boolean` | `true` / `false` | 开关 |
| `numberList` | 数字数组 | 可增删的数字输入列表，最多4项 |

步骤示例：

```json
[
  {"type":"equalizer","params":{"frequency":1200,"width":1,"gain":-3,"mix":1},"enabled":true},
  {"type":"rubberband","params":{"pitch":1.1,"tempo":1,"formant":"preserved"},"enabled":true},
  {"type":"afftdn","params":{"reduction":12,"floor":-50,"tracking":true,"adaptivity":0.5},"enabled":false}
]
```

`enabled=false` 保留参数但跳过执行；省略时启用。新增步骤使用目录默认值，重置按钮恢复当前目录参数。服务端展开可选默认值并写入模板或配音快照，防止未来修改默认值改变已保存版本。已有必填参数仍须提供。

单个特效最多12步，一条配音最多6个特效、合计24步。所有参数按类型、枚举和范围校验；`chorus` 各声部列表、`reverb` 延迟/衰减列表的长度必须相同。停用步骤同样校验数据。

试听只执行FFmpeg，修改参数、步骤顺序或启用状态后清除旧试听结果。角色配音始终保留克隆干声，修改特效从干声重新处理；清空 `effects=[]` 直接使用原声，不调用TTS。模板更新不会自动改变已有配音快照。

## MCP

`dubbing_list_filters` 使用授权短剧 `dramaId` 查询同一目录。先读取可用处理及参数，再通过 `dubbing_create_audio` 或 `dubbing_update_effects` 的 `effects[].steps` 提交参数；步骤值支持数字、枚举字符串、布尔和数字数组。使用已有模板仍可省略 steps。

## 验证

原生FFmpeg集成测试可指定可执行文件：

```powershell
.\mvnw.cmd test -Dtest=VoiceEffectEngineTest '-Dfreyja.test.ffmpeg=F:\program_file\ffmpeg\bin\ffmpeg.exe' '-Dfreyja.test.ffprobe=F:\program_file\ffmpeg\bin\ffprobe.exe'
```

目录最初依据WSL Ubuntu FFmpeg 8.0.1选定，实际可用性以当前后端配置的FFmpeg为准。
