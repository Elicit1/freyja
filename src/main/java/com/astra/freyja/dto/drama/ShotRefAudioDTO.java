package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 分镜参考音频对象 (上限 3 段, 单段 2-15s, 总长 ≤ 15s)。
 * 参考关系严格使用 H3 四种标记，不接受平台来源分类或旧用途字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShotRefAudioDTO {

    /** 唯一标识 (前端生成或自增) */
    private String id;

    /** 配音中心音频 ID；直接引用外部音频时可以不填写。 */
    private Long audioId;

    /** 创作者明确关联的角色；配音中心音频必须与其所属角色一致。 */
    private Long characterId;

    /** 说话人名称 */
    private String characterName;

    /** 音频 URL (MinIO URL) */
    private String audioUrl;

    /** 探测的音频时长 (秒, 2~15)；未知为 null，渲染前验证实际媒体 */
    private BigDecimal duration;

    /** 音频名称/文件名/标题 */
    private String name;

    /** 台词或文本内容 */
    private String text;

    /** fully_copy / partially_copy / reference / weak_reference，必填。 */
    private String referenceType;

    /** 明确说明参考或复用哪些内容，不从文件来源或标签推断。 */
    private String referenceDescription;

    /** 发音语言选择: zh/en/ja/other；未声明保持 null。 */
    private String language;

    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("不支持的参考音频字段: " + name + "；请重新选择音频并指定四种 referenceType 之一");
    }
}
