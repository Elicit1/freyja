package com.astra.freyja.dao;

import com.astra.freyja.entity.DramaScene;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DramaSceneMapper extends BaseMapper<DramaScene> {

    /**
     * 查询某剧集当前最大场次序号
     */
    @Select("SELECT COALESCE(MAX(scene_no), 0) FROM drama_scene WHERE episode_id = #{episodeId} AND deleted = 0")
    Integer selectMaxSceneNoByEpisodeId(@Param("episodeId") Long episodeId);
}
