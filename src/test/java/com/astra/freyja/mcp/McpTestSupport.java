package com.astra.freyja.mcp;

import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Explicit dependencies for schema/handler unit tests; runtime protocol tests use the real platform. */
public final class McpTestSupport {
    public static McpToolCatalog catalog(McpJson json) {
        var catalog = new McpToolCatalog(json);
        ReflectionTestUtils.setField(catalog, "control", mock(McpRuntimeControl.class));
        var options = mock(McpBusinessOptions.class);
        when(options.apply(any())).thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(catalog, "businessOptions", options);
        return catalog;
    }
    static McpAuthenticationFilter filter(McpProperties properties) {
        var control = mock(McpRuntimeControl.class);
        var state = new McpRuntimeSettings();
        state.setEnabled(properties.isEnabled()); state.setWriteEnabled(properties.isWriteEnabled());
        state.setAllowAllProjects(properties.isAllowAllProjects()); state.setAllowCreateProjects(properties.isAllowCreateProjects());
        state.setAllowedOrigins(properties.getAllowedOrigins()); state.setAllowedProjectIds(properties.getAllowedProjectIds());
        when(control.current()).thenReturn(state); when(control.token(state)).thenReturn(properties.getToken());
        var filter = new McpAuthenticationFilter(control, mock(com.astra.freyja.capability.service.CapabilityMcpDispatcher.class));
        return filter;
    }
}
