package io.github.vihuynh72.brownie.core.generation;

import io.github.vihuynh72.brownie.core.model.ModelCompletion;

/**
 * The model replied, but not with usable content: a refusal, a reply cut
 * off before finishing, or a request the provider rejected outright (see
 * {@link ModelCompletion}'s non-{@code Success} variants). Distinct from
 * {@link CompositionResponseParseException}, which is a {@code Success}
 * reply that still did not match the expected schema -- the same split
 * {@link ExtractionFailedException} already draws for extraction.
 */
public class CompositionFailedException extends Exception {

    public CompositionFailedException(String message) {
        super(message);
    }
}
