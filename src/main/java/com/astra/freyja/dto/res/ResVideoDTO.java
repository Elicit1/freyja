package com.astra.freyja.dto.res;
import lombok.Data;

/** Editing metadata never changes the immutable uploaded video URL. */
@Data
public class ResVideoDTO {
    private Long id;
    private Long dramaId;
    private Long shotId;
    private String name;
    private String description;
    private Integer sortOrder;
    private Integer status;
    private String remark;
}
