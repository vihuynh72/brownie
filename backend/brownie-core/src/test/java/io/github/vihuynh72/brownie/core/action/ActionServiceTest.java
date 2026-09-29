package io.github.vihuynh72.brownie.core.action;

import io.github.vihuynh72.brownie.core.connector.Connection;
import io.github.vihuynh72.brownie.core.connector.ConnectionReconnectRequiredException;
import io.github.vihuynh72.brownie.core.connector.ConnectionRepository;
import io.github.vihuynh72.brownie.core.connector.ConnectionState;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorOAuthClient;
import io.github.vihuynh72.brownie.core.connector.ConnectorService;
import io.github.vihuynh72.brownie.core.connector.ConnectorTokenCipher;
import io.github.vihuynh72.brownie.core.connector.ProviderAccount;
import io.github.vihuynh72.brownie.core.connector.ProviderRevocation;
import io.github.vihuynh72.brownie.core.connector.ProviderTokens;
import io.github.vihuynh72.brownie.core.connector.ReconnectReason;
import io.github.vihuynh72.brownie.core.connector.SealedToken;
import io.github.vihuynh72.brownie.core.connector.TokenBinding;
import io.github.vihuynh72.brownie.core.connector.UsableConnection;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceMember;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service's decisions, around a scripted repository (which answers as
 * the database's routines would, and whose own rules are proven against a
 * real database elsewhere) and a scripted handler: the order things happen
 * in, what is never sent, and what each answer from the provider is taken to
 * mean. Every test also checks what did not happen.
 */
class ActionServiceTest {

    private static final long WORKSPACE = 7;
    private static final long PERSON = 3;
    private static final long ACTION = 90;
    private static final long CONNECTION = 50;
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final String PAYLOAD = CanonicalJson.write(Map.of("fileName", "Minutes.docx", "nonce", "n-1"));
    private static final String HASH = CanonicalJson.sha256Hex(PAYLOAD);

    private final List<String> steps = new ArrayList<>();
    private ScriptedActions actions;
    private ScriptedHandler handler;
    private OneConnection connection;
    private Optional<WorkspaceRole> role;
    private boolean offered;
    private ActionService service;

