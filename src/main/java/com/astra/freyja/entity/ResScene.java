package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 场景环境资产表 res_scene。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_scene")
public class ResScene extends BaseEntity {

    /** 归属短剧ID，0为公共资源库 */
    private Long dramaId;

    /** 场景名称 */
    private String name;

    /** 场景中文背景与视觉细节描述 (原著视觉源) */
    private String description;

    /** 场景封面/参考图URL */
    private String coverUrl;

    /** 空间类型 INDOOR(室内)/OUTDOOR(室外)/STUDIO(影棚)/VIRTUAL(虚构) */
    private String sceneType;

    /** 时间时段 DAY(日间)/NIGHT(夜间)/SUNSET(黄昏)/DAWN(拂晓) */
    private String timeOfDay;

    /** 天气氛围 (如 SUNNY/RAINY/FOGGY/CYBERPUNK/NEON/MOODY) */
    private String weatherAtmosphere;

    /** 场景生图Prompt (英文，空间、陈设与光影一体化描述) */
    private String scenePrompt;

    /** 场景专属负向Prompt */
    private String negativePrompt;

    /** 场景风格LoRA */
    private String loraName;

    /** LoRA权重 */
    private BigDecimal loraWeight;

    /** ControlNet/IP-Adapter 空间参考图URL */
    private String referenceImageUrl;

    /** 显示排序 */
    private Integer sortOrder;

    /** 状态 0-停用 1-启用 */
    private Integer status;
}
