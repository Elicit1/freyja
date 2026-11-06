package com.astra.freyja.dto.drama;
import lombok.Data;
import java.util.Set;
@Data public class ShotControlNetOptions {
    public static final Set<String> CONTROL_TYPES=Set.of("CANNY","POSE","DEPTH","HED","MLSD","SCRIBBLE","LAYOUT","GRAY","PREPARED");
    private boolean enabled;
    private String referenceId;
    private String controlType="CANNY";
    private double strength=1;
}
