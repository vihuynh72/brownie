package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.api.job.CanonicalRequestHasher;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.CancellationCommand;
import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.CommandReceipt;
import io.github.vihuynh72.brownie.core.job.EnqueueJobCommand;
import io.github.vihuynh72.brownie.core.job.IdempotencyConflictException;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.job.InvalidJobTransitionException;
import io.github.vihuynh72.brownie.core.job.JobDeadlineExceededException;
import io.github.vihuynh72.brownie.core.job.Job;
import io.github.vihuynh72.brownie.core.job.JobCommandRepository;
import io.github.vihuynh72.brownie.core.job.JobEvent;
import io.github.vihuynh72.brownie.core.job.JobEventRepository;
import io.github.vihuynh72.brownie.core.job.JobEventType;
import io.github.vihuynh72.brownie.core.job.JobNotFoundException;
import io.github.vihuynh72.brownie.core.job.JobOutboxRepository;
import io.github.vihuynh72.brownie.core.job.JobStage;
import io.github.vihuynh72.brownie.core.job.JobState;
import io.github.vihuynh72.brownie.core.job.JobTarget;
import io.github.vihuynh72.brownie.core.job.JobType;
import io.github.vihuynh72.brownie.core.job.OutboxEvent;
import io.github.vihuynh72.brownie.core.job.ResumeJobCommand;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct integration coverage for durable command handling under the real
 * non-bypassing runtime role. It proves the command transaction writes only
 * metadata rows, protects idempotency scope, and keeps the worker limited to
 * queue/event/output tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class JdbcJobRepositoryTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String WORKER_PASSWORD = "brownie_worker_local_only";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("brownie")
            .withUsername("postgres")
            .withPassword(BOOTSTRAP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(initScriptPath()), "/docker-entrypoint-initdb.d/01-app-roles.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath()
                .getParent()
                .getParent()
                .resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    @Autowired
    private JobCommandRepository jobCommandRepository;

    @Autowired
    private JobEventRepository jobEventRepository;

    @Autowired
    private JobOutboxRepository jobOutboxRepository;

    @Autowired
    private CanonicalRequestHasher canonicalRequestHasher;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void enqueueAtomicallyCreatesReceiptJobEventAndOutboxAndReturnsTheSameReceiptOnRetry() throws SQLException {
        UserIdentity user = newUser("enqueue-atomic");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        CanonicalRequestHash requestHash = canonicalRequestHasher.hashJson(
                "{\"target\":{\"id\":31,\"version\":4},\"stage\":\"render\"}");
        EnqueueJobCommand command = command("enqueue-key", 31L, requestHash);

        CommandReceipt first = jobCommandRepository.enqueue(workspace.id(), user.id(), command);
        CommandReceipt second = jobCommandRepository.enqueue(workspace.id(), user.id(), command);

        assertThat(second).isEqualTo(first);
        Job job = jobCommandRepository.find(workspace.id(), user.id(), first.jobId()).orElseThrow();
        assertThat(job.state()).isEqualTo(JobState.QUEUED);
        assertThat(job.target()).isEqualTo(new JobTarget("revision", 31L, 4L));
        assertThat(job.lease()).isNull();
        assertThat(jobCommandRepository.findReceipt(workspace.id(), user.id(), first.commandId())).contains(first);

        List<JobEvent> events = jobEventRepository.findAfter(workspace.id(), user.id(), 0L, 10);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(JobEventType.QUEUED);
            assertThat(event.state()).isEqualTo(JobState.QUEUED);
            assertThat(event.sequence()).isEqualTo(1L);
        });
        List<OutboxEvent> outbox = jobOutboxRepository.findByJob(workspace.id(), user.id(), job.id());
        assertThat(outbox).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(JobEventType.QUEUED);
            assertThat(event.jobEventId()).isEqualTo(events.getFirst().id());
        });

        assertThat(countAsMember(user.id(), "SELECT count(*) FROM idempotency_record")).isEqualTo(1);
        assertThat(countAsMember(user.id(), "SELECT count(*) FROM command_receipt")).isEqualTo(1);
        assertThat(countAsMember(user.id(), "SELECT count(*) FROM job")).isEqualTo(1);
        assertThat(countAsMember(user.id(), "SELECT count(*) FROM job_event")).isEqualTo(1);
        assertThat(countAsMember(user.id(), "SELECT count(*) FROM outbox_event")).isEqualTo(1);
    }

    @Test
    void reusingAnIdempotencyKeyWithDifferentCanonicalInputDoesNotCreateAnotherJob() throws SQLException {
        UserIdentity user = newUser("idempotency-conflict");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        CanonicalRequestHash firstHash = canonicalRequestHasher.hashJson("{\"stage\":\"render\",\"target\":31}");
        CanonicalRequestHash differentHash = canonicalRequestHasher.hashJson("{\"stage\":\"render\",\"target\":32}");

        jobCommandRepository.enqueue(workspace.id(), user.id(), command("same-key", 31L, firstHash));

        assertThatThrownBy(() -> jobCommandRepository.enqueue(workspace.id(), user.id(), command("same-key", 32L, differentHash)))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(countAsMember(user.id(), "SELECT count(*) FROM job")).isEqualTo(1);
        assertThat(countAsMember(user.id(), "SELECT count(*) FROM command_receipt")).isEqualTo(1);
        assertThat(countAsMember(user.id(), "SELECT count(*) FROM outbox_event")).isEqualTo(1);
    }

    @Test
    void queuedAndWaitingJobsAreCancelledImmediatelyWhileALeasedJobRecordsACooperativeRequest() throws SQLException {
        UserIdentity user = newUser("cancellation-paths");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        CommandReceipt queuedReceipt = jobCommandRepository.enqueue(
                workspace.id(), user.id(), command("queued", 41L, hashFor("queued")));
        CommandReceipt waitingReceipt = jobCommandRepository.enqueue(
                workspace.id(), user.id(), command("waiting", 42L, hashFor("waiting")));
        CommandReceipt leasedReceipt = jobCommandRepository.enqueue(
                workspace.id(), user.id(), command("leased", 43L, hashFor("leased")));
        updateAsMigration(
                "UPDATE job SET state = 'WAITING_FOR_INPUT' WHERE id = ?",
                waitingReceipt.jobId());
        updateAsMigration(
                """
                UPDATE job
                SET state = 'LEASED', lease_owner = 'worker-a', lease_expires_at = now() + interval '5 minutes', fencing_token = 1
                WHERE id = ?
                """,
                leasedReceipt.jobId());

        jobCommandRepository.requestCancellation(
                workspace.id(), user.id(), new CancellationCommand(new IdempotencyKey("cancel-queued"), hashFor("cancel-queued"), queuedReceipt.jobId()));
        jobCommandRepository.requestCancellation(
                workspace.id(), user.id(), new CancellationCommand(new IdempotencyKey("cancel-waiting"), hashFor("cancel-waiting"), waitingReceipt.jobId()));
        jobCommandRepository.requestCancellation(
                workspace.id(), user.id(), new CancellationCommand(new IdempotencyKey("cancel-leased"), hashFor("cancel-leased"), leasedReceipt.jobId()));

        assertThat(jobCommandRepository.find(workspace.id(), user.id(), queuedReceipt.jobId()).orElseThrow().state())
                .isEqualTo(JobState.CANCELLED);
        assertThat(jobCommandRepository.find(workspace.id(), user.id(), waitingReceipt.jobId()).orElseThrow().state())
                .isEqualTo(JobState.CANCELLED);
        assertThat(jobCommandRepository.find(workspace.id(), user.id(), leasedReceipt.jobId()).orElseThrow().state())
                .isEqualTo(JobState.CANCEL_REQUESTED);

        List<JobEvent> events = jobEventRepository.findAfter(workspace.id(), user.id(), 0L, 20);
        assertThat(events.stream().filter(event -> event.jobId() == queuedReceipt.jobId()).map(JobEvent::type))
                .containsExactly(JobEventType.QUEUED, JobEventType.CANCELLED);
        assertThat(events.stream().filter(event -> event.jobId() == waitingReceipt.jobId()).map(JobEvent::type))
                .containsExactly(JobEventType.QUEUED, JobEventType.CANCELLED);
        assertThat(events.stream().filter(event -> event.jobId() == leasedReceipt.jobId()).map(JobEvent::type))
                .containsExactly(JobEventType.QUEUED, JobEventType.CANCELLATION_REQUESTED);
    }

    @Test
    void waitingJobResumeIsIdempotentAndWritesItsEventAndOutboxAtomically() throws SQLException {
        UserIdentity user = newUser("resume-waiting");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        CommandReceipt queuedReceipt = jobCommandRepository.enqueue(
                workspace.id(), user.id(), command("resume-waiting", 44L, hashFor("resume-waiting")));
        updateAsMigration("UPDATE job SET state = 'WAITING_FOR_INPUT' WHERE id = ?", queuedReceipt.jobId());
        ResumeJobCommand command = new ResumeJobCommand(
                new IdempotencyKey("resume-waiting-key"), hashFor("resume-waiting-command"), queuedReceipt.jobId());

        CommandReceipt first = jobCommandRepository.requestResume(workspace.id(), user.id(), command);
        CommandReceipt second = jobCommandRepository.requestResume(workspace.id(), user.id(), command);

        assertThat(second).isEqualTo(first);
        assertThat(first.commandType().operation()).isEqualTo("job.request-resume");
        assertThat(jobCommandRepository.find(workspace.id(), user.id(), queuedReceipt.jobId()).orElseThrow().state())
                .isEqualTo(JobState.QUEUED);
        assertThat(jobEventRepository.findAfter(workspace.id(), user.id(), 0L, 10))
                .extracting(JobEvent::type)
                .containsExactly(JobEventType.QUEUED, JobEventType.RESUMED);
        assertThat(jobOutboxRepository.findByJob(workspace.id(), user.id(), queuedReceipt.jobId()))
                .extracting(OutboxEvent::type)
                .containsExactly(JobEventType.QUEUED, JobEventType.RESUMED);
    }

    @Test
    void expiredWaitingJobIsTerminalizedInsteadOfResumed() throws SQLException {
        UserIdentity user = newUser("resume-expired");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        CommandReceipt queuedReceipt = jobCommandRepository.enqueue(
                workspace.id(), user.id(), command("resume-expired", 47L, hashFor("resume-expired")));
        updateAsMigration("UPDATE job SET state = 'WAITING_FOR_INPUT' WHERE id = ?", queuedReceipt.jobId());
        expireAsMigration(queuedReceipt.jobId());

        assertThatThrownBy(() -> jobCommandRepository.requestResume(
                workspace.id(),
                user.id(),
                new ResumeJobCommand(
                        new IdempotencyKey("resume-expired-key"),
                        hashFor("resume-expired-command"),
                        queuedReceipt.jobId())))
                .isInstanceOf(JobDeadlineExceededException.class);

        assertThat(jobCommandRepository.find(workspace.id(), user.id(), queuedReceipt.jobId()).orElseThrow().state())
                .isEqualTo(JobState.DEAD);
        assertThat(jobEventRepository.findAfter(workspace.id(), user.id(), 0L, 10))
                .extracting(JobEvent::type)
                .containsExactly(JobEventType.QUEUED, JobEventType.RELEASED);
        assertThat(jobOutboxRepository.findByJob(workspace.id(), user.id(), queuedReceipt.jobId()))
                .extracting(OutboxEvent::type)
                .containsExactly(JobEventType.QUEUED, JobEventType.RELEASED);
        assertThat(countAsMember(user.id(), "SELECT count(*) FROM idempotency_record")).isEqualTo(1);
    }

    @Test
    void memberCannotResumeAnExpiredWaitingJobWithARawStateUpdate() throws SQLException {
        UserIdentity user = newUser("raw-resume-expired");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        CommandReceipt queuedReceipt = jobCommandRepository.enqueue(
                workspace.id(), user.id(), command("raw-resume-expired", 48L, hashFor("raw-resume-expired")));
        updateAsMigration("UPDATE job SET state = 'WAITING_FOR_INPUT' WHERE id = ?", queuedReceipt.jobId());
        expireAsMigration(queuedReceipt.jobId());

        assertThatThrownBy(() -> updateAsMember(
                user.id(),
                "UPDATE job SET state = 'QUEUED' WHERE id = ?",
                queuedReceipt.jobId()))
                .isInstanceOf(SQLException.class);
        assertThat(jobCommandRepository.find(workspace.id(), user.id(), queuedReceipt.jobId()).orElseThrow().state())
                .isEqualTo(JobState.WAITING_FOR_INPUT);
    }

    @Test
    void missingOrCrossWorkspaceJobsAreNotFoundAndInvalidLifecycleCommandsConflict() {
        UserIdentity userA = newUser("job-command-errors-a");
        UserIdentity userB = newUser("job-command-errors-b");
        Workspace workspaceA = workspaceRepository.ensurePersonalWorkspace(userA.id());
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(userB.id());
        CommandReceipt foreignReceipt = jobCommandRepository.enqueue(
                workspaceB.id(), userB.id(), command("foreign-job", 45L, hashFor("foreign-job")));
        CommandReceipt localReceipt = jobCommandRepository.enqueue(
                workspaceA.id(), userA.id(), command("terminal-job", 46L, hashFor("terminal-job")));

        assertThatThrownBy(() -> jobCommandRepository.requestCancellation(
                workspaceA.id(), userA.id(), new CancellationCommand(new IdempotencyKey("missing-job"), hashFor("missing-job"), Long.MAX_VALUE)))
                .isInstanceOf(JobNotFoundException.class);
        assertThatThrownBy(() -> jobCommandRepository.requestCancellation(
                workspaceA.id(), userA.id(), new CancellationCommand(new IdempotencyKey("foreign-job"), hashFor("foreign-job-command"), foreignReceipt.jobId())))
                .isInstanceOf(JobNotFoundException.class);
        assertThatThrownBy(() -> jobCommandRepository.requestResume(
                workspaceA.id(), userA.id(), new ResumeJobCommand(new IdempotencyKey("resume-queued"), hashFor("resume-queued"), localReceipt.jobId())))
                .isInstanceOf(InvalidJobTransitionException.class);

        jobCommandRepository.requestCancellation(
                workspaceA.id(), userA.id(), new CancellationCommand(new IdempotencyKey("cancel-terminal"), hashFor("cancel-terminal"), localReceipt.jobId()));
        assertThatThrownBy(() -> jobCommandRepository.requestCancellation(
                workspaceA.id(), userA.id(), new CancellationCommand(new IdempotencyKey("cancel-terminal-again"), hashFor("cancel-terminal-again"), localReceipt.jobId())))
                .isInstanceOf(InvalidJobTransitionException.class);
    }

    @Test
    void workspaceEventCursorUsesGlobalEventIdsAcrossJobs() {
        UserIdentity user = newUser("event-cursor");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        CommandReceipt first = jobCommandRepository.enqueue(workspace.id(), user.id(), command("cursor-a", 51L, hashFor("cursor-a")));
        CommandReceipt second = jobCommandRepository.enqueue(workspace.id(), user.id(), command("cursor-b", 52L, hashFor("cursor-b")));

        List<JobEvent> all = jobEventRepository.findAfter(workspace.id(), user.id(), 0L, 10);
        assertThat(all).hasSize(2);
        assertThat(all.get(1).id()).isGreaterThan(all.getFirst().id());
        assertThat(all.getFirst().jobId()).isEqualTo(first.jobId());
        assertThat(jobEventRepository.exists(workspace.id(), user.id(), all.getFirst().id())).isTrue();
        assertThat(jobEventRepository.exists(workspace.id(), user.id(), Long.MAX_VALUE)).isFalse();
        assertThat(jobEventRepository.findAfter(workspace.id(), user.id(), all.getFirst().id(), 10))
                .extracting(JobEvent::jobId)
                .containsExactly(second.jobId());
    }

    @Test
    void workerCannotReadOrWriteTenantOrQueueTablesEvenWithASpoofedUserContext() throws SQLException {
        UserIdentity user = newUser("worker-policy");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        CommandReceipt receipt = jobCommandRepository.enqueue(workspace.id(), user.id(), command("worker-policy", 61L, hashFor("worker-policy")));

        assertThatThrownBy(() -> countAsWorker("SELECT count(*) FROM job WHERE id = " + receipt.jobId()))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> countAsWorkerWithContext(user.id(), "SELECT count(*) FROM idempotency_record"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> countAsWorkerWithContext(user.id(), "SELECT count(*) FROM command_receipt"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> countAsWorkerWithContext(user.id(), "SELECT count(*) FROM document"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertStagedOutputAsWorker(workspace.id(), receipt.jobId()))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void runtimeRolesCannotReplaceAJobTargetWhileApiCancellationStillUsesItsNarrowWriteSet() throws SQLException {
        UserIdentity user = newUser("job-column-grants");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        CommandReceipt receipt = jobCommandRepository.enqueue(workspace.id(), user.id(), command("column-grants", 62L, hashFor("column-grants")));
        CommandReceipt leasedReceipt = jobCommandRepository.enqueue(
                workspace.id(), user.id(), command("column-grants-leased", 64L, hashFor("column-grants-leased")));
        updateAsMigration(
                """
                UPDATE job
                SET state = 'LEASED', lease_owner = 'worker-column-grants',
                    lease_expires_at = now() + interval '5 minutes', fencing_token = 1
                WHERE id = ?
                """,
                leasedReceipt.jobId());

        assertThatThrownBy(() -> updateAsMember(
                user.id(),
                "UPDATE job SET resource_id = 999 WHERE id = ?",
                receipt.jobId()))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> executeAsWorker("UPDATE job SET resource_id = 999 WHERE id = ?", receipt.jobId()))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> executeAsWorker("UPDATE job SET state = 'LEASED' WHERE id = ?", receipt.jobId()))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertPreLeasedJobAsMember(user.id(), workspace.id()))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> updateAsMember(
                user.id(),
                "UPDATE job SET state = 'CANCELLED', cancellation_requested_at = now() WHERE id = ?",
                leasedReceipt.jobId()))
                .isInstanceOf(SQLException.class);

        jobCommandRepository.requestCancellation(
                workspace.id(), user.id(), new CancellationCommand(new IdempotencyKey("column-grants-cancel"), hashFor("column-grants-cancel"), receipt.jobId()));
        Job job = jobCommandRepository.find(workspace.id(), user.id(), receipt.jobId()).orElseThrow();
        assertThat(job.target()).isEqualTo(new JobTarget("revision", 62L, 4L));
        assertThat(job.state()).isEqualTo(JobState.CANCELLED);
        assertThat(countAsMember(user.id(), "SELECT count(*) FROM job WHERE workspace_id = " + workspace.id() + " AND resource_id = 63"))
                .isZero();
    }

    @Test
    void receiptCannotMismatchItsIdempotencyIdentity() throws SQLException {
        UserIdentity user = newUser("receipt-identity");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        CommandReceipt receipt = jobCommandRepository.enqueue(
                workspace.id(), user.id(), command("receipt-identity-job", 65L, hashFor("receipt-identity-job")));
        UUID idempotencyCommandId = UUID.randomUUID();
        UUID mismatchedReceiptCommandId = UUID.randomUUID();
        String requestHash = "b".repeat(64);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, user.id());
            long idempotencyRecordId;
            try (PreparedStatement insertRecord = connection.prepareStatement(
                    """
                    INSERT INTO idempotency_record
                        (workspace_id, actor_user_id, operation, idempotency_key, request_hash, command_id)
                    VALUES (?, ?, 'job.enqueue', 'receipt-identity-key', ?, ?)
                    RETURNING id
                    """)) {
                insertRecord.setLong(1, workspace.id());
                insertRecord.setLong(2, user.id());
                insertRecord.setString(3, requestHash);
                insertRecord.setObject(4, idempotencyCommandId);
                try (ResultSet result = insertRecord.executeQuery()) {
                    result.next();
                    idempotencyRecordId = result.getLong(1);
                }
            }

            assertThatThrownBy(() -> {
                try (PreparedStatement insertReceipt = connection.prepareStatement(
                        """
                        INSERT INTO command_receipt
                            (command_id, workspace_id, actor_user_id, idempotency_record_id, job_id, operation, request_hash)
                        VALUES (?, ?, ?, ?, ?, 'job.enqueue', ?)
                        """)) {
                    insertReceipt.setObject(1, mismatchedReceiptCommandId);
                    insertReceipt.setLong(2, workspace.id());
                    insertReceipt.setLong(3, user.id());
                    insertReceipt.setLong(4, idempotencyRecordId);
                    insertReceipt.setLong(5, receipt.jobId());
                    insertReceipt.setString(6, requestHash);
                    insertReceipt.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
            connection.rollback();
        }
    }

    @Test
    void databaseRejectsALeaseThatExtendsPastTheJobDeadline() throws SQLException {
        UserIdentity user = newUser("lease-deadline-shape");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        CommandReceipt receipt = jobCommandRepository.enqueue(
                workspace.id(), user.id(), command("lease-deadline-shape", 66L, hashFor("lease-deadline-shape")));

        assertThatThrownBy(() -> updateAsMigration(
                """
                UPDATE job
                SET state = 'LEASED', attempt_count = 1, lease_owner = 'worker-deadline-shape',
                    lease_expires_at = deadline_at + interval '1 minute', fencing_token = 1
                WHERE id = ?
                """,
                receipt.jobId()))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void anotherWorkspaceMemberContextCannotReadAJobOrItsEvents() {
        UserIdentity userA = newUser("job-rls-a");
        UserIdentity userB = newUser("job-rls-b");
        Workspace workspaceA = workspaceRepository.ensurePersonalWorkspace(userA.id());
        CommandReceipt receipt = jobCommandRepository.enqueue(workspaceA.id(), userA.id(), command("private", 71L, hashFor("private")));

        assertThat(jobCommandRepository.find(workspaceA.id(), userB.id(), receipt.jobId())).isEmpty();
        assertThat(jobEventRepository.findAfter(workspaceA.id(), userB.id(), 0L, 10)).isEmpty();
        assertThat(jobEventRepository.exists(workspaceA.id(), userB.id(), 1L)).isFalse();
        assertThat(jobOutboxRepository.findByJob(workspaceA.id(), userB.id(), receipt.jobId())).isEmpty();
    }

    private UserIdentity newUser(String subject) {
        return userIdentityRepository.recordLogin("https://issuer-job-tests", subject, null, null);
    }

    private EnqueueJobCommand command(String key, long resourceId, CanonicalRequestHash requestHash) {
        OffsetDateTime availableAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        return new EnqueueJobCommand(
                new IdempotencyKey(key),
                requestHash,
                new JobType("compile"),
                new JobTarget("revision", resourceId, 4L),
                new JobStage("render"),
                CanonicalRequestHash.sha256OfCanonicalText("configuration-v1"),
                availableAt);
    }

    private CanonicalRequestHash hashFor(String key) {
        return canonicalRequestHasher.hashJson("{\"key\":\"" + key + "\"}");
    }

    private long countAsMember(long userId, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                long count = resultSet.getLong(1);
                connection.rollback();
                return count;
            }
        }
    }

    private long countAsWorker(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_worker", WORKER_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private long countAsWorkerWithContext(long userId, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_worker", WORKER_PASSWORD)) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                long count = resultSet.getLong(1);
                connection.rollback();
                return count;
            }
        }
    }

    private void updateAsMember(long userId, String sql, long jobId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, jobId);
                statement.executeUpdate();
            } finally {
                connection.rollback();
            }
        }
    }

    private void insertPreLeasedJobAsMember(long userId, long workspaceId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO job
                        (workspace_id, requested_by_user_id, job_type, resource_type, resource_id, resource_version,
                         stage, processing_configuration_hash, state, attempt_count, lease_owner, lease_expires_at, fencing_token)
                    VALUES (?, ?, 'compile', 'revision', 63, 4, 'render', ?, 'LEASED', 1, 'unauthorized-worker', now() + interval '1 minute', 1)
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, userId);
                statement.setString(3, "a".repeat(64));
                statement.executeUpdate();
            } finally {
                connection.rollback();
            }
        }
    }

    private void updateAsMigration(String sql, long jobId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, jobId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private void executeAsWorker(String sql, long jobId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_worker", WORKER_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, jobId);
            statement.executeUpdate();
        }
    }

    private void expireAsMigration(long jobId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE job SET deadline_at = clock_timestamp() - interval '1 minute' WHERE id = ?")) {
            statement.setLong(1, jobId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private void insertStagedOutputAsWorker(long workspaceId, long jobId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_worker", WORKER_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO job_staged_output
                            (workspace_id, job_id, worker_id, fencing_token, output_kind, object_key, expires_at)
                        VALUES (?, ?, 'worker-a', 1, 'render', 'temporary/object-key', now() + interval '1 hour')
                        """)) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, jobId);
            statement.executeUpdate();
        }
    }

    private void setLocalContext(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            statement.setString(1, String.valueOf(userId));
            statement.executeQuery();
        }
    }
}
