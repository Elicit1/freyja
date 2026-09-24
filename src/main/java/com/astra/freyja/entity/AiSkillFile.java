package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent Skill 版本内的附属文件索引。
 * 文件内容保存于 MinIO，数据库只保存安全相对路径、哈希和对象键。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_skill_file")
public class AiSkillFile extends BaseEntity {

    private Long skillVersionId;

    /** 相对于 Skill 根目录的路径，如 references/camera.md。 */
    private String relativePath;

    /** ENTRYPOINT / SCRIPT / REFERENCE / ASSET / RESOURCE。 */
    private String fileType;

    private String objectKey;

    private String contentHash;

    private Long contentSize;
}
