package com.astra.freyja.dao;

import com.astra.freyja.entity.McpBusinessOperation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

@Mapper
public interface McpBusinessOperationMapper extends BaseMapper<McpBusinessOperation> {
    @Insert("INSERT IGNORE INTO mcp_business_operation (id,request_id,tool_name,request_hash,created_project,status,create_time) "
            + "VALUES (#{id},#{requestId},#{toolName},#{requestHash},0,'RUNNING',CURRENT_TIMESTAMP)")
    int reserve(McpBusinessOperation operation);

    @Select("SELECT * FROM mcp_business_operation WHERE request_id=#{key} FOR UPDATE")
    McpBusinessOperation lock(@Param("key") String requestId);

    @Select("SELECT * FROM mcp_business_operation WHERE request_id=#{key}")
    McpBusinessOperation find(@Param("key") String requestId);

    @Select("SELECT COUNT(*) FROM mcp_business_operation WHERE drama_id=#{id} AND created_project=1 AND status='SUCCEEDED'")
    Long createdProjectCount(@Param("id") Long dramaId);

    @Update("UPDATE mcp_business_operation SET drama_id=#{dramaId},created_project=#{createdProject},status='SUCCEEDED',"
            + "result_json=#{resultJson},complete_time=CURRENT_TIMESTAMP WHERE id=#{id} AND status='RUNNING'")
    int complete(McpBusinessOperation operation);
}
