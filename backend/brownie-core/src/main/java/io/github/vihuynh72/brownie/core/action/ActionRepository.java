package io.github.vihuynh72.brownie.core.action;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Tenant-scoped persistence for a person's actions. Every method sees only
 * the caller's own actions in the named workspace. Apart from recording a new
 * proposal, every change goes through the database's own routines, which
 * hold the lifecycle's rules and check them under lock; the methods here
 * report what those routines answered rather than deciding it.
 */
public interface ActionRepository {

    /** Records a new proposal, awaiting approval, in the person's own name. */
    ActionRequest propose(long workspaceId, long userId, NewAction action);

    Optional<ActionRequest> find(long workspaceId, long userId, long actionId);

    /** Every action proposed for this document, newest first. */
    List<ActionRequest> findForDocument(long workspaceId, long userId, long documentId);

    /** Every attempt made for this action, oldest first. */
    List<ActionAttempt> attempts(long workspaceId, long userId, long actionId);

    /**
     * Whether another action of this person for the same change (the same
     * sibling key) was sent after {@code since} in a way that may have taken
     * effect: a send the provider answered as not processed does not count.
     */
    boolean siblingSentAfter(long workspaceId, long userId, long actionId, Instant since);

    /** Approves and claims, or claims again under the same approval, if every fact the approval depends on still holds. */
    Claim claim(long workspaceId, long userId, long actionId, String presentedHash, int leaseSeconds);

    /** Claims an action whose outcome is unknown, or whose attempt lost its hold, to ask the provider what became of it. */
    Claim claimReconcile(long workspaceId, long userId, long actionId, long connectionId, int leaseSeconds);

    /** Whether this attempt may send now, asked just before the change leaves. */
    SendPermission markSent(long workspaceId, long userId, long actionId, long attemptId, int minLeaseSeconds);

    /** Keeps the provider's id for what it made, the moment an answer names one. */
    void recordExternalId(long workspaceId, long userId, long actionId, long attemptId, String externalId);

    FinishResult finish(long workspaceId, long userId, long actionId, long attemptId, AttemptResult result);

    /** Ends an action nothing was sent for, because what it depends on changed before sending. False when it was not open. */
    boolean failBeforeSending(long workspaceId, long userId, long actionId, ActionFailure reason);

    /** False when it could no longer be cancelled. */
    boolean cancel(long workspaceId, long userId, long actionId);

    /** False when its outcome was not unknown, or was already acknowledged. */
    boolean acknowledgeUnknown(long workspaceId, long userId, long actionId);

    /** What a claim answered, and the attempt and lease it holds when it succeeded. */
    record Claim(ClaimOutcome outcome, Long attemptId, Instant leaseExpiresAt) {
    }

    enum ClaimOutcome {
        CLAIMED,
        NOT_FOUND,
        /** The presented hash is not the hash of the stored payload. */
        HASH_MISMATCH,
        /** Its connection is waiting for its person to connect again; the action is unchanged. */
        CONNECTION_UNUSABLE,
        /** The same change is under way, or its outcome is unknown and unacknowledged, through another action. */
        SIBLING_UNRESOLVED,
        /** It was not in a state this claim can take; read it for what it is now. */
        NOT_CLAIMABLE,
        /** It was ended by this claim (expired, or a fact it depended on changed); read it for why. */
        ENDED
    }

    enum SendPermission {
        SEND,
        NOT_FOUND,
        GONE,
        FENCED_OUT,
        LEASE_TOO_SHORT,
        ALREADY_SENT
    }

    enum FinishResult {
        FINISHED,
        NOT_FOUND,
        /** The document or workspace was deleted meanwhile; nothing is left to record against. */
        GONE,
        /** Another attempt took the action over; this one's result is not recorded on it. */
        FENCED_OUT
    }

    /** How an attempt ended, and the action with it. */
    record AttemptResult(
            ActionState nextState,
            AttemptOutcome outcome,
            ActionVerification verification,
            ActionFailure failure,
            String externalId,
            String externalLink,
            Integer providerStatus,
            List<String> providerReasons,
            String resultRevision,
            ConversionCount conversionCount) {

        public AttemptResult {
            providerReasons = providerReasons == null ? List.of() : List.copyOf(providerReasons);
        }
    }
}
