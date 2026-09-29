package io.github.vihuynh72.brownie.core.action;

import java.time.Instant;
import java.util.Objects;

/**
 * One talk with the provider about an action. {@code sentAt} is set just
 * before a change leaves, so an attempt without it certainly sent nothing;
 * {@code externalId} is kept the moment an answer names one, even if the
 * attempt later lost its hold on the action. {@code resultRevision} is the
 * revision Google Docs reports after a change it applied.
 */
public record ActionAttempt(
        long id,
        long actionId,
        int number,
        AttemptKind kind,
        Instant startedAt,
        Instant leaseExpiresAt,
        Instant sentAt,
        Instant finishedAt,
        AttemptOutcome outcome,
        Integer providerStatus,
        String externalId,
        String resultRevision) {

    public ActionAttempt {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
    }

    /** Sent, and not answered in a way that showed the provider did not process it: it may have taken effect. */
    public boolean mayHaveTakenEffect() {
        return wasSent() && outcome != AttemptOutcome.NOT_APPLIED;
    }

    public boolean wasSent() {
        return sentAt != null;
    }
}
