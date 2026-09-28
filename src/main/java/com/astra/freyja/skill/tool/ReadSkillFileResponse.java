package com.astra.freyja.skill.tool;

/** Skill 附属文件工具结果；不向模型暴露对象存储地址或对象键。 */
public record ReadSkillFileResponse(String status, String name, String path,
                                    String content, String message) {
}
