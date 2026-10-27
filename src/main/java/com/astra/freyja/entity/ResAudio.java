package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_audio")
public class ResAudio extends BaseEntity {
    private Long dramaId;
    private Long shotId;
    private String name;
    private String description;
    private String audioUrl;
    private String originalFilename;
    private Long fileSize;
    private BigDecimal duration;
    private String audioKind;
    private String text;
    private String language;
    private String sourceType;
    private Integer sortOrder;
    private Integer status;
}
