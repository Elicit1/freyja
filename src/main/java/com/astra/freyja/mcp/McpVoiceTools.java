package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.dao.McpQueryMapper;
import com.astra.freyja.dto.voice.CharacterVoiceDesignDTO;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.service.CharacterVoiceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Explicit audio generation entry point; unrelated business tools never start inference. */
@Component
@RequiredArgsConstructor
public class McpVoiceTools {
    public static final String MODELS_TOOL = "list_voice_design_models";
    public static final String DESIGN_TOOL = "design_character_voice";
    private final McpScope scope;
    private final McpBusinessRecords records;
    private final McpBusinessView views;
    private final McpQueryMapper queries;
    private final McpOperationStore journal;
    private final CharacterVoiceService voices;
    private final AiProviderMapper providers;
    private final AiModelMapper models;

    public record Design(String dramaId, String characterId, String requestId, String expectedFingerprint,
                         String providerId, String modelCode,
                         @ToolParam(description = "明确的音色设计描述，1–512字符") String voiceDesc,
                         @ToolParam(required = false, description = "试音文本，1–255字符；省略沿用角色文本或服务默认文本") String sampleText,
                         @ToolParam(required = false, description = "默认true，保存音色描述、试音文本和样音URL；false仅生成试听") Boolean autoSave) {}
    public record VoiceModel(String providerId, String providerName, String modelCode, String modelName, String modelType) {}

    public List<McpTool<?>> tools() {
        return List.of(
                McpTool.read(MODELS_TOOL, "查询授权项目可用的已启用声音模型（TTS/AUDIO/VOICE）与提供商标识；不返回密钥、连接配置或内部提示词。请按模型实际能力选择声音设计模型。",
                        McpInputs.Project.class, this::listModels),
                new McpTool<>(DESIGN_TOOL, "调用所选声音模型设计角色样音并归档至MinIO，会消耗模型额度。仅限授权项目角色，公共人物不可操作。需当前角色expectedFingerprint和稳定requestId；默认回填voiceDesc、voiceSampleText、voiceSampleUrl，autoSave=false仅试听。返回样音URL、时长、saved及角色当前数据；成功请求重试返回原结果。",
                        Design.class, McpTool.Effect.WRITE, null, this::design));
    }

    private Object listModels(McpPrincipal caller, McpInputs.Project input) {
        scope.project(caller, input.dramaId());
        Map<Long, AiProvider> enabled = providers.selectList(new LambdaQueryWrapper<AiProvider>()
                        .eq(AiProvider::getStatus, 1)).stream()
                .collect(Collectors.toMap(AiProvider::getId, Function.identity()));
        if (enabled.isEmpty()) return Map.of("models", List.of());
        List<VoiceModel> options = models.selectList(new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getStatus, 1).in(AiModel::getModelType, List.of("TTS", "AUDIO", "VOICE"))
                        .in(AiModel::getProviderId, enabled.keySet())
                        .orderByAsc(AiModel::getSortOrder).orderByAsc(AiModel::getId)).stream()
                .map(model -> new VoiceModel(model.getProviderId().toString(), enabled.get(model.getProviderId()).getProviderName(),
                        model.getModelCode(), model.getModelName(), model.getModelType())).toList();
        return Map.of("models", options);
    }

    private Object design(McpPrincipal caller, Design input) {
        if (!caller.write()) throw new BizException(403, "MCP_WRITE_DISABLED");
        Long project = scope.project(caller, input.dramaId());
        Long character = McpScope.id(input.characterId());
        Long provider = McpScope.id(input.providerId());
        text(input.modelCode(), "modelCode", 128, true);
        text(input.voiceDesc(), "voiceDesc", 512, true);
        text(input.sampleText(), "sampleText", 255, false);
        text(input.expectedFingerprint(), "expectedFingerprint", 128, true);
        // Recheck project ownership even when replaying a previously committed result.
        records.require(caller, McpRecordType.CHARACTER, input.characterId(), input.dramaId(), true);
        return journal.execute(input.requestId(), DESIGN_TOOL, input, () -> {
            if (queries.lockProject(project) == null) throw new BizException(404, "项目不存在");
            var current = records.lockScoped(caller, McpRecordType.CHARACTER, input.characterId(), input.dramaId());
            views.checkFingerprint(McpBusinessView.Kind.CHARACTER, current, input.expectedFingerprint(), Set.of("voiceDesc", "voiceSampleText"));
            CharacterVoiceDesignDTO dto = new CharacterVoiceDesignDTO();
            dto.setCharacterId(character);
            dto.setProviderId(provider);
            dto.setModelCode(input.modelCode().trim());
            dto.setVoiceDesc(input.voiceDesc());
            dto.setSampleText(input.sampleText());
            boolean save = !Boolean.FALSE.equals(input.autoSave());
            dto.setAutoSave(save);
            var audio = voices.designCharacterVoice(character, dto);
            return new McpOperationStore.Outcome(Map.of("requestId", input.requestId(), "dramaId", input.dramaId(),
                    "saved", save, "audio", audio,
                    "character", records.read(caller, McpRecordType.CHARACTER, input.characterId(), input.dramaId())), project, false);
        });
    }

    private static void text(String value, String name, int max, boolean required) {
        if ((required && value == null) || (value != null && (value.isBlank() || value.length() > max)))
            throw new BizException(400, name + " 必须为1–" + max + "字符的非空文本");
    }
}
