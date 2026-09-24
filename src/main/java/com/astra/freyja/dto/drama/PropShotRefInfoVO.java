package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 分镜镜头引用的道具信息展示 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PropShotRefInfoVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 道具资产 ID (res_prop.id) */
    private Long propId;

    /** 道具中文名称 */
    private String propName;

    /** 道具类型: KEY_PROP/WEAPON/COSTUME_ACCESSORY/DAILY */
    private String propType;

    /** 道具英文 Prompt */
    private String propPrompt;

    /** 道具设计/参考图封面 URL */
    private String coverUrl;
}
