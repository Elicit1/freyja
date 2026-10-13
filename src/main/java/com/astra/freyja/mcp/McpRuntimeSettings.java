package com.astra.freyja.mcp;

import lombok.Data;
import lombok.ToString;
import java.util.*;

@Data
public class McpRuntimeSettings {
    private int revision;
    private boolean enabled;
    private boolean writeEnabled;
    private boolean allowAllProjects;
    private boolean allowCreateProjects;
    private Set<String> allowedProjectIds = new LinkedHashSet<>();
    private Set<String> allowedOrigins = new LinkedHashSet<>();
    private Map<String, Boolean> tools = new LinkedHashMap<>();
    @ToString.Exclude private String tokenCiphertext;
    public record Update(Integer revision, Boolean enabled, Boolean writeEnabled, Boolean allowAllProjects, Boolean allowCreateProjects,
                         Set<String> allowedProjectIds, Set<String> allowedOrigins, Map<String, Boolean> tools, String token) {}
    public record Switch(Integer revision, Boolean enabled) {}
}
