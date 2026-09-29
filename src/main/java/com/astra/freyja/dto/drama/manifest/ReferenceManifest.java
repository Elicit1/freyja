package com.astra.freyja.dto.drama.manifest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * MiniMax H3 结构化参考素材清单 (ReferenceManifest)。
 * 作为提示词中 <Picture N> / <Audio N> 编号与底层节点上传顺序的唯一定义与唯一真源。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceManifest {

    @Builder.Default
    private List<PictureManifestItem> pictures = new ArrayList<>();

    @Builder.Default
    private List<AudioManifestItem> audios = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PictureManifestItem {
        /** 1-based index (e.g. 1 -> Picture 1) */
        private Integer pictureIndex;
        private String referenceId;
        private String sourceType; // SCENE, CHARACTER, PROP, UPLOAD, KEYFRAME
        private Long sourceId;
        private String entityName;
        private String usageRole; // For KEYFRAME: one of the seven KeyImageRole values
        private String description; // 完整的原著/资产文字描述 (外貌、场景、道具材质等)
        private String imageUrl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AudioManifestItem {
        /** 1-based index (e.g. 1 -> Audio 1) */
        private Integer audioIndex;
        private String referenceId;
        private String sourceType; // TTS, UPLOAD, CHARACTER
        private Long characterId;
        private String characterName;
        private String usageMode; // DIALOGUE_REUSE, VOICE_TIMBRE, BGM_REUSE, BGM_STYLE, SFX_REUSE, SFX_REFERENCE, RHYTHM_REFERENCE
        private String language; // Chinese, English, etc.
        private String text; // 原生台词
        private String audioUrl;
        private BigDecimal duration;
    }

    /**
     * 格式化输出为大模型提示词注入的结构化素材清单块。
     */
    public String toPromptContext() {
        if ((pictures == null || pictures.isEmpty()) && (audios == null || audios.isEmpty())) {
            return "【MiniMax H3 有序参考素材清单】: 本镜头未提供外部参考图或参考音频。\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【MiniMax H3 有序参考素材清单】\n");
        sb.append("（注意：以下 Picture 与 Audio 编号与底层生成模型输入槽位严格从 1 开始按顺序完全绑定，生成提示词时必须严格使用以下标签且不得跳号或虚构不存在的编号）\n\n");

        if (pictures != null && !pictures.isEmpty()) {
            sb.append("--- 视觉参考图清单 (Picture 1..").append(pictures.size()).append(") ---\n");
            for (PictureManifestItem pic : pictures) {
                sb.append(String.format("Picture %d:\n", pic.getPictureIndex()));
                sb.append(String.format("- 标签引用: <Picture %d>\n", pic.getPictureIndex()));
                sb.append(String.format("- 实体类型: %s\n", pic.getSourceType()));
                sb.append(String.format("- 实体名称: %s\n", StringUtils.defaultString(pic.getEntityName(), "未命名")));
                sb.append(String.format("- 建议角色定位: %s\n", StringUtils.defaultString(pic.getUsageRole(), "IDENTITY")));
                if (StringUtils.isNotBlank(pic.getDescription())) {
                    sb.append(String.format("- 实体特征与保留基准: %s\n", pic.getDescription().trim()));
                }
                if ("KEYFRAME".equalsIgnoreCase(pic.getSourceType())) {
                    KeyImageRole role = KeyImageRole.fromCode(pic.getUsageRole());
                    sb.append("- 关键图用途: ").append(role != null ? role.name() : "未指定").append("；这不是环境场景资产。\n");
                    sb.append("- 文字依据只能来自资产 Prompt（优先）或 description（回退）；提示词 AI 看不到图片本身。\n");
                }
                sb.append("\n");
            }
        }

        if (audios != null && !audios.isEmpty()) {
            sb.append("--- 听觉参考音频清单 (Audio 1..").append(audios.size()).append(") ---\n");
            for (AudioManifestItem aud : audios) {
                sb.append(String.format("Audio %d:\n", aud.getAudioIndex()));
                sb.append(String.format("- 标签引用: <Audio %d>\n", aud.getAudioIndex()));
                sb.append(String.format("- 对应说话人/实体: %s\n", StringUtils.defaultString(aud.getCharacterName(), "环境音/未指定")));
                sb.append(String.format("- 音频用途模式: %s\n", StringUtils.defaultString(aud.getUsageMode(), "VOICE_TIMBRE")));
                sb.append(String.format("- 发音语言: %s\n", StringUtils.defaultString(aud.getLanguage(), "Chinese")));
                if (StringUtils.isNotBlank(aud.getText())) {
                    sb.append(String.format("- 关联台词/源文本: %s\n", aud.getText().trim()));
                }
                if (aud.getDuration() != null) {
                    sb.append(String.format("- 音频时长: %s 秒\n", aud.getDuration().toPlainString()));
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /** Per-picture system instructions, after the selected role has been resolved. */
    public String toSystemRoleGuidance() {
        StringBuilder sb = new StringBuilder("\n【本次关键图 Picture 的用途约束】\n");
        sb.append("以下逐图规则优先于模板中笼统的关键图定格或参考图构图规则。KEYFRAME 只是本系统的资产来源类型；目标视频中的用途由下列逐图角色决定。提示词 AI 未收到图片像素，只能依据提供的文字事实编写提示词。\n");
        sb.append("本次 Ref2VA 输出按官方 full-reference 语言规则：六段正文使用英文，但 <d> 内的对白、歌词和画面可见文字保留原语言；如旧模板要求把非英文台词翻译为英文，以本条为准。\n");
        sb.append("按官方 full-reference 格式：具体帧或构图/故事板/镜头规划锚点在 subject_definitions 中独立定义 <Picture N>；summary 根据实际用途使用 [keyframe completion] 或 [reference generation]，并与其他任务类型去重组合。retention_analysis 按该 Picture 的已定义用途说明保留关系；detailed_description 只在该用途实际生效处引用。仅凭关键图来源不得虚构人物、时间点、Shot 映射或画面细节。\n");
        if (pictures != null) {
            for (PictureManifestItem pic : pictures) {
                if (pic == null || !"KEYFRAME".equalsIgnoreCase(pic.getSourceType())) continue;
                KeyImageRole role = KeyImageRole.fromCode(pic.getUsageRole());
                if (role != null) sb.append(role.systemGuidance(pic.getPictureIndex())).append("\n");
            }
        }
        return sb.toString();
    }
}
