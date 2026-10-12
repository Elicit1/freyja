package com.astra.freyja.mcp;

import java.util.Set;

/** Immutable request identity; credentials never enter tool arguments or results. */
public record McpPrincipal(boolean write, boolean allProjects,
                           boolean createProjects, Set<Long> projects) {
    public static final String CONTEXT_KEY = "freyja.mcp.principal";
    public McpPrincipal { projects = Set.copyOf(projects); }
    public boolean canRead(Long projectId) { return projectId != null && (allProjects || projects.contains(projectId)); }
}
