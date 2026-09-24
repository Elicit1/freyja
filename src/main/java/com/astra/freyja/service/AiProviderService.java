package com.astra.freyja.service;

import com.astra.freyja.dto.AiProviderDTO;
import com.astra.freyja.dto.AiProviderQuery;
import com.astra.freyja.dto.AiProviderVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * AI 提供商管理服务。
 */
public interface AiProviderService {

    /**
     * 分页查询提供商，apiKey 掩码返回。
     */
    Page<AiProviderVO> page(AiProviderQuery query);

    /**
     * 提供商详情，apiKey 掩码返回。
     */
    AiProviderVO getById(Long id);

    /**
     * 启用中的提供商列表（优先读 Redis 缓存）。
     */
    List<AiProviderVO> listEnabled();

    /**
     * 新增提供商，apiKey 加密落库。
     */
    void create(AiProviderDTO dto);

    /**
     * 修改提供商，apiKey 留空表示保持不变。
     */
    void update(AiProviderDTO dto);

    /**
     * 删除提供商（逻辑删除），并失效缓存与工厂实例。
     */
    void delete(Long id);

    /**
     * 连通性测试：经工厂构建模型并发送极简请求。
     */
    void test(Long providerId, String modelCode);
}