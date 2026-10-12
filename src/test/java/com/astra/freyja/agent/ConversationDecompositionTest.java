package com.astra.freyja.agent;

import com.astra.freyja.agent.context.ProductionContextContributor;
import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.production.*;
import com.astra.freyja.agent.profile.CoreAssistantProfile;
import com.astra.freyja.agent.runtime.AgentStore;
import com.astra.freyja.agent.tool.ProductionTools;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.agent.ProductionPreparationDTO.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationDecompositionTest {
    private final AgentJson json = new AgentJson();
    private final AgentPreparationJobMapper mapper = mock(AgentPreparationJobMapper.class);
    private final AgentMessageMapper messages = mock(AgentMessageMapper.class);
    private final DramaMapper dramas = mock(DramaMapper.class);
    private final AgentStore store = mock(AgentStore.class);
    private final ProductionPlans plans = mock(ProductionPlans.class);
    private AgentSession session;
    private AgentPreparationJob saved;
    private ProductionJobs jobs;
    private ToolContext ctx;

    @BeforeEach void setup() {
        AgentTestSupport.metadata();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), AgentPreparationJob.class);
        @SuppressWarnings("unchecked") var stores = (ObjectProvider<AgentStore>) mock(ObjectProvider.class);
        when(stores.getObject()).thenReturn(store);
        jobs = new ProductionJobs(mapper, dramas, mock(DramaShotMapper.class), stores, json,
                mock(ScriptDecomposeService.class), mock(AiTaskService.class), mock(ProductionAssets.class),
                mock(AiTaskMapper.class), mock(AgentConfigMapper.class), mock(DramaEpisodeMapper.class), messages);
        session = new AgentSession(); session.setId(AgentTestSupport.SESSION); session.setActiveRunId(AgentTestSupport.RUN);
        when(store.session(session.getId())).thenReturn(session);
        when(mapper.lockByRunId(AgentTestSupport.RUN)).thenAnswer(call -> saved);
        when(mapper.selectOne(any())).thenAnswer(call -> saved);
        when(mapper.insert(any(AgentPreparationJob.class))).thenAnswer(call -> { saved = call.getArgument(0); return 1; });
        ctx = context(null, "NORMAL");
    }
    private ToolContext context(Long drama, String type) {
        return new ToolContext(session.getId(), AgentTestSupport.RUN, AgentTestSupport.STEP, drama, 100L,
                "chat-tool", AgentTestSupport.snapshot(List.of("prepare_script_decomposition", "request_user_input"), type));
    }
    private ConversationStart input() {
        var input = new ConversationStart(); input.setSourceText("小岚走进灯塔，点亮窗边的灯。"); return input;
    }
    private CoreAssistantProfile profile() {
        @SuppressWarnings("unchecked") var provider = (ObjectProvider<ProductionPlans>) mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(plans); return new CoreAssistantProfile(mapper, provider);
    }
    @Test void missingSourceAsksAQuestionAndDoesNotCreateATask() {
        var result = jobs.prepare(new ConversationStart(), ctx);
        assertEquals("NEED_USER", result.kind()); assertNull(result.errorCode());
        assertTrue(result.message().contains("原文")); assertNull(saved);
        verify(mapper, never()).insert(any(AgentPreparationJob.class)); verifyNoInteractions(messages);
    }
    @Test void suppliedTextCreatesCheckpointInSameRunWithScriptOnlyDefaults() {
        assertNull(jobs.prepare(input(), ctx).errorCode());
        assertEquals(ctx.runId(), saved.getRunId()); assertEquals(ctx.sessionId(), saved.getSessionId());
        assertNull(saved.getDramaId()); assertEquals(input().getSourceText(), saved.getSourceText());
        assertEquals("SCRIPT_DECOMPOSITION", jobs.options(saved).getWorkflow());
        assertEquals("9:16", jobs.options(saved).getAspectRatio()); assertEquals("AUTO", jobs.options(saved).getPacingPreset());
        assertEquals("chat:" + ctx.runId(), jobs.options(saved).getClientRequestId());
        verify(store, never()).createSession(any(), anyString(), anyString()); verify(store, never()).submit(anyLong(), any(), any());
    }
    @Test void sourceMessageUsesExactStoredNovelAndRejectsOtherSessionsOrAssistantText() {
        var source = new AgentMessage(); source.setId(2032095628944588805L); source.setSessionId(session.getId());
        source.setMessageType("USER"); var original = "夜色中的灯塔。\n".repeat(2000);
        source.setContentJson(json.write(MessageBody.text(original))); when(messages.selectById(source.getId())).thenReturn(source);
        var input = new ConversationStart(); input.setSourceMessageId(source.getId());
        assertNull(jobs.prepare(input, ctx).errorCode()); assertEquals(original, saved.getSourceText());
        assertEquals(source.getId(), json.read(json.write(input), ConversationStart.class).getSourceMessageId());
        source.setSessionId(99L); assertEquals("INVALID_SOURCE", jobs.prepare(input, ctx).errorCode());
        source.setSessionId(session.getId()); source.setMessageType("ASSISTANT");
        assertEquals("INVALID_SOURCE", jobs.prepare(input, ctx).errorCode());
        verify(mapper, times(1)).insert(any(AgentPreparationJob.class));
    }
    @Test void ambiguousSourceAndInvalidConfigurationNeverCreateBusinessData() {
        var input = input(); input.setSourceMessageId(100L);
        assertEquals("INVALID_SOURCE", jobs.prepare(input, ctx).errorCode());
        input.setSourceMessageId(null); input.setWorkflow("UNKNOWN"); assertThrows(BizException.class, () -> jobs.prepare(input, ctx));
        input.setWorkflow("SCRIPT_DECOMPOSITION"); input.setAspectRatio(null); assertThrows(BizException.class, () -> jobs.prepare(input, ctx));
        assertNull(saved); verify(mapper, never()).insert(any(AgentPreparationJob.class));
        assertThrows(BizException.class, () -> json.read("{\"dramaId\":\"2032095628944588806\",\"sourceText\":\"原文\"}", ConversationStart.class));
    }
    @Test void boundProjectIsInheritedAndStaleScopeCannotSwitchProjects() {
        Long drama = 2032095628944588806L; session.setDramaId(drama); when(dramas.selectById(drama)).thenReturn(new Drama());
        assertThrows(BizException.class, () -> jobs.prepare(input(), ctx));
        assertNull(jobs.prepare(input(), context(drama, "NORMAL")).errorCode());
        assertEquals(drama, saved.getDramaId()); assertEquals(drama, jobs.options(saved).getDramaId());
        verify(store, never()).bindProject(anyLong(), anyLong());
    }
    @Test void replayIsIdempotentEvenAfterCommitBindsANewProjectAndRejectsChangedSource() {
        var input = input(); jobs.prepare(input, ctx); jobs.prepare(input, ctx);
        Long drama = 2032095628944588806L; session.setDramaId(drama); saved.setDramaId(drama); saved.setStatus("COMMITTED");
        when(dramas.selectById(drama)).thenReturn(new Drama());
        assertNull(jobs.prepare(input, context(drama, "NORMAL")).errorCode());
        input.setSourceText("另一篇原文");
        assertEquals(409, assertThrows(BizException.class, () -> jobs.prepare(input, context(drama, "NORMAL"))).getCode());
        assertEquals(input().getSourceText(), saved.getSourceText()); verify(mapper, times(1)).insert(any(AgentPreparationJob.class));
    }
    @Test void configurationTestCannotSaveSource() {
        assertEquals("READ_ONLY_TEST", jobs.prepare(input(), context(null, "CONFIG_TEST")).errorCode()); assertNull(saved);
        verifyNoInteractions(store, mapper, messages);
    }
    @Test void scriptOnlyCompletionRequiresCommittedProjectAndShotsWithoutRequiringVideoPlans() {
        jobs.prepare(input(), ctx); var profile = profile();
        var reply = new ModelReply("拆解完成", List.of(), Map.of(), null, null, null);
        assertFalse(profile.checkCompletion(reply, ctx.snapshot(), ctx.runId()).complete());
        saved.setStatus("COMMITTED"); assertFalse(profile.checkCompletion(reply, ctx.snapshot(), ctx.runId()).complete());
        saved.setDramaId(2032095628944588806L); assertFalse(profile.checkCompletion(reply, ctx.snapshot(), ctx.runId()).complete());
        saved.setShotIdsJson("[\"2032095628944588807\"]"); assertTrue(profile.checkCompletion(reply, ctx.snapshot(), ctx.runId()).complete());
        verifyNoInteractions(plans);
    }
    @Test void videoPreparationAndLegacyJobsStillRequireCurrentDeliveryReport() {
        var input = input(); input.setWorkflow("VIDEO_PREPARATION"); jobs.prepare(input, ctx);
        saved.setStatus("COMMITTED"); saved.setDramaId(2032095628944588806L); saved.setShotIdsJson("[\"2032095628944588807\"]");
        var profile = profile(); var reply = new ModelReply("完成", List.of(), Map.of(), null, null, null);
        assertFalse(profile.checkCompletion(reply, ctx.snapshot(), ctx.runId()).complete());
        when(plans.isDeliveryCurrent(saved)).thenReturn(true); assertTrue(profile.checkCompletion(reply, ctx.snapshot(), ctx.runId()).complete());
        saved.setRequestJson("{}"); when(plans.isDeliveryCurrent(saved)).thenReturn(false);
        assertFalse(profile.checkCompletion(reply, ctx.snapshot(), ctx.runId()).complete());
    }
    @Test void ordinaryChatDoesNotCreateAJobAndPreparedContextRestoresWorkflow() {
        var contributor = new ProductionContextContributor(jobs);
        contributor.contribute(session, ctx.snapshot()); assertNull(saved);
        assertTrue(profile().checkCompletion(new ModelReply("你好", List.of(), Map.of(), null, null, null), ctx.snapshot(), ctx.runId()).complete());
        jobs.prepare(input(), ctx);
        assertTrue(contributor.contribute(session, ctx.snapshot()).contains("workflow=SCRIPT_DECOMPOSITION"));
    }
    @Test void chatInitializationToolIsTransactionalAndAvailableBeforeProjectBinding() {
        var config = new ProductionTools(jobs, plans, mock(ProductionAssets.class), mock(ProductionTaskRecovery.class));
        var tool = org.springframework.test.util.ReflectionTestUtils.invokeMethod(config, "prepareScriptDecomposition");
        var typed = (com.astra.freyja.agent.tool.AgentTool<?>) tool;
        assertEquals(Effect.WRITE, typed.effect()); assertEquals(RecoveryMode.TRANSACTIONAL, typed.recoveryMode());
        assertFalse(typed.projectRequired()); assertTrue(profile().requiredTools().contains(typed.name()));
    }
}
