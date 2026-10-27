package com.astra.freyja.dto.res;
import lombok.Data;

/** Editing metadata never changes the immutable uploaded audio URL. */
@Data
public class ResAudioDTO {
    private Long id;
    private Long dramaId;
    private Long shotId;
    private String name;
    private String description;
    private String audioKind;
    private String text;
    private String language;
    private Integer sortOrder;
    private Integer status;
    private String remark;
}
