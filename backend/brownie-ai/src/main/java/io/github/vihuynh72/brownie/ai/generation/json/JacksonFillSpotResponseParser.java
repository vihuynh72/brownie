package io.github.vihuynh72.brownie.ai.generation.json;

import io.github.vihuynh72.brownie.core.prepare.FillSpotReply;
import io.github.vihuynh72.brownie.core.prepare.FillSpotResponseParseException;
import io.github.vihuynh72.brownie.core.prepare.FillSpotResponseParser;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses a naming reply's raw JSON into a {@link FillSpotReply}, in the
 * same generic Map/List idiom as {@link JacksonExtractionResponseParser},
 * with this project's pinned Jackson 3. Only the shape is checked here:
 * exactly the asked-for properties, each of the asked-for kind. Whether an
 * id was offered, a label is usable or a row may repeat is for the caller,
 * which knows what it asked. Every failure is a checked {@link
 * FillSpotResponseParseException}, because a third party's reply that does
 * not fit is an expected outcome, answered by naming with the rules.
 */
class JacksonFillSpotResponseParser implements FillSpotResponseParser {

    private static final Set<String> REPLY_PROPERTIES = Set.of("spots", "repeatingRow");
    private static final Set<String> SPOT_PROPERTIES = Set.of("id", "keep", "label", "type", "required");

    private final ObjectMapper objectMapper;

    JacksonFillSpotResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public FillSpotReply parse(String json) throws FillSpotResponseParseException {
        Object decoded;
        try {
            decoded = objectMapper.readValue(json, Object.class);
        } catch (JacksonException e) {
            throw new FillSpotResponseParseException("The reply was not valid JSON.", e);
        }
        Map<String, Object> root = objectMap(decoded, "naming reply");
        requireExactKeys(root, REPLY_PROPERTIES, "naming reply");

        if (!(root.get("spots") instanceof List<?> rawSpots)) {
            throw new FillSpotResponseParseException("Expected naming reply property spots to be an array.");
        }
        List<FillSpotReply.Spot> spots = new ArrayList<>();
        for (Object rawSpot : rawSpots) {
            Map<String, Object> spot = objectMap(rawSpot, "spots entry");
            requireExactKeys(spot, SPOT_PROPERTIES, "spots entry");
            spots.add(new FillSpotReply.Spot(
                    requiredString(spot, "id"),
                    requiredBoolean(spot, "keep"),
                    requiredString(spot, "label"),
                    requiredString(spot, "type"),
                    requiredBoolean(spot, "required")));
        }

        Object repeatingRow = root.get("repeatingRow");
        if (repeatingRow != null && !(repeatingRow instanceof String)) {
            throw new FillSpotResponseParseException("Expected naming reply property repeatingRow to be a string or null.");
        }
        return new FillSpotReply(spots, (String) repeatingRow);
    }

    private static Map<String, Object> objectMap(Object value, String description) throws FillSpotResponseParseException {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new FillSpotResponseParseException("Expected " + description + " to be an object.");
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new FillSpotResponseParseException("Expected " + description + " keys to be strings.");
            }
            mapped.put(key, entry.getValue());
        }
        return mapped;
    }

    private static void requireExactKeys(Map<String, Object> value, Set<String> expected, String description)
            throws FillSpotResponseParseException {
        if (!value.keySet().equals(expected)) {
            throw new FillSpotResponseParseException(
                    "Unexpected properties in " + description + ": got " + value.keySet() + ", expected " + expected + ".");
        }
    }

    private static String requiredString(Map<String, Object> value, String key) throws FillSpotResponseParseException {
        if (!(value.get(key) instanceof String string)) {
            throw new FillSpotResponseParseException("Expected spots entry " + key + " to be a string.");
        }
        return string;
    }

    private static boolean requiredBoolean(Map<String, Object> value, String key) throws FillSpotResponseParseException {
        if (!(value.get(key) instanceof Boolean flag)) {
            throw new FillSpotResponseParseException("Expected spots entry " + key + " to be a boolean.");
        }
        return flag;
    }
}
