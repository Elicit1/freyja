package com.astra.freyja.agent;

import com.astra.freyja.agent.gateway.AgentModelGateway;
import com.astra.freyja.agent.memory.AgentMemoryService;
import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.runtime.*;
import com.astra.freyja.agent.settings.AgentConfigService;
import com.astra.freyja.agent.settings.AgentAvatarMediaService;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.agent.AgentRequests.*;
import com.astra.freyja.entity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.mock.web.MockMultipartFile;
import io.minio.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt in with -Dfreyja.agent.db-test=true after the additive migration. All fixture changes roll back. */
@SpringBootTest(properties = {"logging.level.com.astra.freyja=INFO", "logging.level.com.astra.freyja.dao=INFO"})
@Transactional
@EnabledIfSystemProperty(named = "freyja.agent.db-test", matches = "true")
class AgentPersistenceIntegrationTest {
    @Autowired AgentStore store;
    @Autowired AgentRuntime runtime;
    @Autowired AgentConfigService configs;
    @Autowired AgentMemoryService memory;
    @Autowired AgentJson json;
    @Autowired AiProviderMapper providers;
    @Autowired AiModelMapper models;
    @Autowired AgentAvatarMediaService avatars;
    @Autowired MinioClient minio;
    @Autowired MinioProperties minioProperties;
    @MockitoBean AgentExecutionScheduler scheduler;
    @MockitoBean AgentModelGateway gateway;

    @BeforeEach void configureDisposableProvider() {
        AiProvider provider = new AiProvider();
        provider.setProviderCode("agent-db-test-" + UUID.randomUUID()); provider.setProviderName("Agent DB fixture");
        provider.setProviderType("OPENAI"); provider.setBaseUrl("http://127.0.0.1:1"); provider.setStatus(1);
        providers.insert(provider);
        AiModel model = new AiModel(); model.setProviderId(provider.getId()); model.setModelCode("db-fixture");
        model.setModelName("DB fixture"); model.setModelType("CHAT"); model.setStatus(1);
        model.setParamsJson("{\"capabilities\":{\"toolCalling\":true}}"); models.insert(model);
        var config = configs.get("core-assistant"); config.setEnabled(true);
        config.getModel().setProviderId(provider.getId()); config.getModel().setModelCode(model.getModelCode());
        config.getTools().setNames(List.of("save_session_note", "list_session_notes"));
        config.getSkills().setNames(List.of()); configs.save("core-assistant", config);
    }

    @Test void actualMySqlJournalCompletesToolLoopAndPreservesStringIds() {
        CreateSession request = new CreateSession(); request.setAgentCode("core-assistant");
        AgentSession session = store.createSession(request, "NORMAL");
        SendMessage message = new SendMessage(); message.setContent("请记住：项目使用写实风格。"); message.setClientRequestId(UUID.randomUUID().toString());
        Submission submitted = store.submit(session.getId(), message, null);
        String args = json.write(new AgentMemoryService.SaveNote("SESSION", "style", "写实风格", submitted.messageId(), true));
        when(gateway.call(any(), anyList(), anyList(), anyLong())).thenReturn(
                new ModelReply("", List.of(new ToolCall("save-1", "save_session_note", args)), Map.of(), 12, 5, "first"),
                new ModelReply("已保存你的写实风格规则。", List.of(), Map.of(), 20, 8, "second"));
        AgentRun claimed = store.claim(submitted.runId(), "db-test");
        runtime.execute(claimed.getId(), "db-test", claimed.getLeaseEpoch());
        AgentRun finished = store.run(claimed.getId());
        assertEquals("COMPLETED", finished.getStatus()); assertEquals(2, finished.getModelTurns()); assertEquals(1, finished.getToolCalls());
        assertNull(finished.getLeaseOwner()); assertNull(store.session(session.getId()).getActiveRunId());
        assertEquals(submitted, store.submit(session.getId(), message, null));
        var history = store.after(session.getId(), 0);
        assertTrue(history.stream().anyMatch(row -> "TOOL_RESULT".equals(row.messageType())));
        for (int i = 0; i < history.size(); i++) assertEquals(i + 1, history.get(i).seq());
        ToolContext context = new ToolContext(session.getId(), finished.getId(), null, null, submitted.messageId(), null, store.snapshot(finished));
        assertEquals("写实风格", memory.list(context).getFirst().getContent());
        assertTrue(json.write(history).contains("\"" + submitted.messageId() + "\""));
    }

