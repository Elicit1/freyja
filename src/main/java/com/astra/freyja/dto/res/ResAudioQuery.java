package com.astra.freyja.dto.res;
import lombok.Data;

@Data
public class ResAudioQuery {
    private Integer current = 1;
    private Integer size = 12;
    private String name;
    private Long dramaId;
    private Long shotId;
    /** Library picker includes public materials and this drama's materials. */
    private Long availableDramaId;
    private String sourceType;
    private String audioKind;
    private Integer status;
}
