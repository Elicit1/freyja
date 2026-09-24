package com.astra.freyja.service;

import com.astra.freyja.dto.video.VideoProbeInfoVO;
import com.astra.freyja.dto.video.VideoProcessQuery;
import com.astra.freyja.dto.video.VideoProcessResultVO;
import com.astra.freyja.dto.video.VideoProcessSubmitDTO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 视频后处理任务服务 (超分、补帧等)。
 */
public interface VideoProcessingService {

    /**
     * 提交视频后处理任务 (创建任务、同步注册渲染中心并异步调用网关)。
     */
    VideoProcessResultVO submitTask(VideoProcessSubmitDTO dto);

    /**
     * 根据任务唯一标识获取任务详情与执行产物。
     */
    VideoProcessResultVO getTaskById(String taskId);

    /**
     * 分页查询历史视频后处理任务。
     */
    Page<VideoProcessResultVO> pageTasks(VideoProcessQuery query);

    /**
     * 取消视频后处理任务 (向 ComfyUI 网关发送精准中断并更新状态)。
     */
    boolean cancelTask(String taskId);

    /**
     * 根据视频 URL 获取视频元数据信息 (分辨率、帧率、时长等)。
     */
    VideoProbeInfoVO probeVideo(String videoUrl);

    /**
     * 根据来源结构化引用探测视频元信息 (支持 DIRECT_URL, SHOT_CURRENT, SHOT_VIDEO_TAKE)。
     */
    VideoProbeInfoVO probeVideoSource(com.astra.freyja.dto.video.VideoProcessProbeSourceDTO dto);

    /**
     * 分页查询已生成视频的分镜列表 (供分镜视频选择器使用)。
     */
    Page<com.astra.freyja.dto.video.ShotVideoSourceOptionVO> querySourceShots(com.astra.freyja.dto.video.ShotVideoSourceQuery query);

    /**
     * 查询指定提供商下已启用的视频后处理模型选项 (供工作台下拉与安全配置使用)。
     */
    java.util.List<com.astra.freyja.dto.video.VideoProcessingModelOptionVO> listModelOptions(Long providerId, String operation);

    /**
     * 将视频后处理任务成功产物保存为对应分镜的候选版本历史 (Take)，可选择是否设为当前生效视频。
     *
     * @param taskId 视频后处理任务 ID
     * @param dto    保存请求参数 (包含可选的目标分镜 ID 与 setAsCurrent 开关)
     * @return 录入/更新后的分镜视频候选版本 VO
     */
    com.astra.freyja.dto.drama.ShotVideoTakeVO saveToShot(String taskId, com.astra.freyja.dto.video.VideoProcessSaveToShotDTO dto);
}
