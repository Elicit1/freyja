package com.astra.freyja.capability.service;

import com.astra.freyja.common.BizException;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;

/** Author annotations and narrower validation; executable structure always belongs to the handler. */
@Component
public class CapabilitySchemaPolicy {
    private static final Set<String> TEXT = Set.of("description", "title", "examples");
    private static final Set<String> UPPER = Set.of("maxItems", "maxLength", "maximum");
    private static final Set<String> LOWER = Set.of("minItems", "minLength", "minimum");
    private static final Set<String> NAMED_SCHEMAS = Set.of("properties", "$defs", "definitions", "patternProperties", "dependentSchemas");
    private static final Set<String> SCHEMA_CHILDREN = Set.of("items", "additionalProperties", "unevaluatedProperties",
            "unevaluatedItems", "not", "if", "then", "else", "contains", "propertyNames", "contentSchema",
            "allOf", "anyOf", "oneOf", "prefixItems");

    public void verify(Object baseline, Object edited) { verify("参数", baseline, edited); }
    public void verify(String name, Object baseline, Object edited) { verifyNode(name, baseline, edited, "#", false, true); }

    private void verifyNode(String name, Object baseline, Object edited, String path, boolean named, boolean schemaNode) {
        if (baseline instanceof Map<?, ?> base && edited instanceof Map<?, ?> next) {
            for (Object key : next.keySet()) {
                if (!base.containsKey(key) && (named || !schemaNode || !canAdd(base, key.toString()))) {
                    invalid(name, child(path, key), "字段或约束不受当前处理器支持");
                }
                if (schemaNode && TEXT.contains(key.toString())) {
                    Object value = next.get(key);
                    if (key.equals("examples") ? !(value instanceof List<?>) : !(value instanceof String))
                        invalid(name, child(path, key), "说明字段的值类型无效");
                }
            }
            for (var entry : base.entrySet()) {
                String key = entry.getKey().toString();
                if (schemaNode && TEXT.contains(key)) continue;
                String location = child(path, key);
                if (!next.containsKey(key)) invalid(name, location, "不能删除处理器定义的字段或约束");
                Object before = entry.getValue(), after = next.get(key);
                if (schemaNode && key.equals("required")) {
                    verifyRequired(name, before, after, next, location);
                } else if (schemaNode && key.equals("enum") && before instanceof List<?> allowed && after instanceof List<?> selected) {
                    if (selected.isEmpty() || !allowed.containsAll(selected) || new HashSet<>(selected).size() != selected.size())
                        invalid(name, location, "枚举只能选取当前支持值的非空子集");
                } else if (schemaNode && (UPPER.contains(key) || LOWER.contains(key))) {
                    BigDecimal oldValue = number(name, before, location), newValue = number(name, after, location);
                    if (UPPER.contains(key) ? newValue.compareTo(oldValue) > 0 : newValue.compareTo(oldValue) < 0) {
                        invalid(name, location, "不能放宽处理器限制（当前 " + oldValue + "，提交 " + newValue + "）");
                    }
                } else verifyNode(name, before, after, location, schemaNode && NAMED_SCHEMAS.contains(key),
                        named || schemaNode && SCHEMA_CHILDREN.contains(key));
            }
            if (schemaNode) {
                for (Object key : next.keySet()) {
                    if (!base.containsKey(key) && key.equals("required")) verifyRequired(name, List.of(), next.get(key), next, child(path, key));
                }
                verifyBounds(name, next, path);
            }
        } else if (baseline instanceof List<?> base && edited instanceof List<?> next) {
            if (base.size() != next.size()) invalid(name, path, "不能修改处理器的参数结构或分支数量");
            for (int i = 0; i < base.size(); i++) verifyNode(name, base.get(i), next.get(i), path + "/" + i, false, schemaNode);
        } else if (!same(baseline, edited)) {
            invalid(name, path, "不能修改处理器字段、类型或固定值（当前 " + baseline + "，提交 " + edited + "）");
        }
    }

