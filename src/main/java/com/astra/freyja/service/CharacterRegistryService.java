package com.astra.freyja.service;

import com.astra.freyja.dto.res.*;
import com.astra.freyja.dto.script.CharacterRegistryItemVO;
import com.astra.freyja.dto.script.DecomposedCharacterVO;
import com.astra.freyja.entity.ResCharacter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 角色注册表管理与实体合并服务接口 (Character Registry Service)。
 * 它是整个短剧生命周期内角色身份的唯一事实来源。
 */
public interface CharacterRegistryService {

    /**
     * 构建指定短剧的角色注册表上下文（包含正式名、展示称谓、身份状态、别名列表与外貌提示词）。
     *
     * @param dramaId 短剧 ID
     * @return 角色注册表条目列表
     */
    List<CharacterRegistryItemVO> buildRegistryContext(Long dramaId);

    /**
     * 将角色注册表格式化为注入给大模型 Prompt 的结构化文本。
     *
     * @param dramaId 短剧 ID
     * @return Prompt 注册表上下文字符串
     */
    String formatRegistryForPrompt(Long dramaId);

    /**
     * 按规范正式姓名精准查询角色。
     *
     * @param dramaId 短剧 ID
     * @param canonicalName 正式姓名
     * @return 匹配角色 (Optional)
     */
    Optional<ResCharacter> findByCanonicalName(Long dramaId, String canonicalName);

    /**
     * 按别名/历史称谓查询可能匹配的角色列表。
     *
     * @param dramaId 短剧 ID
     * @param alias 别名或提及词
     * @return 匹配的角色列表
     */
    List<ResCharacter> findByAlias(Long dramaId, String alias);

    /**
     * 查询指定短剧与指定性别下的所有候选角色列表。
     *
     * @param dramaId 短剧 ID
     * @param gender 性别 (可选)
     * @return 候选角色条目列表
     */
    List<CharacterRegistryItemVO> findCandidates(Long dramaId, String gender);

    /**
     * 获取单个角色的完整注册上下文（含别名列表、默认造型、证据链）。
     *
     * @param characterId 角色 ID
     * @return 角色详情 VO
     */
    ResCharacterVO getCharacterContext(Long characterId);

    /**
     * 将剧本提及 (Mention) 绑定至已有角色实体，并自动更新别名与时序证据。
     *
     * @param context 消歧上下文
     * @param characterId 绑定的角色 ID
     */
    void bindMention(CharacterResolutionContext context, Long characterId);

    /**
     * 确认角色正式身份 (从 PARTIAL 升级为 CONFIRMED)。
     *
     * @param characterId 角色 ID
     * @param canonicalName 正式规范名
     * @param evidenceText 原文依据 (如 自我介绍)
     * @param evidenceType 证据类型
     */
    void confirmIdentity(Long characterId, String canonicalName, String evidenceText, String evidenceType);

    /**
     * 执行角色合并 (Merge Characters)：将源角色全量迁移并合并到目标主角色中。
     *
     * @param dto 合并参数
     */
    void mergeCharacter(CharacterMergeDTO dto);

    /**
     * 查询指定角色的所有别名与称谓。
     *
     * @param characterId 角色 ID
     * @return 别名列表
     */
    List<ResCharacterAliasVO> listAliasesByCharacterId(Long characterId);

    /**
     * 为角色新增别名/称谓。
     *
     * @param dto 别名信息
     * @return 别名 ID
     */
    Long addAlias(ResCharacterAliasDTO dto);

    /**
     * 更新角色别名信息。
     *
     * @param dto 别名信息
     */
    void updateAlias(ResCharacterAliasDTO dto);

    /**
     * 删除角色别名。
     *
     * @param aliasId 别名 ID
     */
    void removeAlias(Long aliasId);

    /**
     * 查询指定角色的身份证据链。
     *
     * @param characterId 角色 ID
     * @return 证据列表
     */
    List<ResCharacterEvidenceVO> listEvidencesByCharacterId(Long characterId);

    /** Persist identity-uncertain character records returned by the Planner for later manual review. */
    void persistUnresolvedPreview(List<DecomposedCharacterVO> characters, Long dramaId, Long episodeId);

    /**
     * 记录一条角色身份判定依据/证据。
     *
     * @param dramaId 短剧 ID
     * @param characterId 角色 ID
     * @param episodeId 剧集 ID
     * @param sceneId 场次 ID
     * @param sourceText 原文依据
     * @param evidenceType 证据类型
     * @param confidence 置信度
     * @param reason 推理原因
     * @return 证据记录 ID
     */
    Long recordEvidence(Long dramaId, Long characterId, Long episodeId, Long sceneId,
                        String sourceText, String evidenceType, BigDecimal confidence, String reason);

    /**
     * 查询指定短剧（或全局）待人工处理的未决消歧列表。
     *
     * @param dramaId 短剧 ID (可选)
     * @return 未决消歧列表
     */
    List<ResCharacterResolutionVO> listPendingResolutions(Long dramaId);

    /**
     * 处理/决议未决消歧项。
     *
     * @param dto 决议参数
     */
    void resolveResolution(ResCharacterResolutionDTO dto);
}
