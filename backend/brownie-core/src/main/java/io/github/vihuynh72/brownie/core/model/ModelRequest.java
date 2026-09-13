package io.github.vihuynh72.brownie.core.model;

import java.util.List;
import java.util.Objects;

/**
 * One bounded request to the model gateway. {@code promptVersion} is a
 * caller-assigned identifier for the exact prompt-construction logic that
 * built {@code messages} -- this module does not interpret it, but a
 * caller freezing a generation run records it alongside the model
 * snapshot and schema version, so a later prompt change is visible in
 * what was actually used to produce a given result, not silently
 * indistinguishable from it. The gateway itself sends exactly the
 * messages given, in order, to exactly one fixed, centrally configured
 * model -- there is no field here to choose a different model per
 * request, so a request can never accidentally (or by injected instruction)
 * select a different, unevaluated model. {@code maxOutputTokens} bounds
 * this one physical call; enforcing an aggregate budget across an entire
 * generation run is the caller's responsibility, not this type's.
 */
public record ModelRequest(String promptVersion, List<ModelMessage> messages, JsonSchema responseSchema, int maxOutputTokens) {

    public ModelRequest {
        Objects.requireNonNull(promptVersion, "promptVersion must not be null");
        if (promptVersion.isBlank()) {
            throw new IllegalArgumentException("promptVersion must not be blank");
        }
        Objects.requireNonNull(messages, "messages must not be null");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages = List.copyOf(messages);
        Objects.requireNonNull(responseSchema, "responseSchema must not be null");
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive, was " + maxOutputTokens);
        }
    }
}
