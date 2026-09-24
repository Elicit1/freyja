package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 短剧下拉选项 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DramaOptionVO {

    private Long id;
    private String title;
    private String coverUrl;
    private String genre;
    private String aspectRatio;
    private String stylePreset;
    private String styleTone;
    private String status;
}
