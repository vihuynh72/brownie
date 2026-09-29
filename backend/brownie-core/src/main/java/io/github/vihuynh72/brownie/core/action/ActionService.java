package io.github.vihuynh72.brownie.core.action;

import io.github.vihuynh72.brownie.core.connector.Connection;
import io.github.vihuynh72.brownie.core.connector.ConnectionReconnectRequiredException;
import io.github.vihuynh72.brownie.core.connector.ConnectorService;
import io.github.vihuynh72.brownie.core.connector.ProviderTokenRejectedException;
import io.github.vihuynh72.brownie.core.connector.ReconnectReason;
import io.github.vihuynh72.brownie.core.connector.UsableConnection;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapabilities;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The only way Brownie changes anything in a person's outside account.
 *
 * <p>A change is first proposed: its exact payload is recorded and shown, and
 * nothing is sent. It is carried out only when the person who proposed it
 * approves it with the hash of that payload, and only if, at that moment and
 * under lock, everything the approval depended on still holds. Then it is
 * sent once, what the provider made is read back and compared with what was
 * approved, and the outcome is recorded. Everything happens during the
 * request that asked for it; no database transaction is open while the
 * provider is being asked anything.
 *
 * <p>An answer that is lost after the change was sent means the change may
 * have happened. Such an action is never sent again until the provider has
 * been asked what became of it, and some kinds (a conversion, an event) are
 * never sent again at all, because the provider cannot be relied on to
 * recognise a repeat. Retrying an action that certainly did not happen is
 * approving it again with the same hash while its approval lasts.
 */
public class ActionService {

    private static final Logger log = LoggerFactory.getLogger(ActionService.class);

    /** How long an attempt holds an action: longer than the slowest request it makes (an upload) with its readback. */
    public static final int EXECUTE_LEASE_SECONDS = 180;
    public static final int RECONCILE_LEASE_SECONDS = 120;
    /**
     * Sending starts only while at least this much of the lease is left, so a
     * request that stalled before sending cannot send after another attempt
     * took the action over. It must exceed the longest a single request can take.
     */
    public static final int MIN_SEND_LEASE_SECONDS = 90;
    /**
     * How long after a change was sent a provider's "not found" is believed.
     * Right after a timeout, the change may still be on its way or not yet
     * visible; after this, it would have arrived.
     */
    public static final Duration SETTLE_INTERVAL = Duration.ofMinutes(5);

    private final ActionRepository actionRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ConnectorService connectorService;
    private final ActionOffer offer;
    private final Map<ActionType, ActionHandler> handlers;
    private final Clock clock;

    public ActionService(
            ActionRepository actionRepository,
            WorkspaceRepository workspaceRepository,
            ConnectorService connectorService,
            ActionOffer offer,
            List<ActionHandler> handlers,
            Clock clock) {
        this.actionRepository = Objects.requireNonNull(actionRepository, "actionRepository");
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository");
        this.connectorService = Objects.requireNonNull(connectorService, "connectorService");
        this.offer = Objects.requireNonNull(offer, "offer");
        this.clock = Objects.requireNonNull(clock, "clock");
        Map<ActionType, ActionHandler> byType = new EnumMap<>(ActionType.class);
        for (ActionHandler handler : handlers) {
            if (byType.put(handler.type(), handler) != null) {
                throw new IllegalArgumentException("Two handlers for " + handler.type() + ".");
            }
        }
        this.handlers = Map.copyOf(byType);
    }

    /**
     * Records a proposal built by the service that knows its kind. Nothing is
     * sent. The payload must already be canonical: it was written by Brownie,
     * so anything else is a defect, not a person's mistake.
     */
    public ActionRequest propose(long workspaceId, long userId, NewAction action) {
        requirePermitted(workspaceId, userId);
        requireOffered(action.type());
        handler(action.type());
        requireCanonical(action.payloadCanonical());
        return actionRepository.propose(workspaceId, userId, action);
    }

    /**
     * Whether this person may propose this kind of change here at all, asked
     * by the service that builds a proposal before it asks the provider
     * anything on their behalf.
     */
    public void requireProposable(long workspaceId, long userId, ActionType type) {
        requirePermitted(workspaceId, userId);
        requireOffered(type);
        handler(type);
    }

    public Optional<ActionRequest> find(long workspaceId, long userId, long actionId) {
        return actionRepository.find(workspaceId, userId, actionId);
    }

    public List<ActionRequest> forDocument(long workspaceId, long userId, long documentId) {
        return actionRepository.findForDocument(workspaceId, userId, documentId);
    }

    public List<ActionAttempt> attempts(long workspaceId, long userId, long actionId) {
        require(workspaceId, userId, actionId);
        return actionRepository.attempts(workspaceId, userId, actionId);
    }

