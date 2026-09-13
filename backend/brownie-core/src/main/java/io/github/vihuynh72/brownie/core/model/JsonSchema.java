package io.github.vihuynh72.brownie.core.model;

import java.util.Objects;

/**
 * A JSON Schema (2020-12, the shape OpenAI's structured-output feature
 * accepts) that the model's response must conform to. This module has no
 * JSON library of its own to parse and validate {@code schemaJson} against
 * -- it is carried as text and handed to the real adapter, which asks the
 * provider's own client library to parse it when the request is actually
 * built, failing the call rather than silently sending an unchecked
 * string if it is not well-formed. Every request built against this
 * gateway is sent in strict mode; there is no field here to relax that,
 * because the fixed baseline model this gateway targets requires it (see
 * {@link ModelGateway}).
 */
public record JsonSchema(String schemaJson) {

    public JsonSchema {
        Objects.requireNonNull(schemaJson, "schemaJson must not be null");
        if (schemaJson.isBlank()) {
            throw new IllegalArgumentException("schemaJson must not be blank");
        }
    }
}
