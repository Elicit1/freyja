package com.astra.freyja.dao;

import com.astra.freyja.entity.McpBusinessOperation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface McpBusinessOperationMapper extends BaseMapper<McpBusinessOperation> {
    int reserve(McpBusinessOperation operation);

    McpBusinessOperation lock(@Param("key") String requestId);

    McpBusinessOperation find(@Param("key") String requestId);

    Long createdProjectCount(@Param("id") Long dramaId);

    int complete(McpBusinessOperation operation);
}