    private void verifyRequired(String name, Object before, Object after, Map<?, ?> schema, String path) {
        if (!(before instanceof List<?> required) || !(after instanceof List<?> actual)
                || !actual.containsAll(required) || actual.stream().anyMatch(item -> !(item instanceof String))
                || new HashSet<>(actual).size() != actual.size()
                || (schema.get("properties") instanceof Map<?, ?> properties
                    ? !properties.keySet().containsAll(actual) : !actual.equals(required)))
            invalid(name, path, "必填项不能减少，也不能引用不存在的字段");
    }

    private boolean canAdd(Map<?, ?> base, String key) {
        if (TEXT.contains(key)) return true;
        if (key.equals("required")) return base.get("properties") instanceof Map;
        Object type = base.get("type");
        if (key.equals("maxLength") || key.equals("minLength")) return hasType(type, "string");
        if (key.equals("maxItems") || key.equals("minItems")) return hasType(type, "array");
        if (key.equals("maximum") || key.equals("minimum")) return hasType(type, "number") || hasType(type, "integer");
        return false;
    }
    private boolean hasType(Object type, String value) { return value.equals(type) || type instanceof List<?> values && values.contains(value); }

    private void verifyBounds(String name, Map<?, ?> next, String path) {
        for (String[] pair : List.of(new String[]{"minItems", "maxItems"}, new String[]{"minLength", "maxLength"}, new String[]{"minimum", "maximum"})) {
            for (String key : pair) if (next.containsKey(key)) {
                BigDecimal value = number(name, next.get(key), child(path, key));
                if (!key.equals("minimum") && !key.equals("maximum") && (value.signum() < 0 || value.stripTrailingZeros().scale() > 0)) {
                    invalid(name, child(path, key), "数量和长度必须为非负整数");
                }
            }
            if (next.containsKey(pair[0]) && next.containsKey(pair[1]) && number(name, next.get(pair[0]), path).compareTo(number(name, next.get(pair[1]), path)) > 0) {
                invalid(name, path, "下限大于上限，约束无法满足");
            }
        }
    }

