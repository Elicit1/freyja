package com.astra.freyja.util;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.ShotRefAudioDTO;
import com.astra.freyja.dto.drama.ShotRefImageDTO;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShotReferenceOptionsTest {
    @Test void exactlyFourRelationshipsAreAcceptedWithoutDefaultsOrAliases() {
        var audio = ShotRefAudioDTO.builder().audioUrl("https://media/audio.wav").build();
        assertThrows(BizException.class, () -> ShotAudioReferenceValidator.validate(List.of(audio)));
        for (String type : ShotAudioReferenceValidator.REFERENCE_TYPES) {
            audio.setReferenceType(type);
            assertDoesNotThrow(() -> ShotAudioReferenceValidator.validate(List.of(audio)));
        }
        for (String type : List.of("VOICE_TIMBRE", "DIALOGUE_REUSE", "SFX_REUSE", "REFERENCE", "REFERENCE_AUDIO", "")) {
            audio.setReferenceType(type);
            assertThrows(BizException.class, () -> ShotAudioReferenceValidator.validate(List.of(audio)));
        }
    }

    @Test void bothJacksonVersionsRejectRetiredAudioFieldsEvenWhenUnknownFieldsAreIgnored() {
        var jackson2 = new com.fasterxml.jackson.databind.ObjectMapper()
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var jackson3 = tools.jackson.databind.json.JsonMapper.builder()
                .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        for (String field : List.of("sourceType", "usageMode")) {
            String raw = "{\"audioUrl\":\"https://media/audio.wav\",\"referenceType\":\"reference\",\"" + field + "\":\"UPLOAD\"}";
            assertThrows(Exception.class, () -> jackson2.readValue(raw, ShotRefAudioDTO.class));
            assertThrows(Exception.class, () -> jackson3.readValue(raw, ShotRefAudioDTO.class));
        }
    }

    @Test void saveRejectsUnrecognizedUploadRolesAndKeyframeMisclassification() {
        ShotRefImageDTO image = ShotRefImageDTO.builder().sourceType("UPLOAD").usageRole("CHARACTER_DESIGN").build();
        assertTrue(assertThrows(BizException.class, () -> ShotImageReferenceValidator.validate(List.of(image)))
                .getMessage().contains("refImages[0].usageRole"));
        image.setUsageRole("SUBJECT"); image.setReferenceRole("IDENTITY");
        assertDoesNotThrow(() -> ShotImageReferenceValidator.validate(List.of(image)));
        image.setSourceType("KEYFRAME");
        assertThrows(BizException.class, () -> ShotImageReferenceValidator.validate(List.of(image)));
        image.setUsageRole("FIRST_FRAME");
        assertDoesNotThrow(() -> ShotImageReferenceValidator.validate(List.of(image)));
    }

    @Test void audioSavesUseOnlySupportedModesAndLanguageCodes() {
        ShotRefAudioDTO audio = ShotRefAudioDTO.builder().audioUrl("https://media/audio.wav")
                .referenceType("AMBIENT_REUSE").language("zh").build();
        assertThrows(BizException.class, () -> ShotAudioReferenceValidator.validateForSave(List.of(audio)));
        audio.setReferenceType("reference");
        assertDoesNotThrow(() -> ShotAudioReferenceValidator.validateForSave(List.of(audio)));
        audio.setLanguage("Invented Language");
        assertThrows(BizException.class, () -> ShotAudioReferenceValidator.validateForSave(List.of(audio)));
    }
}
