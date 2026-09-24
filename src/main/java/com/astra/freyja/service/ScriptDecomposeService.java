package com.astra.freyja.service;

import com.astra.freyja.dto.script.DecomposedEpisodeVO;
import com.astra.freyja.dto.script.ScriptDecomposeCommitDTO;
import com.astra.freyja.dto.script.ScriptDecomposeRequestDTO;
import com.astra.freyja.dto.script.ScriptDecomposeResultVO;

/**
 * 剧本智能拆解与角色提取服务接口。
 */
public interface ScriptDecomposeService {
    String startDecomposeTask(ScriptDecomposeRequestDTO request);

    /**
     * 基于 Spring AI 调用大模型进行剧本智能拆解、角色提取、分集分场及分镜对白生成。
     *
     * @param request 拆解请求参数
     * @return 结构化拆解结果 (供前端审查看板展示)
     */
    ScriptDecomposeResultVO decompose(ScriptDecomposeRequestDTO request);

    /**
     * 流式 (SSE) 调用大模型进行剧本拆解：实时推送 Token 片元，并在完成时推送最终结构化结果。
     *
     * @param request 拆解请求参数
     * @return SseEmitter 响应式推送流
     */
    org.springframework.web.servlet.mvc.method.annotation.SseEmitter decomposeStream(ScriptDecomposeRequestDTO request);

    /**
     * 将审核确认后的剧本拆解结果一键持久化至数据库 (短剧、剧集、场次、分镜及角色/场景资产)。
     *
     * @param commitDTO 提交入库参数
     * @return 持久化后的短剧 ID
     */
    Long commit(ScriptDecomposeCommitDTO commitDTO);

    /**
     * 单集/单段落深度细化拆解（可选：针对某具体剧集或片段生成详尽分镜台词流）。
     *
     * @param request 拆解请求参数
     * @return 细化后的剧集分镜数据
     */
    DecomposedEpisodeVO decomposeEpisode(ScriptDecomposeRequestDTO request);

    /**
     * 针对失败的单个 Worker 分段进行局部提示词微调并重新执行合并。
     *
     * @param taskId 父拆解任务 ID
     * @param retryDTO 微调重试参数 (分段编号、自定义提示词、修改后的小说文本、可选模型)
     * @return 更新并重新合并后的完整预览结果
     */
    ScriptDecomposeResultVO retryWorker(Long taskId, com.astra.freyja.dto.script.WorkerRetryDTO retryDTO);
}
