package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分镜参考图对象 (上限 9 张)。
 * 来源: 场景、人物造型、道具或自行上传。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShotRefImageDTO {

    /** 唯一标识 (前端生成或自增) */
    private String id;

    /** 来源类型: SCENE/CHARACTER_REFERENCE/CHARACTER/PROP/KEYFRAME/UPLOAD。CHARACTER 为旧兼容值。 */
    private String sourceType;

    /** 关联源资产 ID (如 res_scene.id / res_character_outfit.id / res_prop.id) */
    private Long sourceId;

    /** 人物参考图专用：图片所属角色 ID。 */
    private Long characterId;

    /** 人物参考图专用：图片所属视觉造型 ID，可为空。 */
    private Long lookId;

    /** 资产名称或标签 (如: 顶层办公室 / 主角日常装 / 核心道具) */
    private String name;

    /** 图片 URL (MinIO URL) */
    private String imageUrl;

    /**
     * 参考图角色定位 (MiniMax H3 / 视觉映射):
     * 保存时必填，必须精确匹配用途下拉选项，不接受自由文本或默认推断。
     * SUBJECT: 主体人物参考
     * NON_SUBJECT: 第一人称视角/画外人物设定参考，不据此安排人物出镜
     * SCENE: 场景布局
     * PROP: 道具外观
     * STYLE: 艺术风格基调
     * 身份/造型细分角色使用 referenceRole。
     * 关键图参考可使用七种 KeyImageRole 值。
     */
    private String usageRole;

    /** 人物参考图的细分约束角色：IDENTITY/LOOK/COMBINED/POSE/STYLE，与是否作为主体独立。 */
    private String referenceRole;
}
