package com.astra.freyja.dto.drama;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

/** AI-derived speech, read-only to the creator; scriptContent remains the authoring source. */
@Data
public class ShotSpeechLine {
    public enum Kind { ON_SCREEN_DIALOGUE, OFF_SCREEN_DIALOGUE, VOICEOVER, INNER_MONOLOGUE }

    @JsonPropertyDescription("声音类型：ON_SCREEN_DIALOGUE、OFF_SCREEN_DIALOGUE、VOICEOVER、INNER_MONOLOGUE")
    private Kind kind;
    @JsonPropertyDescription("明确的角色资产 ID；旁白或无法确定身份时为 null，严禁按出场列表顺序猜测")
    private Long characterId;
    @JsonPropertyDescription("剧本明确的说话人名称；匿名旁白可为 null")
    private String speakerName;
    @JsonPropertyDescription("从 scriptContent 提取的原文台词，逐字保留原语言，不翻译或改写")
    private String text;
    @JsonPropertyDescription("AI 明确识别的原声语言，例如 Chinese、English；不得由 Java 猜测")
    private String language;
    @JsonPropertyDescription("该台词在最终视频提示词中的完整 <d>...</d> 标签，必须逐字包含 text")
    private String dialogueTag;
    @JsonPropertyDescription("明确使用的音色参考或本镜授权录音编号 Audio N；不用音频参考时为 null")
    private Integer audioIndex;
}
