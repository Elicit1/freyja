package com.astra.freyja.util;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.ShotRefVideoDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;

public final class ShotVideoReferenceValidator {
    private ShotVideoReferenceValidator() {}

    public static List<ShotRefVideoDTO> parse(String value, ObjectMapper json) {
        if (value == null || value.isBlank()) return List.of();
        try {
            List<ShotRefVideoDTO> references = json.readValue(value, new TypeReference<>() {});
            validate(references);
            return references == null ? List.of() : references;
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(400, "参考视频配置无效，请重新选择"); }
    }

    public static void validate(List<ShotRefVideoDTO> references) {
        if (references == null) return;
        Set<String> ids = new java.util.HashSet<>();
        for (ShotRefVideoDTO ref : references) {
            if (ref == null || ref.getId() == null || ref.getId().isBlank() || ref.getId().length() > 100) fail("参考视频缺少稳定标识");
            if (!ids.add(ref.getId())) fail("参考视频标识不能重复");
            if (ref.getSourceType() == null || !Set.of("DIRECT_URL", "SHOT_CURRENT", "SHOT_VIDEO_TAKE").contains(ref.getSourceType())) fail("参考视频来源类型无效");
            if ("DIRECT_URL".equals(ref.getSourceType())) {
                if (ref.getSourceVideoUrl() == null || ref.getSourceVideoUrl().isBlank() || ref.getSourceShotId() != null || ref.getSourceVideoTakeId() != null) fail("直接视频地址与分镜来源不能混传");
            } else {
                if (ref.getSourceShotId() == null || ref.getSourceVideoUrl() != null && !ref.getSourceVideoUrl().isBlank()) fail("分镜视频必须由服务端解析来源地址");
                if ("SHOT_VIDEO_TAKE".equals(ref.getSourceType()) != (ref.getSourceVideoTakeId() != null)) fail("历史视频必须指定 Take，当前视频不能混传 Take");
            }
            if (ref.getStartSeconds() != null && ref.getStartSeconds().signum() < 0) fail("参考片段起点不能小于零");
            if (ref.getEndSeconds() != null && (ref.getEndSeconds().signum() <= 0 || ref.getStartSeconds() != null && ref.getEndSeconds().compareTo(ref.getStartSeconds()) <= 0)) fail("参考片段终点必须大于起点");
            if (ref.getReferenceDescription() != null && ref.getReferenceDescription().length() > 1000) fail("参考视频说明不能超过 1000 字符");
        }
    }

    private static void fail(String message) { throw new BizException(400, message); }
}
