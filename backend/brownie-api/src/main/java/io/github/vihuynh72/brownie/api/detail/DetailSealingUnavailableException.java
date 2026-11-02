package io.github.vihuynh72.brownie.api.detail;

/**
 * Sealing was asked for with no {@code brownie.details.key} configured:
 * the API starts anyway, but this is thrown instead of a sensitive value
 * being kept anywhere unsealed. The caller-facing refusal is decided one
 * layer up.
 */
public class DetailSealingUnavailableException extends RuntimeException {

    public DetailSealingUnavailableException() {
        super("BROWNIE_DETAILS_KEY is not configured, so sensitive details cannot be sealed.");
    }
}