    @Test void actualMySqlCancelSealsCallsAndRejectsOldLeaseWrites() {
        CreateSession request = new CreateSession(); request.setAgentCode("core-assistant");
        AgentSession session = store.createSession(request, "NORMAL");
        SendMessage input = new SendMessage(); input.setContent("记住这条规则"); input.setClientRequestId(UUID.randomUUID().toString());
        Submission submission = store.submit(session.getId(), input, null);
        AgentRun run = store.claim(submission.runId(), "db-test");
        AgentStep model = store.prepareModel(run.getId(), "db-test", run.getLeaseEpoch());
        store.saveModel(run.getId(), "db-test", run.getLeaseEpoch(), model,
                new ModelReply("", List.of(new ToolCall("pending", "save_session_note", "{}")), Map.of(), 1, 1, "cancel"), 1);
        AgentStep tool = store.steps(run.getId()).stream().filter(s -> "TOOL".equals(s.getStepType())).findFirst().orElseThrow();
        store.cancel(run.getId());
        assertEquals("CANCELLED", store.run(run.getId()).getStatus());
        assertNull(store.session(session.getId()).getActiveRunId());
        assertThrows(AgentStore.LeaseLost.class, () -> store.commitTool(run.getId(), "db-test", run.getLeaseEpoch(), tool.getId(), () -> {
            fail("a cancelled run must not write"); return null;
        }, 0));
        assertEquals("CANCELLED", store.steps(run.getId()).getLast().getStatus());
        verifyNoInteractions(gateway);
    }

    @Test void configTestRetryReusesSessionAndRunAndRemainsIsolated() {
        CreateSession request = new CreateSession(); request.setAgentCode("core-assistant");
        String key = "core-assistant/" + UUID.randomUUID();
        AgentSession first = store.createSession(request, "CONFIG_TEST", key);
        AgentSession repeated = store.createSession(request, "CONFIG_TEST", key);
        assertEquals(first.getId(), repeated.getId());
        SendMessage input = new SendMessage(); input.setContent("测试查询"); input.setClientRequestId("retry");
        Submission submission = store.submit(first.getId(), input, configs.get("core-assistant"));
        assertEquals(submission, store.submit(repeated.getId(), input, configs.get("core-assistant")));
        var snapshot = store.snapshot(store.run(submission.runId()));
        assertEquals("CONFIG_TEST", snapshot.runType());
        assertFalse(snapshot.toolVersions().containsKey("save_session_note"));
        assertTrue(snapshot.config().getMemory().getScopes().isEmpty());
        assertTrue(store.listSessions().stream().noneMatch(s -> s.getId().equals(first.getId())));
        assertTrue(store.activeRuns().stream().noneMatch(r -> r.getId().equals(submission.runId())));
    }

    @Test void petSpriteRoundTripsThroughRealMinioWithOriginalBytes() throws Exception {
        byte[] original = AgentAvatarMediaServiceTest.nativeSprite();
        var upload = avatars.upload(new MockMultipartFile("file", "agent-db-test.zip", "application/zip", AgentAvatarMediaServiceTest.nativePackage()));
        String bucket = minioProperties.getBucketName();
        try {
            assertEquals("image/webp", minio.statObject(StatObjectArgs.builder().bucket(bucket).object(upload.getObjectPath()).build()).contentType());
            try (var response = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(upload.getObjectPath()).build())) {
                assertArrayEquals(original, response.readAllBytes());
            }
        } finally {
            // Remove only the test object returned by this upload, never user assets.
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(upload.getObjectPath()).build());
        }
    }
}
