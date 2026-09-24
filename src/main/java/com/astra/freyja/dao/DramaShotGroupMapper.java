package com.astra.freyja.dao;

import com.astra.freyja.entity.DramaShotGroup;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 连续镜头组 Mapper 接口。
 */
@Mapper
public interface DramaShotGroupMapper extends BaseMapper<DramaShotGroup> {

    /**
     * 查询某剧集下当前最大连续镜头组序号
     */
    @Select("SELECT COALESCE(MAX(g.group_no), 0) FROM drama_shot_group g JOIN drama_scene s ON g.scene_id = s.id WHERE s.episode_id = #{episodeId} AND g.deleted = 0 AND s.deleted = 0")
    Integer selectMaxGroupNoByEpisodeId(@Param("episodeId") Long episodeId);
}
