package io.github.vihuynh72.brownie.core.generation;

import io.github.vihuynh72.brownie.core.model.ModelCompletion;

/**
 * The model replied, but not with usable content: a refusal, a reply cut
 * off before finishing, or a request the provider rejected outright (see
 * {@link ModelCompletion}'s non-{@code Success} variants). Distinct from
 * {@link ExtractionResponseParseException}, which is a {@code Success}
 * reply that still did not match the expected schema. Retrying, or
 * turning either of these into a persisted, resumable state, is later
 * work -- this task's own scope ends at reporting which case happened.
 */
public class ExtractionFailedException extends Exception {

    public ExtractionFailedException(String message) {
        super(message);
    }
}
