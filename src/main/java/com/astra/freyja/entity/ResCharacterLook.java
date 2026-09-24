package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 人物造型表 res_character_look（替代原 res_character_outfit）。
 * 完整聚合一套造型的所有视觉要素（服装、妆发、年龄、伤痕、负向、唯一参考图与一致性状态）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_character_look")
public class ResCharacterLook extends BaseEntity {

    /** 归属人物ID */
    private Long characterId;

    /** 造型名称 (如: 日常便服 / 晚礼服 / 战术装 / 少年期) */
    private String lookName;

    /** 是否默认造型 0-否 1-是 */
    private Integer isDefault;

    /** 造型唯一参考图URL (MinIO) */
    private String referenceImageUrl;

    /** 图片一致性状态：MISSING(无图) / SYNCED(图文一致) / STALE(Prompt已改图已过期) */
    private String imageStatus;

    /** 造型 Prompt 当前版本 */
    private Integer visualVersion;

    /** 图片生成/上传时所对应的 Prompt 版本 */
    private Integer imageVisualVersion;

    /** 服饰装扮Prompt (英文) */
    private String outfitPrompt;

    /** 视觉状态类型：BASE/COSTUME/AGE_PHASE/DISGUISE/DAMAGE/CUSTOM */
    private String lookType;

    /** 造型视觉概念描述 (颜色/面料/轮廓/配饰/妆发) */
    private String designDesc;

    /** 非服装视觉状态描述，如妆发、年龄阶段、伤痕 */
    private String appearancePrompt;

    /** 造型专属负向约束 */
    private String negativePrompt;

    /** 服装专属LoRA (可选) */
    private String loraName;

    /** LoRA权重 */
    private BigDecimal loraWeight;

    /** 显示排序 */
    private Integer sortOrder;

    /** 状态 0-停用 1-启用 */
    private Integer status;
}
