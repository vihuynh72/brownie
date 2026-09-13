package io.github.vihuynh72.brownie.core.model;

/**
 * What a physical model call actually produced, once the provider has
 * replied at all -- a reply that never arrived (network failure, timeout,
 * or a provider-side error the request cannot recover from by itself) is
 * not a value of this type; the gateway throws {@link
 * ModelTransportException} for that instead, the same distinction {@code
 * MalwareScanner} draws between "the scanner was unavailable" and "the
 * scanner said this is clean." Every variant here is a reply the provider
 * completed, but which still may not be a usable generation: a successful
 * transport response with truncated or refused content is not the same
 * as a successful generation, and a caller must not treat it as one.
 */
public sealed interface ModelCompletion {

    /** The model replied with content that matches the requested schema's basic JSON shape. Schema conformance constrains structure, not truth -- the caller still validates the content's actual claims. */
    record Success(String content, ModelUsage usage) implements ModelCompletion {
    }

    /** The provider declined to answer this request at all; {@code reason} is whatever explanation it gave, never fabricated by this gateway when none was given. */
    record Refusal(String reason, ModelUsage usage) implements ModelCompletion {
    }

    /** The model finished normally but its content does not parse as JSON, or does not match the requested schema's basic shape. */
    record MalformedOutput(String rawContent, String reason, ModelUsage usage) implements ModelCompletion {
    }

    /** The model was cut off before finishing, most often by hitting {@code maxOutputTokens} -- {@code partialContent} is not a usable result on its own. */
    record IncompleteOutput(String partialContent, String reason, ModelUsage usage) implements ModelCompletion {
    }

    /**
     * The provider rejected the request itself over a parameter this
     * gateway sent -- not the content of any reply, so there is no {@link
     * ModelUsage} to report. Retrying the identical request will not
     * help; this is a caller or configuration defect, not a transient
     * provider condition.
     */
    record UnsupportedParameters(String reason) implements ModelCompletion {
    }
}