    public Instant now() {
        return clock.instant();
    }

    /**
     * Approves the action with the hash of the payload the person was shown,
     * and carries it out; or, for an action already approved whose earlier
     * attempt certainly did not happen, carries it out again under the same
     * approval. Any other action is answered as it is, and nothing is sent.
     */
    public ActionRequest approve(long workspaceId, long userId, long actionId, String presentedHash) {
        ActionRequest action = require(workspaceId, userId, actionId);
        if (action.state() != ActionState.AWAITING_APPROVAL && action.state() != ActionState.APPROVED) {
            return action;
        }
        // Checked here, before anything is recorded, and again under lock by the claim below.
        requirePermitted(workspaceId, userId);
        requireOffered(action.type());
        requireCanonical(action.payloadCanonical());
        if (presentedHash == null || !presentedHash.equals(action.payloadHash())) {
            throw new ActionPayloadMismatchException(actionId);
        }
        ActionHandler handler = handler(action.type());
        UsableConnection connection = connectorService.use(workspaceId, userId, handler.access());

        // An approval that ran out, or one made through a connection since replaced, is not prepared: nothing is
        // read with the other connection, and the claim below records which of the two it was.
        PreparedWrite prepared = null;
        if (connection.connection().id() == action.connectionId() && action.stateAt(clock.instant()) != ActionState.EXPIRED) {
            try {
                prepared = handler.prepare(action, connection);
            } catch (ActionChangedException e) {
                actionRepository.failBeforeSending(workspaceId, userId, actionId, e.failure());
                return require(workspaceId, userId, actionId);
            } catch (ProviderTokenRejectedException e) {
                throw connectorService.tokenRefusedDuringUse(connection.connection());
            }
        }

        ActionRepository.Claim claim =
                actionRepository.claim(workspaceId, userId, actionId, presentedHash, EXECUTE_LEASE_SECONDS);
        switch (claim.outcome()) {
            case CLAIMED -> {
                if (prepared == null) {
                    // Claimed after all: still nothing was prepared, so the attempt ends without sending.
                    actionRepository.finish(workspaceId, userId, actionId, claim.attemptId(), new ActionRepository.AttemptResult(
                            ActionState.APPROVED, AttemptOutcome.NOT_SENT, null, null, null, null, null, List.of(), null, null));
                    return require(workspaceId, userId, actionId);
                }
                return carryOut(workspaceId, userId, action, claim.attemptId(), handler, prepared, connection);
            }
            case NOT_FOUND -> throw new ActionNotFoundException(actionId);
            case HASH_MISMATCH -> throw new ActionPayloadMismatchException(actionId);
            case SIBLING_UNRESOLVED -> throw new ActionSiblingUnresolvedException(actionId);
            case CONNECTION_UNUSABLE -> throw reconnectRequired(workspaceId, userId, action, handler);
            default -> {
                // Expired, ended because something it depended on changed, or no longer approvable: the action says which.
                return require(workspaceId, userId, actionId);
            }
        }
    }

