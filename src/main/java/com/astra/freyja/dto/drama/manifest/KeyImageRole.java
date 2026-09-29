package com.astra.freyja.dto.drama.manifest;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

/** The selected picture's role in the target shot, not the asset's source format. */
public enum KeyImageRole {
    FIRST_FRAME("Concrete Frame", "[keyframe completion]", "the shot begins from <%s>",
            "This Picture is the target shot's first concrete frame. Preserve the explicitly described state at the beginning."),
    KEYFRAME("Concrete Frame", "[keyframe completion]", "the shot's keyframe corresponds to <%s>",
            "This Picture corresponds to a concrete frame during the shot. The exact time is unspecified; do not place it at the beginning or end without an explicit instruction."),
    LAST_FRAME("Concrete Frame", "[keyframe completion]", "the shot ends on <%s>",
            "This Picture is the target shot's final concrete frame. Preserve the explicitly described state at the end."),
    EDITED_KEYFRAME("Concrete Frame", "[keyframe completion]", "the shot's keyframe corresponds to <%s>",
            "This edited Picture corresponds to a concrete frame. Preserve its stated edits; its position in the shot is unspecified unless explicitly provided. Do not label image editing as video editing."),
    COMPOSITION_ANCHOR("Planning / Reference", "[reference generation]", null,
            "Use this Picture to plan subject placement, relative distance, orientation, scale, viewpoint and composition where explicitly described. Do not require any video frame to reproduce it exactly."),
    STORYBOARD_REFERENCE("Planning / Reference", "[reference generation]", null,
            "Use this Picture as a storyboard reference for the explicitly mapped shot(s): viewpoint, subject placement and shot order where supplied. Do not require an identical video frame or invent other shots."),
    SHOT_PLANNING_REFERENCE("Planning / Reference", "[reference generation]", null,
            "Use this Picture for explicitly stated camera viewpoint, subject layout, spatial relations and shot mappings. Do not require an identical video frame or invent shot mappings.");

    private final String category;
    private final String taskType;
    private final String temporalPhrase;
    private final String guidance;

    KeyImageRole(String category, String taskType, String temporalPhrase, String guidance) {
        this.category = category;
        this.taskType = taskType;
        this.temporalPhrase = temporalPhrase;
        this.guidance = guidance;
    }

    public String systemGuidance(int pictureIndex) {
        String picture = "Picture " + pictureIndex;
        StringBuilder result = new StringBuilder("<").append(picture).append(">: ")
                .append(name()).append(" / ").append(category).append(" / ").append(taskType)
                .append(". ").append(guidance);
        if (temporalPhrase != null) {
            result.append(" When referring to its role, use: ")
                    .append(String.format(Locale.ROOT, temporalPhrase, picture)).append(".");
        } else {
            result.append(" Do not use first-frame, keyframe-correspondence or last-frame timing phrases for this Picture.");
        }
        return result.toString();
    }

    public static KeyImageRole fromCode(String code) {
        if (StringUtils.isBlank(code)) return null;
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        // Existing assets and saved slots use these names. Their meaning is kept on read.
        if ("END_FRAME".equals(normalized)) return LAST_FRAME;
        if ("ACTION_BEAT".equals(normalized) || "MOTION_KEYFRAME".equals(normalized)) return KEYFRAME;
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static KeyImageRole resolve(String slotRole, String assetFrameType) {
        KeyImageRole selected = fromCode(slotRole);
        if (selected != null && !"MOTION_KEYFRAME".equalsIgnoreCase(slotRole)) return selected;
        KeyImageRole assetRole = fromCode(assetFrameType);
        return assetRole != null ? assetRole : selected;
    }
}
