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
    @Builder.Default
    private List<VideoManifestItem> videos = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VideoManifestItem {
        private Integer videoIndex;
        private String referenceId;
        private String name;
        private String sourceType;
        private Long sourceShotId;
        private Long sourceVideoTakeId;
        private BigDecimal startSeconds;
        private BigDecimal endSeconds;
        private Boolean includeAudio;
        private String referenceDescription;
    }

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
        private Long characterId;
        private Long lookId;
        private String entityName;
        private String usageRole; // For KEYFRAME: one of the seven KeyImageRole values
        private String referenceRole; // IDENTITY / LOOK / COMBINED, independent of visibility
        private String description; // 完整的原著/资产文字描述 (外貌、场景、道具材质等)
        private String imageUrl;
        private String bindingMode;
        private String sourceSlot;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AudioManifestItem {
        /** 1-based index (e.g. 1 -> Audio 1) */
        private Integer audioIndex;
        private String referenceId;
        private Long audioId;
        /** 音频素材库 ID，与配音中心 audioId 分开。 */
        private Long resAudioId;
        private String audioKind;
        private Long characterId;
        private String characterName;
        private String referenceType; // fully_copy, partially_copy, reference, weak_reference
        private String referenceDescription;
        private String language; // Chinese, English, etc.
        private String text; // 原生台词
        private String audioUrl;
        private BigDecimal duration;
    }

    /**
     * 格式化输出为大模型提示词注入的结构化素材清单块。
     */
    public String toPromptContext() {
        if ((pictures == null || pictures.isEmpty()) && (audios == null || audios.isEmpty()) && (videos == null || videos.isEmpty())) {
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
                if ("ASSET_LINK".equals(pic.getBindingMode())) {
                    sb.append("- 场景资产引用: ").append(pic.getSourceId()).append(" / ").append(pic.getSourceSlot()).append("\n");
                    sb.append("- 此槽位按文字设定预留，渲染前必须补齐对应图片；提示词 AI 看不到图片，不得描述为已观察到的图片内容。\n");
                }
                if (pic.getCharacterId() != null) {
                    sb.append("- 关联人物 ID: ").append(pic.getCharacterId()).append("\n");
                }
                if (pic.getLookId() != null) {
                    sb.append("- 关联造型 ID: ").append(pic.getLookId()).append("\n");
                }
                if (StringUtils.isNotBlank(pic.getReferenceRole())) {
                    sb.append("- 人物设定约束: ").append(pic.getReferenceRole()).append("\n");
                }
                if ("NON_SUBJECT".equals(pic.getUsageRole())) {
                    sb.append("- 出镜约束: 第一人称视角/画外人物，仅作设定参考，不据此安排人物出镜。\n");
                }
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
                sb.append(String.format("- H3 音频参考关系: %s\n", aud.getReferenceType()));
                sb.append(String.format("- 发音语言: %s\n", StringUtils.defaultIfBlank(aud.getLanguage(), "未指定，不得由服务端猜测")));
                if (aud.getCharacterId() != null) sb.append("- 明确绑定的角色 ID: ").append(aud.getCharacterId()).append("\n");
                if (StringUtils.isNotBlank(aud.getText())) {
                    sb.append(String.format("- %s: %s\n", "创作者提供的源录音文本（仅元数据；是否复用须服从参考关系、参考说明与当前剧本）", aud.getText().trim()));
                }
                if (StringUtils.isNotBlank(aud.getReferenceDescription())) sb.append("- 参考说明: ").append(aud.getReferenceDescription()).append("\n");
                if (aud.getResAudioId() != null) sb.append("- 音频素材 ID: ").append(aud.getResAudioId()).append("\n");
                if (aud.getAudioKind() != null) sb.append("- 素材种类: ").append(aud.getAudioKind()).append("\n");
                if (aud.getAudioId() != null) sb.append("- 配音中心音频 ID: ").append(aud.getAudioId()).append("\n");
                if (aud.getDuration() != null) {
                    sb.append(String.format("- 音频时长: %s 秒\n", aud.getDuration().toPlainString()));
                }
                sb.append("\n");
            }
        }

        if (videos != null) for (VideoManifestItem video : videos) {
            sb.append("--- Video ").append(video.getVideoIndex()).append(" ---\n")
                    .append("- 标签引用: <Video ").append(video.getVideoIndex()).append(">\n")
                    .append("- 素材名称（仅名称，不代表视频内容）: ").append(StringUtils.defaultString(video.getName())).append("\n")
                    .append("- 稳定素材标识: ").append(video.getReferenceId()).append("\n")
                    .append("- 来源类型 / 分镜 / Take: ").append(video.getSourceType()).append(" / ").append(video.getSourceShotId()).append(" / ").append(video.getSourceVideoTakeId()).append("\n")
                    .append("- 参考范围（秒，独立于输出时长）: ").append(video.getStartSeconds() == null ? BigDecimal.ZERO : video.getStartSeconds())
                    .append(" ～ ").append(video.getEndSeconds() == null ? "源视频结尾" : video.getEndSeconds()).append("\n")
                    .append("- 视频原音作为条件: ").append(Boolean.TRUE.equals(video.getIncludeAudio()) ? "开启" : "关闭").append("\n")
                    .append("- 创作者明确参考说明: ").append(StringUtils.defaultIfBlank(video.getReferenceDescription(), "未提供，不得自行推断视频内容或保留意图")).append("\n");
        }
        return sb.toString();
    }

    /** Explicit creator-selected visibility; never infer POV from names or prose. */
    public String toCharacterReferenceGuidance(boolean referenceMode) {
        StringBuilder sb = new StringBuilder();
        if (pictures == null) return "";
        for (PictureManifestItem pic : pictures) {
            if (pic == null || !"NON_SUBJECT".equals(pic.getUsageRole())) continue;
            if (sb.isEmpty()) {
                sb.append("\n【人物参考图的出镜约束】\n");
                sb.append("NON_SUBJECT 是创作者显式选择的本系统参考用途，不是 H3 官方标签或保留关系标记。人物资产绑定、外貌设定和参考图的存在均不代表该人物必须出镜。\n");
            }
            if (referenceMode) {
                sb.append("<Picture ").append(pic.getPictureIndex()).append(">");
            } else {
                sb.append("人物设定参考（不属于 FL2VA 的 Picture 1/2 首尾帧槽位）");
            }
            sb.append("（").append(StringUtils.defaultString(pic.getEntityName(), "未命名人物"))
                    .append("）: NON_SUBJECT，只提供第一人称视角人物或画外人物的身份/造型上下文。")
                    .append("不得为该画外人物建立可见人物 <Subject N>，不得为了展示设定图添加正脸、全身、第三人称外拍、镜面或其他反射。")
                    .append("如果当前分镜明确要求第一人称视角，摄影机必须保持该人物的主观视点；不能仅凭此用途自行改变镜头视角。")
                    .append("只有剧本明确可见的手、衣袖或其他局部才使用对应设定，禁止新增身体局部或把设定图的姿势、表情、背景带入剧情。")
                    .append(referenceMode ? "可以按实际编号说明来源，但不得把该图声明为首帧、尾帧、关键帧或构图锚点。"
                            : "该设定图只指导静态首尾帧生图中的明确可见局部，不得在 videoPrompt 中占用或新增 Picture 编号；videoPrompt 的 Picture 1/2 始终是实际首尾帧。")
                    .append("画外对白仍按原说话人和已提供的 Audio/(Sx) 关系绑定，不得为绑定音频而虚构可见人物 Subject。\n");
        }
        return sb.toString();
    }

    /** Per-picture system instructions, after the selected role has been resolved. */
    public String toSystemRoleGuidance() {
        StringBuilder sb = new StringBuilder("\n【本次关键图 Picture 的用途约束】\n");
        sb.append("以下逐图规则优先于模板中笼统的关键图定格或参考图构图规则。KEYFRAME 只是本系统的资产来源类型；目标视频中的用途由下列逐图角色决定。提示词 AI 未收到图片像素，只能依据提供的文字事实编写提示词。\n");
        if (videos != null && !videos.isEmpty()) sb.append("本次包含有序视频条件，从 <Video 1> 到 <Video ").append(videos.size()).append(">。保持六段结构，在 subject_definitions、retention_analysis 与 detailed_description 中按创作者说明建立各段视频与 Picture/Audio 的关系。提示词 AI 未收到视频像素，不能依据文件名编造动作、运镜或视频内容；缺少说明时标记未指定并由创作者补充。视频片段时长与输出时长独立。原音关闭时不得引用源片声音；开启也不代表允许复用背景音乐，应遵守创作者音乐要求。不要承诺逐帧复刻或引入未经声明的参考强度参数。\n");
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
