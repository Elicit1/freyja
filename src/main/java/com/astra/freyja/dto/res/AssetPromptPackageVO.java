package com.astra.freyja.dto.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 资产提示词任务导出包 VO (用于 人物/角色、道具、场景 的 AI 提示词导出与手工通道)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetPromptPackageVO {

    /** 资产类型: CHARACTER / PROP / SCENE */
    private String assetType;

    /** 资产ID (可选) */
    private Long assetId;

    /** 资产名称 */
    private String assetName;

    /** 独立 System Prompt (角色设定、艺术画风与提示词规范) */
    private String systemPrompt;

    /** 独立 User Prompt (资产属性、细节描述、画风基调与 JSON Schema 格式说明) */
    private String userPrompt;

    /** 专供外部 AI 聊天窗口单输入框的完整合并 Prompt */
    private String combinedPrompt;

    /** 输出 JSON Schema 格式说明 */
    private String outputFormat;

    /** 模板版本号 (如 character-prompt-v1, prop-prompt-v1, scene-prompt-v1) */
    private String templateVersion;

    /** 上下文指纹 (SHA-256，用于检测外部 AI 粘贴时资产设定是否已变动) */
    private String contextFingerprint;
}
