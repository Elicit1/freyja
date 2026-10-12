package com.astra.freyja.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
public class McpServerConfiguration {
    public static final String GUIDE = """
            FREYJA BUSINESS MCP
            1. Search authorized projects; read the outline, assets and shot before editing.
            2. Business IDs are decimal strings. Never round or convert them to floating-point numbers.
            3. Public assets are read-only. Project assets and all parent/reference IDs must belong to the authorized project.
            4. Drama scenes are narrative locations; resource scenes are reusable visual assets.
            5. Use business options for supported enum values. Never invent existing IDs or media URLs.
            6. Writes require explicit server permission. Read the current business record and its edit fingerprint before editing.
            7. Preserve creator locks and inspect version conflicts. Use a stable request ID for retryable writes.
            8. Internal AI, agents, model execution, prompts and skills are outside this server's capabilities.
            """;

    @Bean
    FilterRegistrationBean<McpAuthenticationFilter> mcpAuthenticationFilter(McpProperties properties) {
        var registration = new FilterRegistrationBean<>(new McpAuthenticationFilter(properties));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    @Bean
    WebMvcStatelessServerTransport webMvcStatelessServerTransport() {
        // The servlet filter enforces authentication and the configured Origin allowlist.
        // A separate protocol mapper preserves JSON-RPC numeric request IDs.
        return WebMvcStatelessServerTransport.builder().messageEndpoint("/mcp")
                .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
                .securityValidator(ServerTransportSecurityValidator.NOOP)
                .contextExtractor(request -> {
                    Object caller = request.servletRequest().getAttribute(McpPrincipal.CONTEXT_KEY);
                    return caller instanceof McpPrincipal ? McpTransportContext.create(Map.of(McpPrincipal.CONTEXT_KEY, caller)) : McpTransportContext.EMPTY;
                }).build();
    }

    @Bean
    List<McpStatelessServerFeatures.SyncToolSpecification> freyjaMcpTools(McpReadTools reads, McpToolCatalog catalog, ObjectProvider<McpBusinessTools> business) {
        java.util.List<McpTool<?>> tools = new java.util.ArrayList<>(reads.tools());
        business.ifAvailable(adapter -> tools.addAll(adapter.tools()));
        return tools.stream().map(catalog::specification).toList();
    }

    @Bean
    List<McpStatelessServerFeatures.SyncResourceSpecification> freyjaMcpResources() {
        var resource = McpSchema.Resource.builder().uri("freyja://guide").name("freyja-creation-guide")
                .description("FREYJA 创作、ID、项目范围与工具使用规则").mimeType("text/plain").build();
        return List.of(new McpStatelessServerFeatures.SyncResourceSpecification(resource, (context, request) -> {
            McpToolCatalog.principal(context);
            return McpSchema.ReadResourceResult.builder(List.of(
                    McpSchema.TextResourceContents.builder(request.uri(), GUIDE).mimeType("text/plain").build())).build();
        }));
    }
}
