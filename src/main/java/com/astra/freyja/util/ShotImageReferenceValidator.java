package com.astra.freyja.util;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.ShotRefImageDTO;
import com.astra.freyja.dto.drama.manifest.KeyImageRole;

import java.util.*;

/** Exact protocol choices; never infer a reference's meaning from its text. */
public final class ShotImageReferenceValidator {
    public static final Set<String> SOURCE_TYPES = Set.of("SCENE", "CHARACTER", "CHARACTER_LOOK", "CHARACTER_REFERENCE", "PROP", "KEYFRAME", "UPLOAD");
    public static final Set<String> REFERENCE_ROLES = Set.of("IDENTITY", "LOOK", "COMBINED", "POSE", "STYLE");
    public static final Set<String> USAGE_ROLES;
    static {
        Set<String> roles = new LinkedHashSet<>(List.of("SUBJECT", "NON_SUBJECT", "SCENE", "PROP", "STYLE"));
        Arrays.stream(KeyImageRole.values()).map(Enum::name).forEach(roles::add);
        USAGE_ROLES = Collections.unmodifiableSet(roles);
    }
    private ShotImageReferenceValidator() {}

    public static void validate(List<ShotRefImageDTO> images) {
        if (images != null) for (int i = 0; i < images.size(); i++) validate(images.get(i), i);
    }

    public static void validate(ShotRefImageDTO image, int index) {
        if (image == null) throw new BizException(400, "refImages[" + index + "] 必须为对象");
        String path = "refImages[" + index + "]";
        if (image.getSourceType() == null) throw new BizException(400, path + ".sourceType 必填");
        requireChoice(image.getSourceType(), SOURCE_TYPES, path + ".sourceType");
        requireChoice(image.getUsageRole(), USAGE_ROLES, path + ".usageRole");
        requireChoice(image.getReferenceRole(), REFERENCE_ROLES, path + ".referenceRole");
        if ("NON_SUBJECT".equals(image.getUsageRole()) && !Set.of("CHARACTER", "CHARACTER_LOOK", "CHARACTER_REFERENCE", "UPLOAD").contains(image.getSourceType()))
            throw new BizException(400, path + ".usageRole 的 NON_SUBJECT 仅用于人物或上传参考图");
        if ("KEYFRAME".equals(image.getSourceType()) && image.getUsageRole() != null
                && Arrays.stream(KeyImageRole.values()).noneMatch(role -> role.name().equals(image.getUsageRole())))
            throw new BizException(400, path + ".usageRole 对 KEYFRAME 来源仅支持 " + Arrays.toString(KeyImageRole.values()));
    }

    private static void requireChoice(String value, Set<String> allowed, String path) {
        if (value != null && !allowed.contains(value)) throw new BizException(400, path + " 选项无效，允许值=" + allowed);
    }
}
