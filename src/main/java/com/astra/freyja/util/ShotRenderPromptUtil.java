package com.astra.freyja.util;

import org.apache.commons.lang3.StringUtils;

/** Select the prompt actually sent to the video model for the shot's generation mode. */
public final class ShotRenderPromptUtil {

    private ShotRenderPromptUtil() {
    }

    public static String resolve(String generationMode, String prompt, String videoPrompt, String submittedPrompt) {
        if (StringUtils.isNotBlank(submittedPrompt)) {
            return submittedPrompt.trim();
        }
        return "REFERENCE_MODE".equalsIgnoreCase(generationMode)
                ? StringUtils.firstNonBlank(prompt, videoPrompt)
                : StringUtils.firstNonBlank(videoPrompt, prompt);
    }
}
