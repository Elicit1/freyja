package com.astra.freyja.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShotRenderPromptUtilTest {

    @Test
    void referenceModeUsesShotPromptInsteadOfStaleVideoPrompt() {
        assertEquals("new reference prompt",
                ShotRenderPromptUtil.resolve("REFERENCE_MODE", "new reference prompt", "old video prompt", null));
    }

    @Test
    void firstLastFrameModeUsesVideoPrompt() {
        assertEquals("motion prompt",
                ShotRenderPromptUtil.resolve("FIRST_LAST_FRAME", "image prompt", "motion prompt", null));
    }

    @Test
    void submittedEditOverridesStoredPromptInEitherMode() {
        assertEquals("edited prompt",
                ShotRenderPromptUtil.resolve("REFERENCE_MODE", "old image", "old video", " edited prompt "));
        assertEquals("edited prompt",
                ShotRenderPromptUtil.resolve("FIRST_LAST_FRAME", "old image", "old video", " edited prompt "));
    }
}
