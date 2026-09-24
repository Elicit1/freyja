package com.astra.freyja.skill.model;

/**
 * Skill ZIP 导出包数据载荷。
 *
 * @param filename 推荐的下载文件名，如 "cinematography-v1.zip"
 * @param zipData  ZIP 压缩包二进制字节
 */
public record SkillExportPackage(String filename, byte[] zipData) {
}
