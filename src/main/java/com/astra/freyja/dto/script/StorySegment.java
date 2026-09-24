package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 剧情分段实体 (StorySegment)。
 * 由 Planner AI 在全局宏观理解整章剧情后以完整戏剧事件为单位切分，严禁包含 Shot/ShotGroup/Camera/Duration/Prompt。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StorySegment implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 分段标识 (如 SEG001) */
    @JsonPropertyDescription("分段标识 (如 SEG001, SEG002)")
    private String id;

    /** 顺序序号 (1, 2, 3...) */
    @JsonPropertyDescription("顺序序号 (从 1 开始递增)")
    private Integer sequence;

    /** 起始句锚点 (首句前 10~20 字，用于精准锚定原文，严禁输出全文) */
    @JsonPropertyDescription("分段起始处的第一句话 (前 10~20 字，用于精准锚定原文，严禁输出全文)")
    private String startSnippet;

    /** 结束句锚点 (末句后 10~20 字，用于精准锚定原文，严禁输出全文) */
    @JsonPropertyDescription("分段结束处的最后一句话 (后 10~20 字，用于精准锚定原文，严禁输出全文)")
    private String endSnippet;

    /** 起始字符偏移量 (在全文章节中的 offset) */
    @JsonPropertyDescription("分段在原文章节中的起始字符索引 offset (0-based)")
    private Integer startOffset;

    /** 结束字符偏移量 (在全文章节中的 offset) */
    @JsonPropertyDescription("分段在原文章节中的结束字符索引 offset")
    private Integer endOffset;

    /** 剧情事件标题 (如: 进入办公室) */
    @JsonPropertyDescription("剧情事件简明中文标题 (如: 办公室谈判、地下车库对峙)")
    private String title;

    /** 剧情事件核心概要 (如: 葛明进入办公室并发现杜宁) */
    @JsonPropertyDescription("剧情事件核心内容概要")
    private String summary;

    /** 出场角色中文姓名列表 (必须填写中文人名，如 [\"苏清雪\", \"苏明宇\"]，严禁输出 char_001 等编号) */
    @JsonPropertyDescription("出场角色中文姓名列表 (必须直接填写具体中文人名，如 [\"苏清雪\", \"苏明宇\"]，严禁输出 char_001 等英文编号)")
    @Builder.Default
    private List<String> characterIds = new ArrayList<>();

    /** 环境场景中文地点列表 */
    @JsonPropertyDescription("发生地点/场景中文名称列表 (如 [\"顶层总裁办公室\", \"地下车库\"])")
    @Builder.Default
    private List<String> locationIds = new ArrayList<>();

    /** 绑定的权威场景编号或名称 (如 SC001 或 顶层总裁办公室) */
    @JsonPropertyDescription("绑定的权威场景编号或场景名称 (如 SC001)")
    private String sceneId;

    /** 关键道具列表 */
    @JsonPropertyDescription("关键道具列表 (如 [\"离婚协议书\", \"黑色手提箱\", \"斑驳大圆桌\"])")
    @Builder.Default
    private List<String> importantPropIds = new ArrayList<>();

    /** 涉及的道具ID列表 */
    @JsonPropertyDescription("涉及的道具ID列表 (如 [\"PR001\", \"PR002\"])")
    @Builder.Default
    private List<String> propIds = new ArrayList<>();

    /** 叙事目的与戏剧张力 */
    @JsonPropertyDescription("叙事目的与戏剧张力")
    private String narrativePurpose;

    /** 切片原文 (由 Java 根据 offset 与锚点在本地截取填充，严禁 AI 输出以节约 90% Output Token) */
    @JsonIgnore
    private String rawText;
}
