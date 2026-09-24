package com.astra.freyja.dao;

import com.astra.freyja.entity.AiSkillFile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiSkillFileMapper extends BaseMapper<AiSkillFile> {

    @Delete("DELETE FROM ai_skill_file WHERE skill_version_id = #{versionId}")
    int hardDeleteByVersionId(@Param("versionId") Long versionId);
}
