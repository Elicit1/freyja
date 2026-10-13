package com.astra.freyja.capability.service;

import com.astra.freyja.common.BizException;
import org.springframework.stereotype.Component;
import java.util.*;

/** Published schemas may explain or narrow a handler contract, never change executable fields. */
@Component
public class CapabilitySchemaPolicy {
    private static final Set<String> TEXT = Set.of("description", "title", "examples");
    public void verify(Object baseline, Object edited) {
        if (baseline instanceof Map<?, ?> base && edited instanceof Map<?, ?> next) {
            for (Object key : next.keySet()) if (!base.containsKey(key) && !TEXT.contains(key)) invalid();
            for (var entry : base.entrySet()) {
                String key = entry.getKey().toString();
                if (TEXT.contains(key)) continue;
                if (!next.containsKey(key)) invalid();
                Object before = entry.getValue(), after = next.get(key);
                if (key.equals("required") && before instanceof List<?> required && after instanceof List<?> actual) {
                    if (!actual.containsAll(required) || !(next.get("properties") instanceof Map<?, ?> properties)
                            || !properties.keySet().containsAll(actual)) invalid();
                } else if (key.equals("enum") && before instanceof List<?> allowed && after instanceof List<?> selected) {
                    if (selected.isEmpty() || !allowed.containsAll(selected)) invalid();
                } else if (Set.of("maxItems", "maxLength", "maximum").contains(key) && before instanceof Number n && after instanceof Number m) {
                    if (m.doubleValue() > n.doubleValue() || m.doubleValue() < 0) invalid();
                } else if (Set.of("minItems", "minLength", "minimum").contains(key) && before instanceof Number n && after instanceof Number m) {
                    if (m.doubleValue() < n.doubleValue()) invalid();
                } else verify(before, after);
            }
        } else if (baseline instanceof List<?> base && edited instanceof List<?> next) {
            if (base.size() != next.size()) invalid();
            for (int i = 0; i < base.size(); i++) verify(base.get(i), next.get(i));
        } else if (!Objects.equals(baseline, edited)) invalid();
    }
    private void invalid() { throw new BizException(400, "CAPABILITY_SCHEMA_INVALID: 只能编辑说明或收紧已支持的约束，不能修改处理器字段/类型或放宽硬限制"); }
}
