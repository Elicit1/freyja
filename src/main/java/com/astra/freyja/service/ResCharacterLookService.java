package com.astra.freyja.service;

import com.astra.freyja.dto.res.AssetPromptPackageVO;
import com.astra.freyja.dto.res.OutfitPromptDeriveDTO;
import com.astra.freyja.dto.res.OutfitPromptDeriveVO;
import com.astra.freyja.dto.res.ResCharacterLookDTO;
import com.astra.freyja.dto.res.ResCharacterLookVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 人物造型管理服务。
 */
public interface ResCharacterLookService {

    /**
     * 查询某人物的所有造型列表 (默认造型排第一)
     */
    List<ResCharacterLookVO> listByCharacterId(Long characterId);

    /**
     * 获取单个造型详情
     */
    ResCharacterLookVO getById(Long id);

    /**
     * 获取人物的默认造型
     */
    ResCharacterLookVO getDefaultLook(Long characterId);

    /**
     * 新增造型 (第一套造型自动设为默认)
     */
    Long create(ResCharacterLookDTO dto);

    /**
     * 修改造型 (检测 Prompt 变更转移 image_status，维护默认互斥)
     */
    void update(ResCharacterLookDTO dto);

    /**
     * 删除造型 (若为默认，自动将下一个启用的可用造型提升为默认)
     */
    void delete(Long id);

    /**
     * 设为该人物的默认造型 (自动启用并清除旧默认)
     */
    void setDefault(Long id);

    /**
     * 更新造型图片 (并标记 image_status 为 SYNCED)
     */
    void updateLookImage(Long id, String imageUrl);

    /**
     * AI 智能衍生角色造型/服饰专属提示词
     */
    OutfitPromptDeriveVO deriveLookPrompt(OutfitPromptDeriveDTO dto);

    /**
     * AI 智能衍生角色造型/服饰专属提示词 (流式 SSE)
     */
    SseEmitter deriveLookPromptStream(OutfitPromptDeriveDTO dto);

    /**
     * 构建角色造型提示词任务导出包 (外部 AI 复制使用)
     */
    AssetPromptPackageVO buildLookPromptPackage(OutfitPromptDeriveDTO dto);
}
