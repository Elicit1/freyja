package com.astra.freyja.dao;

import com.astra.freyja.entity.AgentStep;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentStepMapper extends BaseMapper<AgentStep> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM agent_step WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    AgentStep lockById(@org.apache.ibatis.annotations.Param("id") Long id);
}
