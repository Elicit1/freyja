package com.astra.freyja.capability;

import com.astra.freyja.capability.service.CapabilitySchemaPolicy;
import com.astra.freyja.common.BizException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CapabilitySchemaPolicyTest {
    final CapabilitySchemaPolicy policy = new CapabilitySchemaPolicy();
    final Map<String, Object> baseline = Map.of("type", "object", "additionalProperties", false, "required", List.of("name"),
            "properties", Map.of("name", Map.of("type", "string", "maxLength", 64), "mode", Map.of("type", "string", "enum", List.of("A", "B"))));
    @Test void descriptionsAndNarrowerLimitsAreConfigurable() {
        var edited = new LinkedHashMap<>(baseline);
        edited.put("description", "数据库维护的说明");
        edited.put("properties", Map.of("name", Map.of("type", "string", "maxLength", 32, "description", "更紧凑的名称"), "mode", Map.of("type", "string", "enum", List.of("A"))));
        policy.verify(baseline, edited);
    }
    @Test void cannotWidenOrReplaceExecutableContract() {
        for (Object replacement : List.of(Map.of("type", "string", "maxLength", 128), Map.of("type", "number", "maxLength", 64))) {
            var edited = new LinkedHashMap<>(baseline); edited.put("properties", Map.of("name", replacement, "mode", Map.of("type", "string", "enum", List.of("A", "B"))));
            assertThrows(BizException.class, () -> policy.verify(baseline, edited));
        }
        var edited = new LinkedHashMap<>(baseline); edited.put("required", List.of());
        var missingRequired = edited; assertThrows(BizException.class, () -> policy.verify(baseline, missingRequired));
        edited = new LinkedHashMap<>(baseline); edited.put("additionalProperties", true);
        var invalid = edited; assertThrows(BizException.class, () -> policy.verify(baseline, invalid));
    }
    @Test void propertyNamedLikeAnnotationCannotBypassStructuralValidation() {
        var source = Map.<String, Object>of("type", "object", "properties", Map.of("description", Map.of("type", "string")));
        var edited = Map.<String, Object>of("type", "object", "properties", Map.of("description", Map.of("type", "number")));
        BizException error = assertThrows(BizException.class, () -> policy.verify("测试能力", source, edited));
        assertTrue(error.getMessage().contains("#/properties/description/type"));
    }
    @Test void fixedValueDescriptionIsNotAnEditableSchemaAnnotation() {
        var source = Map.<String, Object>of("type", "object", "const", Map.of("description", "固定内容"));
        var edited = Map.<String, Object>of("type", "object", "const", Map.of("description", "篡改内容"));
        BizException error = assertThrows(BizException.class, () -> policy.verify("测试能力", source, edited));
        assertTrue(error.getMessage().contains("#/const/description"));
    }
    @Test void rebasePreservesOnlyCompatibleCustomizations() {
        var previous = Map.<String, Object>of("type", "object", "required", List.of("script"), "properties",
                Map.of("script", Map.of("type", "string", "maxLength", 100), "dialogue", Map.of("type", "string")));
        var customized = Map.<String, Object>of("type", "object", "required", List.of("script", "dialogue"), "properties",
                Map.of("script", Map.of("type", "string", "maxLength", 60, "description", "脚本"), "dialogue", Map.of("type", "string")));
        var current = Map.<String, Object>of("type", "object", "required", List.of("script"), "properties",
                Map.of("script", Map.of("type", "string", "maxLength", 80), "shotId", Map.of("type", "string")));
        var result = policy.rebase("测试能力", previous, customized, current);
        assertEquals(List.of("script"), result.get("required"));
        Map<?, ?> properties = (Map<?, ?>) result.get("properties");
        assertFalse(properties.containsKey("dialogue"));
        assertTrue(properties.containsKey("shotId"));
        assertEquals(60, ((Number) ((Map<?, ?>) properties.get("script")).get("maxLength")).intValue());
    }
    @Test void rejectsDisjointCustomizedEnumDuringRebase() {
        var previous = Map.<String, Object>of("type", "string", "enum", List.of("A", "B"));
        var customized = Map.<String, Object>of("type", "string", "enum", List.of("A"));
        var current = Map.<String, Object>of("type", "string", "enum", List.of("C", "D"));
        assertTrue(assertThrows(BizException.class, () -> policy.rebase("测试能力", previous, customized, current))
                .getMessage().contains("#/enum"));
    }
}
