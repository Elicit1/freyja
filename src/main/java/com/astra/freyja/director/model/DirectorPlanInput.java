package com.astra.freyja.director.model;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.ShotPromptDeriveDTO;
import com.astra.freyja.entity.DramaShot;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Only AI inputs from the basic shot specification card. No semantic inference. */
public record DirectorPlanInput(Integer shotNo, String shotName, Double duration,
        Boolean shotTypeLocked, String shotType, Boolean cameraMovementLocked, String cameraMovement,
        String scriptContent, String actionDescription, String dialogue, String dialogueSpeaker,
        String voiceover, String soundEffect) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public DirectorPlanInput normalized() {
        boolean sizeLocked = Boolean.TRUE.equals(shotTypeLocked) && text(shotType) != null && !"AUTO".equalsIgnoreCase(shotType.trim());
        boolean movementLocked = Boolean.TRUE.equals(cameraMovementLocked) && text(cameraMovement) != null && !"AUTO".equalsIgnoreCase(cameraMovement.trim());
        return new DirectorPlanInput(shotNo == null ? 1 : shotNo, text(shotName), duration,
                sizeLocked, sizeLocked ? text(shotType) : null, movementLocked, movementLocked ? text(cameraMovement) : null,
                text(scriptContent), text(actionDescription), text(dialogue), text(dialogueSpeaker), text(voiceover), text(soundEffect));
    }

    private static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public static DirectorPlanInput fromShot(DramaShot shot) {
        return new DirectorPlanInput(shot.getShotNo(), shot.getShotName(), shot.getDuration() == null ? null : shot.getDuration().doubleValue(),
                shot.getShotTypeLocked(), shot.getShotType(), shot.getCameraMovementLocked(), shot.getCameraMovement(),
                shot.getScriptContent(), shot.getActionDescription(), shot.getDialogue(), shot.getDialogueSpeaker(),
                shot.getVoiceover(), shot.getSoundEffect()).normalized();
    }

    public static DirectorPlanInput fromRequest(ShotPromptDeriveDTO dto) {
        return new DirectorPlanInput(dto.getShotNo(), dto.getShotName(), dto.getDuration(),
                dto.getShotTypeLocked(), dto.getShotType(), dto.getCameraMovementLocked(), dto.getCameraMovement(),
                dto.getScriptContent(), dto.getActionDescription(), dto.getDialogue(), dto.getDialogueSpeaker(),
                dto.getVoiceover(), dto.getSoundEffect()).normalized();
    }

    public void applyTo(ShotPromptDeriveDTO dto) {
        dto.setShotNo(shotNo); dto.setShotName(shotName); dto.setDuration(duration);
        dto.setShotTypeLocked(shotTypeLocked); dto.setShotType(shotType);
        dto.setCameraMovementLocked(cameraMovementLocked); dto.setCameraMovement(cameraMovement);
        dto.setScriptContent(scriptContent); dto.setActionDescription(actionDescription);
        dto.setDialogue(dialogue); dto.setDialogueSpeaker(dialogueSpeaker);
        dto.setVoiceover(voiceover); dto.setSoundEffect(soundEffect);
    }

    public String encode() {
        try { return JSON.writeValueAsString(normalized()); }
        catch (Exception e) { throw new BizException(400, "导演计划输入无法序列化"); }
    }

    public String fingerprint() {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(encode().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

}
