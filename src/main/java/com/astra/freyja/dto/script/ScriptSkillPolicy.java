package com.astra.freyja.dto.script;

import lombok.Data;

/** Planner and Worker Skill settings are independent. */
@Data
public class ScriptSkillPolicy {
    private ScriptSkillStagePolicy planner;
    private ScriptSkillStagePolicy worker;

    public boolean isActive() {
        return planner != null && planner.isActive() || worker != null && worker.isActive();
    }
}
