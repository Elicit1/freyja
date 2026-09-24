package com.astra.freyja.service.prompt;

import com.astra.freyja.dto.script.DecomposedCharacterVO;
import com.astra.freyja.dto.script.DecomposedSceneVO;
import com.astra.freyja.dto.script.DecomposedShotVO;

import java.util.List;
import java.util.Map;

/**
 * 镜头生图与视频提示词本地组装构建器 (PromptBuilder)。
 * 严禁调用 AI，纯 Java 将角色资产主数据、场景主数据、镜头动作、运镜机位、连续性提示词与视觉风格组装为标准视频提示词。
 */
public interface PromptBuilder {

    /**
     * 组装单个分镜的正向生成提示词 (Positive Video/Image Prompt - 适用于 T2I 首帧生图)
     *
     * @param shot 分镜镜头对象
     * @param characters 涉及的角色列表 (含外貌、服装提示词)
     * @param scene 所属环境场景 (含场景 Prompt、光影 Prompt)
     * @param stylePreset 画面视觉风格预设 (如 cinematic-realism, 3d-animation)
     * @return 标准化正向生图提示词字符串
     */
    default String buildPositivePrompt(DecomposedShotVO shot,
                                       List<DecomposedCharacterVO> characters,
                                       DecomposedSceneVO scene,
                                       String stylePreset) {
        return buildPositivePrompt(shot, characters, scene, null, stylePreset);
    }

    /**
     * 组装单个分镜的正向生成提示词 (包含关键道具支持)
     */
    String buildPositivePrompt(DecomposedShotVO shot,
                               List<DecomposedCharacterVO> characters,
                               DecomposedSceneVO scene,
                               List<com.astra.freyja.dto.script.DecomposedPropVO> props,
                               String stylePreset);

    /**
     * 组装专为 I2V (图生视频) 优化的极简动态运镜提示词 (Video Motion Prompt - 适用于可灵/Runway/Hailuo/Sora)
     * 仅聚焦运镜机位、主体物理运动、光影粒子动态，剥离冗余静态角色外貌以杜绝混淆。
     *
     * @param shot 分镜镜头对象
     * @param scene 所属环境场景
     * @param stylePreset 画面视觉风格预设
     * @return 纯净动态视频提示词
     */
    String buildVideoPrompt(DecomposedShotVO shot,
                            DecomposedSceneVO scene,
                            String stylePreset);

    /**
     * 组装负向提示词 (Negative Prompt)
     *
     * @param stylePreset 视觉风格预设
     * @param customNegative 自定义负向词
     * @return 负向提示词字符串
     */
    String buildNegativePrompt(String stylePreset, String customNegative);
}
