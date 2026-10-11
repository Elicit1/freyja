package com.astra.freyja.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper = true) @TableName("shot_production_plan")
public class ShotProductionPlan extends BaseEntity {
    private Long dramaId;
    private Long shotId;
    private Long runId;
    private Integer revision;
    private String planJson;
    private String inputFingerprint;
    private String appliedFingerprint;
    private String shotStateFingerprint;
    private String promptFingerprint;
    private Long promptTaskId;
    private String promptTaskFingerprint;
    private Integer promptTaskRevision;
    private String lockedFieldsJson;
    private String preparationStatus;
}
