package com.astra.freyja.agent.profile;

import com.astra.freyja.common.BizException;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentProfileRegistry {
    public static final String ASSISTANT_CODE = "core-assistant";
    public static final String LEGACY_PRODUCTION_CODE = "production-preparation";
    public static String canonicalCode(String code) { return code; }
    private final Map<String, AgentProfile> profiles = new LinkedHashMap<>();
    public AgentProfileRegistry(List<AgentProfile> entries) {
        for (AgentProfile profile : entries) {
            if (profiles.putIfAbsent(profile.code(), profile) != null)
                throw new IllegalStateException("重复的 Agent 模板: " + profile.code());
        }
    }
    public AgentProfile require(String code) {
        AgentProfile profile = profiles.get(canonicalCode(code));
        if (profile == null) throw new BizException(404, "Agent 模板不存在");
        return profile;
    }
    public List<AgentProfile> all() { return List.copyOf(profiles.values()); }
}
