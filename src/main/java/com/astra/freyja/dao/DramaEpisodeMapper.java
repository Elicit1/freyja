package com.astra.freyja.dao;

import com.astra.freyja.entity.DramaEpisode;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DramaEpisodeMapper extends BaseMapper<DramaEpisode> {

    /**
     * 忽略 MyBatis-Plus 逻辑删除拦截，原生查询是否存在指定短剧和集数序号的记录 (防止唯一键冲突)
     */
    @Select("SELECT * FROM drama_episode WHERE drama_id = #{dramaId} AND episode_no = #{episodeNo} LIMIT 1")
    DramaEpisode selectAnyByDramaIdAndEpisodeNo(@Param("dramaId") Long dramaId, @Param("episodeNo") Integer episodeNo);

    /**
     * 原生强制恢复并更新剧集记录 (包含重置 deleted = 0)
     */
    @Update("UPDATE drama_episode SET title = #{title}, summary = #{summary}, script_content = #{scriptContent}, target_duration = #{targetDuration}, actual_duration = #{actualDuration}, status = #{status}, deleted = 0, update_time = NOW() WHERE id = #{id}")
    int restoreAndUpdate(DramaEpisode episode);

    /**
     * 查询某短剧当前最大有效集号
     */
    @Select("SELECT COALESCE(MAX(episode_no), 0) FROM drama_episode WHERE drama_id = #{dramaId} AND deleted = 0")
    Integer selectMaxEpisodeNoByDramaId(@Param("dramaId") Long dramaId);
}
