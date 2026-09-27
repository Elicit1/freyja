package com.astra.freyja.dao;

import com.astra.freyja.entity.DramaScene;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DramaSceneMapper extends BaseMapper<DramaScene> {

    String ACTIVE_DRAMA_SCENES = " FROM drama_scene sc "
            + "JOIN drama_episode ep ON ep.id = sc.episode_id AND ep.deleted = 0 "
            + "WHERE sc.drama_id = #{dramaId} AND sc.deleted = 0 AND ep.drama_id = sc.drama_id";

    @Select("SELECT sc.*" + ACTIVE_DRAMA_SCENES + " ORDER BY sc.scene_no, sc.sort_order")
    List<DramaScene> selectActiveByDramaId(@Param("dramaId") Long dramaId);

    @Select("SELECT COUNT(*)" + ACTIVE_DRAMA_SCENES)
    Long countActiveByDramaId(@Param("dramaId") Long dramaId);

    /**
     * 查询某剧集当前最大场次序号
     */
    @Select("SELECT COALESCE(MAX(scene_no), 0) FROM drama_scene WHERE episode_id = #{episodeId} AND deleted = 0")
    Integer selectMaxSceneNoByEpisodeId(@Param("episodeId") Long episodeId);
}
