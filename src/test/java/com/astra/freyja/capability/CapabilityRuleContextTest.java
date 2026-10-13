package com.astra.freyja.capability;

import com.astra.freyja.capability.service.CapabilityRuleContext;
import com.astra.freyja.dto.drama.ShotPromptDeriveDTO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityRuleContextTest {
    @Test void scopedRulesRestoreAndHttpCannotSupplyTrustedSnapshot() throws Exception {
        try (var outer = CapabilityRuleContext.open("旧运行规则")) {
            try (var inner = CapabilityRuleContext.open("嵌套规则")) { assertEquals("嵌套规则", CapabilityRuleContext.current()); }
            assertEquals("旧运行规则", CapabilityRuleContext.current());
        }
        assertNull(CapabilityRuleContext.current());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var request = mapper.readValue("{\"capabilityRulesSnapshot\":\"伪造规则\",\"includeBgm\":true}", ShotPromptDeriveDTO.class);
        assertNull(request.getCapabilityRulesSnapshot()); assertTrue(request.getIncludeBgm());
        request.setCapabilityRulesSnapshot("服务器规则");
        assertEquals("服务器规则", mapper.readTree(mapper.writeValueAsString(request)).get("capabilityRulesSnapshot").asText());
        var productionMapper = tools.jackson.databind.json.JsonMapper.builder().build();
        assertNull(productionMapper.readValue("{\"capabilityRulesSnapshot\":\"伪造规则\"}", ShotPromptDeriveDTO.class).getCapabilityRulesSnapshot());
        assertEquals("服务器规则", productionMapper.readTree(productionMapper.writeValueAsString(request)).get("capabilityRulesSnapshot").asText());
    }
}
