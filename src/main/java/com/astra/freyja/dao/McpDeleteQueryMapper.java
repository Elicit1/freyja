package com.astra.freyja.dao;

import com.astra.freyja.entity.DramaScene;
import com.astra.freyja.entity.DramaShot;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;

/** Only live narrative records can block asset deletion; narrow projections omit prompts and AI state. */
@Mapper
public interface McpDeleteQueryMapper {
    List<DramaShot> referencingShots();

    List<DramaScene> referencingScenes();
}
