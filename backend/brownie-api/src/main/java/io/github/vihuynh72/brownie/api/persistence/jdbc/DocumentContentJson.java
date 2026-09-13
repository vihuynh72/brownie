package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.revision.FieldValue;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The closed typed-field JSON shape both a full document revision's own
 * content and a patch proposal's own proposed values share -- extracted
 * so the same encode/decode logic (and the same malformed-input checks)
 * is never independently maintained twice. A proposal's own {@code
 * proposedValues} is exactly this shape over a bounded subset of fields,
 * not a whole revision's content, so this takes a plain field map rather
 * than a {@code DocumentContent} directly.
 */
final class DocumentContentJson {

    private static final int SCHEMA_VERSION = 1;

    private DocumentContentJson() {
    }

    static String encode(ObjectMapper objectMapper, Map<String, FieldValue> fields) {
        List<Map<String, Object>> encodedFields = new ArrayList<>();
        fields.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("fieldId", entry.getKey());
            switch (entry.getValue()) {
                case FieldValue.TextValue(String value) -> {
                    field.put("type", "TEXT");
                    field.put("cardinality", "SCALAR");
                    field.put("value", value);
                }
                case FieldValue.DateValue(LocalDate value) -> {
                    field.put("type", "DATE");
                    field.put("cardinality", "SCALAR");
                    field.put("value", value.toString());
                }
                case FieldValue.RepeatedTextValue(List<String> values) -> {
                    field.put("type", "TEXT");
                    field.put("cardinality", "REPEATED");
                    field.put("values", values);
                }
                case FieldValue.RepeatedDateValue(List<LocalDate> values) -> {
                    field.put("type", "DATE");
                    field.put("cardinality", "REPEATED");
                    field.put("values", values.stream().map(LocalDate::toString).toList());
                }
            }
            encodedFields.add(field);
        });
        try {
            return objectMapper.writeValueAsString(Map.of("schemaVersion", SCHEMA_VERSION, "fields", encodedFields));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize typed document content.", e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, FieldValue> decode(ObjectMapper objectMapper, String json) {
        try {
            Object decoded = objectMapper.readValue(json, Object.class);
            Map<String, Object> root = objectMap(decoded, "document content");
            requireKeys(root, Set.of("schemaVersion", "fields"), "document content");
            if (!(root.get("schemaVersion") instanceof Number schemaVersion) || schemaVersion.intValue() != SCHEMA_VERSION) {
                throw malformed("Document content has an unsupported schema version.");
            }
            if (!(root.get("fields") instanceof List<?> rawFields)) {
                throw malformed("Document content fields must be an array.");
            }
            Map<String, FieldValue> fields = new LinkedHashMap<>();
            for (Object rawField : rawFields) {
                Map<String, Object> field = objectMap(rawField, "document content field");
                String fieldId = requiredString(field, "fieldId", "document content field");
                FieldValue value = valueFromMap(field);
                if (fields.putIfAbsent(fieldId, value) != null) {
                    throw malformed("Document content repeats field ID " + fieldId + ".");
                }
            }
            return fields;
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize typed document content.", e);
        }
    }

    private static FieldValue valueFromMap(Map<String, Object> field) {
        String type = requiredString(field, "type", "document content field");
        String cardinality = requiredString(field, "cardinality", "document content field");
        return switch (type + ":" + cardinality) {
            case "TEXT:SCALAR" -> {
                requireKeys(field, Set.of("fieldId", "type", "cardinality", "value"), "TEXT scalar field");
                yield new FieldValue.TextValue(requiredString(field, "value", "TEXT scalar field"));
            }
            case "DATE:SCALAR" -> {
                requireKeys(field, Set.of("fieldId", "type", "cardinality", "value"), "DATE scalar field");
                yield new FieldValue.DateValue(parseDate(requiredString(field, "value", "DATE scalar field")));
            }
            case "TEXT:REPEATED" -> {
                requireKeys(field, Set.of("fieldId", "type", "cardinality", "values"), "TEXT repeated field");
                yield new FieldValue.RepeatedTextValue(requiredStringList(field, "values", "TEXT repeated field"));
            }
            case "DATE:REPEATED" -> {
                requireKeys(field, Set.of("fieldId", "type", "cardinality", "values"), "DATE repeated field");
                yield new FieldValue.RepeatedDateValue(
                        requiredStringList(field, "values", "DATE repeated field").stream().map(DocumentContentJson::parseDate).toList());
            }
            default -> throw malformed("Unsupported document field shape " + type + ":" + cardinality + ".");
        };
    }

    private static Map<String, Object> objectMap(Object value, String description) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw malformed("Expected " + description + " to be an object.");
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw malformed("Expected " + description + " keys to be strings.");
            }
            mapped.put(key, entry.getValue());
        }
        return mapped;
    }

    private static void requireKeys(Map<String, Object> value, Set<String> expected, String description) {
        if (!value.keySet().equals(expected)) {
            throw malformed("Unexpected properties in " + description + ".");
        }
    }

    private static String requiredString(Map<String, Object> value, String key, String description) {
        if (!(value.get(key) instanceof String string)) {
            throw malformed("Expected " + description + " property " + key + " to be a string.");
        }
        return string;
    }

    private static List<String> requiredStringList(Map<String, Object> value, String key, String description) {
        if (!(value.get(key) instanceof List<?> rawValues)) {
            throw malformed("Expected " + description + " property " + key + " to be an array.");
        }
        List<String> values = new ArrayList<>();
        for (Object rawValue : rawValues) {
            if (!(rawValue instanceof String string)) {
                throw malformed("Expected " + description + " values to be strings.");
            }
            values.add(string);
        }
        return values;
    }

    private static LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw malformed("Expected DATE value to use ISO local-date form.");
        }
    }

    private static IllegalStateException malformed(String detail) {
        return new IllegalStateException("Stored typed document content is invalid: " + detail);
    }
}
