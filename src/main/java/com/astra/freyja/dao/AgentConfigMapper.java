package com.astra.freyja.dao;

import com.astra.freyja.entity.AgentConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentConfigMapper extends BaseMapper<AgentConfig> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM agent_config WHERE agent_code = #{code} AND deleted = 0 FOR UPDATE")
    AgentConfig lockByCode(@org.apache.ibatis.annotations.Param("code") String code);
}
