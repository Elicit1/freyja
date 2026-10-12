package com.astra.freyja.dao;

import com.astra.freyja.entity.DramaShot;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface McpQueryMapper {
    String VALID_SHOT_GROUP = " AND (s.shot_group_id IS NULL OR EXISTS (SELECT 1 FROM drama_shot_group g "
            + "WHERE g.id=s.shot_group_id AND g.deleted=0 AND g.drama_id=s.drama_id "
            + "AND g.episode_id=s.episode_id AND g.scene_id=s.scene_id))";
    @Select("<script>SELECT s.*" + com.astra.freyja.dao.DramaShotMapper.ACTIVE_DRAMA_SHOTS
            + VALID_SHOT_GROUP
            + "<if test='episodeId != null'> AND s.episode_id = #{episodeId}</if>"
            + "<if test='sceneId != null'> AND s.scene_id = #{sceneId}</if>"
            + " ORDER BY ep.episode_no, sc.scene_no, s.sort_order, s.shot_no, s.id</script>")
    Page<DramaShot> shots(Page<DramaShot> page, @Param("dramaId") Long dramaId,
                          @Param("episodeId") Long episodeId, @Param("sceneId") Long sceneId);
}
