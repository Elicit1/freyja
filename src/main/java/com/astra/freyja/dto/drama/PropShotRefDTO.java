package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 分镜镜头引用的道具信息 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PropShotRefDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 道具资产 ID (res_prop.id) */
    private Long propId;

    /** 道具中文名称 (如: 古董黄铜座钟, 黑色匕首) */
    private String propName;

    /** 道具类型: KEY_PROP(核心叙事道具)/WEAPON(武器)/COSTUME_ACCESSORY(服饰配饰)/DAILY(日常杂物) */
    private String propType;

    /** 道具英文生图/材质提示词 */
    private String propPrompt;
}
