package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.entity.SysDictData;
import com.astra.freyja.service.DictService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpBusinessSchemasTest {
    final McpJson json = new McpJson();
    final McpSchemaMetadata metadata = new McpSchemaMetadata(json);
    final McpBusinessSchemas schemas = new McpBusinessSchemas(json, metadata);
    final DictService dictionaries = McpBusinessDatabaseTest.dictionaryFixture();
    final McpBusinessOptions options = new McpBusinessOptions(dictionaries, metadata, json);
    final McpBusinessInputValidator validator = new McpBusinessInputValidator(schemas, options, metadata);
    McpToolCatalog catalog;

    @BeforeEach void setup() {
        catalog = new McpToolCatalog(json);
        ReflectionTestUtils.setField(catalog, "businessOptions", options);
    }

    static SysDictData option(String value, String label, int status) {
        SysDictData result = new SysDictData();
        result.setDictValue(value); result.setDictLabel(label); result.setStatus(status);
        return result;
    }

    Map<?, ?> field(Map<String, Object> schema, String field) {
        return (Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get(field);
    }

    @Test void publishedOptionsRefreshWithoutChangingTheCapabilitySnapshot() {
        Map<String, Object> snapshot = schemas.singleCreate(McpRecordType.PROJECT);
        String original = json.write(snapshot);
        var tool = new McpTool<>("create_project", "创建项目", McpRecordInputs.Create.class, McpTool.Effect.WRITE,
                snapshot, (caller, data) -> Map.of());
        when(dictionaries.listByType("drama_aspect_ratio")).thenReturn(List.of(
                option("16:9", "横屏", 1), option("9:16", "已停用竖屏", 0)));
        var registered = catalog.specification(tool);
        Map<?, ?> data = field(registered.tool().inputSchema(), "data");
        Map<?, ?> ratio = (Map<?, ?>) ((Map<?, ?>) data.get("properties")).get("aspectRatio");
        assertEquals(List.of("16:9"), ratio.get("enum"));
        assertTrue(ratio.get("description").toString().contains("横屏"));

        when(dictionaries.listByType("drama_aspect_ratio")).thenReturn(List.of(option("2.39:1", "电影宽银幕", 1)));
        data = field(catalog.specification(tool).tool().inputSchema(), "data");
        assertEquals(List.of("2.39:1"), ((Map<?, ?>) ((Map<?, ?>) data.get("properties")).get("aspectRatio")).get("enum"));
        assertDoesNotThrow(() -> validator.validate(McpRecordType.PROJECT, Map.of("title", "新片", "aspectRatio", "2.39:1"), false, true));
        assertThrows(BizException.class, () -> validator.validate(McpRecordType.PROJECT, Map.of("title", "新片", "aspectRatio", "16:9"), false, true));
        var caller = new McpPrincipal(true, false, true, Set.of());
        var context = McpTransportContext.create(Map.of(McpPrincipal.CONTEXT_KEY, caller));
        // Even an SDK specification registered before the change validates against current options.
        var accepted = registered.callHandler().apply(context, new McpSchema.CallToolRequest("create_project",
                Map.of("requestId", "new-ratio-request", "data", Map.of("title", "新片", "aspectRatio", "2.39:1"))));
        assertFalse(Boolean.TRUE.equals(accepted.isError()));
        var rejected = registered.callHandler().apply(context, new McpSchema.CallToolRequest("create_project",
                Map.of("requestId", "old-ratio-request", "data", Map.of("title", "新片", "aspectRatio", "16:9"))));
        assertTrue(Boolean.TRUE.equals(rejected.isError()));
        assertEquals(original, json.write(snapshot));
    }

    @Test void shotOptionsAreCheckedAgainstCurrentEnabledDictionaryValues() {
        when(dictionaries.listByType("shot_type")).thenReturn(List.of(option("AUTO", "自动", 1), option("FULL_SHOT", "全景", 0)));
        when(dictionaries.listByType("camera_movement")).thenReturn(List.of(option("TRACKING", "跟随", 1)));
        when(dictionaries.listByType("drama_style_preset")).thenReturn(List.of(option("studio-style", "自定义风格", 1)));
        assertDoesNotThrow(() -> validator.validate(McpRecordType.SHOT,
                Map.of("sceneId", "2032095628944588801", "shotType", "AUTO", "cameraMovement", "TRACKING", "stylePreset", "studio-style"), false, true));
        assertThrows(BizException.class, () -> validator.validate(McpRecordType.SHOT, Map.of("shotType", "FULL_SHOT"), false, false));
        assertThrows(BizException.class, () -> validator.validate(McpRecordType.SHOT, Map.of("cameraMovement", "STATIC"), false, false));
        assertDoesNotThrow(() -> validator.validate(McpRecordType.SHOT, Map.of("remark", "仅改备注，保留旧景别"), false, false));
    }

    @Test void emptyDictionariesDoNotAllowArbitraryValuesButPermitOmissionAndExplicitEnvironmentNull() {
        when(dictionaries.listByType("res_scene_type")).thenReturn(List.of());
        when(dictionaries.listByType("shot_type")).thenReturn(List.of());
        Map<String, Object> clear = new HashMap<>(); clear.put("sceneType", null);
        assertDoesNotThrow(() -> validator.validate(McpRecordType.SCENE, clear, false, false));
        assertThrows(BizException.class, () -> validator.validate(McpRecordType.SCENE, Map.of("sceneType", "OUTDOOR"), false, false));
        assertThrows(BizException.class, () -> validator.validate(McpRecordType.SHOT, Map.of("shotType", "AUTO"), false, false));
        assertDoesNotThrow(() -> validator.validate(McpRecordType.SHOT, Map.of("remark", "保留"), false, false));
    }

    @Test void dynamicOptionsPreserveProtocolAndAdministratorRestrictions() {
        when(dictionaries.listByType("shot_generation_mode")).thenReturn(List.of(
                option("REFERENCE_MODE", "参考图", 1), option("UNIMPLEMENTED", "尚未实现", 1)));
        assertEquals(List.of("REFERENCE_MODE"), field(options.apply(schemas.data(McpRecordType.SHOT, false, true)), "generationMode").get("enum"));
        assertThrows(BizException.class, () -> validator.validate(McpRecordType.SHOT, Map.of("sceneId", "30", "generationMode", "UNIMPLEMENTED"), false, true));
        Map<String, Object> narrowed = Map.of("type", "object", "properties", Map.of("sceneType",
                Map.of("type", List.of("string", "null"), "enum", List.of("OUTDOOR"))));
        assertEquals(List.of("OUTDOOR"), field(options.apply(narrowed), "sceneType").get("enum"));
    }

    @Test void extractingMetadataKeepsIdWhitelistAndBatchReferenceProtection() {
        assertEquals(McpBusinessColumns.CONTENT_COLUMNS.keySet(), metadata.contentFields());
        for (McpRecordType type : McpRecordType.values()) {
            assertDoesNotThrow(() -> schemas.data(type, false, true));
            assertDoesNotThrow(() -> schemas.data(type, false, false));
        }
        assertDoesNotThrow(() -> validator.validate(McpRecordType.SHOT, Map.of("sceneId", Map.of("ref", "scene1")), true, true));
        assertThrows(BizException.class, () -> validator.validate(McpRecordType.SHOT, Map.of("sceneId", Map.of("ref", "scene1")), false, true));
        assertThrows(BizException.class, () -> validator.validate(McpRecordType.SHOT, Map.of("sceneId", 2032095628944588801L), false, true));
        assertThrows(BizException.class, () -> validator.validate(McpRecordType.SHOT, Map.of("sceneId", "30", "renderStatus", "SUCCESS"), false, true));
        assertDoesNotThrow(() -> validator.validate(McpRecordType.PROJECT, Map.of("genre", "自定义题材"), false, false));
    }

    @Test void uploadedImageAndAudioDropdownsRejectInventedValuesWithFieldPaths() {
        var badImage = Map.of("sourceType", "UPLOAD", "imageUrl", "https://media/ref.png", "usageRole", "CHARACTER_DESIGN");
        BizException failure = assertThrows(BizException.class, () -> validator.validate(McpRecordType.SHOT,
                Map.of("refImages", List.of(badImage)), false, false));
        assertTrue(failure.getMessage().contains("data.refImages[0].usageRole"));
        assertTrue(failure.getMessage().contains("SUBJECT"));
        assertDoesNotThrow(() -> validator.validate(McpRecordType.SHOT, Map.of("refImages", List.of(
                Map.of("sourceType", "UPLOAD", "imageUrl", "https://media/ref.png", "usageRole", "SUBJECT", "referenceRole", "IDENTITY"))), false, false));
        for (String field : List.of("referenceType", "language")) {
            Map<String, Object> audio = new HashMap<>(Map.of("referenceType", "reference", "audioUrl", "https://media/audio.wav"));
            audio.put(field, "INVENTED");
            assertTrue(assertThrows(BizException.class, () -> validator.validate(McpRecordType.SHOT,
                    Map.of("refAudios", List.of(audio)), false, false)).getMessage().contains("refAudios[0]." + field));
        }
    }

    @Test void imageUsageIsRequiredForBothCreateAndUpdate() {
        for (boolean create : List.of(true, false)) {
            Map<String, Object> image = new HashMap<>(Map.of("sourceType", "UPLOAD", "imageUrl", "https://media/ref.png"));
            Map<String, Object> data = Map.of("sceneId", "30", "refImages", List.of(image));
            assertTrue(assertThrows(BizException.class, () -> validator.validate(McpRecordType.SHOT, data, false, create))
                    .getMessage().contains("refImages[0].usageRole"));
            for (Object role : Arrays.asList(null, "", " ", "subject", " SUBJECT ", 123)) {
                image.put("usageRole", role);
                assertTrue(assertThrows(BizException.class, () -> validator.validate(McpRecordType.SHOT, data, false, create))
                        .getMessage().contains("refImages[0].usageRole"));
            }
            image.put("usageRole", "SUBJECT");
            assertDoesNotThrow(() -> validator.validate(McpRecordType.SHOT, data, false, create));
        }
    }

    @Test void everyWritableAssetDropdownHasAnExactContractAndStatusIsScoped() {
        record Bad(McpRecordType type, String field, Object value) {}
        for (Bad bad : List.of(new Bad(McpRecordType.CHARACTER, "gender", "PERSON"),
                new Bad(McpRecordType.CHARACTER, "roleType", "HERO"), new Bad(McpRecordType.CHARACTER, "ageGroup", "ADULT"),
                new Bad(McpRecordType.CHARACTER_LOOK, "lookType", "WEDDING"), new Bad(McpRecordType.CHARACTER_LOOK, "isDefault", 2),
                new Bad(McpRecordType.PROP, "propType", "MAGIC"), new Bad(McpRecordType.KEYFRAME, "frameType", "END_FRAME"),
                new Bad(McpRecordType.KEYFRAME, "sourceType", "CUSTOM"), new Bad(McpRecordType.CHARACTER, "status", 9),
                new Bad(McpRecordType.PROJECT, "status", "DRAFT"), new Bad(McpRecordType.EPISODE, "status", "PLANNING")))
            assertThrows(BizException.class, () -> validator.validate(bad.type(), Map.of(bad.field(), bad.value()), false, false), bad.toString());
        assertDoesNotThrow(() -> validator.validate(McpRecordType.PROJECT, Map.of("status", "PLANNING"), false, false));
        assertDoesNotThrow(() -> validator.validate(McpRecordType.EPISODE, Map.of("status", "DRAFT"), false, false));
        assertDoesNotThrow(() -> validator.validate(McpRecordType.CHARACTER, Map.of("status", 1, "gender", "FEMALE"), false, false));
    }

    @Test void publicOptionSetsMatchSaveValidatorsAndFrontendDropdowns() throws Exception {
        assertEquals(com.astra.freyja.util.ShotImageReferenceValidator.USAGE_ROLES,
                new HashSet<>(metadata.options("reference_image_usage_role").stream().map(McpSchemaMetadata.Option::value).toList()));
        assertEquals(com.astra.freyja.util.ShotAudioReferenceValidator.REFERENCE_TYPES,
                new HashSet<>(metadata.options("reference_audio_type").stream().map(McpSchemaMetadata.Option::value).toList()));
        assertEquals(com.astra.freyja.util.ShotAudioReferenceValidator.LANGUAGES,
                new HashSet<>(metadata.options("reference_audio_language").stream().map(McpSchemaMetadata.Option::value).toList()));
        String ts = java.nio.file.Files.readString(java.nio.file.Path.of("front/src/constants/referenceMediaOptions.ts"));
        var blocks = java.util.regex.Pattern.compile("export const \\w+ = (\\[[\\s\\S]*?]) as const").matcher(ts);
        for (String name : List.of("reference_image_usage_role", "reference_audio_type", "reference_audio_language")) {
            assertTrue(blocks.find());
            List<?> entries = json.mapper().readValue(blocks.group(1), List.class);
            assertEquals(new HashSet<>(metadata.options(name).stream().map(McpSchemaMetadata.Option::value).toList()),
                    new HashSet<>(entries.stream().map(entry -> ((Map<?, ?>) entry).get("value")).toList()));
            assertFalse(options.list(name).isEmpty());
        }
        assertThrows(BizException.class, () -> options.list("ai_provider_type"));
    }
}
