package com.astra.freyja.dto.drama;
import lombok.Data;
import com.astra.freyja.director.model.DirectorPlan;
@Data
public class DirectorPlanEditDTO {
    private String directorPlanMode;
    private Integer directorPlanRevision;
    private DirectorPlan directorPlan;
    private Double duration;
    private String shotType;
    private String cameraMovement;
    private Boolean shotTypeLocked;
    private Boolean cameraMovementLocked;
}
