package com.astra.freyja.capability;

import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.tool.*;
import com.astra.freyja.capability.model.CapabilityModels.*;
import com.astra.freyja.capability.service.CapabilityHandlers;
import com.astra.freyja.mcp.McpJson;
import com.astra.freyja.mcp.McpToolInventory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapabilityHandlersTest {
    record Empty() {}
    @SuppressWarnings("unchecked")
    <T> ObjectProvider<T> provider(T value) {
        var result = (ObjectProvider<T>) mock(ObjectProvider.class);
        when(result.getObject()).thenReturn(value); return result;
    }
    CapabilityHandlers handlers(List<AgentTool<?>> tools) {
        var inventory = mock(McpToolInventory.class); when(inventory.registeredHandlers()).thenReturn(List.of());
        return new CapabilityHandlers(provider(inventory), provider(tools), new McpJson(), new AgentJson());
    }
    @Test void firstImportUsesHandlerMetadataWithoutAnAgentProfile() {
        AgentTool<Empty> optional = new AgentTool<>() {
            public String name() { return "optional_lookup"; }
            public String description() { return "可选能力"; }
            public Class<Empty> inputType() { return Empty.class; }
            public ToolDescriptor descriptor() { return new ToolDescriptor(name(), 1, description(), Effect.READ, RecoveryMode.REPLAY_SAFE, false, "{\"type\":\"object\",\"properties\":{}}"); }
            public ToolResult execute(Empty input, ToolContext context) { return ToolResult.success(null); }
        };
        var bindings = handlers(List.of(new RequestUserInputTool(), new UpdateTaskPlanTool(), optional)).initialBindings();
        assertEquals(3, bindings.size());
        assertTrue(bindings.stream().allMatch(b -> b.consumer() == Consumer.INTERNAL_AGENT && b.consumerCode().equals("core-assistant")));
        assertEquals(List.of("request_user_input", "update_task_plan"), bindings.stream().filter(Binding::required).map(Binding::name).toList());
        assertFalse(bindings.getLast().required());
    }
    @Test void duplicateAndUnsafeWriteHandlersAreRejectedByThePlatform() {
        assertThrows(IllegalStateException.class, () -> handlers(List.of(new RequestUserInputTool(), new RequestUserInputTool())).all());
        AgentTool<?> write = mock(AgentTool.class);
        when(write.name()).thenReturn("unsafe_write"); when(write.effect()).thenReturn(Effect.WRITE);
        when(write.recoveryMode()).thenReturn(RecoveryMode.REPLAY_SAFE);
        when(write.descriptor()).thenReturn(new ToolDescriptor("unsafe_write", 1, "不安全写能力", Effect.WRITE, RecoveryMode.REPLAY_SAFE, false, "{}"));
        assertThrows(IllegalStateException.class, () -> handlers(List.of(write)).all());
    }
}
