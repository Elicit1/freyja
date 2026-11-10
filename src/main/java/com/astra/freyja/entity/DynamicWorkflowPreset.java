package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data @EqualsAndHashCode(callSuper=true) @TableName("dynamic_workflow_preset")
public class DynamicWorkflowPreset extends BaseEntity {
    private String name;
    private Long providerId;
    private Long modelId;
    private Integer version;
    private Integer status;
    private String optionsJson;
}
