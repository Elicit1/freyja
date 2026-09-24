package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 情景场次实体 drama_scene。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_scene")
public class DramaScene extends BaseEntity {

    /** 归属短剧ID */
    private Long dramaId;

    /** 归属剧集ID */
    private Long episodeId;

    /** 场次序号 (第N场) */
    private Integer sceneNo;

    /** 场次名称 (如: 场次1-酒店大堂主角受辱) */
    private String name;

    /** 绑定的环境场景资产ID (res_scene.id) */
    private Long resSceneId;

    /** 空间类型 (INDOOR/OUTDOOR/STUDIO/VIRTUAL) */
    private String sceneType;

    /** 时间时段 (DAY/NIGHT/SUNSET/DAWN) */
    private String timeOfDay;

    /** 天气/氛围 (RAINY/NEON/MOODY...) */
    private String weatherAtmosphere;

    /** 地点补充说明 */
    private String locationName;

    /** 场次剧情摘要 */
    private String summary;

    /** 本场剧本台词与动作描述 */
    private String scriptContent;

    /** 显示排序 */
    private Integer sortOrder;
}
