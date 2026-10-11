package com.astra.freyja.dao;

import com.astra.freyja.entity.AgentSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSession> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM agent_session WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    AgentSession lockById(@org.apache.ibatis.annotations.Param("id") Long id);
}
