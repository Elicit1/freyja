package com.astra.freyja.dto.voice;

import java.util.List;
import java.util.Map;

public final class DubbingInputs {
    private DubbingInputs() {}
    public record Step(String type, Map<String, Double> params) {}
    public record EffectSelection(Long effectId, List<Step> steps) {}
    public record Create(String name, String text,
                         Long providerId, String modelCode,
                         String voiceDesc, String emotion, List<EffectSelection> effects,
                         String method, Long replaceBaseId) {}
    public record Effects(Integer revision, List<EffectSelection> effects) {}
    public record Metadata(Integer revision, String name) {}
    public record EffectEdit(Long id, String name, String description,
                             List<Step> steps, Boolean enabled, Integer version) {}
}
