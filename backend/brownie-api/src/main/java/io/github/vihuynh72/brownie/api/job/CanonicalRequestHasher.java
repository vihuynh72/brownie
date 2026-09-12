package io.github.vihuynh72.brownie.api.job;

import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonicalizes a JSON-shaped command by recursively sorting object member
 * names before calculating its request digest. Array order remains meaningful.
 * The canonical serialization is used only transiently; the persistence layer
 * stores the resulting digest rather than the submitted body.
 */
public final class CanonicalRequestHasher {

    private final ObjectMapper objectMapper;

    public CanonicalRequestHasher(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public CanonicalRequestHash hash(Object request) {
        Objects.requireNonNull(request, "request must not be null");
        return hashTree(objectMapper.valueToTree(request));
    }

    public CanonicalRequestHash hashJson(String requestJson) {
        Objects.requireNonNull(requestJson, "requestJson must not be null");
        try {
            JsonNode node = objectMapper.readTree(requestJson);
            if (node == null) {
                throw new IllegalArgumentException("Request JSON must contain one value.");
            }
            return hashTree(node);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Request JSON must be valid JSON.", exception);
        }
    }

    private CanonicalRequestHash hashTree(JsonNode node) {
        try {
            return CanonicalRequestHash.sha256OfCanonicalText(objectMapper.writeValueAsString(canonicalize(node)));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Request could not be serialized as canonical JSON.", exception);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            return canonicalObject(node);
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : node) {
                result.add(canonicalize(item));
            }
            return result;
        }
        return node;
    }

    private ObjectNode canonicalObject(JsonNode node) {
        List<Map.Entry<String, JsonNode>> fields = new ArrayList<>(node.properties());
        fields.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));

        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, JsonNode> field : fields) {
            result.set(field.getKey(), canonicalize(field.getValue()));
        }
        return result;
    }
}