    @BeforeEach
    void setUp() {
        actions = new ScriptedActions(action(ActionState.AWAITING_APPROVAL, PAYLOAD));
        handler = new ScriptedHandler();
        connection = new OneConnection();
        role = Optional.of(WorkspaceRole.OWNER);
        offered = true;
        ConnectorService connectors = new ConnectorService(connection, new PlainCipher(), new CountingProvider(steps));
        WorkspaceRepository workspaces = new Roles();
        service = new ActionService(actions, workspaces, connectors, type -> offered, List.of(handler), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void anApprovedChangeIsCheckedThenClaimedThenSentOnceThenReadBackAndRecorded() {
        handler.answer = new WriteAnswer.Applied(200, List.of(), "made-1", null, null);
        handler.readBack = new ActionOutcome.Done(ActionVerification.MATCHED, "made-1", "https://drive.google.com/file/d/made-1/view", null);

        ActionRequest result = service.approve(WORKSPACE, PERSON, ACTION, HASH);

        assertEquals(List.of("refresh", "prepare", "claim", "markSent", "send", "recordExternalId:made-1", "readBack", "finish:SUCCEEDED"), steps);
        assertEquals(ActionState.SUCCEEDED, result.state());
        assertEquals(ActionVerification.MATCHED, actions.lastFinish.verification());
        assertEquals("made-1", actions.lastFinish.externalId());
        assertEquals(AttemptOutcome.APPLIED, actions.lastFinish.outcome());
    }

    @Test
    void nothingIsAskedOrSentWithoutTheHashOfWhatWasShown() {
        assertThrows(ActionPayloadMismatchException.class, () -> service.approve(WORKSPACE, PERSON, ACTION, "0".repeat(64)));
        assertThrows(ActionPayloadMismatchException.class, () -> service.approve(WORKSPACE, PERSON, ACTION, null));
        assertEquals(List.of(), steps, "not even a token refresh");
    }

    @Test
    void nothingIsAskedOrSentWhenTheRoleDoesNotAllowItOrTheKindIsNotOffered() {
        role = Optional.empty();
        assertThrows(ActionNotPermittedException.class, () -> service.approve(WORKSPACE, PERSON, ACTION, HASH));
        role = Optional.of(WorkspaceRole.OWNER);
        offered = false;
        assertThrows(ActionNotOfferedException.class, () -> service.approve(WORKSPACE, PERSON, ACTION, HASH));
        assertEquals(List.of(), steps);
    }

    @Test
    void anActionAlreadyUnderWayOrFinishedIsAnsweredAsItIsAndNothingIsSent() {
        for (ActionState state : List.of(ActionState.EXECUTING, ActionState.RECONCILING, ActionState.SUCCEEDED, ActionState.FAILED,
                ActionState.OUTCOME_UNKNOWN, ActionState.CANCELLED, ActionState.EXPIRED)) {
            actions.current = action(state, PAYLOAD);
            assertSame(actions.current, service.approve(WORKSPACE, PERSON, ACTION, HASH), state.name());
        }
        assertEquals(List.of(), steps);
    }

    @Test
    void somethingThatChangedBeforeSendingEndsTheActionAndNothingIsClaimedOrSent() {
        handler.prepareFailure = new ActionChangedException(ActionFailure.CONTENT_CHANGED);

        ActionRequest result = service.approve(WORKSPACE, PERSON, ACTION, HASH);

        assertEquals(List.of("refresh", "prepare", "failBeforeSending:CONTENT_CHANGED"), steps);
        assertEquals(ActionState.FAILED, result.state());
    }

    @Test
    void aClaimThatDoesNotSucceedSendsNothing() {
        actions.claimOutcome = ActionRepository.ClaimOutcome.ENDED;
        service.approve(WORKSPACE, PERSON, ACTION, HASH);
        actions.claimOutcome = ActionRepository.ClaimOutcome.SIBLING_UNRESOLVED;
        assertThrows(ActionSiblingUnresolvedException.class, () -> service.approve(WORKSPACE, PERSON, ACTION, HASH));
        actions.claimOutcome = ActionRepository.ClaimOutcome.HASH_MISMATCH;
        assertThrows(ActionPayloadMismatchException.class, () -> service.approve(WORKSPACE, PERSON, ACTION, HASH));
        actions.claimOutcome = ActionRepository.ClaimOutcome.CONNECTION_UNUSABLE;
        assertThrows(ConnectionReconnectRequiredException.class, () -> service.approve(WORKSPACE, PERSON, ACTION, HASH));
        actions.claimOutcome = ActionRepository.ClaimOutcome.NOT_CLAIMABLE;
        service.approve(WORKSPACE, PERSON, ACTION, HASH);

        assertTrue(steps.stream().noneMatch(step -> step.equals("markSent") || step.equals("send")), steps.toString());
    }

    @Test
    void anAttemptWithoutEnoughTimeLeftSendsNothingAndLeavesTheActionApproved() {
        actions.permission = ActionRepository.SendPermission.LEASE_TOO_SHORT;
        service.approve(WORKSPACE, PERSON, ACTION, HASH);
        assertEquals(List.of("refresh", "prepare", "claim", "markSent", "finish:APPROVED"), steps);
        assertEquals(AttemptOutcome.NOT_SENT, actions.lastFinish.outcome());

        steps.clear();
        actions.current = action(ActionState.AWAITING_APPROVAL, PAYLOAD);
        actions.permission = ActionRepository.SendPermission.FENCED_OUT;
        service.approve(WORKSPACE, PERSON, ACTION, HASH);
        assertEquals(List.of("refresh", "prepare", "claim", "markSent"), steps, "another attempt holds it: nothing is sent or recorded");
    }

    @Test
    void anAnswerLostAfterSendingIsAnUnknownOutcomeNeverANotApplied() {
        handler.answer = new WriteAnswer.Unknown(503, List.of("backendError"));
        service.approve(WORKSPACE, PERSON, ACTION, HASH);
        assertEquals(ActionState.OUTCOME_UNKNOWN, actions.lastFinish.nextState());
        assertEquals(List.of("backendError"), actions.lastFinish.providerReasons());

        steps.clear();
        actions.current = action(ActionState.AWAITING_APPROVAL, PAYLOAD);
        handler.answer = null;
        handler.sendFailure = new IllegalStateException("a defect inside Brownie after the request left");
        service.approve(WORKSPACE, PERSON, ACTION, HASH);
        assertEquals(ActionState.OUTCOME_UNKNOWN, actions.lastFinish.nextState());
        assertEquals(AttemptOutcome.UNKNOWN, actions.lastFinish.outcome());
    }

    @Test
    void aReadbackThatCannotBeDoneNowLeavesTheOutcomeUnknownWithWhatWasMadeKept() {
        handler.answer = new WriteAnswer.Applied(200, List.of(), "made-2", null, null);
        handler.readBackFailure = new IllegalStateException("Google did not answer the readback");

        service.approve(WORKSPACE, PERSON, ACTION, HASH);

        assertEquals(ActionState.OUTCOME_UNKNOWN, actions.lastFinish.nextState());
        assertEquals("made-2", actions.lastFinish.externalId());
        assertTrue(steps.contains("recordExternalId:made-2"), "kept the moment the answer named it");
    }

    @Test
    void aRefusedTokenDuringTheSendAsksToConnectAgainAndLeavesTheActionToBeTriedAgain() {
        handler.answer = new WriteAnswer.NotAppliedRetryable(401, List.of(), true);

        service.approve(WORKSPACE, PERSON, ACTION, HASH);

        assertEquals(List.of(ReconnectReason.TOKEN_REJECTED), connection.reconnects);
        assertEquals(ActionState.APPROVED, actions.lastFinish.nextState());
        assertEquals(AttemptOutcome.NOT_APPLIED, actions.lastFinish.outcome());
    }

    @Test
    void aFinalRefusalEndsTheActionWithItsReasonAndAnExistingOneIsReadBack() {
        handler.answer = new WriteAnswer.NotAppliedFinal(403, List.of("storageQuotaExceeded"), ActionFailure.STORAGE_FULL);
        service.approve(WORKSPACE, PERSON, ACTION, HASH);
        assertEquals(ActionState.FAILED, actions.lastFinish.nextState());
        assertEquals(ActionFailure.STORAGE_FULL, actions.lastFinish.failure());
        assertTrue(!steps.contains("readBack"));

        steps.clear();
        actions.current = action(ActionState.AWAITING_APPROVAL, PAYLOAD);
        handler.answer = new WriteAnswer.Exists(409, List.of("duplicate"));
        handler.readBack = new ActionOutcome.Mismatched("made-3", null);
        service.approve(WORKSPACE, PERSON, ACTION, HASH);
        assertTrue(steps.contains("readBack"), "an earlier attempt made it: read it back");
        assertEquals(ActionFailure.READBACK_MISMATCH, actions.lastFinish.failure());
        assertEquals(ActionVerification.MISMATCHED, actions.lastFinish.verification());
    }

    @Test
    void askingAboutAnUnknownOutcomeWhereNothingEverLeftIsCertainAndAsksTheProviderNothing() {
        actions.current = action(ActionState.OUTCOME_UNKNOWN, PAYLOAD);
        actions.attempts = List.of(attempt(false));

        service.reconcile(WORKSPACE, PERSON, ACTION);

        assertEquals(List.of("refresh", "claimReconcile", "finish:APPROVED"), steps);
        assertEquals(AttemptOutcome.NOT_APPLIED, actions.lastFinish.outcome());
    }

    @Test
    void askingAboutAnUnknownOutcomeRecordsWhatTheProviderSaysAndAFailureToAskLeavesItUnknown() {
        actions.current = action(ActionState.OUTCOME_UNKNOWN, PAYLOAD);
        actions.attempts = List.of(attempt(true));
        handler.reconcile = new ActionOutcome.Done(ActionVerification.MATCHED, "made-4", null, null);
        service.reconcile(WORKSPACE, PERSON, ACTION);
        assertEquals(List.of("refresh", "claimReconcile", "reconcile", "finish:SUCCEEDED"), steps);

        steps.clear();
        actions.current = action(ActionState.OUTCOME_UNKNOWN, PAYLOAD);
        handler.reconcileFailure = new IllegalStateException("Google did not answer");
        service.reconcile(WORKSPACE, PERSON, ACTION);
        assertEquals(ActionState.OUTCOME_UNKNOWN, actions.lastFinish.nextState());
    }

    @Test
    void aSendTheProviderRefusedAndAnAttemptThatNeverSentAreCertainlyNotMadeAndTheProviderIsNotAsked() {
        actions.current = action(ActionState.OUTCOME_UNKNOWN, PAYLOAD);
        actions.attempts = List.of(attempt(true, AttemptOutcome.NOT_APPLIED), attempt(false, AttemptOutcome.UNKNOWN));

        service.reconcile(WORKSPACE, PERSON, ACTION);

        assertEquals(List.of("refresh", "claimReconcile", "finish:APPROVED"), steps);
        assertEquals(AttemptOutcome.NOT_APPLIED, actions.lastFinish.outcome());

        steps.clear();
        actions.current = action(ActionState.OUTCOME_UNKNOWN, PAYLOAD);
        actions.attempts = List.of(attempt(true, AttemptOutcome.NOT_APPLIED), attempt(true, AttemptOutcome.UNKNOWN));
        service.reconcile(WORKSPACE, PERSON, ACTION);
        assertEquals(List.of("refresh", "claimReconcile", "reconcile", "finish:OUTCOME_UNKNOWN"), steps,
                "one send may have taken effect: only the provider can say");
    }

    @Test
    void aChangeMadeAgainSinceThroughAnotherActionIsNeverCreditedToThisOneWhereOnlyContentTellsThemApart() {
        actions.current = action(ActionState.OUTCOME_UNKNOWN, PAYLOAD);
        actions.attempts = List.of(attempt(true));
        handler.reconcile = new ActionOutcome.Done(ActionVerification.MATCHED, "doc-1", null, null);
        handler.byContent = true;
        actions.siblingSent = true;

        service.reconcile(WORKSPACE, PERSON, ACTION);

        assertEquals(List.of("refresh", "claimReconcile", "siblingSentAfter", "finish:OUTCOME_UNKNOWN"), steps);
        assertEquals(NOW.minusSeconds(599), actions.siblingSince, "sent after this action's own last send");

        steps.clear();
        actions.current = action(ActionState.OUTCOME_UNKNOWN, PAYLOAD);
        actions.siblingSent = false;
        service.reconcile(WORKSPACE, PERSON, ACTION);
        assertEquals(List.of("refresh", "claimReconcile", "siblingSentAfter", "reconcile", "finish:SUCCEEDED"), steps);

        steps.clear();
        actions.current = action(ActionState.OUTCOME_UNKNOWN, PAYLOAD);
        handler.byContent = false;
        actions.siblingSent = true;
        service.reconcile(WORKSPACE, PERSON, ACTION);
        assertEquals(List.of("refresh", "claimReconcile", "reconcile", "finish:SUCCEEDED"), steps,
                "a change with an id of its own is recognised by that id");
    }

    @Test
    void whatIsAlreadyThereCountsOnlyWhenAnEarlierSendOfThisVeryChangeCanHavePutItThere() {
        handler.byContent = true;
        handler.answer = new WriteAnswer.Exists(400, List.of());
        handler.readBack = new ActionOutcome.Done(ActionVerification.MATCHED, "doc-1", null, null);

        service.approve(WORKSPACE, PERSON, ACTION, HASH);
        assertEquals(List.of("refresh", "prepare", "claim", "markSent", "send", "finish:FAILED"), steps,
                "a first send refused because the text is already there: someone else's doing, and this one made nothing");
        assertEquals(ActionFailure.TARGET_CHANGED, actions.lastFinish.failure());

        steps.clear();
        actions.current = action(ActionState.APPROVED, PAYLOAD);
        actions.attempts = List.of(attempt(true));
        service.approve(WORKSPACE, PERSON, ACTION, HASH);
        assertEquals(List.of("refresh", "prepare", "claim", "markSent", "send", "siblingSentAfter", "readBack", "finish:SUCCEEDED"), steps,
                "an earlier send of this change landed late");

        steps.clear();
        actions.current = action(ActionState.APPROVED, PAYLOAD);
        actions.siblingSent = true;
        service.approve(WORKSPACE, PERSON, ACTION, HASH);
        assertEquals(List.of("refresh", "prepare", "claim", "markSent", "send", "siblingSentAfter", "finish:OUTCOME_UNKNOWN"), steps,
                "the same change was sent again since through another action: either could have put it there");

        steps.clear();
        actions.current = action(ActionState.AWAITING_APPROVAL, PAYLOAD);
        actions.attempts = List.of();
        handler.byContent = false;
        service.approve(WORKSPACE, PERSON, ACTION, HASH);
        assertEquals(List.of("refresh", "prepare", "claim", "markSent", "send", "readBack", "finish:SUCCEEDED"), steps,
                "a change with an id of its own is recognised by that id");
    }

    @Test
    void anApprovalThatRanOutOrWasMadeThroughAConnectionSinceReplacedIsNotPreparedAndTheClaimSaysWhy() {
        actions.current = withTimes(ActionState.AWAITING_APPROVAL, CONNECTION, NOW.minusSeconds(1));
        actions.claimOutcome = ActionRepository.ClaimOutcome.ENDED;
        service.approve(WORKSPACE, PERSON, ACTION, HASH);
        assertEquals(List.of("refresh", "claim"), steps, "nothing is read for an approval that ran out");

        steps.clear();
        actions.current = withTimes(ActionState.AWAITING_APPROVAL, CONNECTION + 1, NOW.plusSeconds(600));
        service.approve(WORKSPACE, PERSON, ACTION, HASH);
        assertEquals(List.of("refresh", "claim"), steps, "nothing is read through another connection");

        steps.clear();
        actions.current = withTimes(ActionState.AWAITING_APPROVAL, CONNECTION + 1, NOW.plusSeconds(600));
        actions.claimOutcome = ActionRepository.ClaimOutcome.CLAIMED;
        service.approve(WORKSPACE, PERSON, ACTION, HASH);
        assertEquals(List.of("refresh", "claim", "finish:APPROVED"), steps, "claimed after all: still nothing is sent");
        assertEquals(AttemptOutcome.NOT_SENT, actions.lastFinish.outcome());
    }

    @Test
    void anActionThatIsNeitherUnknownNorPastItsLeaseIsNotAskedAbout() {
        ActionRequest executing = action(ActionState.EXECUTING, PAYLOAD);
        actions.current = executing;
        assertSame(executing, service.reconcile(WORKSPACE, PERSON, ACTION));
        actions.current = action(ActionState.SUCCEEDED, PAYLOAD);
        service.reconcile(WORKSPACE, PERSON, ACTION);
        assertEquals(List.of(), steps);
    }

    @Test
    void aStoredPayloadInAnyButItsCanonicalSpellingIsNeverSent() {
        String loose = "{ \"fileName\": \"Minutes.docx\" }";
        actions.current = action(ActionState.AWAITING_APPROVAL, loose);
        assertThrows(IllegalStateException.class, () -> service.approve(WORKSPACE, PERSON, ACTION, CanonicalJson.sha256Hex(loose)));
        assertEquals(List.of(), steps);
    }

    // --- fixtures ---

    private static ActionRequest action(ActionState state, String payload) {
        return new ActionRequest(ACTION, WORKSPACE, PERSON, 40, CONNECTION, ActionType.DRIVE_SAVE_FILE, payload,
                CanonicalJson.sha256Hex(payload), "b".repeat(64), 11L, 12L, null, null, "reserved-id-1", state,
                NOW.minusSeconds(60), NOW.plusSeconds(1740), null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static ActionAttempt attempt(boolean sent) {
        return attempt(sent, AttemptOutcome.UNKNOWN);
    }

    private static ActionAttempt attempt(boolean sent, AttemptOutcome outcome) {
        return new ActionAttempt(1, ACTION, 1, AttemptKind.EXECUTE, NOW.minusSeconds(600), NOW.minusSeconds(420),
                sent ? NOW.minusSeconds(599) : null, NOW.minusSeconds(420), outcome, null, null, null);
    }

    private static ActionRequest withTimes(ActionState state, long connectionId, Instant expiresAt) {
        return new ActionRequest(ACTION, WORKSPACE, PERSON, 40, connectionId, ActionType.DRIVE_SAVE_FILE, PAYLOAD, HASH, "b".repeat(64),
                11L, 12L, null, null, "reserved-id-1", state, expiresAt.minusSeconds(1800), expiresAt, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    /** Answers as the database's routines would, and records each call as a step. */
    private final class ScriptedActions implements ActionRepository {

        ActionRequest current;
        ClaimOutcome claimOutcome = ClaimOutcome.CLAIMED;
        SendPermission permission = SendPermission.SEND;
        List<ActionAttempt> attempts = List.of();
        AttemptResult lastFinish;
        boolean siblingSent;
        Instant siblingSince;

        ScriptedActions(ActionRequest current) {
            this.current = current;
        }

        @Override
        public ActionRequest propose(long workspaceId, long userId, NewAction action) {
            throw new AssertionError("not proposed here");
        }

        @Override
        public Optional<ActionRequest> find(long workspaceId, long userId, long actionId) {
            return Optional.of(current);
        }

        @Override
        public List<ActionRequest> findForDocument(long workspaceId, long userId, long documentId) {
            return List.of(current);
        }

        @Override
        public List<ActionAttempt> attempts(long workspaceId, long userId, long actionId) {
            return attempts;
        }

        @Override
        public boolean siblingSentAfter(long workspaceId, long userId, long actionId, Instant since) {
            steps.add("siblingSentAfter");
            siblingSince = since;
            return siblingSent;
        }

        @Override
        public Claim claim(long workspaceId, long userId, long actionId, String presentedHash, int leaseSeconds) {
            steps.add("claim");
            assertEquals(ActionService.EXECUTE_LEASE_SECONDS, leaseSeconds);
            return new Claim(claimOutcome, claimOutcome == ClaimOutcome.CLAIMED ? 501L : null, NOW.plusSeconds(leaseSeconds));
        }

        @Override
        public Claim claimReconcile(long workspaceId, long userId, long actionId, long connectionId, int leaseSeconds) {
            steps.add("claimReconcile");
            current = withState(ActionState.RECONCILING);
            return new Claim(ClaimOutcome.CLAIMED, 502L, NOW.plusSeconds(leaseSeconds));
        }

        @Override
        public SendPermission markSent(long workspaceId, long userId, long actionId, long attemptId, int minLeaseSeconds) {
            steps.add("markSent");
            assertEquals(ActionService.MIN_SEND_LEASE_SECONDS, minLeaseSeconds);
            return permission;
        }

        @Override
        public void recordExternalId(long workspaceId, long userId, long actionId, long attemptId, String externalId) {
            steps.add("recordExternalId:" + externalId);
        }

        @Override
        public FinishResult finish(long workspaceId, long userId, long actionId, long attemptId, AttemptResult result) {
            steps.add("finish:" + result.nextState());
            lastFinish = result;
            current = withState(result.nextState());
            return FinishResult.FINISHED;
        }

        @Override
        public boolean failBeforeSending(long workspaceId, long userId, long actionId, ActionFailure reason) {
            steps.add("failBeforeSending:" + reason);
            current = withState(ActionState.FAILED);
            return true;
        }

        @Override
        public boolean cancel(long workspaceId, long userId, long actionId) {
            throw new AssertionError("not cancelled here");
        }

        @Override
        public boolean acknowledgeUnknown(long workspaceId, long userId, long actionId) {
            throw new AssertionError("not acknowledged here");
        }

        private ActionRequest withState(ActionState state) {
            ActionRequest c = current;
            return new ActionRequest(c.id(), c.workspaceId(), c.userId(), c.documentId(), c.connectionId(), c.type(), c.payloadCanonical(),
                    c.payloadHash(), c.siblingKey(), c.requiredRevisionId(), c.exportReceiptId(), c.targetActionId(), c.targetExternalId(),
                    c.providerKey(), state, c.createdAt(), c.expiresAt(), c.approvedAt(), c.approvalExpiresAt(), null, null,
                    c.externalId(), c.externalLink(), c.verification(), c.checkTotal(), c.checkFound(), c.failure(),
                    c.outcomeAcknowledgedAt(), c.finishedAt());
        }
    }

    private final class ScriptedHandler implements ActionHandler {

        WriteAnswer answer = new WriteAnswer.Unknown(null, List.of());
        ActionOutcome readBack = new ActionOutcome.StillUnknown(null);
        ActionOutcome reconcile = new ActionOutcome.StillUnknown(null);
        RuntimeException prepareFailure;
        RuntimeException sendFailure;
        RuntimeException readBackFailure;
        RuntimeException reconcileFailure;
        boolean byContent;

        @Override
        public ActionType type() {
            return ActionType.DRIVE_SAVE_FILE;
        }

        @Override
        public boolean recognisedByContentOnly() {
            return byContent;
        }

        @Override
        public ConnectorAccess access() {
            return ConnectorAccess.DRIVE_FILES;
        }

        @Override
        public PreparedWrite prepare(ActionRequest action, UsableConnection usable) {
            steps.add("prepare");
            if (prepareFailure != null) {
                throw prepareFailure;
            }
            return new PreparedWrite() {
                @Override
                public WriteAnswer send(UsableConnection usable) {
                    steps.add("send");
                    if (sendFailure != null) {
                        throw sendFailure;
                    }
                    return answer;
                }

                @Override
                public ActionOutcome readBack(UsableConnection usable, WriteAnswer answer) {
                    steps.add("readBack");
                    if (readBackFailure != null) {
                        throw readBackFailure;
                    }
                    return readBack;
                }
            };
        }

        @Override
        public ActionOutcome reconcile(ActionRequest action, UsableConnection usable, List<ActionAttempt> attempts, Instant now) {
            steps.add("reconcile");
            if (reconcileFailure != null) {
                throw reconcileFailure;
            }
            return reconcile;
        }
    }

    private final class Roles implements WorkspaceRepository {

        @Override
        public Workspace ensurePersonalWorkspace(long ownerUserId) {
            throw new AssertionError("not used here");
        }

        @Override
        public List<WorkspaceMember> findMembershipsForUser(long userId) {
            throw new AssertionError("not used here");
        }

        @Override
        public Optional<WorkspaceRole> findRole(long workspaceId, long userId) {
            return role;
        }
    }

    private static final class CountingProvider implements ConnectorOAuthClient {

        private final List<String> steps;

        CountingProvider(List<String> steps) {
            this.steps = steps;
        }

        @Override
        public Set<String> requiredScopes(ConnectorAccess access) {
            return Set.of("scope");
        }

        @Override
        public ProviderTokens exchange(ConnectorAccess access, String authorizationCode, String codeVerifier) {
            throw new AssertionError("no consent here");
        }

        @Override
        public ProviderTokens refresh(ConnectorAccess access, String refreshToken) {
            steps.add("refresh");
            return new ProviderTokens("fresh-access-token", null, Set.of("scope"));
        }

        @Override
        public ProviderAccount describeAccount(ConnectorAccess access, String accessToken) {
            throw new AssertionError("no account lookup here");
        }

        @Override
        public void revoke(String token) {
            throw new AssertionError("nothing revoked here");
        }
    }

    private static final class PlainCipher implements ConnectorTokenCipher {

        @Override
        public SealedToken seal(String token, TokenBinding binding) {
            return new SealedToken("k1", new byte[12], token.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String open(SealedToken sealed, TokenBinding binding) {
            return new String(sealed.ciphertext(), StandardCharsets.UTF_8);
        }
    }

    private static final class OneConnection implements ConnectionRepository {

        final List<ReconnectReason> reconnects = new ArrayList<>();
        private ConnectionState state = ConnectionState.ACTIVE;
        private ReconnectReason reason;

        private Connection current() {
            return new Connection(CONNECTION, WORKSPACE, PERSON, ConnectorAccess.DRIVE_FILES, "account-a", "a@example.org",
                    List.of("scope"), state, reason, null, OffsetDateTime.parse("2026-09-20T10:00:00Z"), null, null);
        }

        @Override
        public List<Connection> findForPerson(long workspaceId, long userId) {
            return List.of(current());
        }

        @Override
        public Optional<Connection> findOpen(long workspaceId, long userId, ConnectorAccess access) {
            return Optional.of(current());
        }

        @Override
        public Optional<SealedToken> findToken(long workspaceId, long userId, long connectionId) {
            return state == ConnectionState.ACTIVE
                    ? Optional.of(new SealedToken("k1", new byte[12], "refresh".getBytes(StandardCharsets.UTF_8)))
                    : Optional.empty();
        }

        @Override
        public Connection saveActive(long workspaceId, long userId, ConnectorAccess access, ProviderAccount account, List<String> grantedScopes,
                SealedToken token) {
            throw new AssertionError("no consent here");
        }

        @Override
        public Optional<Connection> requireReconnect(long workspaceId, long userId, long connectionId, ReconnectReason reason) {
            reconnects.add(reason);
            this.reason = reason;
            state = ConnectionState.RECONNECT_REQUIRED;
            return Optional.of(current());
        }

        @Override
        public Optional<Connection> disconnect(long workspaceId, long userId, long connectionId, ProviderRevocation revocation) {
            throw new AssertionError("nothing disconnected here");
        }
    }
}
