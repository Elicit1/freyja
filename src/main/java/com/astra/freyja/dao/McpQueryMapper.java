package com.astra.freyja.dao;

import com.astra.freyja.entity.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Set;

@Mapper
public interface McpQueryMapper {
    Drama lockProject(@Param("id") Long id);
    DramaEpisode lockEpisode(@Param("id") Long id);
    DramaScene lockScene(@Param("id") Long id);
    DramaShotGroup lockShotGroup(@Param("id") Long id);
    DramaShot lockShot(@Param("id") Long id);
    ResCharacter lockCharacter(@Param("id") Long id);
    ResCharacterLook lockCharacterLook(@Param("id") Long id);
    ResScene lockResourceScene(@Param("id") Long id);
    ResProp lockProp(@Param("id") Long id);
    ResKeyframe lockKeyframe(@Param("id") Long id);
    Page<DramaShot> shots(Page<DramaShot> page, @Param("dramaId") Long dramaId,
                          @Param("episodeId") Long episodeId, @Param("sceneId") Long sceneId);
    Page<Drama> projects(Page<Drama> page, @Param("allProjects") boolean allProjects, @Param("createdProjects") boolean createdProjects,
                         @Param("projectIds") Set<Long> projectIds, @Param("keyword") String keyword);
    Page<DramaScene> scenes(Page<DramaScene> page, @Param("dramaId") Long dramaId,
                           @Param("episodeId") Long episodeId, @Param("sceneId") Long sceneId);
    Page<DramaShotGroup> groups(Page<DramaShotGroup> page, @Param("dramaId") Long dramaId,
                               @Param("episodeId") Long episodeId, @Param("sceneId") Long sceneId);
    Page<ResKeyframe> keyframes(Page<ResKeyframe> page, @Param("dramaId") Long dramaId, @Param("keyword") String keyword);
}
