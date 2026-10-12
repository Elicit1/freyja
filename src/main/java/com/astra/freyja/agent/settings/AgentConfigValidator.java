package com.astra.freyja.agent.settings;

import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.profile.AgentProfile;
import com.astra.freyja.agent.profile.AgentProfileRegistry;
import com.astra.freyja.agent.tool.AgentToolRegistry;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.dto.agent.AgentConfigDTO;
import com.astra.freyja.dto.agent.AgentRequests.*;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.skill.service.SkillCatalogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AgentConfigValidator {
    public static final Set<String> AVATAR_STATES = Set.of("IDLE", "THINKING", "WORKING", "SUCCESS", "ATTENTION", "ERROR");
    private final AgentProfileRegistry profiles;
    private final AgentToolRegistry tools;
    private final AiProviderMapper providers;
    private final AiModelMapper models;
    private final SkillCatalogService skills;
    private final AgentJson json;

    public ValidationResult validate(String code, AgentConfigDTO config, boolean forExecution) {
        AgentProfile profile = profiles.require(code);
        List<ValidationIssue> errors = new ArrayList<>(), warnings = new ArrayList<>();
        if (config == null) return new ValidationResult(false, List.of(issue("config", "REQUIRED", "配置不能为空")), warnings);
        if (config.getAgentCode() != null && !java.util.Objects.equals(AgentProfileRegistry.canonicalCode(code), AgentProfileRegistry.canonicalCode(config.getAgentCode())))
            errors.add(issue("agentCode", "MISMATCH", "Agent 编码不能修改"));
        text(errors, "displayName", config.getDisplayName(), 100);
        if (config.getDescription() != null && config.getDescription().length() > 500)
            errors.add(issue("description", "SIZE", "描述不能超过 500 字"));
        if (config.getModel() == null || config.getInstructions() == null || config.getTools() == null
                || config.getSkills() == null || config.getMemory() == null || config.getRuntime() == null
                || config.getAvatar() == null) {
            errors.add(issue("config", "REQUIRED", "配置分区不能为空"));
            return new ValidationResult(false, errors, warnings);
        }
        text(errors, "instructions.systemPrompt", config.getInstructions().getSystemPrompt(), 30000);
        List<String> names = config.getTools().getNames();
        if (names == null || names.stream().anyMatch(n -> n == null || !profile.allowedTools().contains(n))) {
            errors.add(issue("tools.names", "UNKNOWN_TOOL", "只能选择模板已注册的工具"));
        } else {
            if (!names.containsAll(profile.requiredTools()) || Set.copyOf(names).size() != names.size())
                errors.add(issue("tools.names", "INVALID_TOOLS", "必需工具不可移除，工具不能重复"));
            for (String name : names) tools.require(name);
        }
        List<String> selectedSkills = config.getSkills().getNames();
        Set<String> available = skills.getEnabledCatalog().stream().map(s -> s.getName()).collect(java.util.stream.Collectors.toSet());
        if (selectedSkills == null || selectedSkills.stream().anyMatch(n -> n == null || !available.contains(n))
                || Set.copyOf(selectedSkills).size() != selectedSkills.size())
            errors.add(issue("skills.names", "INVALID_SKILL", "Skill 必须来自启用的版本目录且不能重复"));
        if (selectedSkills != null && !selectedSkills.isEmpty() && config.getSkills().isDynamicLoading()
                && (names == null || !names.containsAll(List.of("load_skill", "read_skill_file"))))
            errors.add(issue("skills.dynamicLoading", "MISSING_TOOLS", "按需加载 Skill 需要启用两个 Skill 工具"));
        var model = config.getModel();
        if (!Set.of("STREAM", "COMPLETE").contains(java.util.Objects.toString(model.getOutputMode(), "")))
            errors.add(issue("model.outputMode", "ENUM", "请选择流式输出或完整回复"));
        boolean hasModel = model.getProviderId() != null || (model.getModelCode() != null && !model.getModelCode().isBlank());
        if (hasModel || config.isEnabled() || forExecution) {
            if (model.getProviderId() == null || model.getModelCode() == null || model.getModelCode().isBlank()) {
                errors.add(issue("model", "REQUIRED", "请选择提供商和 CHAT 模型"));
            } else {
                var provider = providers.selectById(model.getProviderId());
                AiModel entry = models.selectOne(new LambdaQueryWrapper<AiModel>().eq(AiModel::getProviderId, model.getProviderId())
                        .eq(AiModel::getModelCode, model.getModelCode()));
                if (provider == null || !Integer.valueOf(1).equals(provider.getStatus()) || entry == null
                        || !Integer.valueOf(1).equals(entry.getStatus()) || !"CHAT".equals(entry.getModelType())) {
                    errors.add(issue("model", "INVALID_MODEL", "提供商和 CHAT 模型必须存在且启用"));
                } else if (names != null && !names.isEmpty()) {
                    Boolean capable = capability(entry.getParamsJson());
                    if (Boolean.FALSE.equals(capable)) errors.add(issue("model", "TOOL_CALLING_DISABLED", "该模型明确不支持工具调用"));
                    else if (capable == null) warnings.add(issue("model", "UNVERIFIED_TOOL_CALLING", "工具调用能力未声明，请在模型设置中确认其能力"));
                }
            }
        }
        if (model.getTemperature() != null && (!Double.isFinite(model.getTemperature()) || model.getTemperature() < 0 || model.getTemperature() > 2))
            errors.add(issue("model.temperature", "RANGE", "temperature 范围为 0–2"));
        if (model.getTopP() != null && (!Double.isFinite(model.getTopP()) || model.getTopP() <= 0 || model.getTopP() > 1))
            errors.add(issue("model.topP", "RANGE", "topP 范围为 (0, 1]"));
        range(errors, "model.maxTokens", model.getMaxTokens(), 1, 32768);
        var memory = config.getMemory();
        range(errors, "memory.recentMessages", memory.getRecentMessages(), 4, 200);
        range(errors, "memory.contextTokens", memory.getContextTokens(), 2048, 100000);
        if (model.getMaxTokens() != null && model.getMaxTokens() >= memory.getContextTokens())
            errors.add(issue("memory.contextTokens", "OUTPUT_BUDGET", "上下文预算必须大于最大输出 Token 数"));
        if (memory.getScopes() == null || memory.getScopes().stream().anyMatch(s -> s == null || !Set.of("SESSION", "PROJECT").contains(s)))
            errors.add(issue("memory.scopes", "ENUM", "仅支持 SESSION/PROJECT 规则"));
        var runtime = config.getRuntime();
        range(errors, "runtime.maxModelTurns", runtime.getMaxModelTurns(), 0, 100);
        range(errors, "runtime.maxToolCalls", runtime.getMaxToolCalls(), 0, 300);
        range(errors, "runtime.executionTimeoutSeconds", runtime.getExecutionTimeoutSeconds(), 10, 7200);
        range(errors, "runtime.modelTimeoutSeconds", runtime.getModelTimeoutSeconds(), 5, 600);
        range(errors, "runtime.externalWaitTimeoutSeconds", runtime.getExternalWaitTimeoutSeconds(), 10, 86400);
        range(errors, "runtime.maxRetries", runtime.getMaxRetries(), 0, 5);
        range(errors, "runtime.maxConcurrentRuns", runtime.getMaxConcurrentRuns(), 1, 4);
        var avatar = config.getAvatar();
        var pet = avatar.getPet();
        if (pet == null) errors.add(issue("avatar.pet", "REQUIRED", "请选择宠物包或恢复默认"));
        else {
            text(errors, "avatar.pet.id", pet.getId(), 100);
            text(errors, "avatar.pet.displayName", pet.getDisplayName(), 100);
            if (pet.getDescription() != null && pet.getDescription().length() > 1000)
                errors.add(issue("avatar.pet.description", "SIZE", "宠物描述不能超过 1000 字"));
            var sheet = new AgentConfigDTO.StateMedia(); sheet.setUrl(pet.getSpritesheetUrl()); sheet.setContentType(pet.getContentType());
            if (!validMedia(sheet) || !Set.of("image/webp", "image/png").contains(pet.getContentType() == null ? "" : pet.getContentType())
                    || (pet.getSpriteVersionNumber() != 1 && pet.getSpriteVersionNumber() != 2)
                    || pet.getFrameWidth() != 192 || pet.getFrameHeight() != 208 || pet.getColumns() != 8
                    || pet.getRows() != (pet.getSpriteVersionNumber() == 2 ? 11 : 9))
                errors.add(issue("avatar.pet", "INVALID_PET", "宠物必须使用 Codex Pet v1/v2 的 PNG/WebP 精灵图"));
        }
        if (avatar.getStateMedia() == null || avatar.getNotifications() == null) {
            errors.add(issue("avatar", "REQUIRED", "形象分区不能为空"));
        } else avatar.getStateMedia().forEach((state, media) -> {
            if (state == null || !AVATAR_STATES.contains(state) || media == null || !validMedia(media))
                errors.add(issue("avatar.stateMedia." + state, "INVALID_MEDIA", "仅支持 PNG/WebP/GIF 和 HTTP(S)/站内访问地址"));
        });
        return new ValidationResult(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
    }
    public static boolean validMedia(AgentConfigDTO.StateMedia media) {
        if (media.getUrl() == null || media.getUrl().isBlank() || media.getUrl().length() > 2048
                || media.getContentType() == null || !Set.of("image/png", "image/webp", "image/gif").contains(media.getContentType())) return false;
        try {
            URI uri = URI.create(media.getUrl());
            return (uri.getScheme() == null && media.getUrl().startsWith("/") && !media.getUrl().startsWith("//"))
                    || (Set.of("https", "http").contains(uri.getScheme()) && uri.getHost() != null);
        } catch (RuntimeException e) { return false; }
    }
    @SuppressWarnings("unchecked")
    private Boolean capability(String params) {
        if (params == null || params.isBlank()) return null;
        try {
            var value = json.read(params, java.util.Map.class).get("capabilities");
            return value instanceof java.util.Map<?, ?> map && map.get("toolCalling") instanceof Boolean b ? b : null;
        } catch (RuntimeException e) { return null; }
    }
    private static ValidationIssue issue(String field, String code, String message) { return new ValidationIssue(field, code, message); }
    private static void text(List<ValidationIssue> errors, String field, String value, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            errors.add(issue(field, "REQUIRED_OR_SIZE", "不能为空且长度不得超过 " + max));
    }
    private static void range(List<ValidationIssue> errors, String field, Integer value, int min, int max) {
        if (value == null || value < min || value > max) errors.add(issue(field, "RANGE", "范围为 " + min + "–" + max));
    }
}
