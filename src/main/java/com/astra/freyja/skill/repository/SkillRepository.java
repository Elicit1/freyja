package com.astra.freyja.skill.repository;

import java.util.List;

/**
 * 标准 Skill 附属文件的对象存储抽象。
 * SKILL.md 入口仍然保存在 ai_skill_version.content，其他文件按版本存入 MinIO。
 */
public interface SkillRepository {

    void saveFile(String objectKey, byte[] data, String contentType);

    byte[] loadFile(String objectKey, String expectedHash, Long expectedSize);

    String loadTextFile(String objectKey, String expectedHash, Long expectedSize);

    void removeFile(String objectKey);

    void removeFiles(List<String> objectKeys);
}
