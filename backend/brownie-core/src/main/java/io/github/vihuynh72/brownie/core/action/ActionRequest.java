package io.github.vihuynh72.brownie.core.action;

import java.time.Instant;
import java.util.Objects;

/**
 * One proposed change to a person's outside account and what became of it,
 * as Brownie records it.
 *
 * <p>{@code payloadCanonical} is the exact text the person was shown, and
 * {@code payloadHash} its SHA-256: the approval is bound to that hash, and
 * neither ever changes. The columns from {@code requiredRevisionId} to
 * {@code targetExternalId} repeat, typed, the facts the approval depends on
 * (the document's revision, its latest export, the Google Doc to add to), so
 * that they can be checked under lock at the moment the change starts.
 * {@code providerKey} is what the provider accepts to recognise a repeated
 * request, fixed when the action was proposed.
 *
 * <p>{@code checkTotal} and {@code checkFound} are, for a conversion, how
 * many filled-in values were looked for in the converted result and how many
 * were found.
 *
 * <p>{@code externalId} is the provider's own identifier for what was made.
 * It is kept here and never shown to the person, who is given {@code
 * externalLink}, the page that opens it.
 */
public record ActionRequest(
        long id,
        long workspaceId,
        long userId,
        long documentId,
        long connectionId,
        ActionType type,
        String payloadCanonical,
        String payloadHash,
        String siblingKey,
        Long requiredRevisionId,
        Long exportReceiptId,
        Long targetActionId,
        String targetExternalId,
        String providerKey,
        ActionState state,
        Instant createdAt,
        Instant expiresAt,
        Instant approvedAt,
        Instant approvalExpiresAt,
        Long currentAttemptId,
        Instant leaseExpiresAt,
        String externalId,
        String externalLink,
        ActionVerification verification,
        Integer checkTotal,
        Integer checkFound,
        ActionFailure failure,
        Instant outcomeAcknowledgedAt,
        Instant finishedAt) {

    public ActionRequest {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadCanonical, "payloadCanonical");
        Objects.requireNonNull(payloadHash, "payloadHash");
        Objects.requireNonNull(siblingKey, "siblingKey");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /**
     * The state as the person should read it now. A proposal or an approval
     * whose time has run out is recorded as expired only by the next change
     * that touches it, since reading never writes; until then it is shown as
     * what it has become.
     */
    public ActionState stateAt(Instant now) {
        if (state == ActionState.AWAITING_APPROVAL && !now.isBefore(expiresAt)) {
            return ActionState.EXPIRED;
        }
        if (state == ActionState.APPROVED && approvalExpiresAt != null && !now.isBefore(approvalExpiresAt)) {
            return ActionState.EXPIRED;
        }
        return state;
    }

    /** An attempt holds it, and that attempt's time has run out without it finishing. */
    public boolean leaseExpiredAt(Instant now) {
        return (state == ActionState.EXECUTING || state == ActionState.RECONCILING)
                && leaseExpiresAt != null && !now.isBefore(leaseExpiresAt);
    }

    /** The payload and nothing about it: a payload holds what a person wrote, which never belongs in a log line. */
    @Override
    public String toString() {
        return "ActionRequest[id=" + id + ", type=" + type + ", state=" + state + "]";
    }
}
