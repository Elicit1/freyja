package com.astra.freyja.service;

import com.astra.freyja.dto.script.*;
import com.astra.freyja.entity.AiTask;
import com.astra.freyja.service.impl.ParallelShotGenerationServiceImpl;
import com.astra.freyja.service.impl.StoryNormalizerServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StoryNormalizerPipelineTest {

    @Test
    void normalizerUsesPlannerHintsAndBoundAssetsWithoutChangingOriginal() {
        AiModelFactory factory = mock(AiModelFactory.class);
        SysConfigService config = mock(SysConfigService.class);
        AIOutputValidationService validation = mock(AIOutputValidationService.class);
        AiTaskService tasks = mock(AiTaskService.class);
        ChatModel model = mock(ChatModel.class);
        when(factory.getChatModel(1L, "model")).thenReturn(model);
        when(config.getConfigValue(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(tasks.createTask(any(), any(), any(), anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(new AiTask());
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
            String input = ((Prompt) inv.getArgument(0)).getContents();
            assertTrue(input.contains("上一段已在店外，手持手机"));
            assertTrue(input.contains("下一段进入街道"));
            assertTrue(input.contains("PR001 手机"));
            assertTrue(input.contains("SC001 便利店"));
            assertTrue(input.contains("禁止编造精确距离"));
            return Flux.just(response("{\"normalizedContent\":\"两人在门口相向接近后擦肩，各自继续前行。\"}"));
        });
        NormalizedSegment output = new NormalizedSegment();
        output.setNormalizedContent("两人在门口相向接近后擦肩，各自继续前行。");
        when(validation.parseAndValidate(anyString(), eq(NormalizedSegment.class))).thenReturn(output);
        StorySegment segment = StorySegment.builder().id("SEG002").rawText("两人在门口擦肩。")
                .previousStateHint("上一段已在店外，手持手机")
                .currentEventHint("两人在店门口擦肩")
                .nextEventHint("下一段进入街道")
                .sceneId("SC001").characterIds(List.of("雷姆", "菜月昴"))
                .propIds(List.of("PR001")).build();
        GlobalStoryContext context = GlobalStoryContext.builder()
                .scenes(List.of(DecomposedSceneVO.builder().id("SC001").sceneName("便利店").build()))
                .props(List.of(DecomposedPropVO.builder().id("PR001").name("手机").build()))
                .build();
        StoryNormalizerServiceImpl normalizer = new StoryNormalizerServiceImpl(factory, config, validation, tasks);
        StringBuilder streamed = new StringBuilder();
        normalizer.normalize(segment, context, request(), streamed::append);
        assertEquals("两人在门口擦肩。", segment.getRawText());
        assertEquals("两人在门口相向接近后擦肩，各自继续前行。", segment.getNormalizedContent());
        assertTrue(streamed.toString().contains("normalizedContent"));
    }

    @Test
    void segmentsNormalizeConcurrentlyAndWorkerFallsBackForOneFailure() {
        AiModelFactory factory = mock(AiModelFactory.class);
        SysConfigService config = mock(SysConfigService.class);
        AIOutputValidationService validation = mock(AIOutputValidationService.class);
        AiTaskService tasks = mock(AiTaskService.class);
        ChatModel model = mock(ChatModel.class);
        StoryNormalizerService normalizer = mock(StoryNormalizerService.class);
        when(config.getConfigValue(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(factory.getChatModel(1L, "model")).thenReturn(model);
        when(tasks.createTask(any(), any(), any(), anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(new AiTask());
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            String input = ((Prompt) inv.getArgument(0)).getContents();
            assertTrue(input.contains("originalContent（最高事实来源）"));
            if (input.contains("SEG001")) assertTrue(input.contains("标准化事件一"));
            if (input.contains("SEG002")) assertTrue(input.contains("原文事件二"));
            return response("{}");
        });
        when(validation.parseAndValidate(anyString(), eq(WorkerShotResult.class)))
                .thenAnswer(inv -> new WorkerShotResult());
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger normalizations = new AtomicInteger();
        List<String> busEvents = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            StorySegment segment = inv.getArgument(0);
            @SuppressWarnings("unchecked") java.util.function.Consumer<String> chunks = inv.getArgument(3);
            chunks.accept("{\"normalizedContent\":\"流式片元\"}");
            normalizations.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS), "两个 Normalizer 应并行进入");
            if ("SEG002".equals(segment.getId())) throw new IllegalStateException("单段失败");
            segment.setNormalizedContent("标准化事件一");
            return null;
        }).when(normalizer).normalize(any(), any(), any(), any());

        ParallelShotGenerationServiceImpl worker = new ParallelShotGenerationServiceImpl(
                factory, config, validation, tasks, new ObjectMapper());
        ReflectionTestUtils.setField(worker, "storyNormalizerService", normalizer);
        StorySegment one = StorySegment.builder().id("SEG001").sequence(1).rawText("原文事件一").build();
        StorySegment two = StorySegment.builder().id("SEG002").sequence(2).rawText("原文事件二").build();
        var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> worker.generateShotsInParallel(
                List.of(one, two), GlobalStoryContext.builder().build(), request(),
                (channel, data) -> busEvents.add(channel + ":" + data), null, null));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        } finally {
            release.countDown();
        }
        List<SegmentShotResult> results = future.join();
        assertEquals(2, normalizations.get());
        assertTrue(results.stream().allMatch(r -> "SUCCESS".equals(r.getStatus())));
        assertNull(two.getNormalizedContent());
        assertTrue(busEvents.stream().anyMatch(e -> e.startsWith("NORMALIZER_SEG001:")));
        assertTrue(busEvents.stream().anyMatch(e -> e.contains("NORMALIZER_STATUS:") && e.contains("FALLBACK")));
    }

    private ScriptDecomposeRequestDTO request() {
        ScriptDecomposeRequestDTO request = new ScriptDecomposeRequestDTO();
        request.setProviderId(1L);
        request.setModelCode("model");
        return request;
    }

    private ChatResponse response(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    }
}
