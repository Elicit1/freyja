package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mcp_business_operation")
public class McpBusinessOperation {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String requestId;
    private String toolName;
    private String requestHash;
    private Long dramaId;
    private Integer createdProject;
    private String status;
    private String resultJson;
    private LocalDateTime createTime;
    private LocalDateTime completeTime;
}
