package com.astra.freyja.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper = true) @TableName("agent_material_requirement")
public class AgentMaterialRequirement extends BaseEntity {
    private Long dramaId;
    private String materialKey;
    private String sourceType;
    private Long sourceId;
    private String name;
    private String description;
    private String status;
}
