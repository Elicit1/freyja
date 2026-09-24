package com.astra.freyja.dto.script;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 失败 Worker 单段微调重试请求 DTO (WorkerRetryDTO)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkerRetryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待重试的分段编号 (如 "SEG002") */
    private String segmentId;

    /** 用户自定义补充指导词 / 避险提示词 (如 "请避开直接血腥描写，采用侧拍光影反差表现击倒") */
    private String customInstructions;

    /** 用户脱敏或微调后的小说分段文本覆盖 (若为空则沿用原小说文本) */
    private String rawTextOverride;

    /** 指定重试所使用的 AI 模型代码 (可选，如切换至 deepseek-chat, gpt-4o 等) */
    private String modelCodeOverride;

    /** 指定重试所使用的 AI 提供商 ID (可选) */
    private Long providerIdOverride;

    /** Optional override for this Worker retry; null inherits the parent task policy. */
    private ScriptSkillStagePolicy skillPolicyOverride;
}
