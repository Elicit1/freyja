package com.astra.freyja.mcp;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.*;

/** Versioned, classpath-owned MCP contract. Never populated from user-controlled configuration. */
@Component
public class McpSchemaMetadata {
    record RecordDefinition(Set<String> fields, List<String> required) {}
    record Definition(Map<McpRecordType, RecordDefinition> records,
                      Map<String, Set<String>> referenceFields,
                      Map<String, Map<String, Map<String, Object>>> referenceSchemas,
                      Map<String, Map<String, Object>> fieldSchemas,
                      Map<String, String> descriptions,
                      Map<String, String> dictionaries,
                      Set<String> contentFields,
                      Map<String, List<Option>> optionSets) {}
    public record Option(String label, Object value) {}

    private final Definition definition;

    public McpSchemaMetadata(McpJson json) {
        try (var stream = new ClassPathResource("mcp/business-schemas.json").getInputStream()) {
            definition = json.mapper().readValue(stream, Definition.class);
            for (McpRecordType type : McpRecordType.values()) {
                RecordDefinition record = Objects.requireNonNull(definition.records().get(type), "Missing record: " + type);
                if (!record.fields().containsAll(record.required())) throw new IllegalStateException("Unknown required field: " + type);
            }
            if (!McpReadTools.CREATION_DICTS.containsAll(definition.dictionaries().values()))
                throw new IllegalStateException("Schema dictionary must be public business options");
            definition.fieldSchemas().values().forEach(this::initializeChoices);
            definition.referenceSchemas().values().forEach(fields -> fields.values().forEach(this::initializeChoices));
        } catch (Exception e) {
            throw new IllegalStateException("MCP 业务 Schema 元数据加载失败", e);
        }
    }

    public Set<String> fields(McpRecordType type) { return definition.records().get(type).fields(); }
    public List<String> required(McpRecordType type) { return definition.records().get(type).required(); }
    public Set<String> referenceFields(String kind) { return definition.referenceFields().get(kind); }
    public Map<String, Map<String, Object>> referenceSchemas(String kind) { return definition.referenceSchemas().get(kind); }
    public Set<String> contentFields() { return definition.contentFields(); }
    public Map<String, Object> fieldSchema(McpRecordType type, String field) {
        return definition.fieldSchemas().getOrDefault(type.name() + "." + field, definition.fieldSchemas().get(field));
    }
    public List<Option> options(String name) { return definition.optionSets().get(name); }
    public Set<String> optionNames() { return definition.optionSets().keySet(); }

    private void initializeChoices(Map<String, Object> schema) {
        if (schema.get("x-freyja-dict") instanceof String dict && !McpReadTools.CREATION_DICTS.contains(dict))
            throw new IllegalStateException("Non-public dictionary: " + dict);
        if (schema.get("x-freyja-options") instanceof String name) {
            List<Option> options = Objects.requireNonNull(options(name), "Missing option set: " + name);
            List<Object> values = new ArrayList<>(options.stream().map(Option::value).toList());
            if (schema.get("type") instanceof List<?> types && types.contains("null")) values.add(null);
            schema.put("enum", values);
            schema.put("description", schema.getOrDefault("description", "") + " 可选值（" + name + "）："
                    + options.stream().map(option -> option.value() + "（" + option.label() + "）")
                    .collect(java.util.stream.Collectors.joining("、")) + "。");
        }
    }
    public String description(McpRecordType type, String field) {
        return description(type.name(), field);
    }
    public String description(String scope, String field) {
        return definition.descriptions().getOrDefault(scope + "." + field, definition.descriptions().get(field));
    }
    public Map<String, String> dictionaries() { return definition.dictionaries(); }
}
