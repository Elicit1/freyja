package com.astra.freyja.service;

import com.astra.freyja.dto.res.AssetPromptPackageVO;
import com.astra.freyja.dto.res.OutfitPromptDeriveVO;
import com.astra.freyja.dto.res.ResCharacterOutfitDTO;
import com.astra.freyja.dto.res.ResCharacterOutfitVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 人物造型/服装管理服务。
 */
public interface ResCharacterOutfitService {

    /**
     * 查询某人物的所有造型
     */
    List<ResCharacterOutfitVO> listByCharacterId(Long characterId);

    /**
     * 获取单个造型详情
     */
    ResCharacterOutfitVO getById(Long id);

    /**
     * 新增造型
     */
    Long create(ResCharacterOutfitDTO dto);

    /**
     * 修改造型
     */
    void update(ResCharacterOutfitDTO dto);

    /**
     * 删除造型
     */
    void delete(Long id);

    /**
     * 设为该人物的默认造型
     */
    void setDefault(Long id);

    /**
     * AI 智能衍生角色造型/服饰专属提示词
     */
    OutfitPromptDeriveVO deriveOutfitPrompt(com.astra.freyja.dto.res.OutfitPromptDeriveDTO dto);

    /**
     * AI 智能衍生角色造型/服饰专属提示词 (流式 SSE)
     */
    SseEmitter deriveOutfitPromptStream(com.astra.freyja.dto.res.OutfitPromptDeriveDTO dto);

    /**
     * 构建角色造型提示词任务导出包 (外部 AI 复制使用)
     */
    AssetPromptPackageVO buildOutfitPromptPackage(com.astra.freyja.dto.res.OutfitPromptDeriveDTO dto);
}
