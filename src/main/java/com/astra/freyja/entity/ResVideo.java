package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_video")
public class ResVideo extends BaseEntity {
    private Long dramaId;
    private Long shotId;
    private String name;
    private String description;
    private String videoUrl;
    private String originalFilename;
    private Long fileSize;
    private BigDecimal duration;
    private Integer width;
    private Integer height;
    private Double fps;
    private Boolean hasAudio;
    private String sourceType;
    private Integer sortOrder;
    private Integer status;
}
