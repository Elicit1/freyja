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
        private String aspectRatio = "9:16";
        private String stylePreset;
        private String styleTone;
        private String pacingPreset = "AUTO";
        private String workflow = "VIDEO_PREPARATION";
    }
    /** Chat input cannot select a different project or create a separate session/run. */
    @Data public static class ConversationStart {
        private Long sourceMessageId;
        private String sourceText;
        private String dramaTitle;
        private String chapterTitle;
        private String instruction;
        private String aspectRatio = "9:16";
        private String stylePreset;
        private String styleTone;
        private String pacingPreset = "AUTO";
        private String workflow = "SCRIPT_DECOMPOSITION";
    }
    @Data public static class Asset {
        private String key;
        private String type;
        private Long existingId;
        private boolean updateText;
        private Long characterId;
        private Long shotId;
        private String name;
        private String description;
        private String prompt;
        private String personality;
        private String canonicalName;
        private List<String> aliases;
        private String lookType;
        private String outfitPrompt;
        private String propType;
        private String frameType;
        private String sceneType;
        private String timeOfDay;
        private String weatherAtmosphere;
    }
    @Data public static class CharacterBinding {
        private Long characterId;
        private Long lookId;
        private String usageRole = "SUBJECT";
        private String actionPrompt;
        private String emotionPrompt;
        private String positionTag;
    }
    @Data public static class PropBinding {
        private Long propId;
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
        private Long characterId;
        private Long lookId;
        private String usageRole;
        private String referenceRole;
        private boolean required = true;
        private String description;
    }
    @Data public static class FrameRequirement {
        private boolean required;
        private Long keyframeId;
        private String description;
    }
    @Data public static class Plan {
        private Long shotId;
        private Integer expectedRevision = 0;
        private String expectedFingerprint;
        private String generationMode;
        private String decisionReason;
        private List<CharacterBinding> characterBindings = new ArrayList<>();
        private Anchor visualAnchor;
        private List<PropBinding> propBindings = new ArrayList<>();
        private List<ReferenceSlot> referenceSlots = new ArrayList<>();
        private FrameRequirement firstFrameRequirement;
        private FrameRequirement lastFrameRequirement;
    }
    public record ShotQuery(Long shotGroupId, Long afterShotId, Integer limit) {}
    public record Assets(List<Asset> assets) {}
    public record Plans(List<Plan> plans) {}
    public record ShotInput(Long shotId, String expectedFingerprint) {}
    public record Refresh(Long dramaId, List<Long> shotIds) {}
    public record Locks(int expectedRevision, List<String> fields) {}
}
