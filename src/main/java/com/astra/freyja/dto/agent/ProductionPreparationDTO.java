package com.astra.freyja.dto.agent;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** Structured production decisions. Semantic choices belong to the model or creator. */
public final class ProductionPreparationDTO {
    private ProductionPreparationDTO() {}
    @Data public static class Start {
        private String clientRequestId;
        private Long dramaId;
        private String dramaTitle;
        private String chapterTitle;
        private String sourceText;
        private String instruction;
        private String aspectRatio;
        private String stylePreset;
        private String styleTone;
        private String pacingPreset = "AUTO";
        private String workflow = "VIDEO_PREPARATION";
    }
    /** Chat input cannot select a different project or create a separate session/run. */
    @Data public static class ConversationStart {
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private Long sourceMessageId;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String sourceText;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String dramaTitle;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String chapterTitle;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String instruction;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String aspectRatio;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String stylePreset;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String styleTone;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String pacingPreset = "AUTO";
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String workflow = "SCRIPT_DECOMPOSITION";
    }
    @Data public static class Asset {
        private String key;
        private String type;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private Long existingId;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private boolean updateText;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private Long characterId;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private Long shotId;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String name;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String description;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String prompt;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String personality;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String canonicalName;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private List<String> aliases;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String lookType;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String outfitPrompt;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String propType;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String frameType;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String sceneType;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String timeOfDay;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String weatherAtmosphere;
    }
    @Data public static class CharacterBinding {
        private Long characterId;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private Long lookId;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String usageRole = "SUBJECT";
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String actionPrompt;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String emotionPrompt;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String positionTag;
    }
    @Data public static class PropBinding {
        private Long propId;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String purpose;
    }
    @Data public static class Anchor {
        private String sourceType;
        private Long sourceId;
    }
    @Data public static class ReferenceSlot {
        private String slotKey;
        private String sourceType;
        private Long sourceId;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private Long characterId;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private Long lookId;
        private String usageRole;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String referenceRole;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private boolean required = true;
        private String description;
    }
    @Data public static class FrameRequirement {
        private boolean required;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private Long keyframeId;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private String description;
    }
    @Data public static class Plan {
        @org.springframework.ai.tool.annotation.ToolParam(required = false, description = "默认不生成背景音乐；只有用户明确要求配乐才设置true。")
        private Boolean includeBgm;
        private Long shotId;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private Integer expectedRevision = 0;
        private String expectedFingerprint;
        private String generationMode;
        private String decisionReason;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private List<CharacterBinding> characterBindings = new ArrayList<>();
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private Anchor visualAnchor;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private List<PropBinding> propBindings = new ArrayList<>();
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private List<ReferenceSlot> referenceSlots = new ArrayList<>();
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private FrameRequirement firstFrameRequirement;
        @org.springframework.ai.tool.annotation.ToolParam(required = false)
        private FrameRequirement lastFrameRequirement;
    }
    public record ShotQuery(@org.springframework.ai.tool.annotation.ToolParam(required = false) Long shotGroupId, @org.springframework.ai.tool.annotation.ToolParam(required = false) Long afterShotId, @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer limit) {}
    public record Assets(List<Asset> assets) {}
    public record Plans(List<Plan> plans) {}
    public record ShotInput(Long shotId, String expectedFingerprint) {}
    public record Refresh(Long dramaId, List<Long> shotIds) {}
    public record Locks(int expectedRevision, List<String> fields) {}
}
