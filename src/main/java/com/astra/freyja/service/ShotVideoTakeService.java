package com.astra.freyja.service;

import com.astra.freyja.dto.drama.DramaShotRenderRequestDTO;
import com.astra.freyja.dto.drama.ShotVideoTakeQuery;
import com.astra.freyja.dto.drama.ShotVideoTakeSelectVO;
import com.astra.freyja.dto.drama.ShotVideoTakeVO;
import com.astra.freyja.dto.drama.VideoGenerationResultVO;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.DramaShotVideoTake;
import com.astra.freyja.entity.RenderTask;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 分镜视频抽卡候选版本服务接口。
 */
public interface ShotVideoTakeService {

    /**
     * 分页查询分镜下的视频候选版本
     *
     * @param shotId 分镜ID
     * @param query  分页查询参数
     * @return 候选版本分页结果
     */
    Page<ShotVideoTakeVO> pageByShotId(Long shotId, ShotVideoTakeQuery query);

    /**
     * 记录渲染生成的视频 Take，并在符合最新任务条件时自动选为当前视频
     *
     * @param shotSnapshot    执行渲染时的分镜快照
     * @param renderTask      渲染任务实体
     * @param requestSnapshot 请求参数快照
     * @param result          视频生成与归档产物结果
     * @return 创建的候选版本实体
     */
    DramaShotVideoTake recordGeneratedTake(
            DramaShot shotSnapshot,
            RenderTask renderTask,
            DramaShotRenderRequestDTO requestSnapshot,
            VideoGenerationResultVO result);

    /**
     * 重新选择指定候选版本作为当前分镜视频
     *
     * @param shotId 分镜ID
     * @param takeId 候选版本ID
     * @return 选择结果
     */
    ShotVideoTakeSelectVO selectTake(Long shotId, Long takeId);

    /** 删除候选版本及其独占的 MinIO 视频对象。 */
    void deleteTake(Long shotId, Long takeId);

    /**
     * 为分镜分配下一个候选版本编号 (Take No)
     *
     * @param shotId 分镜ID
     * @return 递增的候选编号 (从1开始)
     */
    int allocateNextTakeNo(Long shotId);

    /**
     * 判断指定任务是否仍为最新任务可自动选择
     *
     * @param shotId 分镜ID
     * @param taskId 任务ID
     * @return 是否自动选中
     */
    boolean shouldAutoSelect(Long shotId, String taskId);
}
