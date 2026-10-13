package io.github.vihuynh72.brownie.ai.generation.json;

import io.github.vihuynh72.brownie.core.batch.BatchComposeReply;
import io.github.vihuynh72.brownie.core.batch.BatchComposeReplyParseException;
import io.github.vihuynh72.brownie.core.batch.BatchComposeReplyParser;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The strict decode of a compose reply: the root is exactly
 * {@code {"places": {...}}}, {@code places} holds exactly the field ids
 * the request asked for, and each entry is exactly {@code {"text":
 * "..."}} with a string. Anything else -- extra keys, an id that was not
 * asked for, a missing one, a non-string text -- rejects the whole reply,
 * since a model that showed it could not keep the shape cannot be trusted
 * for the parts that happened to look right.
 */
class JacksonBatchComposeReplyParser implements BatchComposeReplyParser {

    private static final Set<String> REPLY_PROPERTIES = Set.of("places");
    private static final Set<String> PLACE_PROPERTIES = Set.of("text");

    private final ObjectMapper objectMapper;

    JacksonBatchComposeReplyParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public BatchComposeReply parse(String json, Set<String> expectedIds) throws BatchComposeReplyParseException {
        Object decoded;
        try {
            decoded = objectMapper.readValue(json, Object.class);
        } catch (JacksonException e) {
            throw new BatchComposeReplyParseException("The reply was not valid JSON.", e);
        }
        Map<String, Object> root = objectMap(decoded, "compose reply");
        requireExactKeys(root, REPLY_PROPERTIES, "compose reply");

        if (!(root.get("places") instanceof Map<?, ?> rawPlaces)) {
            throw new BatchComposeReplyParseException("Expected compose reply property places to be an object.");
        }
        Map<String, Object> places = objectMap(rawPlaces, "places");
        if (!places.keySet().equals(expectedIds)) {
            throw new BatchComposeReplyParseException(
                    "The reply's places do not match the fields that were asked for.");
        }
        Map<String, String> texts = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : places.entrySet()) {
            Map<String, Object> place = objectMap(entry.getValue(), "places." + entry.getKey());
            requireExactKeys(place, PLACE_PROPERTIES, "places." + entry.getKey());
            Object text = place.get("text");
            if (!(text instanceof String)) {
                throw new BatchComposeReplyParseException(
                        "Expected compose reply places." + entry.getKey() + ".text to be a string.");
            }
            texts.put(entry.getKey(), (String) text);
        }
        return new BatchComposeReply(texts);
    }

    private static Map<String, Object> objectMap(Object value, String description) throws BatchComposeReplyParseException {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new BatchComposeReplyParseException("Expected " + description + " to be an object.");
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new BatchComposeReplyParseException("Expected " + description + " keys to be strings.");
            }
            mapped.put(key, entry.getValue());
        }
        return mapped;
    }

    private static void requireExactKeys(Map<String, Object> value, Set<String> expected, String description)
            throws BatchComposeReplyParseException {
        if (!value.keySet().equals(expected)) {
            throw new BatchComposeReplyParseException(
                    "Expected " + description + " to have exactly the keys " + expected + ", found " + value.keySet() + ".");
        }
    }
}
