package io.github.vihuynh72.brownie.ai.generation.json;

import io.github.vihuynh72.brownie.core.generation.ExtractionResponseParseException;
import io.github.vihuynh72.brownie.core.generation.ExtractionResponseParser;
import io.github.vihuynh72.brownie.core.generation.ExtractionResult;
import io.github.vihuynh72.brownie.core.generation.FieldCandidate;
import io.github.vihuynh72.brownie.core.generation.RepeatedItemCandidate;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldType;
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
 * Parses an extraction reply's raw JSON into a typed {@link
 * ExtractionResult}, the same generic Map/List-traversal idiom {@code
 * JdbcDocumentRepository.fromJson} already established for this
 * codebase's own stored content, using this project's pinned Jackson 3
 * ({@code tools.jackson}) rather than the older {@code com.fasterxml.jackson}
 * the OpenAI SDK happens to pull in transitively for its own unrelated
 * purposes. Every failure here is a checked {@link
 * ExtractionResponseParseException}, not an assertion failure: unlike
 * {@code fromJson} reading this application's own previously-validated
 * data, this class reads a third party's reply, where a malformed shape
 * is an expected, recoverable outcome (see this port's own documentation).
 */
class JacksonExtractionResponseParser implements ExtractionResponseParser {

    private final ObjectMapper objectMapper;

    JacksonExtractionResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ExtractionResult parse(String json, List<FieldDefinition> scalarFields, List<FieldDefinition> repeatedFields)
            throws ExtractionResponseParseException {
        Object decoded;
        try {
            decoded = objectMapper.readValue(json, Object.class);
        } catch (JacksonException e) {
            throw new ExtractionResponseParseException("The reply was not valid JSON.", e);
        }
        Map<String, Object> root = objectMap(decoded, "extraction reply");
        requireExactKeys(root, Set.of("scalarFields", "repeatedItems"), "extraction reply");

        Map<String, FieldCandidate> scalarCandidates = parseFieldMap(root.get("scalarFields"), scalarFields, "scalarFields");

        if (!(root.get("repeatedItems") instanceof List<?> rawItems)) {
            throw new ExtractionResponseParseException("Expected extraction reply property repeatedItems to be an array.");
        }
        List<RepeatedItemCandidate> repeatedItems = new ArrayList<>();
        for (Object rawItem : rawItems) {
            repeatedItems.add(new RepeatedItemCandidate(parseFieldMap(rawItem, repeatedFields, "repeatedItems entry")));
        }

        return new ExtractionResult(scalarCandidates, repeatedItems);
    }

    private Map<String, FieldCandidate> parseFieldMap(Object rawValue, List<FieldDefinition> expectedFields, String description)
            throws ExtractionResponseParseException {
        Map<String, Object> raw = objectMap(rawValue, description);
        Set<String> expectedIds = expectedFields.stream().map(FieldDefinition::fieldId).collect(java.util.stream.Collectors.toSet());
        requireExactKeys(raw, expectedIds, description);

        Map<String, FieldCandidate> candidates = new LinkedHashMap<>();
        for (FieldDefinition field : expectedFields) {
            candidates.put(field.fieldId(), parseCandidate(field, objectMap(raw.get(field.fieldId()), field.fieldId())));
        }
        return candidates;
    }

    private FieldCandidate parseCandidate(FieldDefinition field, Map<String, Object> raw) throws ExtractionResponseParseException {
        requireExactKeys(raw, Set.of("value", "evidenceSpanIds", "unresolved", "ambiguityReason"), field.fieldId());

        if (!(raw.get("unresolved") instanceof Boolean unresolved)) {
            throw new ExtractionResponseParseException("Expected " + field.fieldId() + ".unresolved to be a boolean.");
        }
        String value = optionalString(raw, "value", field.fieldId());
        String ambiguityReason = optionalString(raw, "ambiguityReason", field.fieldId());
        List<Long> evidenceSpanIds = requiredLongList(raw, "evidenceSpanIds", field.fieldId());

        if (!unresolved && field.type() == FieldType.DATE && value != null) {
            value = validatedIsoDate(value, field.fieldId());
        }

        try {
            return new FieldCandidate(field.fieldId(), unresolved ? null : value, unresolved ? List.of() : evidenceSpanIds, unresolved, ambiguityReason);
        } catch (IllegalArgumentException e) {
            throw new ExtractionResponseParseException("Self-contradictory candidate for " + field.fieldId() + ": " + e.getMessage(), e);
        }
    }

    private static String validatedIsoDate(String value, String fieldId) throws ExtractionResponseParseException {
        try {
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException e) {
            throw new ExtractionResponseParseException("Expected " + fieldId + " to be an ISO local date, got '" + value + "'.");
        }
    }

    private static Map<String, Object> objectMap(Object value, String description) throws ExtractionResponseParseException {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new ExtractionResponseParseException("Expected " + description + " to be an object.");
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new ExtractionResponseParseException("Expected " + description + " keys to be strings.");
            }
            mapped.put(key, entry.getValue());
        }
        return mapped;
    }

    private static void requireExactKeys(Map<String, Object> value, Set<String> expected, String description)
            throws ExtractionResponseParseException {
        if (!value.keySet().equals(expected)) {
            throw new ExtractionResponseParseException("Unexpected properties in " + description + ": got " + value.keySet() + ", expected " + expected + ".");
        }
    }

    private static String optionalString(Map<String, Object> value, String key, String description) throws ExtractionResponseParseException {
        Object raw = value.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String string)) {
            throw new ExtractionResponseParseException("Expected " + description + "." + key + " to be a string or null.");
        }
        return string;
    }

    private static List<Long> requiredLongList(Map<String, Object> value, String key, String description) throws ExtractionResponseParseException {
        if (!(value.get(key) instanceof List<?> rawValues)) {
            throw new ExtractionResponseParseException("Expected " + description + "." + key + " to be an array.");
        }
        List<Long> values = new ArrayList<>();
        for (Object rawValue : rawValues) {
            if (!(rawValue instanceof Number number)) {
                throw new ExtractionResponseParseException("Expected " + description + "." + key + " entries to be integers.");
            }
            values.add(number.longValue());
        }
        return values;
    }
}
