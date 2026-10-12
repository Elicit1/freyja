package com.astra.freyja.mcp;

public enum McpRecordType {
    PROJECT, EPISODE, SCENE, SHOT_GROUP, SHOT, CHARACTER, CHARACTER_LOOK, RESOURCE_SCENE, PROP, KEYFRAME;

    public McpBusinessView.Kind viewKind() { return McpBusinessView.Kind.valueOf(name()); }
    public McpInputs.AssetType assetType() {
        return switch (this) {
            case CHARACTER -> McpInputs.AssetType.CHARACTER; case CHARACTER_LOOK -> McpInputs.AssetType.CHARACTER_LOOK;
            case RESOURCE_SCENE -> McpInputs.AssetType.SCENE; case PROP -> McpInputs.AssetType.PROP; case KEYFRAME -> McpInputs.AssetType.KEYFRAME;
            default -> null;
        };
    }
}
