package com.astra.freyja.director.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.director.model.DirectorPlan;
import com.astra.freyja.dto.drama.ShotPromptDeriveDTO;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.service.AiModelFactory;
import com.astra.freyja.skill.service.SkillCatalogService;
import com.astra.freyja.skill.service.SkillPromptContextService;
import com.astra.freyja.skill.model.SkillPromptContext;
import com.astra.freyja.skill.model.SkillCatalogItem;
import com.astra.freyja.skill.tool.LoadSkillToolFactory;
import com.astra.freyja.skill.tool.LoadSkillToolSession;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DirectorPlanningServiceImpl implements DirectorPlanningService {

    private static final List<String> DIRECTOR_SKILL_NAMES = List.of("cinematography");

    private final AiProviderMapper providerMapper;
    private final AiModelMapper modelMapper;
    private final AiModelFactory aiModelFactory;
    private final SkillCatalogService skillCatalogService;
    private final LoadSkillToolFactory loadSkillToolFactory;
    private final SkillPromptContextService skillPromptContextService;
    private final DirectorPlanValidator planValidator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public DirectorPlan plan(ShotPromptDeriveDTO dto) {
        if (dto == null) {
            throw new BizException("ShotPromptDeriveDTO 不能为空");
        }

        Long providerId = dto.getProviderId();
        String modelCode = dto.getModelCode();

        if (providerId == null || StringUtils.isBlank(modelCode)) {
            throw new BizException("未指定 AI 提供商或模型编码 (providerId / modelCode)，无法执行导演规划");
        }

        // 1. 检查模型与 Tool Calling 能力
        validateToolCallingCapability(providerId, modelCode);

        // 2. 准备 Skill Catalog 精简目录与 Tool Session
        List<SkillCatalogItem> catalog = skillCatalogService.getEnabledCatalog();
        if (catalog == null) {
            catalog = List.of();
        }
        log.info("[DirectorPlanning] Skill 目录已准备: enabledSkillCount={}, availableSkills={}",
                catalog.size(), catalog.stream().map(SkillCatalogItem::getName).toList());
        String catalogPrompt = skillCatalogService.formatCatalogForPrompt(catalog);
        Map<String, Long> versionSnapshot = new HashMap<>();
        catalog.forEach(item -> {
            if (item.getVersionId() != null) {
                versionSnapshot.put(item.getName(), item.getVersionId());
            }
        });
        LoadSkillToolSession session = skillPromptContextService != null
                ? skillPromptContextService.createApiSession(
                "director-planning", String.valueOf(dto.getShotId()), DIRECTOR_SKILL_NAMES)
                : new LoadSkillToolSession(versionSnapshot);
        ToolCallback loadSkillTool = loadSkillToolFactory.createTool(session);

        // 3. 构建专用 ChatClient 并执行 Tool Calling 规划
        ChatModel chatModel = aiModelFactory.getChatModel(providerId, modelCode);
        ChatClient.Builder clientBuilder = ChatClient.builder(chatModel);
        ChatOptions defaultOptions = chatModel.getOptions();
        if (defaultOptions != null) {
            clientBuilder.defaultOptions(defaultOptions.mutate());
        }
        ChatClient chatClient = clientBuilder.build();

        String systemPrompt = buildSystemPrompt(catalogPrompt);
        if (skillPromptContextService != null) {
            SkillPromptContext requiredContext = skillPromptContextService.loadSelected(
                    "director-planning", String.valueOf(dto.getShotId()), DIRECTOR_SKILL_NAMES,
                    session.getVersionSnapshot());
            systemPrompt = skillPromptContextService.appendToSystemPrompt(systemPrompt, requiredContext);
        }
        String userPrompt = buildUserPrompt(dto);

        log.info("[DirectorPlanning] 启动导演规划流水线: shotId={}, shotNo={}, duration={}, providerId={}, modelCode={}",
                dto.getShotId(), dto.getShotNo(), dto.getDuration(), providerId, modelCode);

        long startMs = System.currentTimeMillis();
        String responseContent;
        try {
            responseContent = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .tools(loadSkillTool)
                    .call()
                    .content();
        } catch (Exception e) {
            logSkillInvocationSummary(session, "FAILED");
            log.error("[DirectorPlanning] 调用 AI 导演规划失败: {}", e.getMessage(), e);
            throw new BizException("AI 导演规划调用异常: " + e.getMessage());
        }

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("[DirectorPlanning] 导演规划调用完成 (耗时 {}ms), 工具调用次数: {}", durationMs, session.getInvocationCount());
        logSkillInvocationSummary(session, "SUCCESS");

        // 4. 解析为 DirectorPlan
        DirectorPlan plan = cleanAndParsePlan(responseContent);
        if (plan == null) {
            log.warn("[DirectorPlanning] 无法从 AI 输出中解析出有效 DirectorPlan: {}", responseContent);
            throw new BizException("未能从 AI 返回文本中解析出合法的 DirectorPlan 结构");
        }

        // 5. 执行确定性物理校验并套用用户锁定
        BigDecimal expectedDuration = dto.getDuration() != null ? BigDecimal.valueOf(dto.getDuration()) : new BigDecimal("3.0");
        planValidator.validateAndNormalize(
                plan,
                expectedDuration,
                dto.getShotType(),
                dto.getCameraMovement(),
                Boolean.TRUE.equals(dto.getShotTypeLocked()),
                Boolean.TRUE.equals(dto.getCameraMovementLocked()),
                session
        );

        return plan;
    }

    private void logSkillInvocationSummary(LoadSkillToolSession session, String outcome) {
        List<String> invokedSkills = session.getInvokedSkillNames();
        log.info("[DirectorPlanning] Skill 调用追踪: outcome={}, called={}, invocationCount={}, "
                        + "invokedSkills={}, successfullyLoadedSkills={}, loadAttemptCount={}",
                outcome,
                !invokedSkills.isEmpty(),
                session.getInvocationCount(),
                invokedSkills,
                session.getLoadedSkillNames(),
                session.getCallCount());
    }

    private void validateToolCallingCapability(Long providerId, String modelCode) {
        AiProvider provider = providerMapper.selectById(providerId);
        AiModel model = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, providerId)
                .eq(AiModel::getModelCode, modelCode));

        if (provider == null || !Integer.valueOf(1).equals(provider.getStatus())
                || model == null || !Integer.valueOf(1).equals(model.getStatus())) {
            throw new BizException("AI 提供商或模型不存在或已停用");
        }

        if (!"CHAT".equalsIgnoreCase(model.getModelType())) {
            throw new BizException("导演规划模型类型必须为 CHAT 对话模型");
        }

        // 检查显式声明的 capabilities
        if (StringUtils.isNotBlank(model.getParamsJson())) {
            try {
                JsonNode root = objectMapper.readTree(model.getParamsJson());
                JsonNode cap = root.path("capabilities");
                if (cap.has("toolCalling") && !cap.path("toolCalling").asBoolean(true)) {
                    throw new BizException("所选模型 [" + model.getModelName() + "] 声明不支持 Tool Calling，无法用于需要按需加载知识库的导演规划");
                }
            } catch (BizException e) {
                throw e;
            } catch (Exception ignored) {
            }
        }
    }

    private String buildSystemPrompt(String catalogPrompt) {
        return String.format("""
                你是一名顶尖院线级影视分镜导演与摄影机调度大师。
                你的职责是根据输入的剧情事实、人物动作、对白、场景/角色/道具参考、时长及明确的创作者约束，完成完整视觉导演：景别 (shotSize)、机位角度 (cameraAngle)、构图重点，以及与人物动作协调的摄影机运镜节拍序列 (Camera Beats)。
                
                %s
                
                【核心原则与约束（铁律）】
                1. 摄影决策与技能知识加载：
                   你必须先调用 load_skill(name="cinematography") 并等待成功结果，再依据已加载的当前版本摄影技能完成视觉导演；不能用其他 Skill 替代 cinematography。
                2. 创作者锁定绝对优先：
                   - 只有用户输入中明确标注“创作者明确锁定”的景别/运镜才是硬约束；仅有历史字段值不代表用户锁定。
                   - 若景别或运镜状态为 AUTO/未指定，你必须自主做出具体摄影决策。缺少运镜约束绝不意味着 STATIC；STATIC 只能是你根据剧情实际作出的导演决策。
                   - 若输入的景别明确锁定，你的 shotSize 必须严格保持该值；若输入的运镜明确锁定，每一个 Camera Beat 都必须遵循该类型，不得保留冲突运动。
                   - duration 为绝对物理时长锁定，Camera Beats 的起始与结束时间必须严格覆盖 [0.0, duration]，严禁留空或超限！
                3. 克制运镜准则：
                   - 根据动作关系和叙事重点选择合适运镜；静态、摇摄、推拉、跟随等均可，不能仅因输入没有运镜就选择 STATIC；
                   - 摄影机运动必须与人物动作协调，并明确摄影机如何响应主体动作以及动作完成后的最终构图；
                   - 人物动作与摄影机运动可以同时存在，但不得互相争夺画面控制权，也不得为了展示正脸而擅自添加没有剧情依据的回转或姿态复原；
                   - 每个 Camera Beat 必须明确触发节点 (startCue / stopCue) 与叙事目的 (narrativePurpose)。
                4. 输出格式要求：
                   严格输出符合以下 JSON Schema 的纯 JSON 对象，不得包含 Markdown 代码块之外的闲聊前言：
                   {
                     "schemaVersion": "1.0",
                     "duration": 5.0,
                     "shotSize": "MEDIUM_SHOT",
                     "cameraAngle": "EYE_LEVEL",
                     "cameraBeats": [
                       {
                         "startSec": 0.0,
                         "endSec": 2.5,
                         "movement": "PAN_LEFT",
                         "direction": "LEFT",
                         "speed": "SLOW",
                         "startCue": "...",
                         "stopCue": "...",
                         "narrativePurpose": "..."
                       }
                     ],
                     "subjectAction": "...",
                     "gaze": "...",
                     "narrativeIntent": "..."
                   }
                """, catalogPrompt);
    }

    private String buildUserPrompt(ShotPromptDeriveDTO dto) {
        StringBuilder sb = new StringBuilder();
        sb.append("请依据以下当前分镜事实与创作者配置，输出结构化 DirectorPlan 导演规划方案：\n");
        sb.append("【镜头序号】: ").append(dto.getShotNo() != null ? dto.getShotNo() : 1).append("\n");
        sb.append("【镜头物理时长 (duration - 绝对锁定)】: ").append(dto.getDuration() != null ? dto.getDuration() : "3.0").append("秒\n");

        boolean shotTypeLocked = Boolean.TRUE.equals(dto.getShotTypeLocked())
                && StringUtils.isNotBlank(dto.getShotType()) && !"AUTO".equalsIgnoreCase(dto.getShotType().trim());
        String shotTypeStatus = shotTypeLocked
                ? "【LOCKED 创作者明确锁定景别: " + dto.getShotType() + " - AI 不得更改】"
                : "【AUTO - 景别未指定，由 AI 导演自主决定】";
        sb.append("【景别配置】: ").append(shotTypeStatus).append("\n");

        boolean movementLocked = Boolean.TRUE.equals(dto.getCameraMovementLocked())
                && StringUtils.isNotBlank(dto.getCameraMovement()) && !"AUTO".equalsIgnoreCase(dto.getCameraMovement().trim());
        String movementStatus = movementLocked
                ? "【LOCKED 创作者明确锁定运镜: " + dto.getCameraMovement() + " - 所有 Camera Beats 必须遵循】"
                : "【AUTO - 运镜未指定，由 AI 导演自主决定；不得因缺少输入而默认 STATIC】";
        sb.append("【运镜配置】: ").append(movementStatus).append("\n");

        if (StringUtils.isNotBlank(dto.getScriptContent())) {
            sb.append("【镜头剧本文本 (剧情、人物行为、道具状态与事件顺序事实)】: ").append(dto.getScriptContent()).append("\n")
                    .append("若其中包含“原文摄影要求（用户明确指定）”小节，必须将该原文要求作为创作者约束遵守；其余内容由你自主完成视觉导演。\n");
        }
        if (StringUtils.isNotBlank(dto.getActionDescription())) {
            sb.append("【画面核心动作简述】: ").append(dto.getActionDescription()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getDialogueSpeaker())) {
            sb.append("【台词说话人】: ").append(dto.getDialogueSpeaker()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getDialogue())) {
            sb.append("【对白台词】: ").append(dto.getDialogue()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getStylePreset())) {
            sb.append("【短剧画风预设】: ").append(dto.getStylePreset()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getStyleTone())) {
            sb.append("【短剧视觉基调与摄影质感】: ").append(dto.getStyleTone()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getUserInstruction())) {
            sb.append("【创作者特别导演指令】: ").append(dto.getUserInstruction()).append("\n");
        }
        return sb.toString();
    }

    private DirectorPlan cleanAndParsePlan(String rawText) {
        if (StringUtils.isBlank(rawText)) return null;
        String text = rawText.trim();

        if (text.contains("```")) {
            int first = text.indexOf("```");
            int second = text.indexOf("```", first + 3);
            if (second > first) {
                String block = text.substring(first + 3, second).trim();
                if (block.toLowerCase().startsWith("json")) {
                    block = block.substring(4).trim();
                }
                text = block;
            }
        }

        try {
            return objectMapper.readValue(text, DirectorPlan.class);
        } catch (Exception e) {
            log.debug("[cleanAndParsePlan] 直接反序列化失败: {}", e.getMessage());
        }

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                return objectMapper.readValue(text.substring(start, end + 1), DirectorPlan.class);
            } catch (Exception e) {
                log.warn("[cleanAndParsePlan] 截取 JSON 反序列化失败: {}", e.getMessage());
            }
        }
        return null;
    }
}
