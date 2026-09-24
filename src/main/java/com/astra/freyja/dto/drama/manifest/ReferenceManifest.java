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
        private String sourceType; // SCENE, CHARACTER, PROP, UPLOAD
        private Long sourceId;
        private String entityName;
        private String usageRole; // IDENTITY, SCENE_LAYOUT, PROP_APPEARANCE, STYLE, MOTION_KEYFRAME
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
}
