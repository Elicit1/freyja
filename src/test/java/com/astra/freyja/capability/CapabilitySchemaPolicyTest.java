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
}
