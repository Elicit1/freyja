package com.astra.freyja.service;

import com.astra.freyja.dto.res.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 人物角色资产管理服务。
 */
public interface ResCharacterService {

    /**
     * 分页查询人物列表
     */
    Page<ResCharacterVO> page(ResCharacterQuery query);

    /**
     * 根据 ID 获取人物详情（含多造型列表、别名列表与身份证据）
     */
    ResCharacterVO getById(Long id);

    /**
     * 新增人物（自动为该人物创建默认造型与初始别名）
     */
    Long create(ResCharacterDTO dto);

    /**
     * 修改人物基础与视觉设定
     */
    void update(ResCharacterDTO dto);

    /**
     * 逻辑删除人物及所属造型
     */
    void delete(Long id);

    /**
     * 获取人物下拉列表（用于分镜快速引用，支持按剧目过滤，并携带各角色的造型列表）
     */
    List<ResCharacterOptionVO> options(Long dramaId);

    /**
     * 角色合并 (Merge Characters)：将源角色合并至目标主角色
     */
    void merge(CharacterMergeDTO dto);

    /**
     * AI 智能衍生角色身份层纯净视觉提示词 (仅稳定外貌与生物特征，不含服装与镜头)
     */
    CharacterVisualPromptDeriveVO deriveVisualPrompt(com.astra.freyja.dto.res.CharacterVisualPromptDeriveDTO dto);

    /**
     * AI 智能衍生角色身份层纯净视觉提示词 (流式 SSE)
     */
    SseEmitter deriveVisualPromptStream(com.astra.freyja.dto.res.CharacterVisualPromptDeriveDTO dto);

    /**
     * 构建角色身份层提示词任务导出包 (外部 AI 复制使用)
     */
    AssetPromptPackageVO buildVisualPromptPackage(com.astra.freyja.dto.res.CharacterVisualPromptDeriveDTO dto);

}