    /** Three-way rebase of a trusted stored snapshot; HTTP edits must always use verify(), never this method. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> rebase(String name, Map<String, Object> previous, Map<String, Object> customized, Map<String, Object> current) {
        verify(name, previous, customized);
        Map<String, Object> result = (Map<String, Object>) merge(name, previous, customized, current, "#", false, true);
        verify(name, current, result);
        return result;
    }

    private Object merge(String name, Object before, Object edited, Object current, String path, boolean named, boolean schemaNode) {
        if (before instanceof Map<?, ?> old && edited instanceof Map<?, ?> custom && current instanceof Map<?, ?> fresh) {
            if (!named && !schemaNode) return copy(current);
            if (schemaNode && old.get("enum") instanceof List<?> && !fresh.containsKey("enum") && custom.containsKey("enum")
                    && !same(old.get("enum"), custom.get("enum")))
                invalid(name, child(path, "enum"), "新版已改变枚举边界，请在能力编辑页调整自定义枚举");
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : fresh.entrySet()) {
                String key = entry.getKey().toString(); Object value = entry.getValue();
                if (named) {
                    result.put(key, old.containsKey(key) ? merge(name, old.get(key), custom.get(key), value, child(path, key), false, true) : copy(value));
                } else if (schemaNode && TEXT.contains(key)) {
                    result.put(key, custom.containsKey(key) && !same(old.get(key), custom.get(key)) ? copy(custom.get(key)) : copy(value));
                } else if (key.equals("required") && value instanceof List<?> required) {
                    Set<Object> requiredFields = new LinkedHashSet<>(required);
                    if (custom.get(key) instanceof List<?> selected && fresh.get("properties") instanceof Map<?, ?> properties) {
                        List<?> original = old.get(key) instanceof List<?> previous ? previous : List.of();
                        selected.stream().filter(field -> !original.contains(field) && properties.containsKey(field)).forEach(requiredFields::add);
                    }
                    result.put(key, new ArrayList<>(requiredFields));
                } else if (key.equals("enum") && value instanceof List<?> allowed && custom.get(key) instanceof List<?> selected && !same(old.get(key), selected)) {
                    List<?> intersection = selected.stream().filter(allowed::contains).toList();
                    if (intersection.isEmpty()) invalid(name, child(path, key), "旧自定义枚举与新版支持值无交集，请在能力编辑页调整该约束");
                    result.put(key, intersection);
                } else if ((UPPER.contains(key) || LOWER.contains(key)) && same(old.get("type"), fresh.get("type")) && custom.containsKey(key) && !same(old.get(key), custom.get(key))) {
                    BigDecimal hard = number(name, value, path), preferred = number(name, custom.get(key), path);
                    result.put(key, UPPER.contains(key) ? hard.min(preferred) : hard.max(preferred));
                } else result.put(key, old.containsKey(key) ? merge(name, old.get(key), custom.get(key), value, child(path, key),
                        schemaNode && NAMED_SCHEMAS.contains(key), schemaNode && SCHEMA_CHILDREN.contains(key)) : copy(value));
            }
            if (schemaNode) for (var entry : custom.entrySet()) {
                String key = entry.getKey().toString();
                if (!fresh.containsKey(key) && !same(old.get(key), entry.getValue()) && canAdd(fresh, key)) {
                    if (key.equals("required") && fresh.get("properties") instanceof Map<?, ?> properties && entry.getValue() instanceof List<?> selected) {
                        List<?> original = old.get(key) instanceof List<?> previous ? previous : List.of();
                        List<?> retained = selected.stream().filter(field -> !original.contains(field) && properties.containsKey(field)).toList();
                        if (!retained.isEmpty()) result.put(key, retained);
                    } else result.put(key, copy(entry.getValue()));
                }
            }
            return result;
        }
        if (schemaNode && before instanceof List<?> old && edited instanceof List<?> custom && current instanceof List<?> fresh && !same(old, custom)) {
            if (old.size() != fresh.size()) invalid(name, path, "新版参数分支已变化，无法自动对应旧自定义约束，请在能力编辑页调整");
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < fresh.size(); i++) result.add(merge(name, old.get(i), custom.get(i), fresh.get(i), path + "/" + i, false, true));
            return result;
        }
        return copy(current);
    }
    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) { Map<String, Object> result = new LinkedHashMap<>(); map.forEach((k,v) -> result.put(k.toString(), copy(v))); return result; }
        if (value instanceof List<?> list) return list.stream().map(CapabilitySchemaPolicy::copy).toList();
        return value;
    }
    private boolean same(Object first, Object second) {
        if (first instanceof Number a && second instanceof Number b) return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        if (first instanceof Map<?, ?> a && second instanceof Map<?, ?> b) return a.keySet().equals(b.keySet()) && a.keySet().stream().allMatch(k -> same(a.get(k), b.get(k)));
        if (first instanceof List<?> a && second instanceof List<?> b) { if (a.size() != b.size()) return false; for (int i=0; i<a.size(); i++) if (!same(a.get(i), b.get(i))) return false; return true; }
        return Objects.equals(first, second);
    }
    private BigDecimal number(String name, Object value, String path) {
        if (value instanceof Number numeric) try { return new BigDecimal(numeric.toString()); } catch (NumberFormatException ignored) { }
        invalid(name, path, "限制值必须为有效数字"); return null;
    }
    private static String child(String path, Object key) { return path + "/" + key.toString().replace("~", "~0").replace("/", "~1"); }
    private void invalid(String name, String path, String reason) { throw new BizException(400, "CAPABILITY_SCHEMA_INVALID: 能力「" + name + "」参数 " + path + "：" + reason); }
}
