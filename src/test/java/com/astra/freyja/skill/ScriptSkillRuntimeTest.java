package com.astra.freyja.skill;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dto.script.GlobalStoryContext;
import com.astra.freyja.dto.script.ScriptDecomposeRequestDTO;
import com.astra.freyja.dto.script.ScriptSkillPolicy;
import com.astra.freyja.dto.script.ScriptSkillStagePolicy;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.skill.model.LoadedSkill;
import com.astra.freyja.skill.model.SkillCatalogItem;
import com.astra.freyja.skill.model.SkillPromptContext;
import com.astra.freyja.skill.service.ScriptSkillRuntime;
import com.astra.freyja.skill.service.SkillCatalogService;
import com.astra.freyja.skill.service.SkillContentService;
import com.astra.freyja.skill.service.SkillPromptContextService;
import com.astra.freyja.skill.tool.LoadSkillToolFactory;
import com.astra.freyja.skill.tool.LoadSkillToolSession;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScriptSkillRuntimeTest {
    @Mock private SkillCatalogService catalogService;
    @Mock private SkillContentService contentService;
    @Mock private SkillPromptContextService promptContextService;
    @Mock private LoadSkillToolFactory toolFactory;
    @Mock private AiModelMapper modelMapper;
    @Mock private tools.jackson.databind.ObjectMapper taskObjectMapper;
    @InjectMocks private ScriptSkillRuntime runtime;

    @Test
    void requiredSkillIsValidatedBeforeTaskAndSessionsStayIsolated() {
        ScriptDecomposeRequestDTO request = request(policy(false, List.of(" Story-Structure ")));
        request.setSkillCatalogSnapshot(List.of(item("injected", 99L))); // client data must be ignored
        when(catalogService.getEnabledCatalog()).thenReturn(List.of(item("story-structure", 11L)));
        when(contentService.loadSkillVersion(11L)).thenReturn(skill());
        when(promptContextService.loadSelected(anyString(), anyString(), anyList(), anyMap()))
                .thenReturn(new SkillPromptContext("SKILL RULES", "hash", List.of("story-structure@v1")));
        when(promptContextService.appendToSystemPrompt(anyString(), any())).thenAnswer(call ->
                call.getArgument(0, String.class) + "\n" + ((SkillPromptContext) call.getArgument(1)).prompt());

        runtime.prepareRequest(request);
        assertEquals(List.of("story-structure"), request.getSkillPolicy().getPlanner().getRequiredSkillNames());
        assertEquals(11L, request.getSkillCatalogSnapshot().getFirst().getVersionId());

        GlobalStoryContext context = new GlobalStoryContext();
        var first = runtime.begin(request, request.getSkillPolicy().getPlanner(), context,
                "PLANNER", "PLANNER", null, 0, null);
        var second = runtime.begin(request, request.getSkillPolicy().getPlanner(), context,
                "PLANNER", "PLANNER", null, 1, null);
        assertNotSame(first.session(), second.session());
        assertTrue(first.systemPrompt().contains("SKILL RULES"));
        assertNull(first.tool());
        assertEquals(2, context.getSkillEvents().size());
        assertEquals("REQUIRED", context.getSkillEvents().getFirst().source());
        assertEquals(11L, context.getSkillEvents().getFirst().versionId());
    }

    @Test
    void missingRequiredSkillFailsBeforeModelInvocation() {
        ScriptDecomposeRequestDTO request = request(policy(false, List.of("missing")));
        when(catalogService.getEnabledCatalog()).thenReturn(List.of());
        assertThrows(BizException.class, () -> runtime.prepareRequest(request));
        verifyNoInteractions(modelMapper, toolFactory);
    }

    @Test
    void declaredUnsupportedToolModelRejectsDynamicLoadButAllowsRequiredOnly() {
        ScriptDecomposeRequestDTO request = request(policy(true, List.of()));
        request.setProviderId(1L);
        request.setModelCode("plain-chat");
        when(catalogService.getEnabledCatalog()).thenReturn(List.of(item("story-structure", 11L)));
        AiModel model = new AiModel();
        model.setStatus(1);
        model.setModelType("CHAT");
        model.setParamsJson("{\"capabilities\":{\"toolCalling\":false}}");
        when(modelMapper.selectOne(any())).thenReturn(model);

        assertThrows(BizException.class, () -> runtime.prepareRequest(request));
        request.setSkillPolicy(policy(false, List.of()));
        runtime.prepareRequest(request);
        verify(modelMapper, times(1)).selectOne(any());
    }

    @Test
    void dynamicToolUsesFrozenVersionAndRecordsActualCall() {
        ScriptDecomposeRequestDTO request = request(policy(true, List.of()));
        request.setSkillCatalogSnapshot(List.of(item("story-structure", 11L)));
        when(promptContextService.loadSelected(anyString(), anyString(), anyList(), anyMap()))
                .thenReturn(SkillPromptContext.empty());
        when(promptContextService.appendToSystemPrompt(anyString(), any())).thenAnswer(call -> call.getArgument(0));
        when(catalogService.formatCatalogForPrompt(anyList())).thenReturn("story-structure v1");
        when(contentService.loadSkillVersion(11L)).thenReturn(skill());
        when(toolFactory.createTool(any(LoadSkillToolSession.class), any(), any())).thenAnswer(call ->
                new LoadSkillToolFactory(contentService).createTool(
                        call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        GlobalStoryContext context = new GlobalStoryContext();

        var invocation = runtime.begin(request, request.getSkillPolicy().getPlanner(), context,
                "PLANNER", "PLANNER", null, 0, null);
        assertNotNull(invocation.tool());
        invocation.tool().call("{\"name\":\"story-structure\"}");

        assertEquals(1, context.getSkillEvents().size());
        assertEquals("TOOL", context.getSkillEvents().getFirst().source());
        assertEquals("SUCCESS", context.getSkillEvents().getFirst().status());
        assertEquals(11L, context.getSkillEvents().getFirst().versionId());
        assertEquals(List.of("story-structure"), invocation.session().getInvokedSkillNames());
    }

    @Test
    void streamingChatClientExecutesLoadSkillBeforeFinalAnswer() {
        ScriptDecomposeRequestDTO request = request(policy(true, List.of()));
        request.setSkillCatalogSnapshot(List.of(item("story-structure", 11L)));
        when(promptContextService.loadSelected(anyString(), anyString(), anyList(), anyMap()))
                .thenReturn(SkillPromptContext.empty());
        when(promptContextService.appendToSystemPrompt(anyString(), any())).thenAnswer(call -> call.getArgument(0));
        when(catalogService.formatCatalogForPrompt(anyList())).thenReturn("story-structure v1");
        when(contentService.loadSkillVersion(11L)).thenReturn(skill());
        when(toolFactory.createTool(any(LoadSkillToolSession.class), any(), any())).thenAnswer(call ->
                new LoadSkillToolFactory(contentService).createTool(
                        call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        GlobalStoryContext context = new GlobalStoryContext();
        var invocation = runtime.begin(request, request.getSkillPolicy().getPlanner(), context,
                "PLANNER", "PLANNER", null, 0, null);

        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                throw new AssertionError("streaming path should be used");
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                if (modelCalls.getAndIncrement() == 0) {
                    var toolCall = new AssistantMessage.ToolCall("call-1", "function", "load_skill",
                            "{\"name\":\"story-structure\"}");
                    return Flux.just(response(AssistantMessage.builder().toolCalls(List.of(toolCall)).build()));
                }
                return Flux.just(response(new AssistantMessage("{\"segments\":[]}")));
            }
            @Override public ChatOptions getOptions() { return OpenAiChatOptions.builder().model("mock").build(); }
        };
        StringBuilder streamed = new StringBuilder();
        String answer = runtime.streamWithTool(model, invocation, "chapter text", streamed::append);

        assertEquals("{\"segments\":[]}", answer);
        assertEquals(answer, streamed.toString());
        assertEquals(2, modelCalls.get());
        assertEquals("SUCCESS", context.getSkillEvents().getFirst().status());
    }

    private ChatResponse response(AssistantMessage message) {
        return ChatResponse.builder().generations(List.of(new Generation(message))).build();
    }

    private ScriptDecomposeRequestDTO request(ScriptSkillPolicy policy) {
        ScriptDecomposeRequestDTO request = new ScriptDecomposeRequestDTO();
        request.setSkillPolicy(policy);
        return request;
    }

    private ScriptSkillPolicy policy(boolean dynamic, List<String> required) {
        ScriptSkillStagePolicy stage = new ScriptSkillStagePolicy();
        stage.setAllowDynamicLoad(dynamic);
        stage.setRequiredSkillNames(required);
        ScriptSkillPolicy policy = new ScriptSkillPolicy();
        policy.setPlanner(stage);
        return policy;
    }

    private SkillCatalogItem item(String name, Long id) {
        return SkillCatalogItem.builder().name(name).version("1").versionId(id).build();
    }

    private LoadedSkill skill() {
        return LoadedSkill.builder().name("story-structure").version("1")
                .contentHash("hash").content("SKILL RULES").build();
    }
}
