package com.astra.freyja.dao;

import com.astra.freyja.entity.AiSkill;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiSkillMapper extends BaseMapper<AiSkill> {

    @Delete("DELETE FROM ai_skill WHERE id = #{skillId}")
    int hardDeleteById(@Param("skillId") Long skillId);

    /** 在上传事务内锁定 Skill 聚合根，保证同名并发上传的 revision_no 单调递增。 */
    @Select("SELECT * FROM ai_skill WHERE id = #{skillId} FOR UPDATE")
    AiSkill selectForUpdate(@Param("skillId") Long skillId);
}
