package com.astra.freyja.dao;

import com.astra.freyja.entity.AiSkillVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiSkillVersionMapper extends BaseMapper<AiSkillVersion> {

    @Delete("DELETE FROM ai_skill_version WHERE skill_id = #{skillId}")
    int hardDeleteBySkillId(@Param("skillId") Long skillId);
}
