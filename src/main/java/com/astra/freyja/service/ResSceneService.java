package com.astra.freyja.service;

import com.astra.freyja.dto.res.ResSceneDTO;
import com.astra.freyja.dto.res.ResSceneOptionVO;
import com.astra.freyja.dto.res.ResSceneQuery;
import com.astra.freyja.dto.res.ResSceneVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * 场景环境资产管理服务。
 */
public interface ResSceneService {

    /**
     * 分页查询场景列表
     */
    Page<ResSceneVO> page(ResSceneQuery query);

    /**
     * 根据 ID 获取场景详情
     */
    ResSceneVO getById(Long id);

    /**
     * 新增场景
     */
    Long create(ResSceneDTO dto);

    /**
     * 修改场景
     */
    void update(ResSceneDTO dto);

    /**
     * 逻辑删除场景
     */
    void delete(Long id);

    /**
     * 获取场景下拉列表（用于分镜快速引用，支持按剧目过滤）
     */
    List<ResSceneOptionVO> options(Long dramaId);

    /**
     * AI 智能衍生场景专属提示词 (scenePrompt + negativePrompt)
     */
    com.astra.freyja.dto.res.ScenePromptDeriveVO derivePrompts(com.astra.freyja.dto.res.ScenePromptDeriveDTO dto);

    /**
     * AI 智能衍生场景专属提示词 (流式 SSE)
     */
    org.springframework.web.servlet.mvc.method.annotation.SseEmitter derivePromptsStream(com.astra.freyja.dto.res.ScenePromptDeriveDTO dto);

    /**
     * 构建场景提示词任务导出包 (专供外部 AI 手工生成通道复制使用)
     */
    com.astra.freyja.dto.res.AssetPromptPackageVO buildPromptPackage(com.astra.freyja.dto.res.ScenePromptDeriveDTO dto);

    /**
     * 解析并校验外部 AI 返回的场景提示词结果文本 (清洗 Markdown、校验字段并比对指纹)
     */
    com.astra.freyja.dto.res.ScenePromptValidationResult parseAndValidateDerivedPrompt(com.astra.freyja.dto.res.ScenePromptParseRequestDTO request);
}
