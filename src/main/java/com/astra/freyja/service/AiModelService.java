package com.astra.freyja.service;

import com.astra.freyja.dto.AiModelDTO;
import com.astra.freyja.dto.AiModelQuery;
import com.astra.freyja.entity.AiModel;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * AI 模型管理服务。
 */
public interface AiModelService {

    /**
     * 模型分页查询。
     */
    Page<AiModel> page(AiModelQuery query);

    /**
     * 模型详情。
     */
    AiModel getById(Long id);

    /**
     * 指定提供商下启用中的模型列表（优先读 Redis 缓存）。
     */
    List<AiModel> listByProviderId(Long providerId);

    /**
     * 新增模型。
     */
    void create(AiModelDTO dto);

    /**
     * 修改模型。
     */
    void update(AiModelDTO dto);

    /**
     * 删除模型（逻辑删除），并失效缓存与工厂实例。
     */
    void delete(Long id);
}