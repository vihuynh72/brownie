package io.github.vihuynh72.brownie.worker.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the second real step {@code GenerationExtractionJobProcessor}
 * takes once every question is resolved: re-synthesizing a template's own
 * composable field (here {@code meeting.decisions}) with {@code
 * CompositionService} rather than trusting extraction's own more literal
 * first pass at it, then publishing the composed result -- a separate
 * test file (own Postgres/Azurite/Flyway fixtures, mirroring {@code
 * GenerationExtractionJobProcessorIntegrationTest}'s own established
 * shape) specifically so its own fake model gateway can distinguish an
 * extraction call from a composition call by request content without
 * interfering with that other file's own single-field fixtures.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(GenerationCompositionIntegrationTest.FakeModelGatewayConfig.class)
class GenerationCompositionIntegrationTest {

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
        registry.add("brownie.worker.generation.enabled", () -> "false");
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath().getParent().getParent().resolve("infra/local/postgres/init/01-app-roles.sql");
    }

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

    /** Distinguishes an extraction call from a composition call by the request's own {@code promptVersion} rather than call order, so it never needs resetting between tests. */
    @TestConfiguration
    static class FakeModelGatewayConfig {
        @Bean
        @Primary
        ModelGateway fakeModelGateway() {
            return request -> {
                if (request.promptVersion().startsWith("composition")) {
                    String composedJson = """
                            {"composedFields":{"meeting.decisions":{"value":"Composed: the club approved the budget proposal.","evidenceSpanIds":[1],"unresolved":false,"ambiguityReason":null}}}""";
                    return new ModelCompletion.Success(composedJson, new ModelUsage(30, 15));
                }
                String extractionJson = """
                        {"scalarFields":{\
                        "meeting.title":{"value":"Weekly Robotics Club Sync","evidenceSpanIds":[1],"unresolved":false,"ambiguityReason":null},\
                        "meeting.decisions":{"value":"raw: the club voted on the budget","evidenceSpanIds":[1],"unresolved":false,"ambiguityReason":null}},\
                        "repeatedItems":[]}""";
                return new ModelCompletion.Success(extractionJson, new ModelUsage(50, 20));
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
    void aTemplateSComposableFieldIsReSynthesizedRatherThanExtractedVerbatim() throws Exception {
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
                    {"fields":[\
                    {"fieldId":"meeting.title","type":"TEXT","cardinality":"SCALAR","requiredness":"REQUIRED"},\
                    {"fieldId":"meeting.decisions","type":"TEXT","cardinality":"SCALAR","requiredness":"OPTIONAL"}],\
                    "excerpts":[{"spanId":1,"text":"The meeting was called to order and the budget was discussed."}],\
                    "composableFieldIds":["meeting.decisions"]}""";
            bundleHash = sha256Hex(bundleJson);
            blobStore.writeNewAndDigest(
                    "generation-input/" + bundleHash + ".json",
                    new ByteArrayInputStream(bundleJson.getBytes(StandardCharsets.UTF_8)),
                    1_000_000);

            jobId = insertQueuedJob(connection, workspaceId, userId, documentId, revisionId, bundleHash);
        }

        WorkerId workerId = new WorkerId("test-worker-" + UUID.randomUUID());
        LeasedJob leasedJob = jobLeaseRepository.claimNext(workerId, Duration.ofMinutes(2)).orElseThrow(
                () -> new AssertionError("Expected a real eligible job to be claimable."));

        processor.process(leasedJob);

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)) {
            assertThat(jobState(connection, jobId)).isEqualTo("SUCCEEDED");

            long artifactId = publishedArtifactId(connection, jobId, "extraction-result");
            String blobKey = artifactBlobKey(connection, artifactId);
            JsonNode result;
            try (InputStream content = blobStore.openStream(blobKey)) {
                result = OBJECT_MAPPER.readTree(content);
            }
            // The composed value, not extraction's own raw first pass at the same field.
            assertThat(result.get("scalarCandidates").get("meeting.decisions").get("value").asText())
                    .isEqualTo("Composed: the club approved the budget proposal.");
            // An untouched field passes through exactly as extraction produced it.
            assertThat(result.get("scalarCandidates").get("meeting.title").get("value").asText())
                    .isEqualTo("Weekly Robotics Club Sync");
        }

        assertThat(jobLeaseRepository.claimNext(workerId, Duration.ofMinutes(2))).isEmpty();
    }

    private static String sha256Hex(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private long insertUser(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO user_identity (issuer, subject) VALUES (?, ?) RETURNING id")) {
            statement.setString(1, "https://generation-composition-test.invalid");
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
