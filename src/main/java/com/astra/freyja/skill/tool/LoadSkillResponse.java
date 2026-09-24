package com.astra.freyja.skill.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * load_skill 工具返回结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoadSkillResponse implements Serializable {

    private String name;

    private String version;

    private String contentHash;

    /** SKILL.md 正文 */
    private String content;

    /** 状态标识: SUCCESS / ALREADY_LOADED / ERROR */
    private String status;

    /** 提示或错误消息 */
    private String message;
}
