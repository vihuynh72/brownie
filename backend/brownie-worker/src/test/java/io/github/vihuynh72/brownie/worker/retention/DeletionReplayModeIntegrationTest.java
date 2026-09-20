package io.github.vihuynh72.brownie.worker.retention;

import com.azure.storage.blob.BlobServiceClientBuilder;
import io.github.vihuynh72.brownie.core.retention.ArchivedDeletion;
import io.github.vihuynh72.brownie.core.retention.DeletionScope;
import io.github.vihuynh72.brownie.storage.azure.AzureDeletionLedgerArchive;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worker started the way a restore starts it: told to apply the
 * deletions recorded outside the database and then end. It must do that,
 * it must not do anything it does when it serves (above all it must not
 * claim a job from a database that still holds work for documents that are
 * about to be removed), and it must report success only if it finished.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DeletionReplayModeIntegrationTest {

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
        registry.add("brownie.worker.mode", () -> "replay-deletions");
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath().getParent().getParent().resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    /** The worker owns no migrations; its tests apply the API's folder directly, as the other worker tests do. */
    private static long workspaceId;
    private static long documentId;
    private static long untouchedDocumentId;

    /**
     * Everything is in place before the worker starts, as it is in a real
     * restore: a database that still holds a document, and a record outside
     * it saying that document was deleted for good.
     */
    @BeforeAll
    static void aRestoredDatabaseAndARecordOfADeletionItHasForgotten() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)
                .locations("filesystem:" + Path.of("").toAbsolutePath().getParent().resolve("brownie-api/src/main/resources/db/migration"))
                .load()
                .migrate();
        try (Connection connection = ownerConnection()) {
            long userId = insertReturningId(connection,
                    "INSERT INTO user_identity (issuer, subject) VALUES ('https://replay-mode-test.invalid', '" + UUID.randomUUID() + "') RETURNING id");
            workspaceId = insertReturningId(connection, "INSERT INTO workspace (owner_user_id) VALUES (" + userId + ") RETURNING id");
            execute(connection, "INSERT INTO workspace_member (workspace_id, user_id, role, state) VALUES (" + workspaceId + ", " + userId + ", 'OWNER', 'ACTIVE')");
            long artifactId = insertReturningId(connection, """
                    INSERT INTO artifact (workspace_id, blob_key, status, byte_count, sha256, detected_media_type,
                                          display_filename, finalized_at)
                    VALUES (%d, 'workspace-%d/%s', 'READY', 1, '%s', 'DOCX', 'template.docx', now()) RETURNING id
                    """.formatted(workspaceId, workspaceId, UUID.randomUUID(), "f".repeat(64)));
            long extractionVersionId = insertReturningId(connection, """
                    INSERT INTO extraction_version (workspace_id, artifact_id, parser_version, status, feature_report, graph)
                    VALUES (%d, %d, 'fixture', 'COMPLETE', '[]'::jsonb, '{}'::jsonb) RETURNING id
                    """.formatted(workspaceId, artifactId));
            long templateId = insertReturningId(connection,
                    "INSERT INTO template (workspace_id, display_name) VALUES (" + workspaceId + ", 'Replay fixture') RETURNING id");
            long templateVersionId = insertReturningId(connection, """
                    INSERT INTO template_version (
                        workspace_id, template_id, version_number, source_artifact_id, extraction_version_id,
                        status, field_definitions, activated_at)
                    VALUES (%d, %d, 1, %d, %d, 'ACTIVATED', '[]'::jsonb, clock_timestamp()) RETURNING id
                    """.formatted(workspaceId, templateId, artifactId, extractionVersionId));
            documentId = insertReturningId(connection, """
                    INSERT INTO document (workspace_id, title, template_id, template_version_id)
                    VALUES (%d, 'Deleted after the backup', %d, %d) RETURNING id
                    """.formatted(workspaceId, templateId, templateVersionId));
            untouchedDocumentId = insertReturningId(connection, """
                    INSERT INTO document (workspace_id, title, template_id, template_version_id)
                    VALUES (%d, 'Never deleted', %d, %d) RETURNING id
                    """.formatted(workspaceId, templateId, templateVersionId));
            // A job the restored database still thinks is waiting to be claimed, for the document that is about to go.
            execute(connection, """
                    INSERT INTO job (workspace_id, requested_by_user_id, job_type, resource_type, resource_id, resource_version,
                                     stage, processing_configuration_hash)
                    VALUES (%d, %d, 'generation.extract-facts', 'document', %d, 1, 'extracting', '%s')
                    """.formatted(workspaceId, userId, documentId, "d".repeat(64)));

            OffsetDateTime now = OffsetDateTime.now();
            OffsetDateTime documentCreatedAt;
            try (PreparedStatement statement = connection.prepareStatement("SELECT created_at FROM document WHERE id = ?")) {
                statement.setLong(1, documentId);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    documentCreatedAt = rs.getObject(1, OffsetDateTime.class);
                }
            }
            new AzureDeletionLedgerArchive(new BlobServiceClientBuilder().connectionString(AZURITE.getConnectionString()).buildClient())
                    .add(new ArchivedDeletion(
                            41, workspaceId, DeletionScope.DOCUMENT, documentId, userId, now.plusSeconds(1), now.plusSeconds(2),
                            documentCreatedAt, "{}"));
        }
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DeletionReplayRunner replayRunner;

    @Test
    void startedInReplayModeTheWorkerAppliesTheLedgerSchedulesNothingAndReportsThatItFinished() throws Exception {
        assertThat(count("SELECT count(*) FROM document WHERE id = ?", documentId)).isZero();
        assertThat(count("SELECT count(*) FROM job WHERE resource_type = 'document' AND resource_id = ?", documentId)).isZero();
        assertThat(count("SELECT count(*) FROM document WHERE id = ?", untouchedDocumentId)).isEqualTo(1);
        assertThat(replayRunner.getExitCode()).isZero();

        // Nothing the worker does on a timer exists in this mode: no poller claimed that job, and none ever will.
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
    }

    private static long insertReturningId(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static long count(String sql, long... parameters) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setLong(i + 1, parameters[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static String text(String sql, long parameter) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }
}
