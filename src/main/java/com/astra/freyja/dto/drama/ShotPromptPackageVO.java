package com.astra.freyja.dto.drama;

import com.astra.freyja.dto.drama.manifest.ReferenceManifest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分镜提示词任务导出包 VO。
 * 包含系统角色设定、用户任务、合并完整 Prompt、参考素材清单与上下文指纹。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShotPromptPackageVO {

    /** 生成模式: REFERENCE_MODE(多模态参考图与音频模式) / FIRST_LAST_FRAME(首尾帧过渡模式) */
    private String generationMode;

    /** 独立 System Prompt (角色设定、MiniMax H3 六段式/首尾帧规范等) */
    private String systemPrompt;

    /** 独立 User Prompt (镜头事实、角色造型、道具文字设定、参考素材清单等) */
    private String userPrompt;

    /** 专供外部 AI 聊天窗口单输入框的完整合并 Prompt */
    private String combinedPrompt;

    /** 输出 JSON Schema 格式说明 */
    private String outputFormat;

    /** 物理参考素材清单对齐元数据 */
    private ReferenceManifest referenceManifest;

    /** 模板版本号 (如 minimax-h3-ref2va-v1, minimax-h3-fl2va-v1) */
    private String templateVersion;

    /** 上下文指纹 (SHA-256，用于检测外部 AI 粘贴时镜头是否已变动) */
    private String contextFingerprint;
}
