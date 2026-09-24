package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 剧本拆解提取的单分镜镜头信息 VO（含台词说话人与对白）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecomposedShotVO {

    @JsonPropertyDescription("分镜序号，从1递增")
    private Integer shotNo;

    @JsonPropertyDescription("镜头编号标识，例如: S01-01")
    private String shotName;

    @JsonPropertyDescription("景别枚举；AUTO 表示尚未指定，由 Prompt AI 自主决定")
    private String shotType;

    @JsonPropertyDescription("运镜枚举；AUTO 表示尚未指定，由 Prompt AI 自主决定")
    private String cameraMovement;

    /** 是否由创作者主动锁定景别；历史值不应被自动视为锁定。 */
    private Boolean shotTypeLocked;

    /** 是否由创作者主动锁定运镜；历史值不应被自动视为锁定。 */
    private Boolean cameraMovementLocked;

    @JsonPropertyDescription("预估镜头时长(秒)，例如: 3.0")
    private Double duration;

    @JsonPropertyDescription("本镜头剧本文本 (Shot Script: 结合剧情原文，详实撰写该镜头的文学剧本台本，细致刻画角色表演动作与戏剧细节，供后续AI参考原文与剧本生成Prompt)")
    private String scriptContent;

    @JsonPropertyDescription("画面动作与视觉描述，详细描述人物动作、神态反应、光影与关键道具 (中文，供创作者审阅)")
    private String actionDescription;

    @JsonPropertyDescription("首帧画面纯英文静态视觉与构图描述 (Worker不再生成，置空供后续专属AI生成)")
    private String firstFrameVisual;

    @JsonPropertyDescription("本镜头的对白说话人姓名（必须与出场人物名称一致），若无台词则为null")
    private String dialogueSpeaker;

    @JsonPropertyDescription("本镜头的角色台词对白文本，若无台词则为null")
    private String dialogue;

    @JsonPropertyDescription("旁白/内心独白文本，若无则为null")
    private String voiceover;

    @JsonPropertyDescription("音效或环境音描述，例如: 大门撞击声，倒吸冷气声")
    private String soundEffect;

    @JsonPropertyDescription("首图主焦点角色姓名 (专供首帧生图精确加载单人外貌/参考图；若为单人/过肩镜头填正面主体人物姓名；全景空镜无焦点填 null)")
    private String primaryCharacter;

    @JsonPropertyDescription("次要/过肩角色姓名 (在首图中仅作为前景过肩虚焦背影或半出框剪影；无则为 null)")
    private String secondaryCharacter;

    @JsonPropertyDescription("本镜头画面中出现的角色姓名列表")
    private List<String> characterNames;

    @JsonPropertyDescription("归属连续镜头组标识，例如: G001")
    private String groupId;

    @JsonPropertyDescription("引用的场景资产编号，例如: SC001")
    private String sceneId;

    @JsonPropertyDescription("系统环境场景资产实际ID (res_scene.id)")
    private Long resSceneId;

    @JsonPropertyDescription("本镜头涉及的道具列表 (如 [\"PR001\", \"PR002\"])")
    private List<String> propIds;

    @JsonPropertyDescription("首帧生图正向提示词 (由 PromptBuilder 本地组装，适用于 T2I)")
    private String prompt;

    @JsonPropertyDescription("首帧生图专属提示词 (专供首帧 T2I 生图)")
    private String firstFramePrompt;

    @JsonPropertyDescription("尾帧生图专属提示词 (专供尾帧 T2I 生图)")
    private String endFramePrompt;

    @JsonPropertyDescription("视频动态运镜提示词 (I2V 视频生成 Prompt, 由 AI 依据文章语义直接产出，描述运镜/主体动作/对白音效/氛围，供 MiniMax H3 等视频模型)")
    private String videoPrompt;

    @JsonPropertyDescription("增强负向提示词")
    private String negativePrompt;
}
