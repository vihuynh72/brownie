package io.github.vihuynh72.brownie.worker.persistence;

import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.job.JobCompletion;
import io.github.vihuynh72.brownie.core.job.JobCompletionResult;
import io.github.vihuynh72.brownie.core.job.JobFailure;
import io.github.vihuynh72.brownie.core.job.JobFailureKind;
import io.github.vihuynh72.brownie.core.job.JobLeaseRepository;
import io.github.vihuynh72.brownie.core.job.JobOutputPublication;
import io.github.vihuynh72.brownie.core.job.JobOutputPublicationResult;
import io.github.vihuynh72.brownie.core.job.JobOutputPublisher;
import io.github.vihuynh72.brownie.core.job.JobOutputReconciler;
import io.github.vihuynh72.brownie.core.job.JobRelease;
import io.github.vihuynh72.brownie.core.job.JobReleaseResult;
import io.github.vihuynh72.brownie.core.job.JobProgress;
import io.github.vihuynh72.brownie.core.job.JobState;
import io.github.vihuynh72.brownie.core.job.JobTarget;
import io.github.vihuynh72.brownie.core.job.LeasedJob;
import io.github.vihuynh72.brownie.core.job.StagedOutput;
import io.github.vihuynh72.brownie.core.job.StagedJobOutput;
import io.github.vihuynh72.brownie.core.job.StagedOutputRequest;
import io.github.vihuynh72.brownie.core.job.WorkerId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class JdbcJobLeaseFaultInjectionIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String WORKER_PASSWORD = "brownie_worker_local_only";
    private static final String CONFIGURATION_HASH = "a".repeat(64);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("brownie")
            .withUsername("postgres")
            .withPassword(BOOTSTRAP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(initScriptPath()), "/docker-entrypoint-initdb.d/01-app-roles.sql");

    @Container
    static final AzuriteContainer AZURITE = new AzuriteContainer("mcr.microsoft.com/azure-storage/azurite:3.37.0");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_worker");
        registry.add("spring.datasource.password", () -> WORKER_PASSWORD);
        registry.add("brownie.worker.jobs.max-attempts", () -> "3");
        registry.add("brownie.worker.jobs.max-lease", () -> "PT1M");
        registry.add("brownie.storage.local-connection", AZURITE::getConnectionString);
        registry.add("brownie.worker.outputs.reconciliation.enabled", () -> "false");
    }

    @BeforeAll
    static void migrateQueueSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)
                .locations("filesystem:" + apiMigrationPath())
                .target("20")
                .load()
                .migrate();
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath()
                .getParent()
                .getParent()
                .resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    private static Path apiMigrationPath() {
        return Path.of("").toAbsolutePath()
                .getParent()
                .resolve("brownie-api/src/main/resources/db/migration");
    }

    @Autowired
    private JobLeaseRepository jobLeaseRepository;

    @Autowired
    private JobOutputPublisher jobOutputPublisher;

    @Autowired
    private BlobStore blobStore;

    @Autowired
    private JobOutputReconciler jobOutputReconciler;

    @Autowired
    private DataSource workerDataSource;

    @Test
    void reclaimsAnExpiredClaimAfterASimulatedWorkerDeath() throws Exception {
        Fixture fixture = queueReadyJob();
        LeasedJob abandoned = claim(fixture, "worker-crashed", Duration.ofSeconds(1));

        awaitLeaseExpiry(fixture.jobId());
        LeasedJob reclaimed = claim(fixture, "worker-restarted", Duration.ofMinutes(1));

        assertThat(abandoned.leaseToken().fencingToken()).isEqualTo(1);
        assertThat(reclaimed.leaseToken().fencingToken()).isEqualTo(2);
        assertThat(reclaimed.job().attemptCount()).isEqualTo(2);
        assertThat(snapshot(fixture.jobId())).isEqualTo(new JobSnapshot(JobState.LEASED, 2, 2, "worker-restarted"));

        assertThat(complete(reclaimed)).isEqualTo(JobCompletionResult.COMPLETED);
        assertThat(eventCount(fixture.jobId(), "COMPLETED")).isEqualTo(1);
        assertEveryJobEventHasMatchingOutboxEvent(fixture.jobId());
    }

    @Test
    void preventsDuplicatePublicationAfterStagedOutputAndLeaseRecovery() throws Exception {
        Fixture fixture = queueReadyJob();
        LeasedJob firstDelivery = claim(fixture, "worker-first", Duration.ofSeconds(1));
        StagedOutputRequest firstOutput = output("temporary/first", now().plusHours(1));

        StagedOutput recorded = jobLeaseRepository.recordStagedOutput(firstDelivery.leaseToken(), firstOutput).orElseThrow();
        StagedOutput duplicateWrite = jobLeaseRepository.recordStagedOutput(firstDelivery.leaseToken(), firstOutput).orElseThrow();
        assertThat(duplicateWrite.id()).isEqualTo(recorded.id());

        awaitLeaseExpiry(fixture.jobId());
        LeasedJob recovered = claim(fixture, "worker-second", Duration.ofMinutes(1));
        assertThat(jobLeaseRepository.recordStagedOutput(firstDelivery.leaseToken(), firstOutput)).isEmpty();
        StagedOutput secondOutput = jobLeaseRepository
                .recordStagedOutput(recovered.leaseToken(), output("temporary/second", now().plusHours(1)))
                .orElseThrow();

        assertThat(complete(firstDelivery)).isEqualTo(JobCompletionResult.LOST_LEASE);
        assertThat(complete(recovered)).isEqualTo(JobCompletionResult.COMPLETED);
        assertThat(complete(recovered)).isEqualTo(JobCompletionResult.LOST_LEASE);

        assertThat(secondOutput.id()).isNotEqualTo(recorded.id());
        assertThat(stagedOutputState(recorded.id())).isEqualTo("DISCARDED");
        assertThat(snapshot(fixture.jobId())).isEqualTo(new JobSnapshot(JobState.SUCCEEDED, 2, 2, null));
        assertThat(stagedOutputCount(fixture.jobId())).isEqualTo(2);
        assertThat(eventCount(fixture.jobId(), "STAGED_OUTPUT_RECORDED")).isEqualTo(2);
        assertThat(eventCount(fixture.jobId(), "COMPLETED")).isEqualTo(1);
        assertEveryJobEventHasMatchingOutboxEvent(fixture.jobId());
    }

    @Test
    void rejectsAStagedOutputThatIsAlreadyExpiredAtTheDatabaseClock() throws SQLException {
        Fixture fixture = queueReadyJob();
        LeasedJob leased = claim(fixture, "worker-expired-output", Duration.ofMinutes(1));
        try {
            assertThatThrownBy(() -> jobLeaseRepository.recordStagedOutput(
                    leased.leaseToken(), output("temporary/already-expired", now().minusMinutes(1))))
                    .isInstanceOf(DataAccessException.class);
            assertThat(stagedOutputCount(fixture.jobId())).isZero();
        } finally {
            assertThat(complete(leased)).isEqualTo(JobCompletionResult.COMPLETED);
        }
    }

    @Test
    void cancellationBeforeResultPublicationBlocksLateWorkerWrites() throws SQLException {
        Fixture fixture = queueReadyJob();
        LeasedJob leased = claim(fixture, "worker-cancelled", Duration.ofMinutes(1));

        requestCancellationAsMember(fixture);

        assertThat(jobLeaseRepository.recordStagedOutput(leased.leaseToken(), output("temporary/cancelled", now().plusHours(1))))
                .isEmpty();
        assertThat(complete(leased)).isEqualTo(JobCompletionResult.CANCELLATION_REQUESTED);
        assertThat(snapshot(fixture.jobId())).isEqualTo(new JobSnapshot(JobState.CANCELLED, 1, 1, null));
        assertThat(eventCount(fixture.jobId(), "COMPLETED")).isZero();
        assertThat(eventCount(fixture.jobId(), "CANCELLED")).isEqualTo(1);
        assertEveryJobEventHasMatchingOutboxEvent(fixture.jobId());
    }

    @Test
    void refusesCompletionWhenADocumentHasAdvancedBeyondThePinnedRevision() throws Exception {
        Fixture fixture = queueReadyDocumentJob();
        LeasedJob leased = claim(fixture, "worker-stale-document", Duration.ofMinutes(1));
        StagedOutput staged = jobLeaseRepository
                .recordStagedOutput(leased.leaseToken(), output("temporary/stale-document", now().plusHours(1)))
                .orElseThrow();

        advanceDocumentCurrentRevision(fixture);

        assertThat(complete(leased)).isEqualTo(JobCompletionResult.STALE_TARGET);
        assertThat(stagedOutputState(staged.id())).isEqualTo("DISCARDED");
        assertThat(snapshot(fixture.jobId())).isEqualTo(new JobSnapshot(JobState.DEAD, 1, 1, null));
        assertThat(eventCount(fixture.jobId(), "COMPLETED")).isZero();
        assertThat(eventCount(fixture.jobId(), "RELEASED")).isEqualTo(1);
        assertEveryJobEventHasMatchingOutboxEvent(fixture.jobId());
    }

    @Test
    void writesVerifiesAttachesAndIdempotentlyReplaysOneLogicalOutput() throws Exception {
        Fixture fixture = queueReadyDocumentJob();
        LeasedJob leased = claim(fixture, "worker-output", Duration.ofMinutes(1));
        byte[] content = "Published meeting output.".getBytes(StandardCharsets.UTF_8);

        StagedJobOutput staged = jobOutputPublisher.stage(
                leased, "compiled-document", new ByteArrayInputStream(content));
        JobOutputPublication firstPublication = jobOutputPublisher.publish(leased, staged);

        assertThat(firstPublication.result()).isEqualTo(JobOutputPublicationResult.PUBLISHED);
        assertThat(firstPublication.artifactId()).isNotNull();
        assertThat(snapshot(fixture.jobId())).isEqualTo(new JobSnapshot(JobState.SUCCEEDED, 1, 1, null));
        assertThat(stagedOutputState(staged.stagedOutput().id())).isEqualTo("ATTACHED");

        OutputArtifactSnapshot artifact = outputArtifact(fixture.jobId(), "compiled-document");
        assertThat(artifact.id()).isEqualTo(firstPublication.artifactId());
        assertThat(artifact.status()).isEqualTo("READY");
        assertThat(artifact.objectKey()).isEqualTo(staged.stagedOutput().metadata().objectKey());
        assertThat(artifact.byteCount()).isEqualTo(staged.stagedOutput().metadata().byteCount());
        assertThat(artifact.sha256()).isEqualTo(staged.stagedOutput().metadata().sha256());
        assertThat(artifact.mediaType()).isEqualTo("PLAIN_TEXT");
        try (InputStream stored = blobStore.openStream(artifact.objectKey())) {
            assertThat(stored.readAllBytes()).isEqualTo(content);
        }

        JobOutputPublication replay = jobOutputPublisher.stageAndPublish(
                leased, "compiled-document", new ByteArrayInputStream(content));

        assertThat(replay.result()).isEqualTo(JobOutputPublicationResult.ALREADY_PUBLISHED);
        assertThat(replay.artifactId()).isEqualTo(firstPublication.artifactId());
        assertThat(jobOutputArtifactCount(fixture.jobId())).isEqualTo(1);
        assertThat(stagedOutputCount(fixture.jobId())).isEqualTo(1);
        assertThat(eventCount(fixture.jobId(), "STAGED_OUTPUT_RECORDED")).isEqualTo(1);
        assertThat(eventCount(fixture.jobId(), "COMPLETED")).isEqualTo(1);
        assertEveryJobEventHasMatchingOutboxEvent(fixture.jobId());
    }

    @Test
    void recoveryDiscardsTheLostAttemptReclaimsItsObjectAndPreventsItFromOverwritingTheResult() throws Exception {
        Fixture fixture = queueReadyDocumentJob();
        byte[] abandonedBytes = "Abandoned temporary output.".getBytes(StandardCharsets.UTF_8);
        LeasedJob abandoned = claim(fixture, "worker-crashed-output", Duration.ofSeconds(1));
        StagedJobOutput abandonedOutput = jobOutputPublisher.stage(
                abandoned, "compiled-document", new ByteArrayInputStream(abandonedBytes));
        assertThat(blobStore.sizeOf(abandonedOutput.stagedOutput().metadata().objectKey())).contains((long) abandonedBytes.length);

        awaitLeaseExpiry(fixture.jobId());
        LeasedJob recovered = claim(fixture, "worker-recovered-output", Duration.ofMinutes(1));
        byte[] recoveredBytes = "Recovered verified output.".getBytes(StandardCharsets.UTF_8);
        JobOutputPublication recoveredPublication = jobOutputPublisher.stageAndPublish(
                recovered, "compiled-document", new ByteArrayInputStream(recoveredBytes));

        assertThat(recoveredPublication.result()).isEqualTo(JobOutputPublicationResult.PUBLISHED);
        assertThat(stagedOutputState(abandonedOutput.stagedOutput().id())).isEqualTo("DISCARDED");
        OutputArtifactSnapshot recoveredArtifact = outputArtifact(fixture.jobId(), "compiled-document");
        assertThat(recoveredArtifact.id()).isEqualTo(recoveredPublication.artifactId());
        assertThat(recoveredArtifact.objectKey()).doesNotMatch(".*/attempt-1/.*");

        JobOutputPublication stalePublication = jobOutputPublisher.publish(abandoned, abandonedOutput);
        assertThat(stalePublication.result()).isEqualTo(JobOutputPublicationResult.ALREADY_PUBLISHED);
        assertThat(stalePublication.artifactId()).isEqualTo(recoveredPublication.artifactId());
        assertThat(jobOutputArtifactCount(fixture.jobId())).isEqualTo(1);
        assertThat(eventCount(fixture.jobId(), "COMPLETED")).isEqualTo(1);

        jobOutputReconciler.reconcileOnce(32);

        assertThat(blobStore.sizeOf(abandonedOutput.stagedOutput().metadata().objectKey())).isEmpty();
        assertThat(stagedOutputCleanedAt(abandonedOutput.stagedOutput().id())).isNotNull();
        try (InputStream stored = blobStore.openStream(recoveredArtifact.objectKey())) {
            assertThat(stored.readAllBytes()).isEqualTo(recoveredBytes);
        }
        assertEveryJobEventHasMatchingOutboxEvent(fixture.jobId());
    }

    @Test
    void cancellationAfterStagingDeletesTheTemporaryObjectAndPublishesNoArtifact() throws Exception {
        Fixture fixture = queueReadyDocumentJob();
        LeasedJob leased = claim(fixture, "worker-output-cancelled", Duration.ofMinutes(1));
        StagedJobOutput staged = jobOutputPublisher.stage(
                leased, "compiled-document", new ByteArrayInputStream("Cancelled output.".getBytes(StandardCharsets.UTF_8)));

        requestCancellationAsMember(fixture);
        JobOutputPublication publication = jobOutputPublisher.publish(leased, staged);

        assertThat(publication.result()).isEqualTo(JobOutputPublicationResult.CANCELLATION_REQUESTED);
        assertThat(publication.artifactId()).isNull();
        assertThat(snapshot(fixture.jobId())).isEqualTo(new JobSnapshot(JobState.CANCELLED, 1, 1, null));
        assertThat(stagedOutputState(staged.stagedOutput().id())).isEqualTo("DISCARDED");
        assertThat(blobStore.sizeOf(staged.stagedOutput().metadata().objectKey())).isEmpty();
        assertThat(jobOutputArtifactCount(fixture.jobId())).isZero();
        assertThat(eventCount(fixture.jobId(), "COMPLETED")).isZero();
        assertThat(eventCount(fixture.jobId(), "CANCELLED")).isEqualTo(1);
        assertEveryJobEventHasMatchingOutboxEvent(fixture.jobId());
    }

    @Test
    void outputPublicationCannotRaceAPointerAdvanceAfterTargetValidation() throws Exception {
        Fixture fixture = queueReadyDocumentJob();
        LeasedJob leased = claim(fixture, "worker-document-lock", Duration.ofMinutes(1));
        StagedJobOutput staged = jobOutputPublisher.stage(
                leased, "compiled-document", new ByteArrayInputStream("Race-safe output.".getBytes(StandardCharsets.UTF_8)));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection editConnection = migrationConnection();
                PreparedStatement documentLock = editConnection.prepareStatement(
                        "SELECT id FROM document WHERE workspace_id = ? AND id = ? FOR UPDATE")) {
            editConnection.setAutoCommit(false);
            documentLock.setLong(1, fixture.workspaceId());
            documentLock.setLong(2, fixture.target().resourceId());
            try (ResultSet locked = documentLock.executeQuery()) {
                assertThat(locked.next()).isTrue();
            }

            Future<JobOutputPublication> publication = executor.submit(() -> jobOutputPublisher.publish(leased, staged));
            awaitWorkerRoutineWait("worker_publish_staged_output");
            advanceDocumentCurrentRevision(editConnection, fixture);
            editConnection.commit();

            JobOutputPublication result = publication.get(5, TimeUnit.SECONDS);
            assertThat(result.result()).isEqualTo(JobOutputPublicationResult.STALE_TARGET);
            assertThat(result.artifactId()).isNull();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(snapshot(fixture.jobId())).isEqualTo(new JobSnapshot(JobState.DEAD, 1, 1, null));
        assertThat(stagedOutputState(staged.stagedOutput().id())).isEqualTo("DISCARDED");
        assertThat(blobStore.sizeOf(staged.stagedOutput().metadata().objectKey())).isEmpty();
        assertThat(jobOutputArtifactCount(fixture.jobId())).isZero();
        assertThat(eventCount(fixture.jobId(), "COMPLETED")).isZero();
        assertEveryJobEventHasMatchingOutboxEvent(fixture.jobId());
    }

    @Test
    void reconciliationExpiresAndReclaimsAnUnattachedTemporaryObject() throws Exception {
        Fixture fixture = queueReadyDocumentJob();
        LeasedJob leased = claim(fixture, "worker-expired-temporary-output", Duration.ofMinutes(1));
        String objectKey = "temporary/expired-output-" + UUID.randomUUID();
        byte[] content = "Expired temporary output.".getBytes(StandardCharsets.UTF_8);
        var stored = blobStore.writeNewAndDigest(objectKey, new ByteArrayInputStream(content), 1024);
        StagedOutput staged = jobLeaseRepository.recordStagedOutput(
                leased.leaseToken(),
                new StagedOutputRequest(
                        "compiled-document",
                        objectKey,
                        stored.sha256Hex(),
                        stored.byteCount(),
                        now().plusSeconds(1)))
                .orElseThrow();
        try {
            awaitStagedOutputExpiry(staged.id());

            assertThat(jobOutputReconciler.reconcileOnce(32)).isGreaterThanOrEqualTo(1);
            assertThat(stagedOutputState(staged.id())).isEqualTo("DISCARDED");
            assertThat(stagedOutputCleanedAt(staged.id())).isNotNull();
            assertThat(blobStore.sizeOf(objectKey)).isEmpty();
            assertThat(snapshot(fixture.jobId())).isEqualTo(new JobSnapshot(JobState.LEASED, 1, 1, "worker-expired-temporary-output"));
        } finally {
            markTerminal(fixture.jobId());
        }
    }

    @Test
    void persistsProgressAndReleasesALiveLeaseOnlyThroughApprovedRoutines() throws SQLException {
        Fixture fixture = queueReadyJob();
        LeasedJob leased = claim(fixture, "worker-progress", Duration.ofMinutes(1));
        try {
            assertThat(jobLeaseRepository.heartbeat(leased.leaseToken(), Duration.ofMinutes(1))).isTrue();
            assertThat(jobLeaseRepository.reportProgress(
                    leased.leaseToken(), new JobProgress("Compiling source-backed fields.", 1, 2))).isTrue();
            assertThat(jobLeaseRepository.release(
                    leased.leaseToken(), new JobRelease(JobState.WAITING_FOR_INPUT, null, "Input is required.")))
                    .isEqualTo(JobReleaseResult.RELEASED);

            assertThat(snapshot(fixture.jobId())).isEqualTo(new JobSnapshot(JobState.WAITING_FOR_INPUT, 1, 1, null));
            assertThat(eventCount(fixture.jobId(), "PROGRESS")).isEqualTo(1);
            assertThat(eventCount(fixture.jobId(), "RELEASED")).isEqualTo(1);
            assertEveryJobEventHasMatchingOutboxEvent(fixture.jobId());
        } finally {
            markTerminal(fixture.jobId());
        }
    }

    @Test
    void cancellationDiscardsAnOutputThatWasStagedBeforeTheRequest() throws SQLException {
        Fixture fixture = queueReadyJob();
        LeasedJob leased = claim(fixture, "worker-cancel-after-stage", Duration.ofMinutes(1));
        StagedOutput staged = jobLeaseRepository
                .recordStagedOutput(leased.leaseToken(), output("temporary/cancel-after-stage", now().plusHours(1)))
                .orElseThrow();

        requestCancellationAsMember(fixture);

        assertThat(complete(leased)).isEqualTo(JobCompletionResult.CANCELLATION_REQUESTED);
        assertThat(stagedOutputState(staged.id())).isEqualTo("DISCARDED");
        assertThat(snapshot(fixture.jobId())).isEqualTo(new JobSnapshot(JobState.CANCELLED, 1, 1, null));
        assertEveryJobEventHasMatchingOutboxEvent(fixture.jobId());
    }

    @Test
    void releasesADeterministicFailureThroughTheLiveLeaseRoutine() throws SQLException {
        Fixture fixture = queueReadyJob();
        LeasedJob leased = claim(fixture, "worker-deterministic-failure", Duration.ofMinutes(1));
        try {
            assertThat(jobLeaseRepository.releaseAfterFailure(
                    leased.leaseToken(),
                    new JobFailure(JobFailureKind.DETERMINISTIC, "UNSUPPORTED_INPUT", "Unsupported source format.", null)))
                    .isEqualTo(JobReleaseResult.RELEASED);

            assertThat(snapshot(fixture.jobId())).isEqualTo(new JobSnapshot(JobState.DEAD, 1, 1, null));
            assertThat(eventCount(fixture.jobId(), "RELEASED")).isEqualTo(1);
            assertEveryJobEventHasMatchingOutboxEvent(fixture.jobId());
        } finally {
            markTerminal(fixture.jobId());
        }
    }

    @Test
    void expiredLeaseCannotHeartbeatReleasePublishOrRetrieveOutputBeforeReclaim() throws Exception {
        Fixture fixture = queueReadyJob();
        LeasedJob expired = claim(fixture, "worker-expired", Duration.ofSeconds(1));

        awaitLeaseExpiry(fixture.jobId());

        assertThat(jobLeaseRepository.heartbeat(expired.leaseToken(), Duration.ofMinutes(1))).isFalse();
        assertThat(jobLeaseRepository.release(
                expired.leaseToken(),
                new JobRelease(JobState.QUEUED, now().plusSeconds(1), "Retry after lease loss.")))
                .isEqualTo(JobReleaseResult.LOST_LEASE);
        assertThat(complete(expired)).isEqualTo(JobCompletionResult.LOST_LEASE);
        assertThat(jobLeaseRepository.recordStagedOutput(expired.leaseToken(), output("temporary/expired", now().plusHours(1))))
                .isEmpty();
        assertThat(snapshot(fixture.jobId())).isEqualTo(new JobSnapshot(JobState.LEASED, 1, 1, "worker-expired"));

        LeasedJob reclaimed = claim(fixture, "worker-reclaimed", Duration.ofMinutes(1));
        assertThat(complete(reclaimed)).isEqualTo(JobCompletionResult.COMPLETED);
        assertEveryJobEventHasMatchingOutboxEvent(fixture.jobId());
    }

    @Test
    void heartbeatThatWaitsPastLeaseExpiryCannotExtendTheLease() throws Exception {
        Fixture fixture = queueReadyJob();
        LeasedJob leased = claim(fixture, "worker-lock-wait", Duration.ofSeconds(1));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection lockConnection = migrationConnection();
                PreparedStatement lockStatement = lockConnection.prepareStatement(
                        "SELECT id FROM job WHERE id = ? FOR UPDATE")) {
            lockConnection.setAutoCommit(false);
            lockStatement.setLong(1, fixture.jobId());
            try (ResultSet locked = lockStatement.executeQuery()) {
                assertThat(locked.next()).isTrue();
            }

            Future<Boolean> heartbeat = executor.submit(
                    () -> jobLeaseRepository.heartbeat(leased.leaseToken(), Duration.ofMinutes(1)));
            awaitWorkerLeaseMutationWait();
            awaitLeaseExpiry(fixture.jobId());
            lockConnection.commit();

            assertThat(heartbeat.get(5, TimeUnit.SECONDS)).isFalse();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(snapshot(fixture.jobId())).isEqualTo(new JobSnapshot(JobState.LEASED, 1, 1, "worker-lock-wait"));
        LeasedJob reclaimed = claim(fixture, "worker-after-lock-wait", Duration.ofMinutes(1));
        assertThat(complete(reclaimed)).isEqualTo(JobCompletionResult.COMPLETED);
        assertEveryJobEventHasMatchingOutboxEvent(fixture.jobId());
    }

    @Test
    void claimCapsLeaseExpiryAtTheJobDeadline() throws Exception {
        Fixture fixture = queueReadyJob();
        setDeadlineAfter(fixture.jobId(), Duration.ofSeconds(2));

        LeasedJob leased = claim(fixture, "worker-deadline-cap", Duration.ofMinutes(1));

        assertThat(leased.job().lease().expiresAt()).isEqualTo(leased.job().deadlineAt());
        markTerminal(fixture.jobId());
    }

    @Test
    void databaseRejectsAnOutboxRowThatPairsDifferentJobAndEvent() throws SQLException {
        Fixture first = queueReadyJob();
        Fixture second = queueReadyJobInWorkspace(first, 2L);
        try {
            long firstEventId = insertQueuedEvent(first);

            assertThatThrownBy(() -> insertOutboxForDifferentJob(first.workspaceId(), second.jobId(), firstEventId))
                    .isInstanceOf(SQLException.class);
        } finally {
            markTerminal(first.jobId());
            markTerminal(second.jobId());
        }
    }

    @Test
    void databaseRejectsAnOutboxRowWhoseTypeDiffersFromItsEvent() throws SQLException {
        Fixture fixture = queueReadyJob();
        try {
            long eventId = insertQueuedEvent(fixture);

            assertThatThrownBy(() -> insertOutbox(fixture.workspaceId(), fixture.jobId(), eventId, "COMPLETED"))
                    .isInstanceOf(SQLException.class);
        } finally {
            markTerminal(fixture.jobId());
        }
    }

    @Test
    void workerCannotReadOrForgeQueueRowsOutsideTheApprovedRoutines() throws SQLException {
        Fixture fixture = queueReadyJob();
        try {
            assertThatThrownBy(() -> {
                        try (Connection connection = workerDataSource.getConnection();
                                PreparedStatement statement = connection.prepareStatement(
                                        "SELECT state FROM job WHERE id = ?")) {
                            statement.setLong(1, fixture.jobId());
                            statement.executeQuery();
                        }
                    })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> {
                        try (Connection connection = workerDataSource.getConnection();
                                PreparedStatement statement = connection.prepareStatement(
                                        "UPDATE job SET state = 'SUCCEEDED' WHERE id = ?")) {
                            statement.setLong(1, fixture.jobId());
                            statement.executeUpdate();
                        }
                    })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> {
                        try (Connection connection = workerDataSource.getConnection();
                                PreparedStatement statement = connection.prepareStatement(
                                        """
                                        INSERT INTO job_event (workspace_id, job_id, sequence, event_type, state)
                                        VALUES (?, ?, 99, 'COMPLETED', 'SUCCEEDED')
                                        """)) {
                            statement.setLong(1, fixture.workspaceId());
                            statement.setLong(2, fixture.jobId());
                            statement.executeUpdate();
                        }
                    })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> {
                        try (Connection connection = workerDataSource.getConnection();
                                PreparedStatement statement = connection.prepareStatement(
                                        """
                                        INSERT INTO job_staged_output
                                            (workspace_id, job_id, worker_id, fencing_token, output_kind, object_key, expires_at)
                                        VALUES (?, ?, 'forged-worker', 1, 'compiled-document', 'temporary/forged', clock_timestamp() + interval '1 hour')
                                        """)) {
                            statement.setLong(1, fixture.workspaceId());
                            statement.setLong(2, fixture.jobId());
                            statement.executeUpdate();
                        }
                    })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> {
                        try (Connection connection = workerDataSource.getConnection();
                                PreparedStatement statement = connection.prepareStatement(
                                        "UPDATE job_staged_output SET state = 'ATTACHED' WHERE job_id = ?")) {
                            statement.setLong(1, fixture.jobId());
                            statement.executeUpdate();
                        }
                    })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> {
                        try (Connection connection = workerDataSource.getConnection();
                                PreparedStatement statement = connection.prepareStatement(
                                        "SELECT artifact_id FROM job_output_artifact WHERE job_id = ?")) {
                            statement.setLong(1, fixture.jobId());
                            statement.executeQuery();
                        }
                    })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        } finally {
            markTerminal(fixture.jobId());
        }
    }

    @Test
    void competingWorkersHaveExactlyOneClaimWinner() throws Exception {
        Fixture fixture = queueReadyJob();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Optional<LeasedJob>> first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("The competing-claim start signal did not arrive.");
                }
                return jobLeaseRepository.claimNext(new WorkerId("worker-race-a"), Duration.ofMinutes(1));
            });
            Future<Optional<LeasedJob>> second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("The competing-claim start signal did not arrive.");
                }
                return jobLeaseRepository.claimNext(new WorkerId("worker-race-b"), Duration.ofMinutes(1));
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<LeasedJob> winners = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)).stream()
                    .flatMap(Optional::stream)
                    .toList();

            assertThat(winners).hasSize(1);
            assertThat(winners.get(0).job().id()).isEqualTo(fixture.jobId());
            assertThat(snapshot(fixture.jobId())).isEqualTo(new JobSnapshot(JobState.LEASED, 1, 1, winners.get(0).leaseToken().workerId()));
            assertThat(complete(winners.get(0))).isEqualTo(JobCompletionResult.COMPLETED);
            assertEveryJobEventHasMatchingOutboxEvent(fixture.jobId());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private LeasedJob claim(Fixture fixture, String workerId, Duration leaseDuration) {
        LeasedJob claimed = jobLeaseRepository
                .claimNext(new WorkerId(workerId), leaseDuration)
                .orElseThrow();
        assertThat(claimed.job().id()).isEqualTo(fixture.jobId());
        return claimed;
    }

    private JobCompletionResult complete(LeasedJob leasedJob) {
        return jobLeaseRepository.complete(
                leasedJob.leaseToken(),
                new JobCompletion(leasedJob.job().target(), JobState.SUCCEEDED, "Result publication requested."));
    }

    private StagedOutputRequest output(String objectKey, OffsetDateTime expiresAt) {
        return new StagedOutputRequest("compiled-document", objectKey, "b".repeat(64), 42L, expiresAt);
    }

    private Fixture queueReadyJob() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)) {
            connection.setAutoCommit(false);
            try {
                long actorId = insertUser(connection);
                long workspaceId = insertWorkspace(connection, actorId);
                insertMembership(connection, workspaceId, actorId);
                JobTarget target = new JobTarget("queue-test-resource", 1L, 1L);
                long jobId = insertQueuedJob(connection, workspaceId, actorId, target);
                connection.commit();
                return new Fixture(workspaceId, actorId, jobId, target);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private Fixture queueReadyJobInWorkspace(Fixture existing, long resourceId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)) {
            connection.setAutoCommit(false);
            try {
                JobTarget target = new JobTarget("queue-test-resource", resourceId, 1L);
                long jobId = insertQueuedJob(connection, existing.workspaceId(), existing.actorId(), target);
                connection.commit();
                return new Fixture(existing.workspaceId(), existing.actorId(), jobId, target);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private Fixture queueReadyDocumentJob() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)) {
            connection.setAutoCommit(false);
            try {
                long actorId = insertUser(connection);
                long workspaceId = insertWorkspace(connection, actorId);
                insertMembership(connection, workspaceId, actorId);
                long artifactId = insertReadyArtifact(connection, workspaceId);
                long extractionVersionId = insertExtractionVersion(connection, workspaceId, artifactId);
                long templateId = insertTemplate(connection, workspaceId);
                long templateVersionId = insertActivatedTemplateVersion(
                        connection, workspaceId, templateId, artifactId, extractionVersionId);
                long documentId = insertDocument(connection, workspaceId, templateId, templateVersionId);
                long initialRevisionId = insertDocumentRevision(
                        connection, workspaceId, documentId, 1, null, actorId, "d".repeat(64));
                setDocumentCurrentRevision(connection, workspaceId, documentId, initialRevisionId);
                JobTarget target = new JobTarget("document", documentId, initialRevisionId);
                long jobId = insertQueuedJob(connection, workspaceId, actorId, target);
                connection.commit();
                return new Fixture(workspaceId, actorId, jobId, target);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void advanceDocumentCurrentRevision(Fixture fixture) throws SQLException {
        try (Connection connection = migrationConnection()) {
            connection.setAutoCommit(false);
            try {
                advanceDocumentCurrentRevision(connection, fixture);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void advanceDocumentCurrentRevision(Connection connection, Fixture fixture) throws SQLException {
        long nextRevisionId = insertDocumentRevision(
                connection,
                fixture.workspaceId(),
                fixture.target().resourceId(),
                2,
                fixture.target().resourceVersion(),
                fixture.actorId(),
                "e".repeat(64));
        setDocumentCurrentRevision(connection, fixture.workspaceId(), fixture.target().resourceId(), nextRevisionId);
    }

    private long insertUser(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO user_identity (issuer, subject) VALUES (?, ?) RETURNING id")) {
            statement.setString(1, "https://worker-fault-test.invalid");
            statement.setString(2, UUID.randomUUID().toString());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private long insertWorkspace(Connection connection, long actorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO workspace (owner_user_id) VALUES (?) RETURNING id")) {
            statement.setLong(1, actorId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void insertMembership(Connection connection, long workspaceId, long actorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO workspace_member (workspace_id, user_id, role, state) VALUES (?, ?, 'OWNER', 'ACTIVE')")) {
            statement.setLong(1, workspaceId);
            statement.setLong(2, actorId);
            statement.executeUpdate();
        }
    }

    private long insertReadyArtifact(Connection connection, long workspaceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO artifact (workspace_id, blob_key, status, byte_count, sha256, detected_media_type, display_filename)
                VALUES (?, ?, 'READY', 1, ?, 'PLAIN_TEXT', 'fixture.txt')
                RETURNING id
                """)) {
            statement.setLong(1, workspaceId);
            statement.setString(2, "fixture/" + UUID.randomUUID());
            statement.setString(3, "f".repeat(64));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private long insertExtractionVersion(Connection connection, long workspaceId, long artifactId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO extraction_version (workspace_id, artifact_id, parser_version, status, feature_report, graph)
                VALUES (?, ?, 'worker-fixture', 'COMPLETE', '[]'::jsonb, '{}'::jsonb)
                RETURNING id
                """)) {
            statement.setLong(1, workspaceId);
            statement.setLong(2, artifactId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private long insertTemplate(Connection connection, long workspaceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO template (workspace_id, display_name) VALUES (?, 'Worker fixture') RETURNING id")) {
            statement.setLong(1, workspaceId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private long insertActivatedTemplateVersion(
            Connection connection, long workspaceId, long templateId, long artifactId, long extractionVersionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO template_version (
                    workspace_id, template_id, version_number, source_artifact_id, extraction_version_id,
                    status, field_definitions, activated_at)
                VALUES (?, ?, 1, ?, ?, 'ACTIVATED', '[]'::jsonb, clock_timestamp())
                RETURNING id
                """)) {
            statement.setLong(1, workspaceId);
            statement.setLong(2, templateId);
            statement.setLong(3, artifactId);
            statement.setLong(4, extractionVersionId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private long insertDocument(Connection connection, long workspaceId, long templateId, long templateVersionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO document (workspace_id, title, template_id, template_version_id)
                VALUES (?, 'Worker fixture document', ?, ?)
                RETURNING id
                """)) {
            statement.setLong(1, workspaceId);
            statement.setLong(2, templateId);
            statement.setLong(3, templateVersionId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private long insertDocumentRevision(
            Connection connection,
            long workspaceId,
            long documentId,
            int revisionNumber,
            Long parentRevisionId,
            long actorId,
            String contentHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO document_revision (
                    workspace_id, document_id, revision_number, parent_revision_id, content,
                    content_hash, actor_user_id, edit_reason)
                VALUES (?, ?, ?, ?, '{}'::jsonb, ?, ?, 'Worker fixture revision')
                RETURNING id
                """)) {
            statement.setLong(1, workspaceId);
            statement.setLong(2, documentId);
            statement.setInt(3, revisionNumber);
            if (parentRevisionId == null) {
                statement.setNull(4, java.sql.Types.BIGINT);
            } else {
                statement.setLong(4, parentRevisionId);
            }
            statement.setString(5, contentHash);
            statement.setLong(6, actorId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void setDocumentCurrentRevision(Connection connection, long workspaceId, long documentId, long revisionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE document SET current_revision_id = ? WHERE workspace_id = ? AND id = ?")) {
            statement.setLong(1, revisionId);
            statement.setLong(2, workspaceId);
            statement.setLong(3, documentId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private long insertQueuedJob(Connection connection, long workspaceId, long actorId, JobTarget target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO job (
                    workspace_id, requested_by_user_id, job_type, resource_type, resource_id, resource_version,
                    stage, processing_configuration_hash, state, available_at
                )
                VALUES (?, ?, 'document.compile', ?, ?, ?, 'compile', ?, 'QUEUED', ?)
                RETURNING id
                """)) {
            statement.setLong(1, workspaceId);
            statement.setLong(2, actorId);
            statement.setString(3, target.resourceType());
            statement.setLong(4, target.resourceId());
            statement.setLong(5, target.resourceVersion());
            statement.setString(6, CONFIGURATION_HASH);
            statement.setObject(7, now().minusMinutes(1));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private long insertQueuedEvent(Fixture fixture) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO job_event (workspace_id, job_id, sequence, event_type, state)
                        VALUES (?, ?, 1, 'QUEUED', 'QUEUED')
                        RETURNING id
                        """)) {
            statement.setLong(1, fixture.workspaceId());
            statement.setLong(2, fixture.jobId());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void insertOutboxForDifferentJob(long workspaceId, long jobId, long eventId) throws SQLException {
        insertOutbox(workspaceId, jobId, eventId, "QUEUED");
    }

    private void insertOutbox(long workspaceId, long jobId, long eventId, String eventType) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO outbox_event (delivery_key, workspace_id, job_id, job_event_id, event_type)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setLong(2, workspaceId);
            statement.setLong(3, jobId);
            statement.setLong(4, eventId);
            statement.setString(5, eventType);
            statement.executeUpdate();
        }
    }

    private void setDeadlineAfter(long jobId, Duration duration) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE job SET deadline_at = clock_timestamp() + (? * interval '1 millisecond') WHERE id = ?")) {
            statement.setLong(1, duration.toMillis());
            statement.setLong(2, jobId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private void markTerminal(long jobId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE job SET state = 'CANCELLED', lease_owner = NULL, lease_expires_at = NULL WHERE id = ?")) {
            statement.setLong(1, jobId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private void requestCancellationAsMember(Fixture fixture) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_api", API_PASSWORD)) {
            connection.setAutoCommit(false);
            try {
                setCurrentUser(connection, fixture.actorId());
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        UPDATE job
                        SET state = 'CANCEL_REQUESTED', cancellation_requested_at = now(), updated_at = now()
                        WHERE workspace_id = ? AND id = ? AND state = 'LEASED'
                        """)) {
                    statement.setLong(1, fixture.workspaceId());
                    statement.setLong(2, fixture.jobId());
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private JobSnapshot snapshot(long jobId) throws SQLException {
        try (Connection connection = migrationConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT state, attempt_count, fencing_token, lease_owner FROM job WHERE id = ?")) {
            statement.setLong(1, jobId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return new JobSnapshot(
                        JobState.valueOf(result.getString("state")),
                        result.getInt("attempt_count"),
                        result.getLong("fencing_token"),
                        result.getString("lease_owner"));
            }
        }
    }

    private void awaitLeaseExpiry(long jobId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!leaseExpired(jobId)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("The test lease did not expire within five seconds.");
            }
            Thread.sleep(10);
        }
    }

    private void awaitStagedOutputExpiry(long stagedOutputId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!stagedOutputExpired(stagedOutputId)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("The staged output did not expire within five seconds.");
            }
            Thread.sleep(10);
        }
    }

    private void awaitWorkerLeaseMutationWait() throws Exception {
        awaitWorkerRoutineWait("worker_heartbeat");
    }

    private void awaitWorkerRoutineWait(String routineName) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!workerRoutineWaitsOnLock(routineName)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("The worker routine did not wait on the locked row within five seconds.");
            }
            Thread.sleep(10);
        }
    }

    private boolean workerRoutineWaitsOnLock(String routineName) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement statement = connection.prepareStatement(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_stat_activity
                            WHERE usename = 'brownie_worker'
                              AND wait_event_type = 'Lock'
                              AND query LIKE ?
                        )
                        """)) {
            statement.setString(1, "%" + routineName + "%");
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    private boolean leaseExpired(long jobId) throws SQLException {
        try (Connection connection = migrationConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT lease_expires_at <= now() FROM job WHERE id = ?")) {
            statement.setLong(1, jobId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    private boolean stagedOutputExpired(long stagedOutputId) throws SQLException {
        try (Connection connection = migrationConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT expires_at <= now() FROM job_staged_output WHERE id = ?")) {
            statement.setLong(1, stagedOutputId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    private long stagedOutputCount(long jobId) throws SQLException {
        return countAsMigration("SELECT count(*) FROM job_staged_output WHERE job_id = ?", jobId);
    }

    private long jobOutputArtifactCount(long jobId) throws SQLException {
        return countAsMigration("SELECT count(*) FROM job_output_artifact WHERE job_id = ?", jobId);
    }

    private OutputArtifactSnapshot outputArtifact(long jobId, String outputKind) throws SQLException {
        try (Connection connection = migrationConnection();
                PreparedStatement statement = connection.prepareStatement(
                        """
                        SELECT artifact.id, artifact.status, artifact.blob_key, artifact.byte_count,
                               artifact.sha256, artifact.detected_media_type
                        FROM job_output_artifact output
                        JOIN artifact ON artifact.id = output.artifact_id
                        WHERE output.job_id = ? AND output.output_kind = ?
                        """)) {
            statement.setLong(1, jobId);
            statement.setString(2, outputKind);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                OutputArtifactSnapshot snapshot = new OutputArtifactSnapshot(
                        result.getLong("id"),
                        result.getString("status"),
                        result.getString("blob_key"),
                        result.getLong("byte_count"),
                        result.getString("sha256"),
                        result.getString("detected_media_type"));
                assertThat(result.next()).isFalse();
                return snapshot;
            }
        }
    }

    private String stagedOutputState(long stagedOutputId) throws SQLException {
        try (Connection connection = migrationConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT state FROM job_staged_output WHERE id = ?")) {
            statement.setLong(1, stagedOutputId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private OffsetDateTime stagedOutputCleanedAt(long stagedOutputId) throws SQLException {
        try (Connection connection = migrationConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT cleaned_at FROM job_staged_output WHERE id = ?")) {
            statement.setLong(1, stagedOutputId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getObject(1, OffsetDateTime.class);
            }
        }
    }

    private long eventCount(long jobId, String eventType) throws SQLException {
        try (Connection connection = migrationConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM job_event WHERE job_id = ? AND event_type = ?")) {
            statement.setLong(1, jobId);
            statement.setString(2, eventType);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void assertEveryJobEventHasMatchingOutboxEvent(long jobId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(
                        """
                        SELECT count(*)
                        FROM job_event event
                        LEFT JOIN outbox_event outbox
                          ON outbox.workspace_id = event.workspace_id
                         AND outbox.job_id = event.job_id
                         AND outbox.job_event_id = event.id
                         AND outbox.event_type = event.event_type
                        WHERE event.job_id = ? AND outbox.delivery_key IS NULL
                        """)) {
            statement.setLong(1, jobId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                assertThat(result.getLong(1)).isZero();
            }
        }
    }

    private long countAsMigration(String sql, long jobId) throws SQLException {
        try (Connection connection = migrationConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, jobId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private Connection migrationConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
    }

    private void setCurrentUser(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_user_id', ?, true)")) {
            statement.setString(1, String.valueOf(userId));
            statement.executeQuery();
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private record Fixture(long workspaceId, long actorId, long jobId, JobTarget target) {
    }

    private record JobSnapshot(JobState state, int attemptCount, long fencingToken, String leaseOwner) {
    }

    private record OutputArtifactSnapshot(
            long id,
            String status,
            String objectKey,
            long byteCount,
            String sha256,
            String mediaType) {
    }
}
