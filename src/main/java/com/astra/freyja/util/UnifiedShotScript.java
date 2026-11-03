package com.astra.freyja.util;

import org.apache.commons.lang3.StringUtils;
import java.util.ArrayList;
import java.util.List;

/** Lossless legacy-field projection. Formatting only; never parses or guesses script semantics. */
public final class UnifiedShotScript {
    private UnifiedShotScript() {}

    public static String merge(String script, String speaker, String dialogue, String voiceover) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.isNotBlank(script)) parts.add(script);
        if (StringUtils.isNotBlank(dialogue)) {
            parts.add("【历史对白，请核对】\n" + StringUtils.defaultIfBlank(speaker, "未指定说话人") + "：“" + dialogue + "”");
        }
        if (StringUtils.isNotBlank(voiceover)) parts.add("【历史旁白 / 独白，请核对】\n" + voiceover);
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }
}
