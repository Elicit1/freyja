package com.astra.freyja.util;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Migrates stored JSON snapshots only. Field names are explicit legacy schema, never prose heuristics. */
public final class LegacyShotJsonMigration {
    private LegacyShotJsonMigration() {}

    public static JsonNode projectDraft(JsonNode node) { return visit(node.deepCopy(), false); }

    public static JsonNode projectPromptInput(JsonNode node) {
        JsonNode copy = node.deepCopy();
        if (copy instanceof ObjectNode object) {
            migrate(object);
            if (object.get("directorPlanInput") instanceof ObjectNode input) migrate(input);
        }
        return copy;
    }

    private static JsonNode visit(JsonNode node, boolean shot) {
        if (node.isArray()) {
            for (JsonNode item : node) visit(item, shot);
        } else if (node instanceof ObjectNode object) {
            if (shot) migrate(object);
            object.properties().forEach(entry -> visit(entry.getValue(), "shots".equals(entry.getKey())));
        }
        return node;
    }

    private static void migrate(ObjectNode object) {
        if (object.has("dialogue") || object.has("voiceover")) {
            String script = UnifiedShotScript.merge(text(object, "scriptContent"), text(object, "dialogueSpeaker"), text(object, "dialogue"), text(object, "voiceover"));
            if (script != null) object.put("scriptContent", script);
        }
        object.remove("dialogue"); object.remove("dialogueSpeaker"); object.remove("voiceover");
        // Preserve historical recordings in stored data; do not promote them to a new audio selection.
        object.remove("audioUrl");
    }

    private static String text(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
