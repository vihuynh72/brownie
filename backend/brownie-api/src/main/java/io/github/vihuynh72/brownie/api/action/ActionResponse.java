package io.github.vihuynh72.brownie.api.action;

import io.github.vihuynh72.brownie.core.action.ActionAttempt;
import io.github.vihuynh72.brownie.core.action.ActionRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * An action as a page shows it: what it proposes (the payload, exactly as
 * the person approves it, and its hash, which approving must send back),
 * where it stands, and, once it happened, the page at the provider that
 * opens what was made. Never the provider's own identifier for it.
 *
 * <p>{@code state} is what the action has become by now: a proposal or an
 * approval whose time ran out reads as expired even before anything records
 * it. {@code sent} says whether any attempt ever left, which is what tells
 * "nothing happened" from "we could not find out". {@code attemptStopped}
 * says an attempt stopped without finishing and no longer holds the action,
 * so asking Google, or saying the person looked, is what is left to do.
 */
record ActionResponse(
        long id,
        String type,
        long documentId,
        String state,
        JsonNode payload,
        String payloadHash,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime approvedAt,
        OffsetDateTime approvalExpiresAt,
        boolean sent,
        String verification,
        ConversionCheckResponse conversionCheck,
        String failure,
        boolean outcomeAcknowledged,
        String externalLink,
        OffsetDateTime finishedAt,
        boolean attemptStopped) {

    static ActionResponse from(ActionRequest action, List<ActionAttempt> attempts, Instant now, ObjectMapper objectMapper) {
        return new ActionResponse(
                action.id(),
                action.type().name(),
                action.documentId(),
                action.stateAt(now).name(),
                objectMapper.readTree(action.payloadCanonical()),
                action.payloadHash(),
                at(action.createdAt()),
                at(action.expiresAt()),
                at(action.approvedAt()),
                at(action.approvalExpiresAt()),
                attempts.stream().anyMatch(ActionAttempt::wasSent),
                action.verification() == null ? null : action.verification().name(),
                action.checkTotal() == null ? null : new ConversionCheckResponse(action.checkTotal(), action.checkFound()),
                action.failure() == null ? null : action.failure().name(),
                action.outcomeAcknowledgedAt() != null,
                action.externalLink(),
                at(action.finishedAt()),
                action.leaseExpiredAt(now));
    }

    /** For a conversion: of the filled-in values Brownie looked for in the converted result, how many it found. */
    record ConversionCheckResponse(int total, int found) {
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
