package com.astra.freyja.director;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.dao.AiSkillFileMapper;
import com.astra.freyja.director.model.DirectorPlan;
import com.astra.freyja.director.model.DirectorPlanSchema;
import com.astra.freyja.director.service.DirectorPlanValidator;
import com.astra.freyja.director.service.DirectorPlanningServiceImpl;
import com.astra.freyja.dto.drama.ShotPromptDeriveDTO;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.service.AiModelFactory;
import com.astra.freyja.skill.model.LoadedSkill;
import com.astra.freyja.skill.model.SkillPromptContext;
import com.astra.freyja.skill.repository.SkillRepository;
import com.astra.freyja.skill.service.SkillCatalogService;
import com.astra.freyja.skill.service.SkillContentService;
import com.astra.freyja.skill.service.SkillPromptContextService;
import com.astra.freyja.skill.tool.LoadSkillToolFactory;
import com.astra.freyja.skill.tool.LoadSkillToolSession;
import com.astra.freyja.skill.tool.ReadSkillFileToolFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DirectorPlanningServiceTest {
    private static final String VALID = """
            {"schemaVersion":"1.0","duration":3,"shotSize":"MEDIUM_SHOT","cameraAngle":"EYE_LEVEL",
             "cameraBeats":[{"startSec":0,"endSec":3,"movement":"PULL_OUT","speed":"SLOW",
             "direction":"BACKWARD","startCue":"人物起身","stopCue":"人物停步","narrativePurpose":"交代空间"}],
             "subjectAction":"人物起身后停步","narrativeIntent":"展示空间关系"}
            """;
    private final ChatModel model = mock(ChatModel.class);
    private final SkillPromptContextService skillContext = mock(SkillPromptContextService.class);
    private final SkillCatalogService catalog = mock(SkillCatalogService.class);
    private DirectorPlanningServiceImpl service;
    private ShotPromptDeriveDTO dto;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        AiProviderMapper providers = mock(AiProviderMapper.class);
        AiModelMapper models = mock(AiModelMapper.class);
        AiModelFactory factory = mock(AiModelFactory.class);
        AiProvider provider = new AiProvider(); provider.setStatus(1);
        AiModel config = new AiModel(); config.setStatus(1); config.setModelType("CHAT");
        when(providers.selectById(1L)).thenReturn(provider);
        when(models.selectOne(any())).thenReturn(config);
        when(factory.getChatModel(1L, "deepseek-flash")).thenReturn(model);
        when(model.getOptions()).thenReturn(ChatOptions.builder().model("deepseek-flash").build());
        when(catalog.getEnabledCatalog()).thenReturn(List.of());
        when(catalog.formatCatalogForPrompt(anyList())).thenReturn("cinematography v49");
        LoadSkillToolSession session = new LoadSkillToolSession(Map.of("cinematography", 49L));
        LoadedSkill skill = new LoadedSkill(); skill.setName("cinematography");
        skill.setVersion("49"); skill.setContentHash("frozen-version-hash");
        session.preload("cinematography", skill);
        when(skillContext.createApiSession(anyString(), anyString(), anyList())).thenReturn(session);
        when(skillContext.loadSelected(anyString(), anyString(), anyList(), anyMap())).thenReturn(SkillPromptContext.empty());
        when(skillContext.appendToSystemPrompt(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        service = new DirectorPlanningServiceImpl(providers, models, factory, catalog,
                new LoadSkillToolFactory(mock(SkillContentService.class)),
                new ReadSkillFileToolFactory(mock(AiSkillFileMapper.class), mock(SkillRepository.class)),
                skillContext, new DirectorPlanValidator());
        dto = ShotPromptDeriveDTO.builder().shotId(7L).directorPlanningTaskId(2108398458276372481L)
                .providerId(1L).modelCode("deepseek-flash").duration(3.0).shotNo(5)
                .scriptContent("人物起身后停步").shotTypeLocked(false).cameraMovementLocked(false).build();
        logger = (Logger) LoggerFactory.getLogger(DirectorPlanningServiceImpl.class);
        logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() { logger.detachAppender(logs); logs.stop(); }

    @ParameterizedTest
    @ValueSource(strings = {"REFERENCE_MODE", "FIRST_LAST_FRAME"})
    void validPlanUsesOneAttemptAndSharedSchema(String mode) throws Exception {
        dto.setGenerationMode(mode);
        when(model.call(any(Prompt.class))).thenReturn(response(VALID));
        DirectorPlan plan = service.plan(dto);
        assertThat(plan.getShotSize()).isEqualTo("MEDIUM_SHOT");
        assertThat(plan.getCameraBeats().getFirst().getMovement()).isEqualTo("PULL_OUT");
        assertThat(plan.getSkills().getFirst().getVersion()).isEqualTo("49");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(1)).call(prompt.capture());
        assertThat(prompt.getValue().getContents()).contains(DirectorPlanSchema.jsonSchema(), dto.getScriptContent());
        assertThat(messages()).anyMatch(line -> line.contains("outcome=SUCCESS") && line.contains("plan="));
        assertThat(messages()).allMatch(line -> line.contains("taskId=2108398458276372481"));
        verify(catalog, times(1)).getEnabledCatalog();
        verify(skillContext, times(1)).loadSelected(anyString(), anyString(), anyList(), eq(Map.of("cinematography", 49L)));
    }

    static Stream<Arguments> invalidOutputs() {
        return Stream.of("REFERENCE_MODE", "FIRST_LAST_FRAME").flatMap(mode -> Stream.of(
                Arguments.of(mode, VALID.replace("MEDIUM_SHOT", "WIDE_SHOT"), "$.shotSize", "WIDE_SHOT"),
                Arguments.of(mode, VALID.replace("PULL_OUT", "PULL_BACK"), "$.cameraBeats[0].movement", "PULL_BACK"),
                Arguments.of(mode, VALID.replace("EYE_LEVEL", "FRONT"), "$.cameraAngle", "FRONT"),
                Arguments.of(mode, VALID.replace("\"shotSize\":\"MEDIUM_SHOT\",", ""), "$.shotSize", "<missing>"),
                Arguments.of(mode, VALID.replace("\"duration\":3", "\"duration\":\"three\""), "$.duration", "three"),
                Arguments.of(mode, VALID.replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"2.0\""), "$.schemaVersion", "2.0"),
                Arguments.of(mode, VALID.replace("\"speed\":\"SLOW\",", ""), "$.cameraBeats[0].speed", "<missing>"),
                Arguments.of(mode, "not JSON at all", "$ JSON 解析失败", "Unrecognized token"),
                Arguments.of(mode, "{\"shotSize\":", "$ JSON 解析失败", "end-of-input"),
                Arguments.of(mode, "", "$ 响应为空", "实际值=")));
    }

    @ParameterizedTest
    @MethodSource("invalidOutputs")
    void invalidContentNeverRequestsRepairAndKeepsInputSnapshot(String mode, String raw, String path, String actual) throws Exception {
        dto.setGenerationMode(mode);
        DirectorPlan original = new ObjectMapper().readValue(VALID, DirectorPlan.class);
        dto.setDirectorPlan(original); dto.setDirectorPlanRevision(2); dto.setDirectorPlanSourceHash("original-hash");
        when(model.call(any(Prompt.class))).thenReturn(response(raw));
        assertThatThrownBy(() -> service.plan(dto)).isInstanceOf(BizException.class).hasMessageContaining(path).hasMessageContaining(actual);
        verify(model, times(1)).call(any(Prompt.class));
        assertThat(dto.getDirectorPlan()).isSameAs(original);
        assertThat(dto.getDirectorPlanRevision()).isEqualTo(2);
        assertThat(dto.getDirectorPlanSourceHash()).isEqualTo("original-hash");
        assertThat(messages()).anyMatch(line -> line.contains("outcome=RESPONSE_RECEIVED")
                && line.contains("response=" + new ObjectMapper().valueToTree(raw).toString()));
        assertThat(messages()).anyMatch(line -> line.contains("outcome=VALIDATION_FAILED") && line.contains("error="));
        assertThat(messages()).noneMatch(line -> line.contains("outcome=SUCCESS"));
        assertThat(messages()).allMatch(line -> line.contains("taskId=2108398458276372481"));
    }

    @Test
    void transportFailureDoesNotBecomeContentRetry() {
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("connection failed"));
        assertThatThrownBy(() -> service.plan(dto)).isInstanceOf(BizException.class).hasMessageContaining("调用异常");
        verify(model, times(1)).call(any(Prompt.class));
        assertThat(messages()).anyMatch(line -> line.contains("outcome=CALL_FAILED"));
        assertThat(messages()).noneMatch(line -> line.contains("outcome=VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REFERENCE_MODE", "FIRST_LAST_FRAME"})
    void creatorLocksRemainAuthoritative(String mode) {
        dto.setGenerationMode(mode);
        dto.setShotTypeLocked(true); dto.setShotType("CLOSE_UP");
        dto.setCameraMovementLocked(true); dto.setCameraMovement("STATIC");
        when(model.call(any(Prompt.class))).thenReturn(response(VALID));
        DirectorPlan plan = service.plan(dto);
        assertThat(plan.getShotSize()).isEqualTo("CLOSE_UP");
        assertThat(plan.getCameraBeats().getFirst().getMovement()).isEqualTo("STATIC");
        assertThat(plan.getCameraBeats().getFirst().getSpeed()).isEqualTo("NONE");
        assertThat(plan.getCameraBeats().getFirst().getDirection()).isNull();
        assertThat(plan.getLockedFields()).containsExactly("duration", "shotSize", "cameraMovement");
        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test
    void correlationIdCannotBeSuppliedByClient() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ShotPromptDeriveDTO parsed = mapper.readValue("{\"shotId\":7,\"directorPlanningTaskId\":123}", ShotPromptDeriveDTO.class);
        assertThat(parsed.getDirectorPlanningTaskId()).isNull();
        assertThat(mapper.writeValueAsString(dto)).doesNotContain("directorPlanningTaskId");
    }

    @Test
    void schemaDefinesRequiredFieldsAndAllAllowedValues() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(DirectorPlanSchema.jsonSchema());
        assertThat(schema.path("required").toString()).contains("schemaVersion", "duration", "shotSize", "cameraAngle", "cameraBeats");
        Map<String, java.util.Set<String>> enums = Map.of("shotSize", DirectorPlanSchema.SHOT_SIZES,
                "cameraAngle", DirectorPlanSchema.CAMERA_ANGLES);
        enums.forEach((field, values) -> assertThat(schema.path("properties").path(field).path("enum").size()).isEqualTo(values.size()));
        JsonNode beat = schema.path("properties").path("cameraBeats").path("items");
        assertThat(beat.path("properties").path("movement").path("enum").toString()).doesNotContain("PULL_BACK").contains("PULL_OUT");
        assertThat(beat.path("required").toString()).contains("startSec", "endSec", "movement", "speed");
    }

    private List<String> messages() { return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList(); }
    private ChatResponse response(String json) { return new ChatResponse(List.of(new Generation(new AssistantMessage(json)))); }
}
