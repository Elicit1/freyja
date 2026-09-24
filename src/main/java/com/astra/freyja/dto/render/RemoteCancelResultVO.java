package com.astra.freyja.dto.render;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 远程 AI 网关及 ComfyUI 渲染任务取消响应结果 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteCancelResultVO implements Serializable {

    /** 是否成功取消并终止任务计算 */
    private boolean success;

    /** 响应提示信息 */
    private String message;

    /** 详细状态代码: CANCELLED(已取消/中断), QUEUED_DELETED(排队已出队), ALREADY_FINISHED(已渲染完成), NOT_FOUND(未找到任务), FAILED(取消失败) */
    private String detailStatus;

    /** 实际执行中断的计算节点 ID 列表 */
    private List<String> interruptedNodes;

    /** 实际被取消或删除的 ComfyUI Prompt ID 列表 */
    private List<String> cancelledPromptIds;
}
