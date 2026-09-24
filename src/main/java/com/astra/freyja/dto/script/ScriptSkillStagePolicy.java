package com.astra.freyja.dto.script;

import lombok.Data;

import java.util.List;

/** One stage's explicit Skill selection and optional runtime tool access. */
@Data
public class ScriptSkillStagePolicy {
    private boolean allowDynamicLoad;
    private List<String> requiredSkillNames = List.of();

    public boolean isActive() {
        return allowDynamicLoad || requiredSkillNames != null && !requiredSkillNames.isEmpty();
    }
}
