package com.astra.freyja.util;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.ShotRefImageDTO;
import com.astra.freyja.entity.ResScene;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Resolves scene links without mutating saved drafts or renumbering Picture slots. */
public final class SceneImageReferenceResolver {
    private SceneImageReferenceResolver() {}

    public static List<ShotRefImageDTO> resolve(List<ShotRefImageDTO> images, Long dramaId,
                                               Function<Long, ResScene> lookup, boolean freeze) {
        return resolve(images, dramaId, lookup, freeze, true);
    }

    /** Keep an unavailable slot editable without exposing another project's asset image. */
    public static List<ShotRefImageDTO> preview(List<ShotRefImageDTO> images, Long dramaId,
                                               Function<Long, ResScene> lookup) {
        return resolve(images, dramaId, lookup, false, false);
    }

    private static List<ShotRefImageDTO> resolve(List<ShotRefImageDTO> images, Long dramaId,
                                                Function<Long, ResScene> lookup, boolean freeze, boolean requireAvailable) {
        List<ShotRefImageDTO> result = new ArrayList<>();
        if (images == null) return result;
        for (int index = 0; index < images.size(); index++) {
            if (images.get(index) == null) {
                if (freeze) throw new BizException(400, "Picture " + (index + 1) + " 参考图配置为空");
                result.add(null);
                continue;
            }
            ShotRefImageDTO image = new ShotRefImageDTO();
            BeanUtils.copyProperties(images.get(index), image);
            if (image.getBindingMode() != null && !List.of("ASSET_LINK", "SNAPSHOT").contains(image.getBindingMode()))
                throw new BizException(400, "Picture " + (index + 1) + " 图片绑定方式无效");
            if ("ASSET_LINK".equals(image.getBindingMode())) {
                ShotImageReferenceValidator.validate(image, index);
                ResScene scene = lookup.apply(image.getSourceId());
                if (scene == null || !Integer.valueOf(1).equals(scene.getStatus())
                        || !(Objects.equals(scene.getDramaId(), 0L)
                        || dramaId != null && Objects.equals(scene.getDramaId(), dramaId))) {
                    if (!requireAvailable) {
                        image.setImageUrl(null);
                        result.add(image);
                        continue;
                    }
                    throw new BizException(400, "Picture " + (index + 1) + " 的场景不存在、已停用或不属于当前短剧/公共库");
                }
                image.setImageUrl("SCENE_REFERENCE".equals(image.getSourceSlot())
                        ? scene.getReferenceImageUrl() : scene.getCoverUrl());
            }
            if (freeze) {
                if (StringUtils.isBlank(image.getImageUrl())) {
                    throw new BizException(400, "Picture " + (index + 1) + "「"
                            + StringUtils.defaultString(image.getName(), "参考图") + "」尚未补图，请先完善场景资产图片");
                }
                image.setBindingMode("SNAPSHOT");
            }
            result.add(image);
        }
        return result;
    }

    /** Text facts shared by director planning, prompt writing and reference manifests. */
    public static String sceneContext(ResScene scene) {
        if (scene == null) return "";
        StringBuilder text = new StringBuilder();
        append(text, "场景名称", scene.getName());
        append(text, "中文场景设定（空间事实依据）", scene.getDescription());
        append(text, "场景生图 Prompt（视觉表现参考）", scene.getScenePrompt());
        append(text, "空间类型", scene.getSceneType());
        append(text, "时间时段", scene.getTimeOfDay());
        append(text, "天气与氛围", scene.getWeatherAtmosphere());
        return text.toString();
    }

    private static void append(StringBuilder text, String label, String value) {
        if (StringUtils.isNotBlank(value)) text.append("- ").append(label).append(": ").append(value.trim()).append('\n');
    }
}
