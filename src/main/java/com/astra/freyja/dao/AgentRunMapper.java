package com.astra.freyja.dao;

import com.astra.freyja.entity.AgentRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM agent_run WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    AgentRun lockById(@org.apache.ibatis.annotations.Param("id") Long id);
}
