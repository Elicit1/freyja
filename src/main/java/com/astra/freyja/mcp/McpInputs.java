package com.astra.freyja.mcp;

import org.springframework.ai.tool.annotation.ToolParam;

public final class McpInputs {
    private McpInputs() {}
    public enum OutlineLevel { EPISODE, SCENE, SHOT_GROUP }
    public enum AssetType { CHARACTER, CHARACTER_LOOK, SCENE, PROP, KEYFRAME }
    public record Empty() {}
    public record Search(@ToolParam(required = false) String keyword,
                         @ToolParam(required = false) Integer page, @ToolParam(required = false) Integer size) {}
    public record Project(String dramaId) {}
    public record Outline(String dramaId, OutlineLevel level, @ToolParam(required = false) String episodeId,
                          @ToolParam(required = false) String sceneId,
                          @ToolParam(required = false) Integer page, @ToolParam(required = false) Integer size) {}
    public record Shots(String dramaId, @ToolParam(required = false) String episodeId, @ToolParam(required = false) String sceneId,
                        @ToolParam(required = false) Integer page, @ToolParam(required = false) Integer size) {}
    public record Shot(String dramaId, String shotId) {}
    public record Assets(AssetType type, @ToolParam(required = false) String dramaId,
                         @ToolParam(required = false) Boolean publicOnly,
                         @ToolParam(required = false) String characterId, @ToolParam(required = false) String keyword,
                         @ToolParam(required = false) Integer page, @ToolParam(required = false) Integer size) {}
    public record Asset(AssetType type, String assetId, @ToolParam(required = false) String dramaId) {}
    public record CharacterLooks(String characterId, @ToolParam(required = false) String dramaId,
                            @ToolParam(required = false) Integer page, @ToolParam(required = false) Integer size) {}
    public record Options(String dictType) {}
}
