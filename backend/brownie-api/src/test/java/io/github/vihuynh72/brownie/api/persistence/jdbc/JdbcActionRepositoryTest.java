package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.action.ActionAttempt;
import io.github.vihuynh72.brownie.core.action.ActionFailure;
import io.github.vihuynh72.brownie.core.action.ActionRepository;
import io.github.vihuynh72.brownie.core.action.ActionRepository.AttemptResult;
import io.github.vihuynh72.brownie.core.action.ActionRepository.Claim;
import io.github.vihuynh72.brownie.core.action.ActionRepository.ClaimOutcome;
import io.github.vihuynh72.brownie.core.action.ActionRepository.FinishResult;
import io.github.vihuynh72.brownie.core.action.ActionRepository.SendPermission;
import io.github.vihuynh72.brownie.core.action.ActionRequest;
import io.github.vihuynh72.brownie.core.action.ActionState;
import io.github.vihuynh72.brownie.core.action.ActionType;
import io.github.vihuynh72.brownie.core.action.ActionVerification;
import io.github.vihuynh72.brownie.core.action.AttemptKind;
import io.github.vihuynh72.brownie.core.action.AttemptOutcome;
import io.github.vihuynh72.brownie.core.action.CanonicalJson;
import io.github.vihuynh72.brownie.core.action.ConversionCount;
import io.github.vihuynh72.brownie.core.action.NewAction;
import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.export.ExportApproval;
import io.github.vihuynh72.brownie.core.export.ExportApprovalRepository;
import io.github.vihuynh72.brownie.core.export.ExportFormat;
import io.github.vihuynh72.brownie.core.export.ExportReceipt;
import io.github.vihuynh72.brownie.core.export.ExportRepository;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.DocumentFieldEdit;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database's side of changing a person's outside account, against a real
 * Postgres with the real runtime logins: who may propose what, that nothing
 * but the routines can move an action along, that an approval is bound to
 * its exact payload and to every fact it depended on, that only the attempt
 * holding an action may send or finish it, that a change which may have
 * happened is not sent again (unless the provider itself refuses repeats),
 * that the same change cannot be under way twice, and that deleting a
 * document or a workspace waits for a change in the air and then removes
 * every record of what was proposed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DockerTest
class JdbcActionRepositoryTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String WORKER_PASSWORD = "brownie_worker_local_only";
    private static final int LEASE = 180;

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", DB::jdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    }

    @Autowired
    private ActionRepository actionRepository;

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private ExtractionVersionRepository extractionVersionRepository;

    @Autowired
    private RevisionService revisionService;

    @Autowired
    private ExportApprovalRepository exportApprovalRepository;

    @Autowired
    private ExportRepository exportRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void aProposalIsRecordedOnlyInThePersonsOwnNameThroughTheirOwnUsableConnectionOfTheKindItNeeds() throws Exception {
        Fixture mine = fixture("subject-action-propose-a");
        Fixture theirs = fixture("subject-action-propose-b");

        ActionRequest proposed = actionRepository.propose(mine.workspaceId, mine.userId, driveSave(mine, "one"));
        assertThat(proposed.state()).isEqualTo(ActionState.AWAITING_APPROVAL);
        assertThat(proposed.payloadHash()).isEqualTo(CanonicalJson.sha256Hex(proposed.payloadCanonical()));
        assertThat(proposed.expiresAt()).isEqualTo(proposed.createdAt().plusSeconds(1800));
        assertThat(proposed.approvedAt()).isNull();
        assertThat(actionRepository.find(mine.workspaceId, mine.userId, proposed.id())).isPresent();
        assertThat(actionRepository.find(mine.workspaceId, theirs.userId, proposed.id()))
                .as("another person never sees it").isEmpty();

        assertThatThrownBy(() -> actionRepository.propose(mine.workspaceId, mine.userId, driveSaveThrough(mine, mine.calendarConnectionId, "wrong kind")))
                .as("a Drive save through the calendar connection").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> actionRepository.propose(mine.workspaceId, mine.userId, driveSaveThrough(mine, theirs.driveConnectionId, "theirs")))
                .as("through someone else's connection").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> actionRepository.propose(theirs.workspaceId, mine.userId, driveSave(theirs, "not a member")))
                .as("in a workspace the person is not a member of").isInstanceOf(DataAccessException.class);

        owner("UPDATE connector_connection SET state = 'DISCONNECTED', disconnected_at = now(), provider_revocation = 'REVOKED',"
                + " token_key_id = NULL, token_nonce = NULL, token_ciphertext = NULL WHERE id = ?", mine.driveConnectionId);
        assertThatThrownBy(() -> actionRepository.propose(mine.workspaceId, mine.userId, driveSave(mine, "disconnected")))
                .as("through a connection that has been disconnected").isInstanceOf(DataAccessException.class);

        owner("UPDATE document SET trashed_at = now() WHERE id = ?", theirs.documentId);
        assertThatThrownBy(() -> actionRepository.propose(theirs.workspaceId, theirs.userId, driveSave(theirs, "trashed")))
                .as("for a document in the trash").isInstanceOf(DataAccessException.class);
    }

    @Test
    void theRuntimeLoginsCannotChangeAnActionExceptThroughItsRoutinesAndTheWorkerCannotSeeOne() throws Exception {
        Fixture fixture = fixture("subject-action-privileges");
        ActionRequest action = actionRepository.propose(fixture.workspaceId, fixture.userId, driveSave(fixture, "privileges"));

        for (String statement : List.of(
                "UPDATE action_request SET state = 'SUCCEEDED' WHERE id = " + action.id(),
                "UPDATE action_request SET approved_at = now(), approval_expires_at = now() + interval '15 minutes' WHERE id = " + action.id(),
                "UPDATE action_request SET payload_canonical = '{}' WHERE id = " + action.id(),
                "DELETE FROM action_request WHERE id = " + action.id(),
                "INSERT INTO action_attempt (workspace_id, action_id, attempt_number, kind, started_at, lease_expires_at)"
                        + " VALUES (" + fixture.workspaceId + ", " + action.id() + ", 1, 'EXECUTE', now(), now() + interval '3 minutes')")) {
            assertThatThrownBy(() -> asApi(fixture.userId, statement)).as(statement).hasMessageContaining("permission denied");
        }
        try (Connection worker = DriverManager.getConnection(DB.jdbcUrl(), "brownie_worker", WORKER_PASSWORD);
                PreparedStatement select = worker.prepareStatement("SELECT count(*) FROM action_request")) {
            assertThatThrownBy(select::executeQuery).hasMessageContaining("permission denied");
        }
        // A payload whose hash does not match its text cannot even be written by the table's owner.
        assertThatThrownBy(() -> owner("UPDATE action_request SET payload_hash = ? WHERE id = ?", "b".repeat(64), action.id()))
                .hasMessageContaining("action_request_payload_hash_matches");
        assertThat(actionRepository.find(fixture.workspaceId, fixture.userId, action.id()).orElseThrow().state())
                .isEqualTo(ActionState.AWAITING_APPROVAL);
    }

    @Test
    void anApprovalNeedsTheHashOfThePayloadShownAndTwoApprovalsAtOnceClaimOnce() throws Exception {
        Fixture fixture = fixture("subject-action-approve");
        ActionRequest action = actionRepository.propose(fixture.workspaceId, fixture.userId, driveSave(fixture, "approve"));

        Claim wrong = actionRepository.claim(fixture.workspaceId, fixture.userId, action.id(), "c".repeat(64), LEASE);
        assertThat(wrong.outcome()).isEqualTo(ClaimOutcome.HASH_MISMATCH);
        assertThat(actionRepository.find(fixture.workspaceId, fixture.userId, action.id()).orElseThrow().approvedAt()).isNull();

        Fixture stranger = fixture("subject-action-approve-stranger");
        assertThat(actionRepository.claim(fixture.workspaceId, stranger.userId, action.id(), action.payloadHash(), LEASE).outcome())
                .as("someone else cannot approve it").isEqualTo(ClaimOutcome.NOT_FOUND);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Claim> approve = () -> {
                start.await();
                return actionRepository.claim(fixture.workspaceId, fixture.userId, action.id(), action.payloadHash(), LEASE);
            };
            Future<Claim> first = pool.submit(approve);
            Future<Claim> second = pool.submit(approve);
            start.countDown();
            List<ClaimOutcome> outcomes = List.of(first.get().outcome(), second.get().outcome());
            assertThat(outcomes).containsExactlyInAnyOrder(ClaimOutcome.CLAIMED, ClaimOutcome.NOT_CLAIMABLE);
        } finally {
            pool.shutdownNow();
        }
        ActionRequest claimed = actionRepository.find(fixture.workspaceId, fixture.userId, action.id()).orElseThrow();
        assertThat(claimed.state()).isEqualTo(ActionState.EXECUTING);
        assertThat(claimed.approvalExpiresAt()).isEqualTo(claimed.approvedAt().plusSeconds(900));
        assertThat(actionRepository.attempts(fixture.workspaceId, fixture.userId, action.id())).hasSize(1);
        assertThat(ownerCount("SELECT count(*) FROM audit_event WHERE action = 'EXTERNAL_ACTION_APPROVED' AND resource_id = ?", action.id()))
                .as("approved once").isEqualTo(1);
        assertThat(ownerText("SELECT details::text FROM audit_event WHERE action = 'EXTERNAL_ACTION_APPROVED' AND resource_id = ?", action.id()))
                .contains(action.payloadHash()).doesNotContain("approve-payload");
    }

    @Test
    void anApprovalEndsForGoodWhenAnythingItDependedOnChangedAndOnlyAReconnectWaits() throws Exception {
        Fixture edited = fixture("subject-action-depends-edit");
        ActionRequest editedAction = actionRepository.propose(edited.workspaceId, edited.userId, driveSave(edited, "edit"));
        revisionService.applyUserEdits(edited.workspaceId, edited.userId,
                new IdempotencyKey("edit-" + UUID.randomUUID()), CanonicalRequestHash.sha256OfCanonicalText("edit-" + UUID.randomUUID()),
                edited.documentId, edited.revisionId,
                List.of(new DocumentFieldEdit.SetValue("meeting.title", new FieldValue.TextValue("Changed"))), Map.of(), "edited");
        assertEnded(edited, editedAction, ActionFailure.DOCUMENT_CHANGED);

        Fixture reexported = fixture("subject-action-depends-export");
        ActionRequest reexportedAction = actionRepository.propose(reexported.workspaceId, reexported.userId, driveSave(reexported, "export"));
        newReceipt(reexported);
        assertEnded(reexported, reexportedAction, ActionFailure.EXPORT_CHANGED);

        Fixture trashed = fixture("subject-action-depends-trash");
        ActionRequest trashedAction = actionRepository.propose(trashed.workspaceId, trashed.userId, driveSave(trashed, "trash"));
        owner("UPDATE document SET trashed_at = now() WHERE id = ?", trashed.documentId);
        assertEnded(trashed, trashedAction, ActionFailure.DOCUMENT_GONE);
        owner("UPDATE document SET trashed_at = NULL WHERE id = ?", trashed.documentId);
        assertThat(actionRepository.claim(trashed.workspaceId, trashed.userId, trashedAction.id(), trashedAction.payloadHash(), LEASE).outcome())
                .as("taking the document back out of the trash does not bring the approval back").isEqualTo(ClaimOutcome.NOT_CLAIMABLE);

        Fixture disconnected = fixture("subject-action-depends-disconnect");
        ActionRequest disconnectedAction = actionRepository.propose(disconnected.workspaceId, disconnected.userId, driveSave(disconnected, "disc"));
        owner("UPDATE connector_connection SET state = 'DISCONNECTED', disconnected_at = now(), provider_revocation = 'REVOKED',"
                + " token_key_id = NULL, token_nonce = NULL, token_ciphertext = NULL WHERE id = ?", disconnected.driveConnectionId);
        assertEnded(disconnected, disconnectedAction, ActionFailure.CONNECTION_CHANGED);

        Fixture waiting = fixture("subject-action-depends-reconnect");
        ActionRequest waitingAction = actionRepository.propose(waiting.workspaceId, waiting.userId, driveSave(waiting, "reconnect"));
        owner("UPDATE connector_connection SET state = 'RECONNECT_REQUIRED', reconnect_reason = 'TOKEN_REJECTED',"
                + " token_key_id = NULL, token_nonce = NULL, token_ciphertext = NULL WHERE id = ?", waiting.driveConnectionId);
        assertThat(actionRepository.claim(waiting.workspaceId, waiting.userId, waitingAction.id(), waitingAction.payloadHash(), LEASE).outcome())
                .isEqualTo(ClaimOutcome.CONNECTION_UNUSABLE);
        assertThat(actionRepository.find(waiting.workspaceId, waiting.userId, waitingAction.id()).orElseThrow().state())
                .as("connecting again brings the same connection back, so the proposal waits").isEqualTo(ActionState.AWAITING_APPROVAL);

        Fixture late = fixture("subject-action-depends-late");
        ActionRequest lateAction = actionRepository.propose(late.workspaceId, late.userId, driveSave(late, "late"));
        owner("UPDATE action_request SET created_at = created_at - interval '31 minutes', expires_at = expires_at - interval '31 minutes'"
                + " WHERE id = ?", lateAction.id());
        assertThat(actionRepository.find(late.workspaceId, late.userId, lateAction.id()).orElseThrow()
                .stateAt(java.time.Instant.now())).as("reading shows it expired without writing").isEqualTo(ActionState.EXPIRED);
        assertEnded(late, lateAction, null);
        assertThat(actionRepository.find(late.workspaceId, late.userId, lateAction.id()).orElseThrow().state()).isEqualTo(ActionState.EXPIRED);
    }

    @Test
    void onlyTheAttemptHoldingAnActionMaySendOrFinishItAndItSendsOnce() throws Exception {
        Fixture fixture = fixture("subject-action-fencing");
        ActionRequest action = actionRepository.propose(fixture.workspaceId, fixture.userId, driveSave(fixture, "fencing"));
        long first = claimed(fixture, action);

        assertThat(actionRepository.markSent(fixture.workspaceId, fixture.userId, action.id(), first + 1000, 90))
                .isEqualTo(SendPermission.FENCED_OUT);
        assertThat(actionRepository.markSent(fixture.workspaceId, fixture.userId, action.id(), first, LEASE + 20))
                .as("not enough of the lease left for a whole request").isEqualTo(SendPermission.LEASE_TOO_SHORT);
        assertThat(actionRepository.markSent(fixture.workspaceId, fixture.userId, action.id(), first, 90)).isEqualTo(SendPermission.SEND);
        assertThat(actionRepository.markSent(fixture.workspaceId, fixture.userId, action.id(), first, 90))
                .as("an attempt sends once").isEqualTo(SendPermission.ALREADY_SENT);
        assertThat(ownerCount("SELECT count(*) FROM audit_event WHERE action = 'EXTERNAL_ACTION_SENT' AND resource_id = ?", action.id()))
                .as("the send is on the record before it leaves").isEqualTo(1);

        assertThat(actionRepository.claimReconcile(fixture.workspaceId, fixture.userId, action.id(), fixture.driveConnectionId, 120).outcome())
                .as("an attempt still holding the action is not taken over").isEqualTo(ClaimOutcome.NOT_CLAIMABLE);
        owner("UPDATE action_request SET lease_expires_at = now() - interval '1 second' WHERE id = ?", action.id());
        Claim takeover = actionRepository.claimReconcile(fixture.workspaceId, fixture.userId, action.id(), fixture.driveConnectionId, 120);
        assertThat(takeover.outcome()).isEqualTo(ClaimOutcome.CLAIMED);

        actionRepository.recordExternalId(fixture.workspaceId, fixture.userId, action.id(), first, "fileMadeByTheFirstAttempt");
        assertThat(actionRepository.finish(fixture.workspaceId, fixture.userId, action.id(), first, succeeded("fileMadeByTheFirstAttempt")))
                .as("the attempt that lost its hold cannot finish it").isEqualTo(FinishResult.FENCED_OUT);
        List<ActionAttempt> attempts = actionRepository.attempts(fixture.workspaceId, fixture.userId, action.id());
        assertThat(attempts.get(0).outcome()).as("closed as unknown when taken over").isEqualTo(AttemptOutcome.UNKNOWN);
        assertThat(attempts.get(0).externalId()).as("but what it made can still be found").isEqualTo("fileMadeByTheFirstAttempt");
        assertThat(attempts.get(1).kind()).isEqualTo(AttemptKind.RECONCILE);

        assertThat(actionRepository.finish(fixture.workspaceId, fixture.userId, action.id(), takeover.attemptId(),
                succeeded("fileMadeByTheFirstAttempt"))).isEqualTo(FinishResult.FINISHED);
        ActionRequest done = actionRepository.find(fixture.workspaceId, fixture.userId, action.id()).orElseThrow();
        assertThat(done.state()).isEqualTo(ActionState.SUCCEEDED);
        assertThat(done.externalId()).isEqualTo("fileMadeByTheFirstAttempt");
        assertThat(done.verification()).isEqualTo(ActionVerification.MATCHED);
        assertThat(done.finishedAt()).isNotNull();
        assertThat(ownerText("SELECT details::text FROM audit_event WHERE action = 'EXTERNAL_ACTION_FINISHED' AND resource_id = ?", action.id()))
                .contains("SUCCEEDED").contains("MATCHED").doesNotContain("fileMadeByTheFirstAttempt");
    }

    @Test
    void aConversionOrAnEventThatMayHaveHappenedIsNeverSentAgainButACertainRefusalMayBe() throws Exception {
        Fixture fixture = fixture("subject-action-resend");
        ActionRequest event = actionRepository.propose(fixture.workspaceId, fixture.userId, calendarEvent(fixture, "ambiguous"));
        long attempt = claimed(fixture, event);
        actionRepository.markSent(fixture.workspaceId, fixture.userId, event.id(), attempt, 90);
        assertThatThrownBy(() -> actionRepository.finish(fixture.workspaceId, fixture.userId, event.id(), attempt, backToApproved(AttemptOutcome.UNKNOWN)))
                .hasMessageContaining("never sent again");
        actionRepository.finish(fixture.workspaceId, fixture.userId, event.id(), attempt, unknown());
        Claim reconcile = actionRepository.claimReconcile(fixture.workspaceId, fixture.userId, event.id(), fixture.calendarConnectionId, 120);
        assertThatThrownBy(() -> actionRepository.finish(fixture.workspaceId, fixture.userId, event.id(), reconcile.attemptId(),
                backToApproved(AttemptOutcome.NOT_APPLIED)))
                .as("a not-found after an ambiguous send is not proof for an event").hasMessageContaining("never sent again");

        ActionRequest conversion = actionRepository.propose(fixture.workspaceId, fixture.userId, conversion(fixture, "ambiguous"));
        long conversionAttempt = claimed(fixture, conversion);
        actionRepository.markSent(fixture.workspaceId, fixture.userId, conversion.id(), conversionAttempt, 90);
        assertThatThrownBy(() -> actionRepository.finish(fixture.workspaceId, fixture.userId, conversion.id(), conversionAttempt,
                backToApproved(AttemptOutcome.UNKNOWN))).hasMessageContaining("never sent again");
        actionRepository.finish(fixture.workspaceId, fixture.userId, conversion.id(), conversionAttempt, unknown());
        Claim conversionReconcile = actionRepository.claimReconcile(fixture.workspaceId, fixture.userId, conversion.id(),
                fixture.driveConnectionId, 120);
        assertThatThrownBy(() -> actionRepository.finish(fixture.workspaceId, fixture.userId, conversion.id(), conversionReconcile.attemptId(),
                backToApproved(AttemptOutcome.NOT_APPLIED)))
                .as("nor for a conversion, which carries no id to ask for").hasMessageContaining("never sent again");
        assertThat(actionRepository.finish(fixture.workspaceId, fixture.userId, conversion.id(), conversionReconcile.attemptId(),
                new AttemptResult(ActionState.SUCCEEDED, AttemptOutcome.APPLIED, ActionVerification.CONVERSION_UNCHECKED, null,
                        "docMadeByTheConversion", null, 200, List.of(), null, new ConversionCount(0, 0))))
                .isEqualTo(FinishResult.FINISHED);
        ActionRequest unchecked = actionRepository.find(fixture.workspaceId, fixture.userId, conversion.id()).orElseThrow();
        assertThat(unchecked.verification()).as("nothing long enough to look for").isEqualTo(ActionVerification.CONVERSION_UNCHECKED);
        assertThat(unchecked.checkTotal()).isZero();

        ActionRequest refused = actionRepository.propose(fixture.workspaceId, fixture.userId, calendarEvent(fixture, "refused"));
        long refusedAttempt = claimed(fixture, refused);
        actionRepository.markSent(fixture.workspaceId, fixture.userId, refused.id(), refusedAttempt, 90);
        assertThat(actionRepository.finish(fixture.workspaceId, fixture.userId, refused.id(), refusedAttempt, backToApproved(AttemptOutcome.NOT_APPLIED)))
                .as("a refusal before processing is certain").isEqualTo(FinishResult.FINISHED);
        assertThat(actionRepository.find(fixture.workspaceId, fixture.userId, refused.id()).orElseThrow().state()).isEqualTo(ActionState.APPROVED);
        Claim retry = actionRepository.claim(fixture.workspaceId, fixture.userId, refused.id(), refused.payloadHash(), LEASE);
        assertThat(retry.outcome()).as("tried again under the same approval").isEqualTo(ClaimOutcome.CLAIMED);
        assertThat(ownerCount("SELECT count(*) FROM audit_event WHERE action = 'EXTERNAL_ACTION_APPROVED' AND resource_id = ?", refused.id()))
                .as("a retry is not a second approval").isEqualTo(1);

        ActionRequest file = actionRepository.propose(fixture.workspaceId, fixture.userId, driveSave(fixture, "resendable"));
        long fileAttempt = claimed(fixture, file);
        actionRepository.markSent(fixture.workspaceId, fixture.userId, file.id(), fileAttempt, 90);
        actionRepository.finish(fixture.workspaceId, fixture.userId, file.id(), fileAttempt, unknown());
        Claim fileReconcile = actionRepository.claimReconcile(fixture.workspaceId, fixture.userId, file.id(), fixture.driveConnectionId, 120);
        assertThat(actionRepository.finish(fixture.workspaceId, fixture.userId, file.id(), fileReconcile.attemptId(), backToApproved(AttemptOutcome.NOT_APPLIED)))
                .as("a file with a reserved id may be sent again: Drive refuses the repeat if the first arrived").isEqualTo(FinishResult.FINISHED);

        owner("UPDATE action_request SET approved_at = approved_at - interval '16 minutes',"
                + " approval_expires_at = approval_expires_at - interval '16 minutes' WHERE id = ?", file.id());
        assertThat(actionRepository.claim(fixture.workspaceId, fixture.userId, file.id(), file.payloadHash(), LEASE).outcome())
                .as("but not once its approval has run out").isEqualTo(ClaimOutcome.ENDED);
        assertThat(actionRepository.find(fixture.workspaceId, fixture.userId, file.id()).orElseThrow().state()).isEqualTo(ActionState.EXPIRED);
    }

    @Test
    void theSameChangeCannotBeUnderWayTwiceOrStartedAgainWhileItsEarlierOutcomeIsUnknown() throws Exception {
        Fixture fixture = fixture("subject-action-sibling");
        String sibling = CanonicalJson.sha256Hex("the same event");
        ActionRequest first = actionRepository.propose(fixture.workspaceId, fixture.userId, calendarEvent(fixture, "sibling-1", sibling));
        ActionRequest second = actionRepository.propose(fixture.workspaceId, fixture.userId, calendarEvent(fixture, "sibling-2", sibling));

        long attempt = claimed(fixture, first);
        assertThat(actionRepository.claim(fixture.workspaceId, fixture.userId, second.id(), second.payloadHash(), LEASE).outcome())
                .isEqualTo(ClaimOutcome.SIBLING_UNRESOLVED);
        actionRepository.markSent(fixture.workspaceId, fixture.userId, first.id(), attempt, 90);
        actionRepository.finish(fixture.workspaceId, fixture.userId, first.id(), attempt, unknown());
        assertThat(actionRepository.claim(fixture.workspaceId, fixture.userId, second.id(), second.payloadHash(), LEASE).outcome())
                .as("while the first may have happened").isEqualTo(ClaimOutcome.SIBLING_UNRESOLVED);
        assertThat(actionRepository.find(fixture.workspaceId, fixture.userId, second.id()).orElseThrow().state())
                .isEqualTo(ActionState.AWAITING_APPROVAL);

        assertThat(actionRepository.acknowledgeUnknown(fixture.workspaceId, fixture.userId, first.id())).isTrue();
        assertThat(actionRepository.find(fixture.workspaceId, fixture.userId, first.id()).orElseThrow().state())
                .as("saying one has checked does not make the outcome known").isEqualTo(ActionState.OUTCOME_UNKNOWN);
        assertThat(actionRepository.claim(fixture.workspaceId, fixture.userId, second.id(), second.payloadHash(), LEASE).outcome())
                .isEqualTo(ClaimOutcome.CLAIMED);
    }

    @Test
    void aChangeLeftHeldByAnAttemptThatStoppedCanBeAcknowledgedAndEverySendIsRecordedWithHowItEnded() throws Exception {
        Fixture fixture = fixture("subject-action-stuck");
        String sibling = CanonicalJson.sha256Hex("the stuck event");
        ActionRequest stuck = actionRepository.propose(fixture.workspaceId, fixture.userId, calendarEvent(fixture, "stuck", sibling));
        long attempt = claimed(fixture, stuck);
        actionRepository.markSent(fixture.workspaceId, fixture.userId, stuck.id(), attempt, 90);

        assertThat(actionRepository.acknowledgeUnknown(fixture.workspaceId, fixture.userId, stuck.id()))
                .as("an attempt still holding its change is not taken from it").isFalse();
        owner("UPDATE action_request SET lease_expires_at = now() - interval '1 second' WHERE id = ?", stuck.id());
        assertThat(actionRepository.acknowledgeUnknown(fixture.workspaceId, fixture.userId, stuck.id()))
                .as("once its lease ran out, even with no connection left to ask through").isTrue();
        ActionRequest acknowledged = actionRepository.find(fixture.workspaceId, fixture.userId, stuck.id()).orElseThrow();
        assertThat(acknowledged.state()).isEqualTo(ActionState.OUTCOME_UNKNOWN);
        assertThat(acknowledged.outcomeAcknowledgedAt()).isNotNull();
        assertThat(actionRepository.attempts(fixture.workspaceId, fixture.userId, stuck.id()).getFirst().outcome()).isEqualTo(AttemptOutcome.UNKNOWN);
        assertThat(actionRepository.finish(fixture.workspaceId, fixture.userId, stuck.id(), attempt, unknown()))
                .as("the stopped attempt can no longer finish it").isEqualTo(FinishResult.FENCED_OUT);
        assertThat(actionRepository.markSent(fixture.workspaceId, fixture.userId, stuck.id(), attempt, 90))
                .as("nor send").isEqualTo(SendPermission.FENCED_OUT);
        actionRepository.recordExternalId(fixture.workspaceId, fixture.userId, stuck.id(), attempt, "eventNamedLate");
        assertThat(actionRepository.attempts(fixture.workspaceId, fixture.userId, stuck.id()).getFirst().externalId())
                .as("what it made can still be found").isEqualTo("eventNamedLate");
        assertThat(actionRepository.find(fixture.workspaceId, fixture.userId, stuck.id()).orElseThrow().externalId()).isNull();
        assertThat(ownerText("SELECT details::text FROM audit_event WHERE action = 'EXTERNAL_ACTION_FINISHED' AND resource_id = ?", stuck.id()))
                .as("the stopped send is on the record with how it ended").contains("OUTCOME_UNKNOWN").contains("acknowledged");
        ActionRequest again = actionRepository.propose(fixture.workspaceId, fixture.userId, calendarEvent(fixture, "stuck-again", sibling));
        assertThat(actionRepository.claim(fixture.workspaceId, fixture.userId, again.id(), again.payloadHash(), LEASE).outcome())
                .as("the change is no longer held").isEqualTo(ClaimOutcome.CLAIMED);

        ActionRequest refused = actionRepository.propose(fixture.workspaceId, fixture.userId, calendarEvent(fixture, "refused-on-record"));
        long refusedAttempt = claimed(fixture, refused);
        actionRepository.markSent(fixture.workspaceId, fixture.userId, refused.id(), refusedAttempt, 90);
        actionRepository.finish(fixture.workspaceId, fixture.userId, refused.id(), refusedAttempt, backToApproved(AttemptOutcome.NOT_APPLIED));
        assertThat(ownerText("SELECT details::text FROM audit_event WHERE action = 'EXTERNAL_ACTION_FINISHED' AND resource_id = ?", refused.id()))
                .as("a send that certainly did not happen is on the record with how it ended").contains("APPROVED");
    }

    @Test
    void somethingFoundChangedBeforeSendingIsRecordedAfterWhatTheClaimWouldHaveFoundFirst() throws Exception {
        Fixture fixture = fixture("subject-action-fail-before");
        ActionRequest changed = actionRepository.propose(fixture.workspaceId, fixture.userId, driveSave(fixture, "changed"));
        assertThat(actionRepository.failBeforeSending(fixture.workspaceId, fixture.userId, changed.id(), ActionFailure.CONTENT_CHANGED)).isTrue();
        assertThat(actionRepository.find(fixture.workspaceId, fixture.userId, changed.id()).orElseThrow().failure())
                .isEqualTo(ActionFailure.CONTENT_CHANGED);

        ActionRequest late = actionRepository.propose(fixture.workspaceId, fixture.userId, driveSave(fixture, "late"));
        owner("UPDATE action_request SET created_at = created_at - interval '31 minutes', expires_at = expires_at - interval '31 minutes'"
                + " WHERE id = ?", late.id());
        actionRepository.failBeforeSending(fixture.workspaceId, fixture.userId, late.id(), ActionFailure.TARGET_CHANGED);
        assertThat(actionRepository.find(fixture.workspaceId, fixture.userId, late.id()).orElseThrow().state())
                .as("a proposal that ran out expired, whatever was found").isEqualTo(ActionState.EXPIRED);

        ActionRequest waiting = actionRepository.propose(fixture.workspaceId, fixture.userId, calendarEvent(fixture, "waiting"));
        owner("UPDATE connector_connection SET state = 'RECONNECT_REQUIRED', reconnect_reason = 'TOKEN_REJECTED',"
                + " token_key_id = NULL, token_nonce = NULL, token_ciphertext = NULL WHERE id = ?", fixture.calendarConnectionId);
        assertThat(actionRepository.failBeforeSending(fixture.workspaceId, fixture.userId, waiting.id(), ActionFailure.TARGET_CHANGED)).isFalse();
        assertThat(actionRepository.find(fixture.workspaceId, fixture.userId, waiting.id()).orElseThrow().state())
                .as("a connection waiting to be made again leaves it as it is").isEqualTo(ActionState.AWAITING_APPROVAL);

        owner("UPDATE connector_connection SET state = 'DISCONNECTED', disconnected_at = now(), provider_revocation = 'REVOKED',"
                + " reconnect_reason = NULL WHERE id = ?", fixture.calendarConnectionId);
        actionRepository.failBeforeSending(fixture.workspaceId, fixture.userId, waiting.id(), ActionFailure.TARGET_CHANGED);
        assertThat(actionRepository.find(fixture.workspaceId, fixture.userId, waiting.id()).orElseThrow().failure())
                .as("a connection since replaced is why it ended").isEqualTo(ActionFailure.CONNECTION_CHANGED);
    }

    @Test
    void theSameChangeSentLaterThroughAnotherActionIsSeenAndNoOtherIs() throws Exception {
        Fixture fixture = fixture("subject-action-sibling-sent");
        String sibling = CanonicalJson.sha256Hex("the same addition");
        ActionRequest first = actionRepository.propose(fixture.workspaceId, fixture.userId, calendarEvent(fixture, "first", sibling));
        long firstAttempt = claimed(fixture, first);
        actionRepository.markSent(fixture.workspaceId, fixture.userId, first.id(), firstAttempt, 90);
        actionRepository.finish(fixture.workspaceId, fixture.userId, first.id(), firstAttempt, unknown());
        java.time.Instant firstSent = actionRepository.attempts(fixture.workspaceId, fixture.userId, first.id()).getFirst().sentAt();
        assertThat(actionRepository.siblingSentAfter(fixture.workspaceId, fixture.userId, first.id(), firstSent)).isFalse();

        ActionRequest unrelated = actionRepository.propose(fixture.workspaceId, fixture.userId, calendarEvent(fixture, "unrelated"));
        long unrelatedAttempt = claimed(fixture, unrelated);
        actionRepository.markSent(fixture.workspaceId, fixture.userId, unrelated.id(), unrelatedAttempt, 90);
        assertThat(actionRepository.siblingSentAfter(fixture.workspaceId, fixture.userId, first.id(), firstSent))
                .as("another change entirely").isFalse();

        actionRepository.acknowledgeUnknown(fixture.workspaceId, fixture.userId, first.id());
        ActionRequest second = actionRepository.propose(fixture.workspaceId, fixture.userId, calendarEvent(fixture, "second", sibling));
        long secondAttempt = claimed(fixture, second);
        assertThat(actionRepository.siblingSentAfter(fixture.workspaceId, fixture.userId, first.id(), firstSent))
                .as("claimed, not sent").isFalse();
        actionRepository.markSent(fixture.workspaceId, fixture.userId, second.id(), secondAttempt, 90);
        assertThat(actionRepository.siblingSentAfter(fixture.workspaceId, fixture.userId, first.id(), firstSent))
                .as("sent, and not yet answered").isTrue();
        actionRepository.finish(fixture.workspaceId, fixture.userId, second.id(), secondAttempt, backToApproved(AttemptOutcome.NOT_APPLIED));
        assertThat(actionRepository.siblingSentAfter(fixture.workspaceId, fixture.userId, first.id(), firstSent))
                .as("answered as not processed: it cannot have made anything").isFalse();
        long secondRetry = claimed(fixture, second);
        actionRepository.markSent(fixture.workspaceId, fixture.userId, second.id(), secondRetry, 90);
        actionRepository.finish(fixture.workspaceId, fixture.userId, second.id(), secondRetry, unknown());
        assertThat(actionRepository.siblingSentAfter(fixture.workspaceId, fixture.userId, first.id(), firstSent)).isTrue();
        assertThat(actionRepository.siblingSentAfter(fixture.workspaceId, fixture.userId, second.id(), java.time.Instant.now().plusSeconds(60)))
                .isFalse();
    }

    @Test
    void deletingADocumentOrAWorkspaceWaitsForAChangeInTheAirThenRemovesEveryRecordOfItsActions() throws Exception {
        Fixture document = fixture("subject-action-purge-document");
        ActionRequest inTheAir = actionRepository.propose(document.workspaceId, document.userId, driveSave(document, "air"));
        long attempt = claimed(document, inTheAir);
        actionRepository.markSent(document.workspaceId, document.userId, inTheAir.id(), attempt, 90);
        long request = asApiLong(document.userId, "SELECT trash_document(" + document.workspaceId + ", " + document.documentId + ", 30)");
        assertThat(asApiText(document.userId, "SELECT purge_trashed_document(" + document.workspaceId + ", " + request + ")"))
                .isEqualTo("JOBS_STILL_STOPPING");
        assertThat(ownerCount("SELECT count(*) FROM action_request WHERE document_id = ?", document.documentId)).isEqualTo(1);

        owner("UPDATE action_request SET lease_expires_at = now() - interval '1 second' WHERE id = ?", inTheAir.id());
        assertThat(asApiText(document.userId, "SELECT purge_trashed_document(" + document.workspaceId + ", " + request + ")"))
                .as("an attempt that stopped holding it does not stop a deletion forever").isEqualTo("PURGED");
        assertThat(ownerCount("SELECT count(*) FROM action_request WHERE document_id = ?", document.documentId)).isZero();
        assertThat(ownerCount("SELECT count(*) FROM action_attempt WHERE action_id = ?", inTheAir.id())).isZero();
        assertThat(ownerCount("SELECT remaining_rows FROM retention_remaining(?)", request)).isZero();
        assertThat(ownerCount("SELECT count(*) FROM audit_event WHERE action = 'EXTERNAL_ACTION_SENT' AND resource_id = ?", inTheAir.id()))
                .as("the record that something was sent outlives the deletion").isEqualTo(1);

        Fixture workspace = fixture("subject-action-purge-workspace");
        ActionRequest held = actionRepository.propose(workspace.workspaceId, workspace.userId, calendarEvent(workspace, "held"));
        claimed(workspace, held);
        assertThat(asApiText(workspace.userId, "SELECT outcome FROM delete_workspace(" + workspace.workspaceId + ")"))
                .isEqualTo("JOBS_STILL_STOPPING");
        owner("UPDATE action_request SET lease_expires_at = now() - interval '1 second' WHERE id = ?", held.id());
        assertThat(asApiText(workspace.userId, "SELECT outcome FROM delete_workspace(" + workspace.workspaceId + ")")).isEqualTo("PURGED");
        assertThat(ownerCount("SELECT count(*) FROM action_request WHERE workspace_id = ?", workspace.workspaceId)).isZero();
        assertThat(ownerCount("SELECT count(*) FROM action_attempt WHERE workspace_id = ?", workspace.workspaceId)).isZero();
        assertThat(ownerCount("SELECT count(*) FROM connector_connection WHERE workspace_id = ?", workspace.workspaceId)).isZero();
    }

    @Test
    void aDeletionWaitsForAnAskingAlreadyUnderWayAndThenSeesItInTheAir() throws Exception {
        Fixture fixture = fixture("subject-action-purge-race");
        ActionRequest lost = actionRepository.propose(fixture.workspaceId, fixture.userId, driveSave(fixture, "race"));
        long attempt = claimed(fixture, lost);
        actionRepository.markSent(fixture.workspaceId, fixture.userId, lost.id(), attempt, 90);
        actionRepository.finish(fixture.workspaceId, fixture.userId, lost.id(), attempt, unknown());
        long request = asApiLong(fixture.userId, "SELECT trash_document(" + fixture.workspaceId + ", " + fixture.documentId + ", 30)");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection asking = dataSource.getConnection()) {
            asking.setAutoCommit(false);
            setContext(asking, fixture.userId);
            try (PreparedStatement claim = asking.prepareStatement("SELECT claim_outcome FROM action_claim_reconcile(?, ?, ?, ?)")) {
                claim.setLong(1, fixture.workspaceId);
                claim.setLong(2, lost.id());
                claim.setLong(3, fixture.driveConnectionId);
                claim.setInt(4, 120);
                try (ResultSet rs = claim.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("CLAIMED");
                }
            }
            // The asking holds its action and has not committed: the deletion must wait for it, not work around it.
            Future<String> purge = pool.submit(() ->
                    asApiText(fixture.userId, "SELECT purge_trashed_document(" + fixture.workspaceId + ", " + request + ")"));
            long waitUntil = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!purge.isDone() && !waitingOnALock() && System.nanoTime() < waitUntil) {
                Thread.sleep(20);
            }
            assertThat(purge.isDone()).as("the deletion waits for the asking already under way").isFalse();
            asking.commit();
            assertThat(purge.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("and then sees it in the air, as it would a running job").isEqualTo("JOBS_STILL_STOPPING");
        } finally {
            pool.shutdownNow();
        }
        assertThat(ownerCount("SELECT count(*) FROM action_attempt WHERE action_id = ?", lost.id())).isEqualTo(2);
    }

    /** Whether one of the runtime login's own sessions is waiting for a row another holds. */
    private boolean waitingOnALock() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%purge_trashed_document%'"
                                + " AND datname = current_database()");
                ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getLong(1) > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // --- fixtures ---

    private record Fixture(long userId, long workspaceId, long documentId, long revisionId, long receiptId,
                           long driveConnectionId, long calendarConnectionId) {
    }

    private Fixture fixture(String subject) throws SQLException {
        long userId = userIdentityRepository.recordLogin("https://issuer-action-tests", subject, null, null).id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        TemplateVersion version = newActivatedTemplateVersion(workspaceId, userId);
        long documentId = revisionService.createDocument(
                workspaceId, userId, new IdempotencyKey("create-" + UUID.randomUUID()),
                CanonicalRequestHash.sha256OfCanonicalText("create-" + UUID.randomUUID()),
                "Test document", version.templateId(), version.id(),
                new DocumentContent(Map.of("meeting.title", new FieldValue.TextValue("Test meeting"))), Map.of(), "initial draft")
                .document().id();
        long revisionId = revisionService.findDocument(workspaceId, userId, documentId).orElseThrow().currentRevisionId();
        long drive = insertConnection(workspaceId, userId, "DRIVE_SAVING", "drive-account-" + subject);
        long calendar = insertConnection(workspaceId, userId, "CALENDAR_EVENT_CREATION", "calendar-account-" + subject);
        Fixture partial = new Fixture(userId, workspaceId, documentId, revisionId, 0, drive, calendar);
        long receiptId = newReceipt(partial);
        return new Fixture(userId, workspaceId, documentId, revisionId, receiptId, drive, calendar);
    }

    private long newReceipt(Fixture fixture) {
        TemplateVersion version = templateVersionOf(fixture);
        long artifactId = insertArtifact(fixture.workspaceId, fixture.userId);
        long manifestId = insertValidationManifest(fixture, version, artifactId);
        ExportApproval approval = exportApprovalRepository.save(
                fixture.workspaceId, fixture.userId, fixture.documentId, fixture.revisionId, version.id(), manifestId, ExportFormat.DOCX);
        ExportReceipt receipt = exportRepository.save(
                fixture.workspaceId, fixture.userId, fixture.documentId, fixture.revisionId, version.id(), approval.id(), manifestId,
                artifactId, "a".repeat(64), null, null, ExportFormat.DOCX);
        return receipt.id();
    }

    private NewAction driveSave(Fixture fixture, String marker) {
        return driveSaveThrough(fixture, fixture.driveConnectionId, marker);
    }

    private NewAction driveSaveThrough(Fixture fixture, long connectionId, String marker) {
        String payload = CanonicalJson.write(Map.of("marker", marker + "-payload", "nonce", UUID.randomUUID().toString()));
        return new NewAction(fixture.documentId, connectionId, ActionType.DRIVE_SAVE_FILE, payload,
                CanonicalJson.sha256Hex(payload), fixture.revisionId, fixture.receiptId, null, null, reservedId());
    }

    private NewAction conversion(Fixture fixture, String marker) {
        String payload = CanonicalJson.write(Map.of("marker", marker + "-conversion", "nonce", UUID.randomUUID().toString()));
        return new NewAction(fixture.documentId, fixture.driveConnectionId, ActionType.DRIVE_SAVE_AS_GOOGLE_DOC, payload,
                CanonicalJson.sha256Hex(payload), fixture.revisionId, fixture.receiptId, null, null, null);
    }

    private NewAction calendarEvent(Fixture fixture, String marker) {
        return calendarEvent(fixture, marker, null);
    }

    private NewAction calendarEvent(Fixture fixture, String marker, String sibling) {
        String payload = CanonicalJson.write(Map.of("marker", marker + "-payload", "nonce", UUID.randomUUID().toString()));
        return new NewAction(fixture.documentId, fixture.calendarConnectionId, ActionType.CALENDAR_CREATE_EVENT, payload,
                sibling == null ? CanonicalJson.sha256Hex(payload) : sibling, null, null, null, null, reservedId());
    }

    private static String reservedId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private long claimed(Fixture fixture, ActionRequest action) {
        Claim claim = actionRepository.claim(fixture.workspaceId, fixture.userId, action.id(), action.payloadHash(), LEASE);
        assertThat(claim.outcome()).isEqualTo(ClaimOutcome.CLAIMED);
        return claim.attemptId();
    }

    private void assertEnded(Fixture fixture, ActionRequest action, ActionFailure failure) {
        assertThat(actionRepository.claim(fixture.workspaceId, fixture.userId, action.id(), action.payloadHash(), LEASE).outcome())
                .isEqualTo(ClaimOutcome.ENDED);
        ActionRequest ended = actionRepository.find(fixture.workspaceId, fixture.userId, action.id()).orElseThrow();
        if (failure != null) {
            assertThat(ended.state()).isEqualTo(ActionState.FAILED);
            assertThat(ended.failure()).isEqualTo(failure);
        }
        assertThat(ended.approvedAt()).as("nothing was approved").isNull();
        assertThat(actionRepository.attempts(fixture.workspaceId, fixture.userId, action.id())).isEmpty();
    }

    private static AttemptResult succeeded(String externalId) {
        return new AttemptResult(ActionState.SUCCEEDED, AttemptOutcome.APPLIED, ActionVerification.MATCHED, null,
                externalId, "https://drive.google.com/file/d/" + externalId + "/view", 200, List.of(), null, null);
    }

    private static AttemptResult unknown() {
        return new AttemptResult(ActionState.OUTCOME_UNKNOWN, AttemptOutcome.UNKNOWN, null, null, null, null, 503, List.of("backendError"), null, null);
    }

    private static AttemptResult backToApproved(AttemptOutcome outcome) {
        return new AttemptResult(ActionState.APPROVED, outcome, null, null, null, null, 429, List.of("rateLimitExceeded"), null, null);
    }

    private TemplateVersion newActivatedTemplateVersion(long workspaceId, long userId) {
        long artifactId = insertArtifact(workspaceId, userId);
        StructuralNode control = new StructuralNode("p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.title", null, List.of());
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(control));
        DocxStructuralGraph graph = new DocxStructuralGraph(
                "test-parser-v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
        ExtractionVersion extraction = extractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "test-parser-v1", graph);
        Template template = templateRepository.createDraft(workspaceId, userId, "Club Minutes", artifactId, extraction.id());
        TemplateVersion draft = templateRepository.replaceDraftBindings(workspaceId, userId, template.id(), 1, List.of(new FieldDefinition(
                "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                new FieldBindingTarget.ContentControlTag("meeting.title"))));
        return templateRepository.activate(workspaceId, userId, template.id(), draft.versionNumber());
    }

    private TemplateVersion templateVersionOf(Fixture fixture) {
        long versionId = revisionService.findDocument(fixture.workspaceId, fixture.userId, fixture.documentId).orElseThrow().templateVersionId();
        long templateId = revisionService.findDocument(fixture.workspaceId, fixture.userId, fixture.documentId).orElseThrow().templateId();
        return templateRepository.findVersion(fixture.workspaceId, fixture.userId, templateId, versionId).orElseThrow();
    }

    private long insertArtifact(long workspaceId, long userId) {
        return asApiInsert(userId, "INSERT INTO artifact (workspace_id, blob_key, status, byte_count, detected_media_type)"
                + " VALUES (" + workspaceId + ", 'test-blob-" + UUID.randomUUID() + "', 'READY', 100, 'DOCX') RETURNING id");
    }

    private long insertValidationManifest(Fixture fixture, TemplateVersion version, long artifactId) {
        return asApiInsert(fixture.userId, "INSERT INTO validation_manifest"
                + " (workspace_id, document_id, revision_id, template_id, template_version_id, docx_artifact_id, docx_sha256, findings)"
                + " VALUES (" + fixture.workspaceId + ", " + fixture.documentId + ", " + fixture.revisionId + ", " + version.templateId()
                + ", " + version.id() + ", " + artifactId + ", '" + "a".repeat(64) + "', '[]'::jsonb) RETURNING id");
    }

    /** A usable connection as the consent flow leaves one: made by the table's owner, because a token's shape is all that matters here. */
    private static long insertConnection(long workspaceId, long userId, String access, String accountId) throws SQLException {
        try (Connection owner = ownerConnection();
                PreparedStatement insert = owner.prepareStatement(
                        "INSERT INTO connector_connection (workspace_id, user_id, provider, access, account_id, account_email, granted_scopes,"
                                + " state, token_key_id, token_nonce, token_ciphertext, token_issued_at)"
                                + " VALUES (?, ?, 'GOOGLE', ?, ?, 'person@example.org', 'scope', 'ACTIVE', 'k', ?, ?, now()) RETURNING id")) {
            insert.setLong(1, workspaceId);
            insert.setLong(2, userId);
            insert.setString(3, access);
            insert.setString(4, accountId);
            insert.setBytes(5, new byte[12]);
            insert.setBytes(6, new byte[32]);
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void asApi(long userId, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                setContext(connection, userId);
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.execute();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private long asApiInsert(long userId, String sql) {
        return asApiLong(userId, sql);
    }

    private long asApiLong(long userId, String sql) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
                rs.next();
                long value = rs.getLong(1);
                connection.commit();
                return value;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String asApiText(long userId, String sql) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
                rs.next();
                String value = rs.getString(1);
                connection.commit();
                return value;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setContext(Connection connection, long userId) throws SQLException {
        try (PreparedStatement setContext = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            setContext.setString(1, String.valueOf(userId));
            setContext.executeQuery();
        }
    }

    private static void owner(String sql, Object... parameters) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    private static long ownerCount(String sql, long parameter) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String ownerText(String sql, long parameter) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            List<String> values = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
            }
            return String.join("\n", values);
        }
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DB.jdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
    }
}
