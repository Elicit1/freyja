package com.astra.freyja.agent;

import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.tool.*;
import com.astra.freyja.capability.model.CapabilityModels.*;
import com.astra.freyja.capability.service.CapabilityCatalogService;
import com.astra.freyja.common.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentToolRegistryTest {
    CapabilityCatalogService capabilities = mock(CapabilityCatalogService.class);
    AgentToolRegistry registry = new AgentToolRegistry(capabilities);

    @Test void publishedSnapshotControlsNamesSchemasVersionsAndRevocation() {
        var snapshot = AgentTestSupport.snapshot(List.of("published_lookup"), "NORMAL");
        var binding = snapshot.capabilities().bindings().getFirst();
        when(capabilities.release("1")).thenReturn(new Release("1", 23, List.of(binding), "", "固定版本规则"));
        when(capabilities.requireCurrent(binding.id())).thenReturn(binding);
        AgentTool<?> tool = mock(AgentTool.class);
        when(tool.descriptor()).thenReturn(new ToolDescriptor("published_lookup", 23, "发布说明", Effect.READ, RecoveryMode.REPLAY_SAFE, false,
                "{\"type\":\"object\",\"properties\":{\"recordId\":{\"type\":\"string\"}}}"));
        doReturn(tool).when(capabilities).agentTool(binding);
        var definitions = registry.definitions(List.of("published_lookup"), snapshot);
        assertEquals("published_lookup", definitions.getFirst().getToolDefinition().name());
        assertTrue(definitions.getFirst().getToolDefinition().inputSchema().contains("recordId"));
        assertEquals(23, registry.releaseVersion(snapshot.capabilities()));
        assertEquals("固定版本规则", registry.sharedRules(snapshot.capabilities()));
        assertThrows(IllegalStateException.class, () -> definitions.getFirst().call("{}"));
        assertEquals(403, assertThrows(BizException.class, () -> registry.require("unpublished_tool", snapshot)).getCode());
        when(capabilities.requireCurrent(binding.id())).thenThrow(new BizException(403, "CAPABILITY_DISABLED"));
        assertEquals(403, assertThrows(BizException.class, () -> registry.require("published_lookup", snapshot)).getCode());
        verify(capabilities, times(1)).agentTool(binding);
        verify(capabilities, never()).current(); // Running tasks keep their own published contract.
    }

    @Test void missingPlatformSnapshotCannotUseNativeToolFallback() {
        var original = AgentTestSupport.snapshot(List.of("read"), "NORMAL");
        var legacy = new Snapshot(original.agentCode(), original.configRevision(), original.profileVersion(), original.config(),
                original.fixedInstructions(), Map.of(), original.toolVersions(), original.pinnedModel(), original.runType(), null, null);
        assertEquals(409, assertThrows(BizException.class, () -> registry.require("read", legacy)).getCode());
        verifyNoInteractions(capabilities);
    }

    @Test void platformFailureIsReportedWithoutAnotherRegistry() {
        when(capabilities.agentSnapshot("core-assistant")).thenThrow(new BizException(503, "能力目录尚未发布"));
        assertEquals(503, assertThrows(BizException.class, () -> registry.available("core-assistant")).getCode());
        verify(capabilities, never()).agentTool(any());
    }
}
