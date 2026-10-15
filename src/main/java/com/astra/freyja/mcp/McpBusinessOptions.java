package com.astra.freyja.mcp;

import com.astra.freyja.entity.SysDictData;
import com.astra.freyja.service.DictService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/** Resolve enabled business options at publication/call time, outside immutable capability snapshots. */
@Component
@RequiredArgsConstructor
public class McpBusinessOptions {
    private final DictService dictionaries;
    private final McpSchemaMetadata metadata;
    private final McpJson json;

    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(Map<String, Object> source) {
        // A published capability may share its schema with other consumers. Always work on a deep copy.
        Map<String, Object> result = json.read(source, Map.class);
        visit(result, new HashMap<>());
        return result;
    }

    @SuppressWarnings("unchecked")
    private void visit(Object node, Map<String, List<SysDictData>> snapshot) {
        if (node instanceof Map<?, ?> map) {
            if (map.get("properties") instanceof Map<?, ?> properties) {
                for (var entry : properties.entrySet()) {
                    if (!(entry.getValue() instanceof Map<?, ?> property)) continue;
                    String dictType = property.get("x-freyja-dict") instanceof String explicit ? explicit : metadata.dictionaries().get(entry.getKey());
                    if (dictType == null) continue;
                    List<SysDictData> options = snapshot.computeIfAbsent(dictType, this::enabled);
                    applyOptions((Map<String, Object>) property, dictType, options);
                }
            }
            map.values().forEach(value -> visit(value, snapshot));
        } else if (node instanceof List<?> list) list.forEach(value -> visit(value, snapshot));
    }

    public List<Map<String, Object>> list(String name) {
        if (metadata.options(name) != null) return metadata.options(name).stream()
                .map(option -> Map.<String, Object>of("label", option.label(), "value", option.value(), "order", 0)).toList();
        if (!McpReadTools.CREATION_DICTS.contains(name)) throw new com.astra.freyja.common.BizException(403, "仅允许读取业务选项");
        return enabled(name).stream().map(option -> Map.<String, Object>of("label", Objects.toString(option.getDictLabel(), option.getDictValue()),
                "value", option.getDictValue(), "order", option.getSortOrder() == null ? 0 : option.getSortOrder())).toList();
    }

    public Map<String, Object> querySchema() {
        Set<String> names = new TreeSet<>(McpReadTools.CREATION_DICTS);
        names.addAll(metadata.optionNames());
        return Map.of("type", "object", "properties", Map.of("dictType", Map.of("type", "string", "enum", names)),
                "required", List.of("dictType"), "additionalProperties", false);
    }

    /** Check explicit selections before general JSON Schema validation, with a compact, actionable error. */
    public void validateChoices(Map<String, Object> schema, Map<String, Object> data) { validateChoices(schema, data, "data"); }

    private void validateChoices(Map<?, ?> schema, Object value, String path) {
        if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(value))
            throw new com.astra.freyja.common.BizException(400, path + " 不支持该选项，允许值=" + allowed + "；请按 Schema 或 get_business_options 选择");
        if (schema.containsKey("not") && schema.get("not") instanceof Map<?, ?> not && not.isEmpty())
            throw new com.astra.freyja.common.BizException(400, path + " 当前没有启用选项，请先在系统数据字典配置");
        if (value instanceof Map<?, ?> object && schema.get("properties") instanceof Map<?, ?> properties)
            object.forEach((key, item) -> { if (properties.get(key) instanceof Map<?, ?> child) validateChoices(child, item, path + "." + key); });
        if (value instanceof List<?> items && schema.get("items") instanceof Map<?, ?> itemSchema)
            for (int i = 0; i < items.size(); i++) validateChoices(itemSchema, items.get(i), path + "[" + i + "]");
        for (String keyword : List.of("oneOf", "anyOf")) if (schema.get(keyword) instanceof List<?> branches)
            for (Object branch : branches) if (branch instanceof Map<?, ?> child) {
                Object type = child.get("type");
                if (value instanceof Map && "object".equals(type) || value == null && "null".equals(type)) validateChoices(child, value, path);
            }
    }

    private List<SysDictData> enabled(String type) {
        List<SysDictData> values = dictionaries.listByType(type);
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull)
                .filter(item -> Integer.valueOf(1).equals(item.getStatus()))
                .filter(item -> item.getDictValue() != null && !item.getDictValue().isBlank()).toList();
    }

    private void applyOptions(Map<String, Object> property, String dictType, List<SysDictData> options) {
        Object type = property.get("type");
        boolean nullable = type instanceof List<?> types && types.contains("null");
        List<Object> allowed = new ArrayList<>(options.stream().map(SysDictData::getDictValue).distinct().toList());
        // Protocol enums and administrator-narrowed capability enums remain hard upper bounds.
        if (property.get("enum") instanceof List<?> restriction) allowed.retainAll(restriction);
        if (nullable && (!(property.get("enum") instanceof List<?> restriction) || restriction.contains(null))) allowed.add(null);
        if (allowed.isEmpty()) {
            property.remove("enum");
            property.put("not", Map.of()); // No enabled value; an omitted optional property is still valid.
        } else property.put("enum", allowed);
        String labels = options.stream().filter(item -> allowed.contains(item.getDictValue()))
                .map(item -> item.getDictValue() + (item.getDictLabel() == null ? "" : "（" + item.getDictLabel() + "）"))
                .distinct().collect(java.util.stream.Collectors.joining("、"));
        property.put("description", property.getOrDefault("description", "")
                + " 当前启用选项（" + dictType + "）：" + (labels.isEmpty() ? "无" : labels)
                + "。以 get_business_options 最新结果为准；省略的更新字段保留原值。");
    }
}
