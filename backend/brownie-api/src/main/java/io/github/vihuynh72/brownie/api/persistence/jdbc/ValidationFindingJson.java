package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.validation.ValidationFinding;
import io.github.vihuynh72.brownie.core.validation.ValidationFindingCode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The stored JSON shape of a manifest's findings: a closed array of
 * {@code {code, fieldId, message}} objects -- extracted so the batch
 * repository, which reads manifests set-based for the batch detail, never
 * maintains its own second copy of the shape {@code
 * JdbcValidationRepository} writes.
 */
final class ValidationFindingJson {

    private ValidationFindingJson() {
    }

    static String encode(ObjectMapper objectMapper, List<ValidationFinding> findings) {
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (ValidationFinding finding : findings) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", finding.code().name());
            entry.put("fieldId", finding.fieldId());
            entry.put("message", finding.message());
            encoded.add(entry);
        }
        try {
            return objectMapper.writeValueAsString(encoded);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize validation findings.", e);
        }
    }

    static List<ValidationFinding> decode(ObjectMapper objectMapper, String json) {
        try {
            Object decoded = objectMapper.readValue(json, Object.class);
            if (!(decoded instanceof List<?> rawList)) {
                throw malformed("validation findings must be a JSON array");
            }
            List<ValidationFinding> findings = new ArrayList<>();
            for (Object rawEntry : rawList) {
                Map<String, Object> entry = objectMap(rawEntry, "validation finding");
                if (!entry.keySet().equals(Set.of("code", "fieldId", "message"))) {
                    throw malformed("unexpected properties in a stored validation finding");
                }
                findings.add(new ValidationFinding(
                        requiredCode(entry, "code"),
                        optionalString(entry, "fieldId"),
                        requiredString(entry, "message")));
            }
            return findings;
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize stored validation findings.", e);
        }
    }

    private static Map<String, Object> objectMap(Object value, String description) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw malformed("expected " + description + " to be an object");
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw malformed("expected " + description + " keys to be strings");
            }
            mapped.put(key, entry.getValue());
        }
        return mapped;
    }

    private static ValidationFindingCode requiredCode(Map<String, Object> entry, String key) {
        if (!(entry.get(key) instanceof String value)) {
            throw malformed("expected validation finding property " + key + " to be a string");
        }
        try {
            return ValidationFindingCode.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw malformed("unrecognized validation finding code " + value);
        }
    }

    private static String requiredString(Map<String, Object> entry, String key) {
        if (!(entry.get(key) instanceof String value)) {
            throw malformed("expected validation finding property " + key + " to be a string");
        }
        return value;
    }

    private static String optionalString(Map<String, Object> entry, String key) {
        Object value = entry.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String stringValue)) {
            throw malformed("expected validation finding property " + key + " to be a string or null");
        }
        return stringValue;
    }

    private static IllegalStateException malformed(String detail) {
        return new IllegalStateException("Stored validation findings are invalid: " + detail);
    }
}
