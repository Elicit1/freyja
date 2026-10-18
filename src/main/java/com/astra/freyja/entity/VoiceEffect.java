package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("voice_effect")
public class VoiceEffect extends BaseEntity {
    private String name;
    private String description;
    private String stepsJson;
    private Integer version;
    private Integer enabled;
}
