package com.astra.freyja.dto.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 人工将剧本提及绑定到既有角色时使用的事实与证据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CharacterResolutionContext implements Serializable {

    /** 短剧 ID */
    private Long dramaId;

    /** 剧集 ID */
    private Long episodeId;

    /** 场次 ID */
    private Long sceneId;

    /** 剧本中的人物提及/称谓 (如: 女人, 她, 林小姐, 医生) */
    private String mention;

    /** 包含提及的周边上下文剧本文本 */
    private String surroundingText;

}
