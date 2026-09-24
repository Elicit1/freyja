package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 连续镜头组实体 drama_shot_group。
 * 承载一段连续动作链、对白或连续情绪事件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_shot_group")
public class DramaShotGroup extends BaseEntity {

    /** 归属短剧ID */
    private Long dramaId;

    /** 归属剧集ID */
    private Long episodeId;

    /** 归属场次ID (drama_scene.id) */
    private Long sceneId;

    /** 镜头组序号 (1, 2, 3...) */
    private Integer groupNo;

    /** 镜头组名称/连续叙事动作描述 (如: 葛明进入办公室并发现杜宁) */
    private String name;

    /** 导演意图/叙事目的/戏剧张力目标 */
    private String purpose;

    /** 显示排序 */
    private Integer sortOrder;
}
