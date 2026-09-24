package com.astra.freyja.service;

import com.astra.freyja.dto.drama.DramaShotFirstFrameDTO;

/**
 * 云端/第三方 AI 生图服务接口 (支持 OpenAI 规范 /v1/images/generations 如 DALL-E, SiliconFlow FLUX, DashScope 等)。
 */
public interface AiImageApiService {

    /**
     * 调用云端生图 API 生成单张图像，并自动下载转存归档至 MinIO。
     *
     * @param shotId   关联的分镜 ID
     * @param dramaId  关联的项目/短剧 ID
     * @param prompt   正向提示词
     * @param dto      请求参数 (providerId, modelCode, size 等)
     * @return 归档后可直接访问的 MinIO URL
     */
    String generateAndArchiveImage(Long shotId, Long dramaId, String prompt, DramaShotFirstFrameDTO dto);

    String generateAndArchiveAssetImage(String targetType, Long targetId, String slot,
                                        String prompt, DramaShotFirstFrameDTO dto);

    /**
     * 调用云端/网关 AI 视频 API 生成视频，下载并转存归档至 MinIO，并自动提取视频末帧图。
     * 采用全量 Base64 媒体数据传输，支持 MiniMax H3 首尾帧 (FL2VA) 与多参考图 (Ref2VA) 视频工作流。
     *
     * @param shotId   关联的分镜 ID
     * @param dramaId  关联的项目/短剧 ID
     * @param shot     分镜实体详情 (包含提示词、首尾帧图片、时长、参考图、音频等)
     * @param dto      请求参数 (包含 providerId, workflowTemplateId, seed 等)
     * @return 包含 videoUrl、lastFrameUrl 与 revisedPrompt 的结果对象
     */
    com.astra.freyja.dto.drama.VideoGenerationResultVO generateAndArchiveVideo(
            Long shotId, Long dramaId, com.astra.freyja.entity.DramaShot shot, com.astra.freyja.dto.drama.DramaShotRenderRequestDTO dto);

    /**
     * 根据提供商 ID 解析生效的 AI 提供商配置 (若为空则自动选择首个已启用的提供商)。
     */
    com.astra.freyja.entity.AiProvider resolveProvider(Long providerId);

    /**
     * 根据请求参数与提供商 ID 解析生效的生图模型代码。
     */
    String resolveModelCode(DramaShotFirstFrameDTO dto, Long providerId);

    /**
     * 根据请求参数与提供商 ID 解析生效的视频模型代码。
     */
    String resolveVideoModelCode(com.astra.freyja.dto.drama.DramaShotRenderRequestDTO dto, Long providerId, String generationMode);

    /**
     * 向远程 AI 提供商 / ComfyUI 网关发送精准任务取消与中断指令。
     *
     * @param providerId 提供商 ID (可为 null，为 null 则自动查找默认已启用提供商)
     * @param taskId     渲染任务 ID
     * @param shotId     关联的分镜 ID (可选)
     * @return 包含取消是否成功、详细状态及受影响节点和 Prompt ID 的结果对象
     */
    com.astra.freyja.dto.render.RemoteCancelResultVO cancelRemoteTask(Long providerId, String taskId, Long shotId);
}
