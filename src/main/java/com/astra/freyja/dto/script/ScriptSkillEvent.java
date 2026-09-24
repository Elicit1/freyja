package com.astra.freyja.dto.script;

/** Server-observed Skill use, never inferred from model output. */
public record ScriptSkillEvent(String stage, String segmentId, int attempt,
                               String name, Long versionId, String version,
                               String contentHash, String source, String status) {
}
