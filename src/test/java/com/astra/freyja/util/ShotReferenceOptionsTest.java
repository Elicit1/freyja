package com.astra.freyja.util;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.ShotRefAudioDTO;
import com.astra.freyja.dto.drama.ShotRefImageDTO;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShotReferenceOptionsTest {
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
        ShotRefAudioDTO audio = ShotRefAudioDTO.builder().sourceType("UPLOAD").audioUrl("https://media/audio.wav")
                .usageMode("AMBIENT_REUSE").language("zh").build();
        assertThrows(BizException.class, () -> ShotAudioReferenceValidator.validateForSave(List.of(audio)));
        audio.setUsageMode("SFX_REUSE");
        assertDoesNotThrow(() -> ShotAudioReferenceValidator.validateForSave(List.of(audio)));
        audio.setLanguage("Invented Language");
        assertThrows(BizException.class, () -> ShotAudioReferenceValidator.validateForSave(List.of(audio)));
    }
}
