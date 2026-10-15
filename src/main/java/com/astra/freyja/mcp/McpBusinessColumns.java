package com.astra.freyja.mcp;

import java.util.Map;

/** Explicit SQL columns for clearing values that updateById would otherwise ignore. */
final class McpBusinessColumns {
    private McpBusinessColumns() {}
    public static final Map<String, String> CONTENT_COLUMNS = Map.ofEntries(Map.entry("prompt", "prompt"), Map.entry("firstFramePrompt", "first_frame_prompt"),
            Map.entry("endFramePrompt", "end_frame_prompt"), Map.entry("negativePrompt", "negative_prompt"), Map.entry("videoPrompt", "video_prompt"),
            Map.entry("customScenePrompt", "custom_scene_prompt"), Map.entry("appearancePrompt", "appearance_prompt"), Map.entry("outfitPrompt", "outfit_prompt"),
            Map.entry("scenePrompt", "scene_prompt"), Map.entry("propPrompt", "prop_prompt"));
    public static final Map<String, String> ENVIRONMENT_COLUMNS = Map.of(
            "sceneType", "scene_type", "timeOfDay", "time_of_day", "weatherAtmosphere", "weather_atmosphere");
}
