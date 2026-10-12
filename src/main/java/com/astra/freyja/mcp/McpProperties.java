package com.astra.freyja.mcp;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
@ConfigurationProperties("freyja.mcp")
public class McpProperties {
    private boolean enabled;
    @ToString.Exclude
    private String token = "";
    private boolean writeEnabled;
    private boolean allowAllProjects;
    private boolean allowCreateProjects;
    private Set<String> allowedProjectIds = new LinkedHashSet<>();
    private Set<String> allowedOrigins = new LinkedHashSet<>();
}
