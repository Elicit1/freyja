package com.astra.freyja.dao;

import com.astra.freyja.entity.SysConfig;
import org.apache.ibatis.annotations.*;

/** One reserved row provides a unique lock even when the general config key index is non-unique. */
@Mapper
public interface McpServerConfigMapper {
    long CONFIG_ID = 900000000000000019L;
    String CONFIG_KEY = "mcp.server.settings";
    @Select("SELECT * FROM sys_config WHERE id=900000000000000019 AND deleted=0")
    SysConfig find();
    @Insert("INSERT IGNORE INTO sys_config (id,config_name,config_key,config_value,config_type,is_builtin,status,deleted,create_time,update_time) "
            + "VALUES (900000000000000019,'MCP 服务设置','mcp.server.settings',#{value},'JSON',1,1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
    int reserve(@Param("value") String defaults);
    @Select("SELECT * FROM sys_config WHERE id=900000000000000019 AND deleted=0 FOR UPDATE")
    SysConfig lock();
    @Update("UPDATE sys_config SET config_value=#{value},update_time=CURRENT_TIMESTAMP WHERE id=900000000000000019 AND deleted=0")
    int save(@Param("value") String value);
}
