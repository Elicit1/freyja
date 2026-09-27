package com.astra.freyja.dao;

import com.astra.freyja.entity.DramaShot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DramaShotMapper extends BaseMapper<DramaShot> {

    String ACTIVE_DRAMA_SHOTS = " FROM drama_shot s "
            + "JOIN drama_scene sc ON sc.id = s.scene_id AND sc.deleted = 0 "
            + "JOIN drama_episode ep ON ep.id = s.episode_id AND ep.deleted = 0 "
            + "AND ep.id = sc.episode_id "
            + "WHERE s.drama_id = #{dramaId} AND s.deleted = 0 "
            + "AND sc.drama_id = s.drama_id AND ep.drama_id = s.drama_id";

    /** 仅包含所属场次、剧集均未删除的分镜，兼容历史遗留的孤立分镜。 */
    @Select("SELECT s.*" + ACTIVE_DRAMA_SHOTS + " ORDER BY s.shot_no, s.sort_order")
    List<DramaShot> selectActiveByDramaId(@Param("dramaId") Long dramaId);

    @Select("SELECT COUNT(*)" + ACTIVE_DRAMA_SHOTS)
    Long countActiveByDramaId(@Param("dramaId") Long dramaId);

    @Select("SELECT COUNT(*)" + ACTIVE_DRAMA_SHOTS
            + " AND s.video_url IS NOT NULL AND TRIM(s.video_url) <> ''")
    Long countRenderedActiveByDramaId(@Param("dramaId") Long dramaId);

    /**
     * 查询某剧集下当前最大分镜序号
     */
    @Select("SELECT COALESCE(MAX(shot_no), 0) FROM drama_shot WHERE episode_id = #{episodeId} AND deleted = 0")
    Integer selectMaxShotNoByEpisodeId(@Param("episodeId") Long episodeId);

    /**
     * 仅当完成任务仍是分镜最新任务时，原子地将视频 Take 投影为当前视频。
     * 避免旧任务完成或旧编辑表单覆盖新任务状态。
     */
    @Update("UPDATE drama_shot SET current_video_take_id = #{takeId}, video_url = #{videoUrl}, " +
            "render_status = 'SUCCESS', last_frame_url = NULL, last_frame_source_video_url = NULL, " +
            "last_frame_source_take_id = NULL WHERE id = #{shotId} AND latest_task_id = #{taskId} AND deleted = 0")
    int promoteVideoTakeIfLatest(@Param("shotId") Long shotId,
                                 @Param("taskId") String taskId,
                                 @Param("takeId") Long takeId,
                                 @Param("videoUrl") String videoUrl);

    /** 仅更新当前最新任务的运行状态，避免异步线程用旧实体覆盖 latest_task_id。 */
    @Update("UPDATE drama_shot SET render_status = 'RENDERING' " +
            "WHERE id = #{shotId} AND latest_task_id = #{taskId} AND deleted = 0")
    int markRenderingIfLatest(@Param("shotId") Long shotId,
                              @Param("taskId") String taskId);
}
