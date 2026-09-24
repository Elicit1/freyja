package com.astra.freyja.dto.video;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分镜视频选择器选项视图对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShotVideoSourceOptionVO {

    private String dramaId;
    private String dramaTitle;
    private String episodeId;
    private Integer episodeNo;
    private String episodeTitle;
    private String sceneId;
    private Integer sceneNo;
    private String sceneName;
    private String shotGroupId;
    private String shotId;
    private Integer shotNo;
    private String shotName;
    private BigDecimal duration;
    private String generationMode;
    private String videoUrl;
    private String posterUrl;
    private String currentVideoTakeId;
    private Integer videoTakeCount;
    private LocalDateTime videoUpdatedTime;
}
