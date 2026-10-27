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
            Output language: Generate all non-prompt text in Simplified Chinese, including project/episode/scene/shot/asset names, titles, genres, summaries, descriptions, character biographies, look design descriptions, scripts, actions, dialogue, voiceover, sound descriptions, user-facing explanations and completion summaries. Prompt fields follow the language requirements of the relevant Skill/model/user request; preserve original-language dialogue inside prompts. Keep JSON field names, enum codes, IDs, URLs, technical identifiers and existing creator-authored text unchanged. This language rule also applies when a loaded Skill uses English examples for non-prompt content.
            1. Search authorized projects; read the outline, assets and shot before editing.
            2. Business IDs are decimal strings. Never round or convert them to floating-point numbers.
            3. Public assets are read-only. Project assets and all parent/reference IDs must belong to the authorized project.
            4. Drama scenes are narrative locations; resource scenes are reusable visual assets.
            5. Use business options for supported enum values. Project genre is free text up to 64 characters; drama_genre offers suggestions, not an exhaustive enum. Use an existing suggested value or a clear custom genre name, never invent an enum code for an unlisted genre. Episodes inherit project genre. Never invent existing IDs or media URLs.
            6. Writes require explicit server permission. Read the current business record and its edit fingerprint before editing.
            7. Preserve creator locks and inspect version conflicts. Use a stable request ID for retryable writes.
            8. Directly fill prompt-workshop cards through business CRUD. REFERENCE_MODE uses prompt; FIRST_LAST_FRAME uses videoPrompt and optional firstFramePrompt/endFramePrompt or existing frame URLs.
            9. Read published, enabled Skills with list_skills/read_skill/read_skill_file. Start platform creation with freyja-platform-guide and its relevant references for narrative hierarchy, shot groups, visual reference selection, first/last-frame versus reference mode, and explicit performance in scripts. Character plus scene references may be combined; a verified key image covering the required identities, looks and environment may replace redundant images while preserving business character associations. Internal Agent/model execution, AI prompt generation and system prompt templates remain private. Reading Skills never starts inference or executes scripts.
            10. The administrator can disable the server or individual tools at runtime. Refresh tools/list after configuration changes.
            11. DirectorPlan and directorPlanMode are optional advanced manual content. Normally omit both; no plan is generated, and an existing plan is preserved on update.
            12. Character creation needs one of name/canonicalName/displayName. Look creation needs characterId, lookName and designDesc. Identity/outfit/appearance prompts and images are optional; do not invent missing visual states.
            Character naming: When creating a character, default to the confirmed name from the creator, source story or existing project character records. Use that confirmed name consistently in name/canonicalName/displayName when supplied; do not replace it with an invented name, generic role label, temporary designation, translated name or transliteration. Read existing project characters first and reuse the matching confirmed identity instead of creating a duplicate under an alias. Preserve confirmed names in their original form; the Simplified Chinese output rule does not rename characters. If no name is confirmed, keep an explicit source designation without inventing a real name; ask the creator when identity or naming is ambiguous. Only rename when explicitly requested by the creator.
            13. Pictures and audios keep the submitted array order: item 1 is <Picture 1> or <Audio 1>. Each needs a nonblank media URL. Images require sourceType; audios require referenceType. Omitted slot IDs are generated. Update the final prompt and reordered media together.
            14. refImages items use imageUrl/sourceType/sourceId, never url/type/assetId. Asset pictures need sourceId; CHARACTER uses a character ID; CHARACTER_LOOK (alias of CHARACTER_REFERENCE) uses a look ID and requires characterId. UPLOAD omits sourceId and may explicitly bind characterId/lookId. usageRole/referenceRole describe weak binding; NON_SUBJECT does not require an on-screen character.
            15. refAudios uses audioUrl and required referenceType: fully_copy, partially_copy, reference or weak_reference. No sourceType or usageMode is accepted. Library selections include resAudioId with its archived audioUrl; center selections include audioId and matching characterId. These source IDs are mutually exclusive. Music and effects need no characterId. Optionally provide actual duration and referenceDescription. Existing invalid audio configurations must be reselected, never translated or defaulted.
            16. Use prepare_asset_image_upload for binary image uploads up to 10 MiB: submit file metadata, size and computed SHA-256, POST multipart/form-data to the returned uploadUrl with every returned field unchanged and the actual file last, then call complete_asset_image_upload with a separate requestId. Only the completion result supplies a final asset URL. The client needs HTTP/file transfer capability; local paths do not transfer files. Choose CHARACTER/CHARACTER_LOOK/SCENE/PROP/KEYFRAME/SHOT_REFERENCE purpose; use the returned final url in business CRUD. Uploading does not overwrite records or generate internal prompts.
            17. Each business object has independent get/create/update/delete tools: project, episode, scene, shot_group, shot, character, character_look, resource_scene, prop, keyframe. Batch updates use object-specific batch_update_<plural> tools (e.g. batch_update_shots), without a type parameter. Cross-object batch_create_records/batch_delete_records remain available. SCENE means narrative scene; RESOURCE_SCENE means visual scene asset. Writes appear only when server, write permission and the tool are enabled.
            18. Read editFingerprint before updating or deleting. Deletion is logical, requires empty child collections and no live asset/media references, and preserves stored media files. Delete child records before parents; a failure rolls back the whole batch. Reuse requestId after timeouts, including deletion of a project.
            19. For completed storyboard work, fill basic specifications, duration, scriptContent (integrating all narrative action, character dialogue and voiceover naturally into unified script text), characterRefs with selected lookId, propRefs, and the scene or keyframe association. Storyboard shots do not use separate dialogue or voiceover input fields. Use AUTO for undecided shotType/cameraMovement with corresponding locks false. Preserve existing creator locks. Unstated facts stay absent; do not invent IDs, dialogue or visual states. resSceneId and resKeyframeId are mutually exclusive; additional media can use refImages.
            20. Before writing MiniMax H3 video cards, read h3-prompt-writing and the referenced guide. FIRST_LAST_FRAME: read references/base-en.txt, write videoPrompt in three sections (integrated_multimodal_description, overall_soundscape, non_diegetic_music). REFERENCE_MODE: read references/ref-en.txt, write prompt in six sections (subject_definitions, summary, retention_analysis, detailed_description, overall_soundscape, non_diegetic_music). Match duration and ordered media labels. English sections preserve original-language dialogue. Image prompts alone do not complete video cards. Saving a draft does not automatically generate missing prompts.
            21. Upload multiple images through separate prepare/direct-transfer/complete flows with stable request IDs for each image. Track each file's returned final URL before associating assets. Keep image bytes out of tool arguments and model output; do not print signed upload fields or full validation dumps. Display paths, final URLs, created IDs and concise verification summaries. Write local deliverables through workspace file-edit tools; on EPERM use an authorized file tool.
            22. Discover Skills with list_skills at creation start and when moving between image design, storyboard planning and video writing; select by catalog descriptions and read the relevant SKILL.md and referenced files before generating. For character/character-look design images, read character-sheet-design. A requested three-view sheet has full-body front/side/back views, a separate head-portrait region and established detail close-ups on ONE canvas. Respect explicit layout/style/background choices. Real transparent output requires PNG alpha and the image tool's transparency option, not a painted checkerboard; solid white is an alternative only when allowed. Inspect the result and bind its URL to the correct identity/look. A multi-view sheet depicts one character, not multiple people in the video.
            23. These workflow instructions guide external Agents; MCP does not prove that an Agent loaded or obeyed a Skill. If a required Skill is unavailable or disabled, report the missing guidance rather than claim compliance. For story creation, restore required Skill access before following the story-writing workflow. For H3, read both h3-prompt-writing and its mode-specific reference before saving the final video card.
            24. Creating a project requires an explicit aspectRatio from enabled drama_aspect_ratio options. If the user has not supplied a ratio, ask them to choose from get_business_options before creating the project. Only when the user explicitly delegates this choice to AI, prefer enabled 16:9; if unavailable, choose another enabled option consistent with their request. Do not silently default to 9:16. Preserve existing project ratios when appending episodes or updating unrelated fields.
            25. When creating shots, estimate duration explicitly from complete action and dialogue at natural performance speed. Ordinary actions often take 3–5 seconds; 7–8 seconds is a comfortable long-shot rendering reference. Longer shots increase backend rendering pressure: when a shot would exceed 8 seconds, actively look for complete action boundaries, dialogue turns or independently meaningful narrative beats and split where continuity permits. Avoid excessive fragmentation: keep preparation, execution and result under one goal together where possible, and never cut solely to fill a fixed duration bucket. Preserve complete dialogue, original speed and explicit creator requests for longer continuous takes. Pacing presets are references, not quotas; parallel actions share time, environment and held-object states add no time. Do not pad to a target episode runtime or invent pauses, slow motion or safety buffers. Write scriptContent as a concise continuous action chain with necessary continuity facts. The server does not automatically split or truncate shots; exact model limits are checked during rendering.
            26. Scene fields are optional: sceneType accepts INDOOR, OUTDOOR, STUDIO, VIRTUAL; timeOfDay accepts DAY, NIGHT, SUNSET, DAWN. Explicitly choose sceneType from the actual setting (riversides and streets use OUTDOOR). Omitted fields remain empty unless a story scene inherits an explicitly configured linked resource scene. Explicit null means empty; omitted update fields preserve existing values. No default INDOOR/OUTDOOR or DAY and no server-side semantic guessing.
            27. Explicitly select the prompt mode for finished video cards: REFERENCE_MODE uses prompt plus ordered refImages and optional refAudios/refVideos; FIRST_LAST_FRAME uses videoPrompt plus firstFramePrompt/endFramePrompt or previewImageUrl/endFrameImageUrl. Video references use DIRECT_URL/sourceVideoUrl, SHOT_CURRENT/sourceShotId or SHOT_VIDEO_TAKE/sourceShotId/sourceVideoTakeId, with explicit seconds range, includeAudio and referenceDescription. They require server preflight and an accepted model before generation; never infer video content from a filename or promise frame-perfect copying. Do not write the final card into the other mode's field or claim a complete card from a draft. Draft omission remains compatible with FIRST_LAST_FRAME and a 3-second duration; always estimate the intended duration explicitly for finished shots.
            28. Upload reference videos with prepare_asset_video_upload, direct multipart transfer, and complete_asset_video_upload. Completion verifies actual video metadata and registers the video material; use the returned reference in ordered shot refVideos. Upload one file per flow, repeat for multiple files, and let the selected model maxVideos and verified gateway limits govern reference count. Never send video Base64 or local paths as tool payloads.
            29. After creating or updating key records, read them back with get_scene/get_resource_scene/get_shot or batch_read_records. Compare persisted sceneType/timeOfDay, duration, generationMode and its prompt/media fields, characterRefs.characterId/lookId and reference image source/character/look IDs against creative intent. Write responses already include persisted data; additionally read current state after batches or related/default-look changes. Correct discrepancies with a fresh editFingerprint before continuing to rendering; never assume defaults reflect intent.
            29. By default generate video prompts without background music or score to allow clean editing; when the creator explicitly requests music, describe its intended use and reference relationship in non_diegetic_music and referenceDescription. In both REFERENCE_MODE prompt and FIRST_LAST_FRAME videoPrompt, keep the required non_diegetic_music section and when music is disabled explicitly write N/A or none / no background music according to the model contract. When music is disabled overall_soundscape retains dialogue, natural ambience and necessary sound effects only; do not introduce music there or via BGM reference audios. This application audio requirement takes precedence over Skill examples suggesting music.
            30. Before original writing, continuation, adaptation or substantive plot rewrites, read story-writing and its references/story-design.md and references/story-review.md through the published Skill tools. Read relevant existing outlines, episode scripts and character facts first. Design and draft in the current task, review motivation, causality, scene changes, knowledge sources, setup/payoff, continuity and ending with concrete story evidence, then revise and recheck affected events. Allow at most two revision rounds by default. Do not save affected plot text while blocking issues remain; explain unresolved conflicts and request necessary information. Otherwise give a brief factual review/change summary and continue authorized writes without an extra approval step. Keep review reports out of plot fields; preserve creator locks, canon and required endings. Pure queries, sorting and media-only edits do not trigger story review. This guidance adds no server-enforced audit or persistent story memory.
            31. Business detail/update/delete tools identify their target with recordId (including get_project/get_shot/get_asset); old target aliases are rejected. dramaId is optional for existing-record CRUD and batch read/update/delete; omitted scope is resolved from stored ownership and authorized. A supplied dramaId must match; batch writes stay within one project and public assets stay read-only. Child creation still requires dramaId. Relationship/filter IDs retain their business names.
            32. Use fields for compact verification: get_shot {"recordId":"<id>","fields":["duration","refImages","negativePrompt"]}; batch_read_records items carry their own fields. Only public top-level fields are accepted, id/editFingerprint always return, omitted fields means full data, [] means identity/fingerprint only. update_shot and batch_update_shots also accept response fields; these never change what is saved. Updates are sparse patches: data={"negativePrompt":"blur"} is sufficient, omitted fields stay unchanged. Arrays replace membership/order ([] clears); characterRefs/propRefs preserve omitted details for matching identities. Prompt content supports null/empty-string clearing; other null values must be explicitly allowed by field schema, media URLs clear with empty strings. Director-plan clearing respects mode and creator locks.
            33. EDIT_CONFLICT errors include latestFingerprint, recordId, attemptedFields and currentValues for public attempted fields, not a historical diff. Inspect those values before retrying with the latest fingerprint and a new requestId; never blindly overwrite concurrent edits. CREATOR_LOCKED names lockedField/lockedFields and how to unlock in the workbench. Batch errors include itemIndex, recordId when known, and rolledBack=true. Reuse the same requestId only for identical requests after uncertain delivery/timeouts.
            34. Character voice configuration and timbre reference policy:
            - Voice design: list_voice_design_models discovers enabled voice models. design_character_voice explicitly invokes the selected model and incurs usage, archives a sample and optionally saves it on the authorized project character. Read the character fingerprint first, reuse requestId after a timeout, and use autoSave=false for audition only. Public characters cannot be modified or used for this operation. Business CRUD and Skill reads never implicitly generate audio.
            - Single Configuration Source: Use the dubbing center to maintain character base/clone recordings. Each character has one effect-free base; clones preserve raw TTS audio and apply configured FFmpeg effects afterward. Query dubbing_list_audios with the authorized drama and character; create a base first when absent. Character CRUD cannot edit voice assets.
            - Unified Scriptwriting: In storyboard shots, all dialogues, actions and voiceovers MUST be written naturally within scriptContent (e.g. 角色名：“台词内容” 旁白：“独白内容”).
            - Voice References: Select each Audio N explicitly with its referenceType; bind characterId only when it has an intended speaker (required for dubbing-center audio). fully_copy reuses the full track; partially_copy reuses explicitly described segments/layers; reference does not copy the signal; weak_reference retains only broad category/atmosphere. Follow referenceDescription. Transcript text is metadata, never automatically current dialogue. Use scriptContent for current dialogue and preserve its language. Never infer speakers from characterRefs order.
            - Explicit selection: For library audio use search_audio_assets/get_audio_asset in the authorized project or public library, or prepare_asset_audio_upload plus direct multipart transfer and complete_asset_audio_upload. These reference uploads require an explicit audioKind and one of the four referenceType values, accept MP3/WAV/FLAC/M4A/OGG up to 30 MiB, and verify a 2–15 second clip. Add the returned reference to refAudios explicitly. For character recordings use dubbing_list_audios scoped to current drama characters. update_audio_asset/delete_audio_asset require the latest fingerprint and stable requestId; public assets are read-only. Query audio_asset_kind/reference_audio_type through get_business_options; a category never selects a reference relation or enables BGM. Never auto-add base samples. Empty refAudios=[] stays empty; removing a reference does not mute the shot. Never truncate references or invent durations/URLs. Limits: at most 3 clips, each 2–15 seconds, combined at most 15 seconds; rendering probes actual media.
            - Structured speech: Prompt derivation returns speechLines from the current scriptContent (kind, characterId, speakerName, original text, language, dialogueTag, optional audioIndex). Regenerate it after script edits. Do not write retired dialogue/dialogueSpeaker/voiceover/audioUrl shot fields or invoke removed shot TTS endpoints.
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
    FilterRegistrationBean<McpAuthenticationFilter> mcpAuthenticationFilter(McpProperties properties, ObjectProvider<McpRuntimeControl> controls,
            ObjectProvider<com.astra.freyja.capability.service.CapabilityMcpDispatcher> dispatchers) {
        var filter = new McpAuthenticationFilter(properties, controls.getIfAvailable());
        filter.setDispatcher(dispatchers.getIfAvailable());
        var registration = new FilterRegistrationBean<>(filter);
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
    List<McpStatelessServerFeatures.SyncToolSpecification> freyjaMcpTools(McpReadTools reads, McpToolCatalog catalog, ObjectProvider<McpBusinessTools> business, ObjectProvider<McpRuntimeControl> controls, ObjectProvider<McpSkillTools> skills,
            ObjectProvider<McpVoiceTools> voices, McpProperties properties) {
        McpRuntimeControl control = controls.getIfAvailable();
        if (control != null) {
            var state = control.current();
            java.util.List<McpTool<?>> all = new java.util.ArrayList<>(reads.tools()); business.ifAvailable(adapter -> all.addAll(adapter.allTools()));
            skills.ifAvailable(adapter -> all.addAll(adapter.tools()));
            voices.ifAvailable(adapter -> all.addAll(adapter.tools()));
            return all.stream().filter(tool -> control.toolEnabled(state, tool)).map(catalog::specification).toList();
        }
        java.util.List<McpTool<?>> tools = new java.util.ArrayList<>(reads.tools());
        business.ifAvailable(adapter -> tools.addAll(adapter.tools()));
        skills.ifAvailable(adapter -> tools.addAll(adapter.tools()));
        voices.ifAvailable(adapter -> tools.addAll(adapter.tools().stream()
                .filter(tool -> tool.effect() == McpTool.Effect.READ || properties.isWriteEnabled()).toList()));
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
