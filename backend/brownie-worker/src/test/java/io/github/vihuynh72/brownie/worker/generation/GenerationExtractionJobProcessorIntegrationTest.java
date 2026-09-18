package io.github.vihuynh72.brownie.worker.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.generation.GenerationJobTypes;
import io.github.vihuynh72.brownie.core.job.JobLeaseRepository;
import io.github.vihuynh72.brownie.core.job.LeasedJob;
import io.github.vihuynh72.brownie.core.job.WorkerId;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the trusted worker's own half of the async generation path end to
 * end: given a real QUEUED job of the real type and a real bundle staged
 * in blob storage (exactly what {@code GenerationStartIntegrationTest}
 * proves the API side actually produces), the real scheduled-poller
 * machinery claims it, the real processor calls the model (through a fake
 * gateway here -- {@code OpenAiModelGatewayTest} already proves the real
 * one's own wire-format handling; this test's own job is proving the
 * plumbing around it), and a real, independently readable artifact ends
 * up published and the job SUCCEEDED, all through the same {@code
 * JobLeaseRepository}/{@code JobOutputPublisher} machinery {@code
 * JdbcJobLeaseFaultInjectionIntegrationTest} already proves generically.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(GenerationExtractionJobProcessorIntegrationTest.FakeModelGatewayConfig.class)
class GenerationExtractionJobProcessorIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String WORKER_PASSWORD = "brownie_worker_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";

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
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_worker");
        registry.add("spring.datasource.password", () -> WORKER_PASSWORD);
        registry.add("brownie.storage.local-connection", AZURITE::getConnectionString);
        // The scheduled poller must not race this test's own direct calls.
        registry.add("brownie.worker.generation.enabled", () -> "false");
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath().getParent().getParent().resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    /**
     * brownie-worker owns no migrations of its own -- every table it
     * touches is defined in brownie-api's own migration folder, so this
     * test (like {@code JdbcJobLeaseFaultInjectionIntegrationTest} before
     * it) runs Flyway against that folder directly rather than depending
     * on brownie-api, which brownie-worker must never do.
     */
    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)
                .locations("filesystem:" + apiMigrationPath())
                .load()
                .migrate();
    }

    private static Path apiMigrationPath() {
        return Path.of("").toAbsolutePath().getParent().resolve("brownie-api/src/main/resources/db/migration");
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** How many times the fake model has been called across this class -- the cancellation test asserts it never moves. */
    static final AtomicInteger MODEL_CALLS = new AtomicInteger();

    @TestConfiguration
    static class FakeModelGatewayConfig {
        @Bean
        @Primary
        ModelGateway fakeModelGateway() {
            return request -> {
                MODEL_CALLS.incrementAndGet();
                String json = """
                        {"scalarFields":{"meeting.title":{"value":"Weekly Robotics Club Sync","evidenceSpanIds":[1],"unresolved":false,"ambiguityReason":null}},"repeatedItems":[]}""";
                return new ModelCompletion.Success(json, new ModelUsage(50, 20));
            };
        }
    }

    @Autowired
    private JobLeaseRepository jobLeaseRepository;

    @Autowired
    private GenerationExtractionJobProcessor processor;

    @Autowired
    private BlobStore blobStore;

    @Test
    void aRealQueuedExtractionJobIsClaimedProcessedAndPublishesARealArtifact() throws Exception {
        long userId;
        long workspaceId;
        long documentId;
        long revisionId;
        long jobId;
        String bundleHash;

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)) {
            userId = insertUser(connection);
            workspaceId = insertWorkspace(connection, userId);
            insertMembership(connection, workspaceId, userId);

            // A document-targeted job is revalidated at completion time
            // against a real document row (worker_complete/worker_publish_
            // staged_output both refuse a target whose document no longer
            // selects that exact revision -- see V17__revalidate_document_
            // job_targets.sql) -- a bare, unattached resource_id is not
            // enough for this to reach SUCCEEDED, so this fixture builds
            // the same minimal real chain JdbcJobLeaseFaultInjectionIntegrationTest
            // already does for its own document-targeted job fixtures.
            long artifactId = insertReadyArtifact(connection, workspaceId);
            long extractionVersionId = insertExtractionVersion(connection, workspaceId, artifactId);
            long templateId = insertTemplate(connection, workspaceId);
            long templateVersionId = insertActivatedTemplateVersion(connection, workspaceId, templateId, artifactId, extractionVersionId);
            documentId = insertDocument(connection, workspaceId, templateId, templateVersionId);
            revisionId = insertDocumentRevision(connection, workspaceId, documentId, 1, null, userId, "a".repeat(64));
            setDocumentCurrentRevision(connection, workspaceId, documentId, revisionId);

            String bundleJson = """
                    {"fields":[{"fieldId":"meeting.title","type":"TEXT","cardinality":"SCALAR","requiredness":"REQUIRED"}],\
                    "excerpts":[{"spanId":1,"text":"The meeting was called to order."}]}""";
            bundleHash = sha256Hex(bundleJson);
            blobStore.writeNewAndDigest(
                    GenerationJobTypes.inputBundleObjectKey(workspaceId, bundleHash),
                    new ByteArrayInputStream(bundleJson.getBytes(StandardCharsets.UTF_8)),
                    1_000_000);

            jobId = insertQueuedJob(connection, workspaceId, userId, documentId, revisionId, bundleHash);
        }

        WorkerId workerId = new WorkerId("test-worker-" + UUID.randomUUID());
        LeasedJob leasedJob = jobLeaseRepository.claimNext(workerId, Duration.ofMinutes(2)).orElseThrow(
                () -> new AssertionError("Expected a real eligible job to be claimable."));
        assertThat(leasedJob.job().id()).isEqualTo(jobId);
        assertThat(leasedJob.job().type().value()).isEqualTo("generation.extract-facts");

        processor.process(leasedJob);

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)) {
            assertThat(jobState(connection, jobId)).isEqualTo("SUCCEEDED");

            long artifactId = publishedArtifactId(connection, jobId, "extraction-result");
            String blobKey = artifactBlobKey(connection, artifactId);
            JsonNode result;
            try (InputStream content = blobStore.openStream(blobKey)) {
                result = OBJECT_MAPPER.readTree(content);
            }
            assertThat(result.get("scalarCandidates").get("meeting.title").get("value").asText())
                    .isEqualTo("Weekly Robotics Club Sync");
        }

        // A second claim attempt finds nothing left to do -- the job reached a terminal state.
        assertThat(jobLeaseRepository.claimNext(workerId, Duration.ofMinutes(2))).isEmpty();
    }

    /**
     * The fake gateway above always proposes "Weekly Robotics Club Sync"
     * for {@code meeting.title}, regardless of what the bundle asks --
     * here the bundle also carries the document's own current value for
     * that field, so the very first attempt must find a real conflict,
     * wait for a person, then -- once resumed with a real, freshly typed
     * answer the model itself never proposed -- finish with exactly that
     * answer rather than re-running the same conflicting proposal.
     */
    @Test
    void aConflictingFieldWaitsForInputThenFinishesWithThePersonSOwnAnswerAfterResume() throws Exception {
        long userId;
        long workspaceId;
        long documentId;
        long revisionId;
        long jobId;
        String bundleHash;

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)) {
            userId = insertUser(connection);
            workspaceId = insertWorkspace(connection, userId);
            insertMembership(connection, workspaceId, userId);

            long artifactId = insertReadyArtifact(connection, workspaceId);
            long extractionVersionId = insertExtractionVersion(connection, workspaceId, artifactId);
            long templateId = insertTemplate(connection, workspaceId);
            long templateVersionId = insertActivatedTemplateVersion(connection, workspaceId, templateId, artifactId, extractionVersionId);
            documentId = insertDocument(connection, workspaceId, templateId, templateVersionId);
            revisionId = insertDocumentRevision(connection, workspaceId, documentId, 1, null, userId, "a".repeat(64));
            setDocumentCurrentRevision(connection, workspaceId, documentId, revisionId);

            String bundleJson = """
                    {"fields":[{"fieldId":"meeting.title","type":"TEXT","cardinality":"SCALAR","requiredness":"REQUIRED",\
                    "existingValueText":"Old Title"}],\
                    "excerpts":[{"spanId":1,"text":"The meeting was called to order."}]}""";
            bundleHash = sha256Hex(bundleJson);
            blobStore.writeNewAndDigest(
                    GenerationJobTypes.inputBundleObjectKey(workspaceId, bundleHash),
                    new ByteArrayInputStream(bundleJson.getBytes(StandardCharsets.UTF_8)),
                    1_000_000);

            jobId = insertQueuedJob(connection, workspaceId, userId, documentId, revisionId, bundleHash);
        }

        WorkerId workerId = new WorkerId("test-worker-" + UUID.randomUUID());
        LeasedJob firstLease = jobLeaseRepository.claimNext(workerId, Duration.ofMinutes(2)).orElseThrow(
                () -> new AssertionError("Expected a real eligible job to be claimable."));

        processor.process(firstLease);

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)) {
            assertThat(jobState(connection, jobId)).isEqualTo("WAITING_FOR_INPUT");
        }
        // WAITING_FOR_INPUT is not itself claimable -- a person (via the API's own resume route) must act first.
        assertThat(jobLeaseRepository.claimNext(workerId, Duration.ofMinutes(2))).isEmpty();

        JsonNode pending;
        try (InputStream content = blobStore.openStream(GenerationJobTypes.pendingQuestionsObjectKey(workspaceId, jobId))) {
            pending = OBJECT_MAPPER.readTree(content);
        }
        // The attempt that staged these -- the first claim's fencing token --
        // travels with them, so the API can tell a current bundle from one a
        // superseded attempt overwrote later.
        assertThat(pending.get("fencingToken").asLong()).isEqualTo(firstLease.leaseToken().fencingToken());
        assertThat(pending.get("questions")).hasSize(1);
        JsonNode question = pending.get("questions").get(0);
        assertThat(question.get("fieldId").asText()).isEqualTo("meeting.title");
        assertThat(question.get("reason").asText()).isEqualTo("CONFLICT");
        assertThat(question.get("candidates").get(0).get("value").asText()).isEqualTo("Old Title");
        assertThat(question.get("candidates").get(1).get("value").asText()).isEqualTo("Weekly Robotics Club Sync");

        // The API's own real resume route (GenerationOrchestrationService#resumeAfterQuestions)
        // stages exactly this shape before requesting resume; this test drives
        // the worker's own reconciliation directly, the same division of
        // labor the rest of this file already keeps between the two sides.
        String resolvedAnswersJson = """
                {"answers":[{"fieldId":"meeting.title","answerValue":"Executive Committee Sync","evidenceSpanIds":[]}]}""";
        blobStore.writeAndDigest(
                GenerationJobTypes.resolvedAnswersObjectKey(workspaceId, jobId),
                new ByteArrayInputStream(resolvedAnswersJson.getBytes(StandardCharsets.UTF_8)),
                1_000_000);
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)) {
            requeueWaitingJob(connection, jobId);
        }

        LeasedJob secondLease = jobLeaseRepository.claimNext(workerId, Duration.ofMinutes(2)).orElseThrow(
                () -> new AssertionError("Expected the resumed job to be claimable again."));
        assertThat(secondLease.job().id()).isEqualTo(jobId);
        assertThat(secondLease.job().attemptCount()).isEqualTo(2);

        processor.process(secondLease);

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)) {
            assertThat(jobState(connection, jobId)).isEqualTo("SUCCEEDED");

            long artifactId = publishedArtifactId(connection, jobId, "extraction-result");
            String blobKey = artifactBlobKey(connection, artifactId);
            JsonNode result;
            try (InputStream content = blobStore.openStream(blobKey)) {
                result = OBJECT_MAPPER.readTree(content);
            }
            JsonNode titleCandidate = result.get("scalarCandidates").get("meeting.title");
            // The person's own typed answer, not the fake gateway's fixed
            // "Weekly Robotics Club Sync" reply -- proving reconciliation
            // actually overrode the second attempt's own fresh candidate
            // rather than merely not having asked about it again.
            assertThat(titleCandidate.get("value").asText()).isEqualTo("Executive Committee Sync");
            assertThat(titleCandidate.get("evidenceSpanIds")).isEmpty();
        }

        assertThat(jobLeaseRepository.claimNext(workerId, Duration.ofMinutes(2))).isEmpty();
    }

    /**
     * A job cancelled through the API while a worker holds its lease: the
     * attempt's very first heartbeat is refused, so no model call is ever
     * placed for it, nothing is published, and the job ends CANCELLED --
     * which is what the workspace's own Cancel button relies on.
     */
    @Test
    void aJobCancelledWhileLeasedStopsBeforeItsModelCallAndEndsCancelled() throws Exception {
        long userId;
        long workspaceId;
        long documentId;
        long revisionId;
        long jobId;

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)) {
            userId = insertUser(connection);
            workspaceId = insertWorkspace(connection, userId);
            insertMembership(connection, workspaceId, userId);
            long artifactId = insertReadyArtifact(connection, workspaceId);
            long extractionVersionId = insertExtractionVersion(connection, workspaceId, artifactId);
            long templateId = insertTemplate(connection, workspaceId);
            long templateVersionId = insertActivatedTemplateVersion(connection, workspaceId, templateId, artifactId, extractionVersionId);
            documentId = insertDocument(connection, workspaceId, templateId, templateVersionId);
            revisionId = insertDocumentRevision(connection, workspaceId, documentId, 1, null, userId, "a".repeat(64));
            setDocumentCurrentRevision(connection, workspaceId, documentId, revisionId);

            String bundleJson = """
                    {"fields":[{"fieldId":"meeting.title","type":"TEXT","cardinality":"SCALAR","requiredness":"REQUIRED"}],\
                    "excerpts":[{"spanId":1,"text":"The meeting was called to order."}]}""";
            String bundleHash = sha256Hex(bundleJson);
            blobStore.writeNewAndDigest(
                    GenerationJobTypes.inputBundleObjectKey(workspaceId, bundleHash),
                    new ByteArrayInputStream(bundleJson.getBytes(StandardCharsets.UTF_8)),
                    1_000_000);
            jobId = insertQueuedJob(connection, workspaceId, userId, documentId, revisionId, bundleHash);
        }

        WorkerId workerId = new WorkerId("test-worker-" + UUID.randomUUID());
        LeasedJob leasedJob = jobLeaseRepository.claimNext(workerId, Duration.ofMinutes(2)).orElseThrow(
                () -> new AssertionError("Expected a real eligible job to be claimable."));

        // Exactly the row change the API's own cancel route makes for a
        // LEASED job (JdbcJobRepository#requestCancellation), applied as
        // the schema owner since this module never depends on brownie-api.
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)) {
            requestCancellation(connection, jobId);
        }

        int modelCallsBefore = MODEL_CALLS.get();
        processor.process(leasedJob);

        assertThat(MODEL_CALLS.get()).isEqualTo(modelCallsBefore);
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)) {
            assertThat(jobState(connection, jobId)).isEqualTo("CANCELLED");
            assertThat(publishedOutputCount(connection, jobId)).isZero();
        }
        assertThat(jobLeaseRepository.claimNext(workerId, Duration.ofMinutes(2))).isEmpty();
    }

    private void requestCancellation(Connection connection, long jobId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE job SET state = 'CANCEL_REQUESTED', cancellation_requested_at = now(), updated_at = now()"
                        + " WHERE id = ? AND state = 'LEASED'")) {
            statement.setLong(1, jobId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private int publishedOutputCount(Connection connection, long jobId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM job_output_artifact WHERE job_id = ?")) {
            statement.setLong(1, jobId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private void requeueWaitingJob(Connection connection, long jobId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE job SET state = 'QUEUED', available_at = ? WHERE id = ? AND state = 'WAITING_FOR_INPUT'")) {
            statement.setObject(1, OffsetDateTime.now().minusSeconds(1));
            statement.setLong(2, jobId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static String sha256Hex(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private long insertUser(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO user_identity (issuer, subject) VALUES (?, ?) RETURNING id")) {
            statement.setString(1, "https://generation-processor-test.invalid");
            statement.setString(2, UUID.randomUUID().toString());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private long insertWorkspace(Connection connection, long actorId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO workspace (owner_user_id) VALUES (?) RETURNING id")) {
            statement.setLong(1, actorId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void insertMembership(Connection connection, long workspaceId, long actorId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO workspace_member (workspace_id, user_id, role, state) VALUES (?, ?, 'OWNER', 'ACTIVE')")) {
            statement.setLong(1, workspaceId);
            statement.setLong(2, actorId);
            statement.executeUpdate();
        }
    }

    private long insertQueuedJob(
            Connection connection, long workspaceId, long actorId, long documentId, long revisionId, String processingConfigurationHash)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO job (
                    workspace_id, requested_by_user_id, job_type, resource_type, resource_id, resource_version,
                    stage, processing_configuration_hash, state, available_at
                )
                VALUES (?, ?, 'generation.extract-facts', 'document', ?, ?, 'extracting', ?, 'QUEUED', ?)
                RETURNING id
                """)) {
            statement.setLong(1, workspaceId);
            statement.setLong(2, actorId);
            statement.setLong(3, documentId);
            statement.setLong(4, revisionId);
            statement.setString(5, processingConfigurationHash);
            statement.setObject(6, OffsetDateTime.now().minusSeconds(1));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private long insertReadyArtifact(Connection connection, long workspaceId) throws Exception {
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

    private long insertExtractionVersion(Connection connection, long workspaceId, long artifactId) throws Exception {
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

    private long insertTemplate(Connection connection, long workspaceId) throws Exception {
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
            Connection connection, long workspaceId, long templateId, long artifactId, long extractionVersionId) throws Exception {
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

    private long insertDocument(Connection connection, long workspaceId, long templateId, long templateVersionId) throws Exception {
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
            Connection connection, long workspaceId, long documentId, int revisionNumber, Long parentRevisionId, long actorId,
            String contentHash) throws Exception {
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

    private void setDocumentCurrentRevision(Connection connection, long workspaceId, long documentId, long revisionId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE document SET current_revision_id = ? WHERE workspace_id = ? AND id = ?")) {
            statement.setLong(1, revisionId);
            statement.setLong(2, workspaceId);
            statement.setLong(3, documentId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private String jobState(Connection connection, long jobId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT state FROM job WHERE id = ?")) {
            statement.setLong(1, jobId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private long publishedArtifactId(Connection connection, long jobId, String outputKind) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT artifact_id FROM job_output_artifact WHERE job_id = ? AND output_kind = ?")) {
            statement.setLong(1, jobId);
            statement.setString(2, outputKind);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AssertionError("No published output of kind " + outputKind + " for job " + jobId + ".");
                }
                return result.getLong(1);
            }
        }
    }

    private String artifactBlobKey(Connection connection, long artifactId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT blob_key FROM artifact WHERE id = ?")) {
            statement.setLong(1, artifactId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }
}
