package com.astra.freyja.service;

import com.astra.freyja.dto.comfy.ComfyRenderTaskVO;
import com.astra.freyja.dto.drama.DramaShotBatchAssembleDTO;
import com.astra.freyja.dto.drama.DramaShotDTO;
import com.astra.freyja.dto.drama.DramaShotRenderRequestDTO;
import com.astra.freyja.dto.drama.DramaShotReorderDTO;
import com.astra.freyja.dto.drama.DramaShotVO;
import com.astra.freyja.dto.res.PromptAssembleResultVO;

import java.util.List;

/**
 * 分镜镜头管理服务接口。
 */
public interface DramaShotService {

    /**
     * 查询某场次下的所有分镜列表
     */
    List<DramaShotVO> listBySceneId(Long sceneId);

    /**
     * 查询某剧集下的所有分镜列表
     */
    List<DramaShotVO> listByEpisodeId(Long episodeId);

    /**
     * 获取分镜详情
     */
    DramaShotVO getById(Long id);

    /**
     * 创建分镜
     */
    Long create(DramaShotDTO dto);

    /**
     * 更新分镜
     */
    void update(DramaShotDTO dto);

    /**
     * 删除分镜
     */
    void delete(Long id);

    /**
     * 复制/克隆分镜
     */
    Long cloneShot(Long id);

    /**
     * 重新排序分镜 (支持跨场次移动)
     */
    void reorderShots(DramaShotReorderDTO dto);

    /**
     * 单分镜实时预览完整组装 Prompt (不修改落库，只读预览包含 LoRA 与 ControlNet)
     */
    PromptAssembleResultVO previewShotPrompt(Long id);

    /**
     * 单分镜一键 Prompt 组装与落库
     */
    PromptAssembleResultVO assembleShotPrompt(Long id);

    /**
     * 单分镜调用本地 ComfyUI 生成首帧关键图 (T2I)
     */
    ComfyRenderTaskVO generateFirstFrame(Long id, com.astra.freyja.dto.drama.DramaShotFirstFrameDTO dto);

    /**
     * 保存/确认为该分镜首帧图
     */
    void setFirstFrame(Long id, String previewImageUrl);

    /**
     * 保存/确认为该分镜尾帧图
     */
    void setEndFrame(Long id, String endFrameImageUrl);

    /**
     * 批量 Prompt 组装
     */
    int batchAssemblePrompts(DramaShotBatchAssembleDTO dto);

    /**
     * 单分镜提交 ComfyUI 异步渲染 (视频或单帧)
     */
    ComfyRenderTaskVO submitShotRender(Long id, DramaShotRenderRequestDTO requestDTO);

    /**
     * 异步执行单分镜视频生成与归档任务
     */
    void executeShotVideoRenderAsync(Long id, String taskId, DramaShotRenderRequestDTO requestDTO);
}
