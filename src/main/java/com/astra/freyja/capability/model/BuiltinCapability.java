package com.astra.freyja.capability.model;

/** Stable executor semantics, independent of configurable model-facing tool names. */
public enum BuiltinCapability {
    TASK_PLAN("INTERNAL_AGENT:update_task_plan"),
    LOAD_SKILL("INTERNAL_AGENT:load_skill");
    private final String handlerKey;
    BuiltinCapability(String handlerKey) { this.handlerKey = handlerKey; }
    public String handlerKey() { return handlerKey; }
}
