package io.github.vihuynh72.brownie.api.generation.json;

import io.github.vihuynh72.brownie.core.generation.CompositionResponseParseException;
import io.github.vihuynh72.brownie.core.generation.CompositionResponseParser;
import io.github.vihuynh72.brownie.core.generation.FieldCandidate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses a composition reply's raw JSON into one {@link FieldCandidate}
 * per requested composable field, the same generic Map/List-traversal
 * idiom and this project's pinned Jackson 3 ({@code tools.jackson})
 * {@link JacksonExtractionResponseParser} already establishes for its own
 * sibling reply shape.
 */
@Component
class JacksonCompositionResponseParser implements CompositionResponseParser {

    private final ObjectMapper objectMapper;

    JacksonCompositionResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, FieldCandidate> parse(String json, List<String> composableFieldIds) throws CompositionResponseParseException {
        Object decoded;
        try {
            decoded = objectMapper.readValue(json, Object.class);
        } catch (JacksonException e) {
            throw new CompositionResponseParseException("The reply was not valid JSON.", e);
        }
        Map<String, Object> root = objectMap(decoded, "composition reply");
        requireExactKeys(root, Set.of("composedFields"), "composition reply");

        Map<String, Object> rawFields = objectMap(root.get("composedFields"), "composedFields");
        requireExactKeys(rawFields, Set.copyOf(composableFieldIds), "composedFields");

        Map<String, FieldCandidate> candidates = new LinkedHashMap<>();
        for (String fieldId : composableFieldIds) {
            candidates.put(fieldId, parseCandidate(fieldId, objectMap(rawFields.get(fieldId), fieldId)));
        }
        return candidates;
    }

    private FieldCandidate parseCandidate(String fieldId, Map<String, Object> raw) throws CompositionResponseParseException {
        requireExactKeys(raw, Set.of("value", "evidenceSpanIds", "unresolved", "ambiguityReason"), fieldId);

        if (!(raw.get("unresolved") instanceof Boolean unresolved)) {
            throw new CompositionResponseParseException("Expected " + fieldId + ".unresolved to be a boolean.");
        }
        String value = optionalString(raw, "value", fieldId);
        String ambiguityReason = optionalString(raw, "ambiguityReason", fieldId);
        List<Long> evidenceSpanIds = requiredLongList(raw, "evidenceSpanIds", fieldId);

        try {
            return new FieldCandidate(fieldId, unresolved ? null : value, unresolved ? List.of() : evidenceSpanIds, unresolved, ambiguityReason);
        } catch (IllegalArgumentException e) {
            throw new CompositionResponseParseException("Self-contradictory candidate for " + fieldId + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> objectMap(Object value, String description) throws CompositionResponseParseException {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new CompositionResponseParseException("Expected " + description + " to be an object.");
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new CompositionResponseParseException("Expected " + description + " keys to be strings.");
            }
            mapped.put(key, entry.getValue());
        }
        return mapped;
    }

    private static void requireExactKeys(Map<String, Object> value, Set<String> expected, String description)
            throws CompositionResponseParseException {
        if (!value.keySet().equals(expected)) {
            throw new CompositionResponseParseException("Unexpected properties in " + description + ": got " + value.keySet() + ", expected " + expected + ".");
        }
    }

    private static String optionalString(Map<String, Object> value, String key, String description) throws CompositionResponseParseException {
        Object raw = value.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String string)) {
            throw new CompositionResponseParseException("Expected " + description + "." + key + " to be a string or null.");
        }
        return string;
    }

    private static List<Long> requiredLongList(Map<String, Object> value, String key, String description) throws CompositionResponseParseException {
        if (!(value.get(key) instanceof List<?> rawValues)) {
            throw new CompositionResponseParseException("Expected " + description + "." + key + " to be an array.");
        }
        List<Long> values = new ArrayList<>();
        for (Object rawValue : rawValues) {
            if (!(rawValue instanceof Number number)) {
                throw new CompositionResponseParseException("Expected " + description + "." + key + " entries to be integers.");
            }
            values.add(number.longValue());
        }
        return values;
    }
}
