package com.astra.freyja.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
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
@org.springframework.boot.autoconfigure.ImportAutoConfiguration(exclude = org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration.class)
public class McpServerConfiguration {
    public static final String GUIDE = """
            FREYJA BUSINESS MCP
            1. Search authorized projects; read the outline, assets and shot before editing.
            2. Business IDs are decimal strings. Never round or convert them to floating-point numbers.
            3. Public assets are read-only. Project assets and all parent/reference IDs must belong to the authorized project.
            4. Drama scenes are narrative locations; resource scenes are reusable visual assets.
            5. Use business options for supported enum values. Project genre is free text up to 64 characters; drama_genre offers suggestions, not an exhaustive enum. Use an existing suggested value or a clear custom genre name, never invent an enum code for an unlisted genre. Episodes inherit project genre. Never invent existing IDs or media URLs.
            6. Writes require explicit server permission. Read the current business record and its edit fingerprint before editing.
            7. Preserve creator locks and inspect version conflicts. Use a stable request ID for retryable writes.
            8. Directly fill prompt-workshop cards through business CRUD. REFERENCE_MODE uses prompt; FIRST_LAST_FRAME uses videoPrompt and optional firstFramePrompt/endFramePrompt or existing frame URLs.
            9. Read published, enabled Skills with list_skills/read_skill/read_skill_file. Internal Agent/model execution, AI prompt generation and system prompt templates remain private. Reading Skills never starts inference or executes scripts.
            10. The administrator can disable the server or individual tools at runtime. Refresh tools/list after configuration changes.
            11. DirectorPlan and directorPlanMode are optional advanced manual content. Normally omit both; no plan is generated, and an existing plan is preserved on update.
            12. Character creation needs one of name/canonicalName/displayName. Look creation needs characterId, lookName and designDesc. Identity/outfit/appearance prompts and images are optional; do not invent missing visual states.
            13. Pictures and audios keep the submitted array order: item 1 is <Picture 1> or <Audio 1>. Each needs sourceType and a nonblank media URL. Omitted slot IDs are generated. Update the final prompt and reordered media together.
            14. refImages items use imageUrl/sourceType/sourceId, never url/type/assetId. Asset pictures need sourceId; CHARACTER uses a character ID; CHARACTER_LOOK (alias of CHARACTER_REFERENCE) uses a look ID and requires characterId. UPLOAD omits sourceId and may explicitly bind characterId/lookId. usageRole/referenceRole describe weak binding; NON_SUBJECT does not require an on-screen character.
            15. Audio source TTS requires characterId but only references existing audio. Optionally specify real duration and usageMode.
            16. upload_asset_image accepts PNG/JPEG/static WebP bytes as raw Base64, up to 10 MiB, for an authorized project. Choose CHARACTER/CHARACTER_LOOK/SCENE/PROP/KEYFRAME/SHOT_REFERENCE purpose and a stable requestId. Use its returned url in business CRUD; uploading does not overwrite a record or generate internal prompts.
            17. Each business object has independent get/create/update/delete tools: project, episode, scene, shot_group, shot, character, character_look, resource_scene, prop, keyframe. Batch updates use object-specific batch_update_<plural> tools (e.g. batch_update_shots), without a type parameter. Cross-object batch_create_records/batch_delete_records remain available. SCENE means narrative scene; RESOURCE_SCENE means visual scene asset. Writes appear only when server, write permission and the tool are enabled.
            18. Read editFingerprint before updating or deleting. Deletion is logical, requires empty child collections and no live asset/media references, and preserves stored media files. Delete child records before parents; a failure rolls back the whole batch. Reuse requestId after timeouts, including deletion of a project.
            19. For completed storyboard work, fill basic specifications, duration, script, action, dialogue/voiceover/sound as applicable, characterRefs with selected lookId, propRefs, and the scene or keyframe association. Use AUTO for undecided shotType/cameraMovement with corresponding locks false. Preserve existing creator locks. Unstated facts stay absent; do not invent IDs, dialogue or visual states. resSceneId and resKeyframeId are mutually exclusive; additional media can use refImages.
            20. Before writing MiniMax H3 video cards, read h3-prompt-writing and the referenced guide. FIRST_LAST_FRAME: read references/base-en.txt, write videoPrompt in three sections (integrated_multimodal_description, overall_soundscape, non_diegetic_music). REFERENCE_MODE: read references/ref-en.txt, write prompt in six sections (subject_definitions, summary, retention_analysis, detailed_description, overall_soundscape, non_diegetic_music). Match duration and ordered media labels. English sections preserve original-language dialogue. Image prompts alone do not complete video cards. Saving a draft does not automatically generate missing prompts.
            21. batch_upload_asset_images uploads 1–20 images, each up to 10 MiB and total up to 20 MiB. Each item needs unique clientKey, purpose and raw dataBase64; returned urlMap maps keys to URLs. Retry with the same requestId and content. Do not print Base64, full binary tool results or full batch validation dumps; display paths, URLs, created IDs and concise verification summaries. Write local deliverables through workspace file-edit tools; on EPERM use an authorized file tool.
            22. Discover Skills with list_skills at creation start and when moving between image design, storyboard planning and video writing; select by catalog descriptions and read the relevant SKILL.md and referenced files before generating. For character/character-look design images, read character-sheet-design. A requested three-view sheet has full-body front/side/back views, a separate head-portrait region and established detail close-ups on ONE canvas. Respect explicit layout/style/background choices. Real transparent output requires PNG alpha and the image tool's transparency option, not a painted checkerboard; solid white is an alternative only when allowed. Inspect the result and bind its URL to the correct identity/look. A multi-view sheet depicts one character, not multiple people in the video.
            23. These workflow instructions guide external Agents; MCP does not prove that an Agent loaded or obeyed a Skill. If a required Skill is unavailable or disabled, report the missing guidance rather than claim compliance. For H3, read both h3-prompt-writing and its mode-specific reference before saving the final video card.
            24. Creating a project requires an explicit aspectRatio from enabled drama_aspect_ratio options. If the user has not supplied a ratio, ask them to choose from get_business_options before creating the project. Only when the user explicitly delegates this choice to AI, prefer enabled 16:9; if unavailable, choose another enabled option consistent with their request. Do not silently default to 9:16. Preserve existing project ratios when appending episodes or updating unrelated fields.
            """;

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true", matchIfMissing = true)
    McpStatelessSyncServer mcpStatelessSyncServer(WebMvcStatelessServerTransport transport,
            List<McpStatelessServerFeatures.SyncToolSpecification> freyjaMcpTools,
            List<McpStatelessServerFeatures.SyncResourceSpecification> freyjaMcpResources,
            org.springframework.core.env.Environment environment) {
        // Every registered tool validates in McpToolCatalog. Avoid SDK oneOf diagnostics
        // repeating all ten record schemas and echoing large payloads before our handler.
        return McpServer.sync(transport).serverInfo(environment.getProperty("spring.ai.mcp.server.name", "freyja-digital-studio"),
                        environment.getProperty("spring.ai.mcp.server.version", "1.0.0"))
                .instructions(environment.getProperty("spring.ai.mcp.server.instructions", GUIDE))
                .requestTimeout(org.springframework.boot.convert.DurationStyle.detectAndParse(environment.getProperty("spring.ai.mcp.server.request-timeout", "300s")))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).resources(false, false).build())
                .validateToolInputs(false).tools(freyjaMcpTools).resources(freyjaMcpResources).build();
    }

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
    List<McpStatelessServerFeatures.SyncToolSpecification> freyjaMcpTools(McpReadTools reads, McpToolCatalog catalog, ObjectProvider<McpBusinessTools> business, ObjectProvider<McpRuntimeControl> controls, ObjectProvider<McpSkillTools> skills) {
        McpRuntimeControl control = controls.getIfAvailable();
        if (control != null) {
            var state = control.current();
            java.util.List<McpTool<?>> all = new java.util.ArrayList<>(reads.tools()); business.ifAvailable(adapter -> all.addAll(adapter.allTools()));
            skills.ifAvailable(adapter -> all.addAll(adapter.tools()));
            return all.stream().filter(tool -> control.toolEnabled(state, tool)).map(catalog::specification).toList();
        }
        java.util.List<McpTool<?>> tools = new java.util.ArrayList<>(reads.tools());
        business.ifAvailable(adapter -> tools.addAll(adapter.tools()));
        skills.ifAvailable(adapter -> tools.addAll(adapter.tools()));
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
