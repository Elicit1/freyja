package com.astra.freyja.dao;

import com.astra.freyja.entity.SysConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** One reserved row provides a unique lock even when the general config key index is non-unique. */
@Mapper
public interface McpServerConfigMapper {
    long CONFIG_ID = 900000000000000019L;
    String CONFIG_KEY = "mcp.server.settings";
    SysConfig find();
    int reserve(@Param("value") String defaults);
    SysConfig lock();
    int save(@Param("value") String value);
}
