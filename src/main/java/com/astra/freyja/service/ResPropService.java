package com.astra.freyja.service;

import com.astra.freyja.dto.res.ResPropDTO;
import com.astra.freyja.dto.res.ResPropOptionVO;
import com.astra.freyja.dto.res.ResPropQuery;
import com.astra.freyja.dto.res.ResPropVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * 道具资产管理服务接口。
 */
public interface ResPropService {

    /**
     * 分页查询道具列表
     */
    Page<ResPropVO> page(ResPropQuery query);

    /**
     * 获取道具详情
     */
    ResPropVO getById(Long id);

    /**
     * 新增道具
     */
    Long create(ResPropDTO dto);

    /**
     * 修改道具
     */
    void update(ResPropDTO dto);

    /**
     * 删除道具
     */
    void delete(Long id);

    /**
     * 道具下拉选项
     */
    List<ResPropOptionVO> options(Long dramaId);

    /**
     * AI 智能衍生道具专属生图/视觉提示词 (propPrompt + negativePrompt)
     */
    com.astra.freyja.dto.res.PropPromptDeriveVO derivePrompts(com.astra.freyja.dto.res.PropPromptDeriveDTO dto);

    /**
     * AI 智能衍生道具专属生图/视觉提示词 (流式 SSE)
     */
    org.springframework.web.servlet.mvc.method.annotation.SseEmitter derivePromptsStream(com.astra.freyja.dto.res.PropPromptDeriveDTO dto);

    /**
     * 构建道具提示词任务导出包 (专供外部 AI 手工生成通道复制使用)
     */
    com.astra.freyja.dto.res.AssetPromptPackageVO buildPromptPackage(com.astra.freyja.dto.res.PropPromptDeriveDTO dto);

    /**
     * 解析并校验外部 AI 返回的道具提示词结果文本 (清洗 Markdown、校验字段并比对指纹)
     */
    com.astra.freyja.dto.res.PropPromptValidationResult parseAndValidateDerivedPrompt(com.astra.freyja.dto.res.PropPromptParseRequestDTO request);
}
