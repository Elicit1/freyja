package com.astra.freyja.service;

import com.astra.freyja.dto.res.PromptAssembleRequestDTO;
import com.astra.freyja.dto.res.PromptAssembleResultVO;

/**
 * 分镜 Prompt 动态组装与多模态控制栈调度服务。
 */
public interface PromptAssembleService {

    /**
     * 根据场景、角色及造型、分镜动作动态组装生成标准化 Prompt 及 LoRA/参考图映射
     *
     * @param request 组装请求参数
     * @return 包含 Positive/Negative Prompt、LoRA栈、参考图及摘要的组装结果
     */
    PromptAssembleResultVO assemble(PromptAssembleRequestDTO request);
}
