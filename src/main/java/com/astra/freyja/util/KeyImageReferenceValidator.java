package com.astra.freyja.util;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.ShotRefImageDTO;
import com.astra.freyja.dto.drama.manifest.KeyImageRole;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.function.Function;

/** Shared validation for saved, planned and submitted key-image slots. */
public final class KeyImageReferenceValidator {
    private KeyImageReferenceValidator() {}

    public static KeyImageRole resolveRole(ShotRefImageDTO image, Function<Long, String> assetTypeLookup) {
        if (StringUtils.isNotBlank(image.getUsageRole())) {
            return KeyImageRole.requireCode(image.getUsageRole());
        }
        String assetType = image.getSourceId() != null ? assetTypeLookup.apply(image.getSourceId()) : null;
        return KeyImageRole.resolve(null, assetType);
    }

    public static void validate(List<ShotRefImageDTO> images, Function<Long, String> assetTypeLookup) {
        if (images == null) return;
        for (ShotRefImageDTO image : images) {
            if (image != null && "KEYFRAME".equalsIgnoreCase(image.getSourceType())) {
                resolveRole(image, assetTypeLookup);
            }
        }
    }

    public static List<ShotRefImageDTO> parseAndValidate(String json, ObjectMapper mapper,
                                                       Function<Long, String> assetTypeLookup) {
        if (StringUtils.isBlank(json)) return List.of();
        List<ShotRefImageDTO> images;
        try {
            images = mapper.readValue(json, new TypeReference<List<ShotRefImageDTO>>() {});
        } catch (Exception e) {
            throw new BizException(400, "参考图列表 JSON 无效，请重新保存分镜参考图");
        }
        validate(images, assetTypeLookup);
        return images != null ? images : List.of();
    }
}
