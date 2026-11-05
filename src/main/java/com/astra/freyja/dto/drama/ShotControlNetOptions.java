package com.astra.freyja.dto.drama;
import lombok.Data;
@Data public class ShotControlNetOptions {
    private boolean enabled;
    private String referenceId;
    private String controlType="CANNY";
    private double strength=1;
}
