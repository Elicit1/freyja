package com.astra.freyja.dto.script;

import lombok.Data;

import java.util.List;

/**
 * 剧本拆解结果确认入库 DTO。
 */
@Data
public class ScriptDecomposeCommitDTO {

    /** 关联的拆解任务 ID (用于新建短剧落库后回填绑定历史记录) */
    private Long taskId;

    /** 若为向现有短剧导入，则传 dramaId；若为空则新建短剧 */
    private Long dramaId;

    /** 短剧标题 */
    private String dramaTitle;

    /** 题材分类 (字典 drama_genre) */
    private String genre;

    /** 故事梗概 */
    private String synopsis;

    /** 画幅比例 (9:16 / 16:9 / 1:1 / 4:3，默认 9:16) */
    private String aspectRatio;

    /** 画面风格预设 (字典 drama_style_preset) */
    private String stylePreset;

    /** 视觉风格基调/导演风格指南（自然语言融入，如光影、色调、镜头质感） */
    private String styleTone;

    /**
     * 入库模式:
     * - APPEND_TO_EPISODE: 累计追加到已有集 (不分集模式，场次与镜头序号顺延接在已有集末尾)
     * - NEW_EPISODE: 作为新集数追加 (分集模式，新建一集)
     * - OVERWRITE_EPISODE: 覆盖更新指定集 (仅重写指定集)
     * - REPLACE_ALL: 全量重置/新建短剧
     */
    private String commitMode;

    /** 目标集数编号 (例如 1 代表第 1 集，2 代表第 2 集) */
    private Integer targetEpisodeNo;

    /** 可选：目标剧集 ID */
    private Long targetEpisodeId;

    /** 确认入库的角色列表 */
    private List<DecomposedCharacterVO> characters;

    /** 确认入库的场景列表 */
    private List<DecomposedSceneVO> scenes;

    /** 确认入库的道具列表 */
    private List<DecomposedPropVO> props;

    /** 确认入库的剧集、场次与分镜树 */
    private List<DecomposedEpisodeVO> episodes;
}
