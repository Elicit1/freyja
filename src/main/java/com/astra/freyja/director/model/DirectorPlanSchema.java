package com.astra.freyja.director.model;

import com.astra.freyja.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared output contract for the director prompt and deterministic validation. */
public final class DirectorPlanSchema {
    public static final String VERSION = "1.0";
    public static final Set<String> SHOT_SIZES = values("EXTREME_CLOSE_UP", "CLOSE_UP", "MEDIUM_CLOSE_UP",
            "MEDIUM_SHOT", "FULL_SHOT", "LONG_SHOT", "OVER_SHOULDER", "TOP_DOWN");
    public static final Set<String> CAMERA_ANGLES = values("EYE_LEVEL", "LOW_ANGLE", "HIGH_ANGLE",
            "DUTCH_ANGLE", "OVER_THE_SHOULDER", "BIRDS_EYE");
    public static final Set<String> MOVEMENTS = values("STATIC", "PUSH_IN", "PULL_OUT", "PAN_LEFT",
            "PAN_RIGHT", "TILT_UP", "TILT_DOWN", "TRACKING", "ORBIT", "ZOOM_IN");
    public static final Set<String> SPEEDS = values("NONE", "SLOW", "NORMAL", "FAST");
    private static final List<String> REQUIRED_PLAN_FIELDS = List.of(
            "schemaVersion", "duration", "shotSize", "cameraAngle", "cameraBeats");
    private static final List<String> REQUIRED_BEAT_FIELDS = List.of("startSec", "endSec", "movement", "speed");
    private static final String JSON_SCHEMA = buildJsonSchema();

    private DirectorPlanSchema() { }

    public static int maxBeats(BigDecimal duration) {
        return duration.compareTo(BigDecimal.TEN) <= 0 ? 3 : 5;
    }

    public static String jsonSchema() { return JSON_SCHEMA; }

    /** Check the wire shape before Jackson can coerce values or supply model defaults. */
    public static void validateGeneratedStructure(JsonNode root) {
        requireObject(root, "$", REQUIRED_PLAN_FIELDS);
        requireType(root.get("schemaVersion"), "$.schemaVersion", "string");
        requireType(root.get("duration"), "$.duration", "number");
        requireType(root.get("shotSize"), "$.shotSize", "string");
        requireType(root.get("cameraAngle"), "$.cameraAngle", "string");
        JsonNode beats = root.get("cameraBeats");
        if (!beats.isArray()) invalid("$.cameraBeats", beats, "必须为数组");
        for (int i = 0; i < beats.size(); i++) {
            String path = "$.cameraBeats[" + i + "]";
            JsonNode beat = beats.get(i);
            requireObject(beat, path, REQUIRED_BEAT_FIELDS);
            requireType(beat.get("startSec"), path + ".startSec", "number");
            requireType(beat.get("endSec"), path + ".endSec", "number");
            requireType(beat.get("movement"), path + ".movement", "string");
            requireType(beat.get("speed"), path + ".speed", "string");
        }
    }

    public static BizException invalidEnum(String path, String value, Set<String> allowed) {
        return new BizException("DirectorPlan " + path + " 无效，实际值=" + String.valueOf(value)
                + "，合法值=" + allowed);
    }

    private static void requireObject(JsonNode node, String path, List<String> required) {
        if (node == null || !node.isObject()) invalid(path, node, "必须为 JSON 对象");
        for (String field : required) {
            if (!node.has(field)) invalid(path + "." + field, null, "缺少必填字段");
        }
    }

    private static void requireType(JsonNode node, String path, String type) {
        boolean valid = "string".equals(type) ? node.isTextual() : node.isNumber();
        if (!valid) invalid(path, node, "必须为 " + type);
    }

    private static void invalid(String path, JsonNode value, String reason) {
        throw new BizException("DirectorPlan " + path + " " + reason + "，实际值="
                + (value == null ? "<missing>" : value.toString()));
    }

    private static Set<String> values(String... entries) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(entries)));
    }

    private static Map<String, Object> enumProperty(Set<String> entries) {
        return Map.of("type", "string", "enum", entries);
    }

    private static String buildJsonSchema() {
        Map<String, Object> beatProperties = new LinkedHashMap<>();
        beatProperties.put("startSec", Map.of("type", "number", "minimum", 0));
        beatProperties.put("endSec", Map.of("type", "number", "exclusiveMinimum", 0));
        beatProperties.put("movement", enumProperty(MOVEMENTS));
        beatProperties.put("direction", Map.of("type", List.of("string", "null")));
        beatProperties.put("speed", enumProperty(SPEEDS));
        for (String field : List.of("startCue", "stopCue", "narrativePurpose")) {
            beatProperties.put(field, Map.of("type", List.of("string", "null")));
        }
        Map<String, Object> beat = Map.of("type", "object", "required", REQUIRED_BEAT_FIELDS,
                "properties", beatProperties, "allOf", List.of(Map.of(
                        "if", Map.of("properties", Map.of("movement", Map.of("const", "STATIC"))),
                        "then", Map.of("properties", Map.of("direction", Map.of("type", "null"),
                                "speed", Map.of("const", "NONE"))),
                        "else", Map.of("properties", Map.of("speed", Map.of("not", Map.of("const", "NONE")))))));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("schemaVersion", Map.of("type", "string", "const", VERSION));
        properties.put("duration", Map.of("type", "number", "exclusiveMinimum", 0));
        properties.put("shotSize", enumProperty(SHOT_SIZES));
        properties.put("cameraAngle", enumProperty(CAMERA_ANGLES));
        properties.put("cameraBeats", Map.of("type", "array", "minItems", 1, "maxItems", 5, "items", beat));
        for (String field : List.of("subjectAction", "gaze", "narrativeIntent")) {
            properties.put(field, Map.of("type", List.of("string", "null")));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        schema.put("required", REQUIRED_PLAN_FIELDS);
        schema.put("properties", properties);
        schema.put("allOf", List.of(Map.of(
                "if", Map.of("properties", Map.of("duration", Map.of("maximum", 10))),
                "then", Map.of("properties", Map.of("cameraBeats", Map.of("maxItems", 3))))));
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