    /**
     * Asks the provider what became of an action whose outcome is unknown, or
     * whose attempt stopped without finishing. An action in any other state
     * is answered as it is.
     */
    public ActionRequest reconcile(long workspaceId, long userId, long actionId) {
        ActionRequest action = require(workspaceId, userId, actionId);
        Instant now = clock.instant();
        if (action.state() != ActionState.OUTCOME_UNKNOWN && !action.leaseExpiredAt(now)) {
            return action;
        }
        requirePermitted(workspaceId, userId);
        requireCanonical(action.payloadCanonical());
        ActionHandler handler = handler(action.type());
        UsableConnection connection = connectorService.use(workspaceId, userId, handler.access());
        ActionRepository.Claim claim = actionRepository.claimReconcile(
                workspaceId, userId, actionId, connection.connection().id(), RECONCILE_LEASE_SECONDS);
        switch (claim.outcome()) {
            case CLAIMED -> {
            }
            case NOT_FOUND -> throw new ActionNotFoundException(actionId);
            case CONNECTION_UNUSABLE -> throw new ActionConnectionUnusableException(actionId);
            case SIBLING_UNRESOLVED -> throw new ActionSiblingUnresolvedException(actionId);
            default -> {
                return require(workspaceId, userId, actionId);
            }
        }
        ActionRequest claimed = require(workspaceId, userId, actionId);
        List<ActionAttempt> attempts = actionRepository.attempts(workspaceId, userId, actionId);
        ActionOutcome outcome;
        if (attempts.stream().noneMatch(ActionAttempt::mayHaveTakenEffect)) {
            // Every earlier attempt stopped before anything left, or was answered in a way that showed it was not processed.
            outcome = new ActionOutcome.NotApplied();
        } else if (handler.recognisedByContentOnly() && actionRepository.siblingSentAfter(workspaceId, userId, actionId,
                attempts.stream().map(ActionAttempt::sentAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElseThrow())) {
            // The same change was sent again since, through another action: what the provider holds now could be that
            // one's doing as well as this one's, so this one stays unknown.
            outcome = new ActionOutcome.StillUnknown(null);
        } else {
            try {
                outcome = handler.reconcile(claimed, connection, attempts, now);
            } catch (ProviderTokenRejectedException e) {
                connectorService.tokenRefusedDuringUse(connection.connection());
                outcome = new ActionOutcome.StillUnknown(null);
            } catch (RuntimeException e) {
                log.warn("Asking about a {} action failed ({}); its outcome stays unknown.", action.type(), e.getClass().getSimpleName());
                outcome = new ActionOutcome.StillUnknown(null);
            }
        }
        finish(workspaceId, userId, claimed, claim.attemptId(), outcome, null);
        return require(workspaceId, userId, actionId);
    }

    /** Withdraws an action nothing has been sent for yet; any other is answered as it is. */
    public ActionRequest cancel(long workspaceId, long userId, long actionId) {
        require(workspaceId, userId, actionId);
        actionRepository.cancel(workspaceId, userId, actionId);
        return require(workspaceId, userId, actionId);
    }

    /** The person says they have checked an outcome Brownie cannot know; the same change may then be proposed and sent again. */
    public ActionRequest acknowledgeUnknown(long workspaceId, long userId, long actionId) {
        require(workspaceId, userId, actionId);
        actionRepository.acknowledgeUnknown(workspaceId, userId, actionId);
        return require(workspaceId, userId, actionId);
    }

    private ActionRequest carryOut(long workspaceId, long userId, ActionRequest action, long attemptId, ActionHandler handler,
            PreparedWrite prepared, UsableConnection connection) {
        ActionRepository.SendPermission permission =
                actionRepository.markSent(workspaceId, userId, action.id(), attemptId, MIN_SEND_LEASE_SECONDS);
        if (permission != ActionRepository.SendPermission.SEND) {
            if (permission == ActionRepository.SendPermission.LEASE_TOO_SHORT) {
                actionRepository.finish(workspaceId, userId, action.id(), attemptId, new ActionRepository.AttemptResult(
                        ActionState.APPROVED, AttemptOutcome.NOT_SENT, null, null, null, null, null, List.of(), null, null));
            }
            return require(workspaceId, userId, action.id());
        }

        WriteAnswer answer;
        try {
            answer = prepared.send(connection);
        } catch (RuntimeException e) {
            // After the change may have left, a failure of Brownie's own is still an unknown outcome, never a "not sent".
            log.warn("Sending a {} action failed inside Brownie ({}); its outcome is unknown.", action.type(), e.getClass().getSimpleName());
            answer = new WriteAnswer.Unknown(null, List.of());
        }
        if (answer instanceof WriteAnswer.Applied applied && applied.externalId() != null) {
            actionRepository.recordExternalId(workspaceId, userId, action.id(), attemptId, applied.externalId());
        }

        ActionOutcome outcome = switch (answer) {
            case WriteAnswer.Applied applied -> readBack(prepared, connection, applied, action);
            case WriteAnswer.Exists exists -> handler.recognisedByContentOnly()
                    ? alreadyThere(workspaceId, userId, action, attemptId, prepared, connection, exists)
                    : readBack(prepared, connection, exists, action);
            case WriteAnswer.NotAppliedRetryable retryable -> {
                if (retryable.tokenRefused()) {
                    connectorService.tokenRefusedDuringUse(connection.connection());
                }
                yield new ActionOutcome.NotApplied();
            }
            case WriteAnswer.NotAppliedFinal refused -> new ActionOutcome.Refused(refused.failure());
            case WriteAnswer.Unknown unknown -> new ActionOutcome.StillUnknown(null);
        };
        finish(workspaceId, userId, action, attemptId, outcome, answer);
        return require(workspaceId, userId, action.id());
    }

    /**
     * The provider refused this send because what it would make is already
     * there, for a change it knows only by its content. Only an earlier send
     * of this very action can have put it there, and only if the same change
     * was not sent again since through another action: otherwise this request
     * made nothing, and what is there is someone else's doing.
     */
    private ActionOutcome alreadyThere(long workspaceId, long userId, ActionRequest action, long attemptId, PreparedWrite prepared,
            UsableConnection connection, WriteAnswer.Exists exists) {
        Optional<Instant> firstEarlierSend = actionRepository.attempts(workspaceId, userId, action.id()).stream()
                .filter(attempt -> attempt.id() != attemptId && attempt.mayHaveTakenEffect())
                .map(ActionAttempt::sentAt)
                .min(Comparator.naturalOrder());
        if (firstEarlierSend.isEmpty()) {
            return new ActionOutcome.Refused(ActionFailure.TARGET_CHANGED);
        }
        if (actionRepository.siblingSentAfter(workspaceId, userId, action.id(), firstEarlierSend.get())) {
            return new ActionOutcome.StillUnknown(null);
        }
        return readBack(prepared, connection, exists, action);
    }

    /** A readback that cannot be done now leaves the outcome unknown, with whatever id the answer named. */
    private ActionOutcome readBack(PreparedWrite prepared, UsableConnection connection, WriteAnswer answer, ActionRequest action) {
        String externalId = answer instanceof WriteAnswer.Applied applied ? applied.externalId() : null;
        try {
            return prepared.readBack(connection, answer);
        } catch (RuntimeException e) {
            log.info("Reading back a {} action could not be done now ({}); its outcome is unknown until asked again.",
                    action.type(), e.getClass().getSimpleName());
            return new ActionOutcome.StillUnknown(externalId);
        }
    }

    private void finish(long workspaceId, long userId, ActionRequest action, long attemptId, ActionOutcome outcome, WriteAnswer answer) {
        Integer status = answer == null ? null : answer.status();
        List<String> reasons = answer == null ? List.of() : answer.reasons();
        String resultRevision = answer instanceof WriteAnswer.Applied applied ? applied.resultRevision() : null;
        ActionRepository.AttemptResult result = switch (outcome) {
            case ActionOutcome.Done done -> new ActionRepository.AttemptResult(
                    ActionState.SUCCEEDED, AttemptOutcome.APPLIED, done.verification(), null, done.externalId(), done.link(),
                    status, reasons, resultRevision, done.conversionCount());
            case ActionOutcome.Mismatched mismatched -> new ActionRepository.AttemptResult(
                    ActionState.FAILED, AttemptOutcome.APPLIED, ActionVerification.MISMATCHED, ActionFailure.READBACK_MISMATCH,
                    mismatched.externalId(), mismatched.link(), status, reasons, resultRevision, null);
            case ActionOutcome.NotApplied ignored -> new ActionRepository.AttemptResult(
                    ActionState.APPROVED, AttemptOutcome.NOT_APPLIED, null, null, null, null, status, reasons, null, null);
            case ActionOutcome.Refused refused -> new ActionRepository.AttemptResult(
                    ActionState.FAILED, AttemptOutcome.NOT_APPLIED, null, refused.failure(), null, null, status, reasons, null, null);
            case ActionOutcome.StillUnknown unknown -> new ActionRepository.AttemptResult(
                    ActionState.OUTCOME_UNKNOWN, AttemptOutcome.UNKNOWN, null, null, unknown.externalId(), null, status, reasons, null, null);
        };
        ActionRepository.FinishResult finished = actionRepository.finish(workspaceId, userId, action.id(), attemptId, result);
        if (finished != ActionRepository.FinishResult.FINISHED) {
            // Deleted meanwhile, or taken over after this attempt's lease ran out: its result is kept on the attempt only.
            log.info("A {} action's attempt ended as {} but was not recorded on the action ({}).",
                    action.type(), result.nextState(), finished);
        }
    }

    private ConnectionReconnectRequiredException reconnectRequired(
            long workspaceId, long userId, ActionRequest action, ActionHandler handler) {
        ReconnectReason reason = connectorService.connections(workspaceId, userId).stream()
                .filter(connection -> connection.id() == action.connectionId())
                .map(Connection::reconnectReason)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(ReconnectReason.TOKEN_REJECTED);
        return new ConnectionReconnectRequiredException(handler.access(), reason);
    }

    private ActionRequest require(long workspaceId, long userId, long actionId) {
        return actionRepository.find(workspaceId, userId, actionId).orElseThrow(() -> new ActionNotFoundException(actionId));
    }

    private void requirePermitted(long workspaceId, long userId) {
        boolean permitted = workspaceRepository.findRole(workspaceId, userId)
                .map(role -> WorkspaceCapabilities.grants(role, WorkspaceCapability.ACT_ON_CONNECTED_ACCOUNTS))
                .orElse(false);
        if (!permitted) {
            throw new ActionNotPermittedException();
        }
    }

    private void requireOffered(ActionType type) {
        if (!offer.offered(type)) {
            throw new ActionNotOfferedException(type);
        }
    }

    private ActionHandler handler(ActionType type) {
        ActionHandler handler = handlers.get(type);
        if (handler == null) {
            throw new ActionNotOfferedException(type);
        }
        return handler;
    }

    /** The stored text must be exactly one canonical spelling; the database already ties its hash to it. */
    private static void requireCanonical(String payloadCanonical) {
        try {
            CanonicalJson.read(payloadCanonical);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("A stored action payload is not canonical; nothing is sent for it.", e);
        }
    }
}
