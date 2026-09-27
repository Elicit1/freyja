package com.astra.freyja.service.impl;

import com.astra.freyja.dto.script.*;
import com.astra.freyja.entity.AiTask;
import com.astra.freyja.entity.enums.AiTaskType;
import com.astra.freyja.service.AIOutputValidationService;
import com.astra.freyja.service.AiModelFactory;
import com.astra.freyja.service.AiTaskService;
import com.astra.freyja.service.StoryNormalizerService;
import com.astra.freyja.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryNormalizerServiceImpl implements StoryNormalizerService {

    private final AiModelFactory aiModelFactory;
    private final SysConfigService sysConfigService;
    private final AIOutputValidationService validationService;
    private final AiTaskService aiTaskService;

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是 Story Normalizer，将小说式 Segment 转换为供分镜 Worker 使用的连续世界事件描述。原始 Segment 是最高剧情事实来源；Planner 连续性提示和已绑定的角色、道具、场景资产用于消除歧义。
            只显式化原事件成立所必需的空间、移动方向、相对距离阶段、动作前后状态和道具持有关系。相遇或擦肩事件应写清原文支持的相向、接近、交汇和分离关系，不增加对视或接触。previousStateHint 中已成立的状态必须继承，除非当前原文明确定义变化；已离开的地点不得重新当作起点，道具持有状态未明确变化时不得让道具消失。后出现的明确状态覆盖失效旧状态。无法安全确定的细节保持泛化，禁止编造精确距离或时间。
            不得新增人物、道具、对白、情绪变化、对视、接触、停顿、互动、冲突、目标或剧情结果。不得拆 ShotGroup/Shot，不得决定 duration，不得加入景别、机位、构图、运镜、screen left/right 或其他摄影语言。
            normalizedContent 使用客观、连续、可观察的事件描述，不重复资产外观或无关背景，篇幅与原文接近，必要时略长。只输出符合 JSON Schema 的对象，无额外解释。
            """;

    @Override
    public void normalize(StorySegment segment, GlobalStoryContext context, ScriptDecomposeRequestDTO request) {
        normalize(segment, context, request, null);
    }

    @Override
    public void normalize(StorySegment segment, GlobalStoryContext context, ScriptDecomposeRequestDTO request,
                          Consumer<String> chunkConsumer) {
        if (segment == null || StringUtils.isBlank(segment.getRawText())) return;
        segment.setNormalizedContent(null);
        Long parentTaskId = context == null ? null : context.getTaskId();
        long started = System.currentTimeMillis();
        AiTask task = null;
        log.info("[StoryNormalizer] taskId={} segment={} start", parentTaskId, segment.getId());
        try {
            var model = aiModelFactory.getChatModel(request.getProviderId(), request.getModelCode());
            var converter = new BeanOutputConverter<>(NormalizedSegment.class);
            String system = sysConfigService.getConfigValue("ai.prompt.story_normalizer_system", DEFAULT_SYSTEM_PROMPT);
            Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(buildInput(segment, context, converter.getFormat()))));
            task = aiTaskService.createTask(request.getDramaId(), context == null ? null : context.getEpisodeId(),
                    AiTaskType.STORY_NORMALIZE, "NORMALIZER_" + segment.getId(), parentTaskId,
                    "Segment: " + segment.getId() + ", TextLen: " + segment.getRawText().length(), request.getModelCode(), null);
            aiTaskService.markRunning(task.getId());
            StringBuilder fullOutput = new StringBuilder();
            try {
                model.stream(prompt).toStream().forEach(chunk -> {
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
                    String token = chunk.getResult().getOutput().getText();
                    if (StringUtils.isEmpty(token)) return;
                    fullOutput.append(token);
                    if (chunkConsumer != null) chunkConsumer.accept(token);
                });
            } catch (Exception streamError) {
                if (!fullOutput.isEmpty()) throw streamError;
                log.debug("[StoryNormalizer] segment={} 流式调用降级为同步调用: {}", segment.getId(), streamError.getMessage());
                ChatResponse response = model.call(prompt);
                if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                    String text = response.getResult().getOutput().getText();
                    if (StringUtils.isNotEmpty(text)) {
                        fullOutput.append(text);
                        if (chunkConsumer != null) chunkConsumer.accept(text);
                    }
                }
            }
            String output = fullOutput.toString();
            NormalizedSegment parsed = StringUtils.isBlank(output) ? null
                    : validationService.parseAndValidate(output, NormalizedSegment.class);
            if (parsed == null || StringUtils.isBlank(parsed.getNormalizedContent())) {
                throw new IllegalStateException("Normalizer 未返回有效 normalizedContent");
            }
            segment.setNormalizedContent(parsed.getNormalizedContent().trim());
            aiTaskService.markSuccess(task.getId(), null, (int) (System.currentTimeMillis() - started));
            log.info("[StoryNormalizer] taskId={} segment={} success durationMs={} inputChars={} outputChars={}",
                    parentTaskId, segment.getId(), System.currentTimeMillis() - started,
                    segment.getRawText().length(), segment.getNormalizedContent().length());
        } catch (Exception e) {
            segment.setNormalizedContent(null);
            if (task != null) aiTaskService.markFailed(task.getId(), e.getMessage());
            log.warn("[StoryNormalizer] taskId={} segment={} failure, fallback=originalContent durationMs={}",
                    parentTaskId, segment.getId(), System.currentTimeMillis() - started, e);
        }
    }

    private String buildInput(StorySegment segment, GlobalStoryContext context, String format) {
        List<String> characters = new ArrayList<>();
        List<String> props = new ArrayList<>();
        String scene = "未提供";
        if (context != null) {
            if (context.getCharacters() != null && segment.getCharacterIds() != null) {
                for (DecomposedCharacterVO item : context.getCharacters()) {
                    if (item != null && (segment.getCharacterIds().contains(item.getName())
                            || segment.getCharacterIds().contains(item.getCanonicalName())
                            || segment.getCharacterIds().contains(item.getDisplayName()))) {
                        characters.add(StringUtils.firstNonBlank(item.getCanonicalName(), item.getName(), item.getDisplayName()));
                    }
                }
            }
            if (context.getProps() != null) {
                for (DecomposedPropVO item : context.getProps()) {
                    if (item != null && (segment.getPropIds() != null && segment.getPropIds().contains(item.getId())
                            || segment.getImportantPropIds() != null && segment.getImportantPropIds().contains(item.getName()))) {
                        props.add(item.getId() + " " + item.getName() + " " + StringUtils.defaultString(item.getDescription()));
                    }
                }
            }
            if (context.getScenes() != null) {
                for (DecomposedSceneVO item : context.getScenes()) {
                    if (item != null && (StringUtils.equals(segment.getSceneId(), item.getId())
                            || StringUtils.equals(segment.getSceneId(), item.getSceneName()))) {
                        scene = item.getId() + " " + item.getSceneName() + " " + StringUtils.defaultString(item.getDescription());
                        break;
                    }
                }
            }
        }
        return "【originalSegment / 最高事实来源】\n" + segment.getRawText()
                + "\n\n【SegmentContext / Planner 全章连续性提示】\npreviousStateHint: " + StringUtils.defaultString(segment.getPreviousStateHint())
                + "\ncurrentEventHint: " + StringUtils.defaultIfBlank(segment.getCurrentEventHint(), segment.getSummary())
                + "\nnextEventHint: " + StringUtils.defaultString(segment.getNextEventHint())
                + "\ncharacters: " + String.join("、", characters)
                + "\nprops: " + String.join("、", props)
                + "\nscene: " + scene
                + "\n\n【JSON Schema】\n" + format;
    }
}
