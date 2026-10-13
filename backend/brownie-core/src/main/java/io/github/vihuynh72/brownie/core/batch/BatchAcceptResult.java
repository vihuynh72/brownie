package io.github.vihuynh72.brownie.core.batch;

import java.util.List;

/**
 * The shape of one bounded accept call. The step evaluates every row
 * (each verdict is cheap once the set-based detail is loaded) but only
 * writes up to the accept budget of revisions per call; {@code remaining}
 * counts rows still accept-eligible afterwards, so the caller stops when
 * it reaches zero and knows the rest were deliberately skipped.
 */
public record BatchAcceptResult(
        int processed,
        int remaining,
        Long retryAfterMs,
        List<AcceptRowResult> rows) {
}
