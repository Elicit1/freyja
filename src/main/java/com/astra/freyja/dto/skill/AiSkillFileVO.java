package com.astra.freyja.dto.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Skill 标准包内文件索引。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSkillFileVO implements Serializable {

    private Long id;

    private Long skillVersionId;

    private String relativePath;

    private String fileType;

    private String contentHash;

    private Long contentSize;
}
