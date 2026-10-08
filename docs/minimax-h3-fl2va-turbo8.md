# MiniMax H3 官方 FL2VA 八步加速

网关模型编码为 `minimax-h3-fl2va-turbo8`，FREYJA 显示名称为 **MiniMax H3 官方 FL2VA Turbo8**，类型为 `TXT2VIDEO_FIRST_LAST`。支持文生视频、仅首帧、首尾帧，以及 H3 原生音频。最多两张图片，不接收独立参考音频/视频。

ComfyUI 工作流为 `video_minimax_h3_i2v_official_turbo8.json`，位于远端默认用户的 `workflows/` 目录和仓库 `comfyUI_workflow/`。API 图为 `comfy_gateway/comfyUIApi/minimax_h3_fl2va_official_turbo8.json`。

基于 Comfy-Org 官方 FL2VA 图，开启 Turbo 分支，使用已安装的 **FL2V Turbo 8step v1.0 标准版**：

```text
minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors
```

该标准版的训练目标为 544p，建议先用 960×544、5 秒测试。它与另行发布的 `8step v1.0 768p` 是不同权重，本入口复用远端现有标准版，无需下载另一份。网关支持其他按 32 像素对齐的尺寸，画质需实测。

采样链为 `UNETLoader → LoraLoaderModelOnly → BasicGuider / BasicScheduler → SamplerCustomAdvanced`，LoRA 强度 1.0，`res_multistep` + `simple`，固定八步。通用 `steps` 参数不会把该入口误改成四步或二十步；视频和音频从同一个 latent 解码后以 24 FPS 合流。现有 Turbo4 模型入口保持独立。

## 部署及平台初始化

在 FREYJA 仓库根目录执行：

```bash
docker compose build comfy-gateway
# 当前渲染结束后再部署，长停止超时给已有网关请求保留完成时间。
docker compose up -d --no-deps --timeout 1800 comfy-gateway
python3 docker/scripts/init_minimax_h3_fl2va.py \
  --base-url http://127.0.0.1:8080 \
  --provider-id 2096886255638646785 \
  --steps 8
```

初始化脚本新增 `--steps 8` 选项，默认仍为四步，旧命令继续有效。它通过平台 API 幂等创建/更新所选模型并使缓存失效。其他部署替换自己的提供商 ID。

刷新 ComfyUI 工作流列表可打开八步工作流；新环境也可将仓库 UI JSON 导入 ComfyUI。首次使用时选择自己的首帧/尾帧；网关文生视频模式可不传图片。

## 新 GPU 节点的 LoRA 镜像下载

当前节点已安装本入口所需权重，无需重复下载。新 GPU 节点可执行：

```bash
cd ~/ComfyUI-Official
mkdir -p models/loras
wget -c -O models/loras/minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors \
  'https://hf-mirror.com/Comfy-Org/MiniMax-H3/resolve/main/loras/minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors'
```

主模型、编码器和音视频 VAE 与 [FL2VA Turbo4](minimax-h3-fl2va-turbo4.md) 相同。`768p` 八步 LoRA 的替代镜像为 [LightX2V FL2V Turbo8 768p](https://hf-mirror.com/lightx2v/Minimax-h3-Turbo/resolve/main/minimax_h3_fl2v_turbo_8step_v1.0_768p_comfyui_bf16.safetensors)，如需切换到该版本，应同时更新 UI/API 图的 LoRA 文件名。

相关自动测试覆盖三种帧输入模式、固定八步、参考连接、尺寸/时长/种子注入与音频合流；部署检查核对网关模型列表、节点健康与远端权重可用性，未执行实际 GPU 生视频。
