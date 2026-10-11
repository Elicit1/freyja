package com.astra.freyja.agent.settings;

import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.profile.AgentProfileRegistry;
import com.astra.freyja.agent.tool.AgentToolRegistry;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AgentConfigMapper;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dto.agent.AgentConfigDTO;
import com.astra.freyja.dto.agent.AgentRequests.ValidationResult;
import com.astra.freyja.entity.AgentConfig;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.skill.service.SkillCatalogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentConfigService {
    private final AgentConfigMapper mapper;
    private final AiModelMapper models;
    private final AgentProfileRegistry profiles;
    private final AgentToolRegistry tools;
    private final SkillCatalogService skills;
    private final AgentConfigValidator validator;
    private final AgentJson json;
    private final TransactionTemplate transactions;

    public AgentConfigDTO get(String code) {
        profiles.require(code);
        AgentConfig entry = mapper.selectOne(new LambdaQueryWrapper<AgentConfig>().eq(AgentConfig::getAgentCode, code));
        if (entry == null) {
            var defaults = profiles.require(code).defaults();
            defaults.setAgentCode(code);
            defaults.setEnabled(false);
            defaults.getTools().setNames(profiles.require(code).allowedTools());
            entry = toEntity(code, defaults, 1);
            try { mapper.insert(entry); }
            catch (DuplicateKeyException concurrentInitialization) {
                entry = mapper.selectOne(new LambdaQueryWrapper<AgentConfig>().eq(AgentConfig::getAgentCode, code));
            }
        }
        return fromEntity(entry);
    }
    public List<AgentConfigDTO> list() { return profiles.all().stream().map(p -> get(p.code())).toList(); }
    public record Display(String agentCode, String displayName, String description, AgentConfigDTO.Avatar avatar) {}
    public List<Display> enabled() {
        return list().stream().filter(AgentConfigDTO::isEnabled)
                .filter(c -> validator.validate(c.getAgentCode(), c, true).valid())
                .map(c -> new Display(c.getAgentCode(), c.getDisplayName(), c.getDescription(), c.getAvatar())).toList();
    }
    public Map<String, Object> catalog(String code) {
        var profile = profiles.require(code);
        return Map.of("tools", tools.catalog(profile.allowedTools()), "requiredTools", profile.requiredTools(),
                "skills", skills.getEnabledCatalog(), "fixedInstructions", profile.fixedInstructions(),
                "defaults", profile.defaults(), "profileVersion", profile.version());
    }
    public ValidationResult validate(String code, AgentConfigDTO dto, boolean execution) {
        return validator.validate(code, dto, execution);
    }
    public AgentConfigDTO save(String code, AgentConfigDTO dto) {
        ValidationResult result = validator.validate(code, dto, false);
        if (!result.valid()) throw new BizException(400, result.errors().getFirst().field() + ": " + result.errors().getFirst().message());
        get(code);
        return transactions.execute(tx -> {
            AgentConfig current = mapper.lockByCode(code);
            if (dto.getExpectedRevision() == null || !dto.getExpectedRevision().equals(current.getRevision()))
                throw new BizException(409, "CONFIG_VERSION_CONFLICT: 配置已被修改，请重新加载");
            if (current.getRevision() == Integer.MAX_VALUE) throw new BizException("配置版本已达到上限");
            AgentConfig updated = toEntity(code, dto, current.getRevision() + 1);
            mapper.update(null, new LambdaUpdateWrapper<AgentConfig>()
                    .eq(AgentConfig::getId, current.getId()).eq(AgentConfig::getRevision, current.getRevision())
                    .set(AgentConfig::getDisplayName, updated.getDisplayName()).set(AgentConfig::getDescription, updated.getDescription())
                    .set(AgentConfig::getEnabled, updated.getEnabled()).set(AgentConfig::getProviderId, updated.getProviderId())
                    .set(AgentConfig::getModelCode, updated.getModelCode()).set(AgentConfig::getSystemPrompt, updated.getSystemPrompt())
                    .set(AgentConfig::getModelOptionsJson, updated.getModelOptionsJson()).set(AgentConfig::getToolPolicyJson, updated.getToolPolicyJson())
                    .set(AgentConfig::getSkillPolicyJson, updated.getSkillPolicyJson()).set(AgentConfig::getMemoryPolicyJson, updated.getMemoryPolicyJson())
                    .set(AgentConfig::getRuntimePolicyJson, updated.getRuntimePolicyJson()).set(AgentConfig::getAvatarConfigJson, updated.getAvatarConfigJson())
                    .set(AgentConfig::getRevision, updated.getRevision()).set(AgentConfig::getProfileVersion, updated.getProfileVersion()));
            return fromEntity(updated);
        });
    }
    /** Called inside the run submission transaction; the copied configuration is never read again during recovery. */
    public Snapshot snapshot(String code, AgentConfigDTO input, String type) {
        var config = json.copy(input, AgentConfigDTO.class);
        if (!"CONFIG_TEST".equals(type) && !config.isEnabled()) throw new BizException("Agent 尚未启用");
        ValidationResult result = validator.validate(code, config, true);
        if (!result.valid()) throw new BizException(400, result.errors().getFirst().message());
        var profile = profiles.require(code);
        AiModel model = models.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, config.getModel().getProviderId()).eq(AiModel::getModelCode, config.getModel().getModelCode()));
        if (config.getModel().getTemperature() == null) config.getModel().setTemperature(model.getTemperature());
        if (config.getModel().getTopP() == null) config.getModel().setTopP(model.getTopP());
        AiModel pinned = new AiModel();
        pinned.setProviderId(config.getModel().getProviderId()); pinned.setModelCode(config.getModel().getModelCode());
        pinned.setModelType("CHAT"); pinned.setStatus(1); pinned.setTemperature(config.getModel().getTemperature());
        pinned.setTopP(config.getModel().getTopP()); pinned.setMaxTokens(config.getModel().getMaxTokens());
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (model.getParamsJson() != null && !model.getParamsJson().isBlank()) {
            Map<?, ?> original = json.read(model.getParamsJson(), Map.class);
            for (String key : List.of("frequency_penalty", "presence_penalty", "stop", "seed", "top_k", "repeat_penalty")) {
                if (original.get(key) != null) parameters.put(key, original.get(key));
            }
        }
        pinned.setParamsJson(json.write(parameters));
        if ("CONFIG_TEST".equals(type)) {
            config.getTools().setNames(config.getTools().getNames().stream()
                    .filter(n -> tools.require(n).effect() == Effect.READ).toList());
            config.getMemory().setScopes(List.of());
        }
        Map<String, Long> versions = new LinkedHashMap<>();
        skills.getEnabledCatalog().stream().filter(s -> config.getSkills().getNames().contains(s.getName()))
                .forEach(s -> versions.put(s.getName(), s.getVersionId()));
        Map<String, Integer> toolVersions = new LinkedHashMap<>();
        config.getTools().getNames().forEach(name -> toolVersions.put(name, tools.require(name).version()));
        config.setExpectedRevision(null);
        return new Snapshot(code, input.getRevision() == null ? 0 : input.getRevision(), profile.version(), config,
                profile.fixedInstructions(), Map.copyOf(versions), Map.copyOf(toolVersions), pinned, type);
    }
    private AgentConfig toEntity(String code, AgentConfigDTO dto, int revision) {
        AgentConfig item = new AgentConfig();
        item.setAgentCode(code); item.setDisplayName(dto.getDisplayName().trim()); item.setDescription(dto.getDescription());
        item.setEnabled(dto.isEnabled()); item.setProviderId(dto.getModel().getProviderId()); item.setModelCode(dto.getModel().getModelCode());
        item.setSystemPrompt(dto.getInstructions().getSystemPrompt()); item.setModelOptionsJson(json.write(dto.getModel()));
        item.setToolPolicyJson(json.write(dto.getTools())); item.setSkillPolicyJson(json.write(dto.getSkills()));
        item.setMemoryPolicyJson(json.write(dto.getMemory())); item.setRuntimePolicyJson(json.write(dto.getRuntime()));
        item.setAvatarConfigJson(json.write(dto.getAvatar())); item.setRevision(revision); item.setProfileVersion(profiles.require(code).version());
        return item;
    }
    private AgentConfigDTO fromEntity(AgentConfig item) {
        AgentConfigDTO dto = new AgentConfigDTO();
        dto.setAgentCode(item.getAgentCode()); dto.setDisplayName(item.getDisplayName()); dto.setDescription(item.getDescription());
        dto.setEnabled(Boolean.TRUE.equals(item.getEnabled())); dto.setRevision(item.getRevision()); dto.setExpectedRevision(item.getRevision());
        dto.setModel(json.read(item.getModelOptionsJson(), AgentConfigDTO.Model.class));
        dto.setInstructions(new AgentConfigDTO.Instructions()); dto.getInstructions().setSystemPrompt(item.getSystemPrompt());
        dto.setTools(json.read(item.getToolPolicyJson(), AgentConfigDTO.Tools.class)); dto.setSkills(json.read(item.getSkillPolicyJson(), AgentConfigDTO.Skills.class));
        dto.setMemory(json.read(item.getMemoryPolicyJson(), AgentConfigDTO.Memory.class)); dto.setRuntime(json.read(item.getRuntimePolicyJson(), AgentConfigDTO.Runtime.class));
        dto.setAvatar(json.read(item.getAvatarConfigJson(), AgentConfigDTO.Avatar.class));
        return dto;
    }
}
