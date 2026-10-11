package com.astra.freyja.agent.tool;

import com.astra.freyja.agent.model.AgentProtocol.*;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;

public interface AgentTool<I> {
    String name();
    String description();
    Class<I> inputType();
    default int version() { return 1; }
    default Effect effect() { return Effect.READ; }
    default RecoveryMode recoveryMode() { return RecoveryMode.REPLAY_SAFE; }
    default boolean projectRequired() { return false; }
    ToolResult execute(I input, ToolContext context);
    default ToolResult reconcile(I input, ToolContext context, WaitHandle handle) {
        if (recoveryMode() == RecoveryMode.REPLAY_SAFE) return execute(input, context);
        throw new IllegalStateException("工具未实现查询恢复: " + name());
    }
    default ToolDescriptor descriptor() {
        return new ToolDescriptor(name(), version(), description(), effect(), recoveryMode(),
                projectRequired(), JsonSchemaGenerator.generateForType(inputType()));
    }
}
