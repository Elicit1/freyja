package com.astra.freyja.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
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
            8. Directly fill prompt-workshop cards through business CRUD. REFERENCE_MODE uses prompt; FIRST_LAST_FRAME uses videoPrompt and optional firstFramePrompt/endFramePrompt or existing frame URLs.
            9. Internal Agent/model execution, AI prompt generation, system prompt templates and Skills are never exposed. Saving configuration does not start inference.
            10. The administrator can disable the server or individual tools at runtime. Refresh tools/list after configuration changes.
            11. DirectorPlan and directorPlanMode are optional advanced manual content. Normally omit both; no plan is generated, and an existing plan is preserved on update.
            12. Character creation needs one of name/canonicalName/displayName. Look creation needs characterId, lookName and designDesc. Identity/outfit/appearance prompts and images are optional; do not invent missing visual states.
            13. Pictures and audios keep the submitted array order: item 1 is <Picture 1> or <Audio 1>. Each needs sourceType and a nonblank media URL. Omitted slot IDs are generated. Update the final prompt and reordered media together.
            14. Asset pictures need sourceId; CHARACTER uses a character ID, CHARACTER_REFERENCE uses a look ID and requires characterId. UPLOAD omits sourceId and may explicitly bind characterId/lookId. usageRole/referenceRole describe weak binding; NON_SUBJECT does not require an on-screen character.
            15. Audio source TTS requires characterId but only references existing audio. Optionally specify real duration and usageMode.
            16. upload_asset_image accepts PNG/JPEG/static WebP bytes as raw Base64, up to 10 MiB, for an authorized project. Choose CHARACTER/CHARACTER_LOOK/SCENE/PROP/KEYFRAME/SHOT_REFERENCE purpose and a stable requestId. Use its returned url in business CRUD; uploading does not overwrite a record or generate internal prompts.
            17. Each business object has independent get/create/update/delete tools: project, episode, scene, shot_group, shot, character, character_look, resource_scene, prop, keyframe. Batch updates use object-specific batch_update_<plural> tools (e.g. batch_update_shots), without a type parameter. Cross-object batch_create_records/batch_delete_records remain available. SCENE means narrative scene; RESOURCE_SCENE means visual scene asset. Writes appear only when server, write permission and the tool are enabled.
            18. Read editFingerprint before updating or deleting. Deletion is logical, requires empty child collections and no live asset/media references, and preserves stored media files. Delete child records before parents; a failure rolls back the whole batch. Reuse requestId after timeouts, including deletion of a project.
            """;

    @Bean
    FilterRegistrationBean<McpAuthenticationFilter> mcpAuthenticationFilter(McpProperties properties, ObjectProvider<McpRuntimeControl> controls) {
        var registration = new FilterRegistrationBean<>(new McpAuthenticationFilter(properties, controls.getIfAvailable()));
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
    List<McpStatelessServerFeatures.SyncToolSpecification> freyjaMcpTools(McpReadTools reads, McpToolCatalog catalog, ObjectProvider<McpBusinessTools> business, ObjectProvider<McpRuntimeControl> controls) {
        McpRuntimeControl control = controls.getIfAvailable();
        if (control != null) {
            var state = control.current();
            java.util.List<McpTool<?>> all = new java.util.ArrayList<>(reads.tools()); business.ifAvailable(adapter -> all.addAll(adapter.allTools()));
            return all.stream().filter(tool -> control.toolEnabled(state, tool)).map(catalog::specification).toList();
        }
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
