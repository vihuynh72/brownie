package io.github.vihuynh72.brownie.worker.retention;

import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.retention.ArtifactRetentionSweeper;
import io.github.vihuynh72.brownie.core.retention.DeletionSweeper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The background half of deletion and file housekeeping, run as the real
 * worker login against real Postgres and a real blob store. The worker can
 * read no table, so every fixture is seeded as the table owner and every
 * outcome is read back as the table owner; what the sweepers themselves
 * see is only what their routines hand them. Each test asks the same two
 * questions of the blob store: is what should be gone actually gone, and
 * is what nobody asked to remove still there.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RetentionSweepIntegrationTest {

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
        // The scheduled passes must not race this test's own direct calls.
        registry.add("brownie.worker.generation.enabled", () -> "false");
        registry.add("brownie.worker.retention.deletion-sweep.enabled", () -> "false");
        registry.add("brownie.worker.retention.artifact-sweep.enabled", () -> "false");
        registry.add("brownie.worker.retention.ledger-maintenance.enabled", () -> "false");
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath().getParent().getParent().resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    /** The worker owns no migrations; its tests apply the API's folder directly, as the other worker tests do. */
    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)
                .locations("filesystem:" + Path.of("").toAbsolutePath().getParent().resolve("brownie-api/src/main/resources/db/migration"))
                .load()
                .migrate();
    }

    @Autowired
    private DeletionSweeper deletionSweeper;

    @Autowired
    private io.github.vihuynh72.brownie.core.retention.DeletionArchiver deletionArchiver;

    @Autowired
    private io.github.vihuynh72.brownie.core.retention.DeletionLedgerArchive deletionLedgerArchive;

    @Autowired
    private ArtifactRetentionSweeper artifactRetentionSweeper;

    @Autowired
    private BlobStore blobStore;

    @Autowired
    private javax.sql.DataSource workerDataSource;

    @Autowired
    private io.github.vihuynh72.brownie.worker.persistence.JdbcWorkerUsageRepository workerUsageRepository;

    @Test
    void expiredTrashIsCarriedOutItsStoredObjectsAreRemovedAndTheRequestIsClosed() throws Exception {
        Fixture fixture = seedDocumentWithACompiledFile();
        long dueRequestId = trashAsOwner(fixture, "now() - interval '1 minute'");

        Fixture notDue = seedDocumentWithACompiledFile();
        long notDueRequestId = trashAsOwner(notDue, "now() + interval '29 days'");

        DeletionSweeper.Result result = deletionSweeper.sweepOnce(32);

        assertThat(result.trashPurged()).isEqualTo(1);
        assertThat(result.trashFailed()).isZero();
        assertThat(result.objectsRemoved()).isEqualTo(2);
        assertThat(result.objectsFailed()).isZero();
        assertThat(result.requestsVerified()).isZero();
        assertThat(count("SELECT count(*) FROM document WHERE id = ?", fixture.documentId())).isZero();
        assertThat(count("SELECT count(*) FROM document_revision WHERE document_id = ?", fixture.documentId())).isZero();
        assertThat(count("SELECT count(*) FROM artifact WHERE id IN (?, ?)", fixture.docxArtifactId(), fixture.pdfArtifactId())).isZero();
        assertThat(blobStore.sizeOf(fixture.docxKey())).isEmpty();
        assertThat(blobStore.sizeOf(fixture.pdfKey())).isEmpty();
        assertThat(count("SELECT count(*) FROM deletion_blob_task WHERE deletion_request_id = ? AND state = 'DELETED'", dueRequestId))
                .isEqualTo(2);
        // Every row and every object is gone, but the request is not closed yet: nothing outside this
        // database knows the deletion happened, so restoring an older backup would bring the document back.
        assertThat(text("SELECT state FROM deletion_request WHERE id = ?", dueRequestId)).isEqualTo("PURGED");

        assertThat(deletionArchiver.archiveOnce(32).failed()).isZero();

        assertThat(text("SELECT state FROM deletion_request WHERE id = ?", dueRequestId)).isEqualTo("VERIFIED");
        assertThat(deletionLedgerArchive.readAll())
                .filteredOn(entry -> entry.requestId() == dueRequestId)
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.workspaceId()).isEqualTo(fixture.workspaceId());
                    assertThat(entry.targetId()).isEqualTo(fixture.documentId());
                    assertThat(entry.scope().name()).isEqualTo("DOCUMENT");
                    assertThat(entry.inventoryJson()).contains("rowsRemoved").doesNotContain("Retention fixture");
                });
        // Writing the same entry again is nothing new, which is what makes "write, then record" safe to repeat.
        int entriesBefore = deletionLedgerArchive.readAll().size();
        deletionLedgerArchive.add(deletionLedgerArchive.readAll().get(0));
        assertThat(deletionLedgerArchive.readAll()).hasSize(entriesBefore);

        // The template's own file was never the document's to remove.
        assertThat(blobStore.sizeOf(fixture.templateKey())).isPresent();
        assertThat(count("SELECT count(*) FROM template WHERE workspace_id = ?", fixture.workspaceId())).isEqualTo(1);

        // Trash whose time has not come is left exactly as it was.
        assertThat(text("SELECT state FROM deletion_request WHERE id = ?", notDueRequestId)).isEqualTo("TRASHED");
        assertThat(count("SELECT count(*) FROM document WHERE id = ?", notDue.documentId())).isEqualTo(1);
        assertThat(blobStore.sizeOf(notDue.docxKey())).isPresent();

        // A second pass finds nothing left to do.
        assertThat(deletionSweeper.sweepOnce(32).didAnything()).isFalse();
    }

    @Test
    void aDeletionThatQueuedNothingIsStillClosedByTheSweep() throws Exception {
        long requestId;
        try (Connection connection = ownerConnection()) {
            requestId = insertReturningId(connection, """
                    INSERT INTO deletion_request (workspace_id, scope, target_id, state, requested_by_user_id, purged_at)
                    VALUES (999001, 'DOCUMENT', 999001, 'PURGED', 999001, clock_timestamp())
                    RETURNING id
                    """);
        }

        // Not before it is on record outside the database, however little it left behind.
        deletionSweeper.sweepOnce(32);
        assertThat(text("SELECT state FROM deletion_request WHERE id = ?", requestId)).isEqualTo("PURGED");

        deletionArchiver.archiveOnce(32);

        assertThat(text("SELECT state FROM deletion_request WHERE id = ?", requestId)).isEqualTo("VERIFIED");
        assertThat(count("SELECT count(*) FROM deletion_request WHERE id = ? AND archived_at IS NOT NULL", requestId)).isEqualTo(1);
    }

    @Test
    void fileHousekeepingMovesStaleUploadsOnAndRemovesOnlyWhatNothingCanStillUse() throws Exception {
        long workspaceId;
        long abandoned;
        long stuckScan;
        long lateRetryScan;
        long forgottenQuarantine;
        long malware;
        long unreferenced;
        long unreferencedButRecent;
        Fixture inUse = seedDocumentWithACompiledFile();
        try (Connection connection = ownerConnection()) {
            workspaceId = inUse.workspaceId();
            abandoned = insertArtifact(connection, workspaceId, "UPLOADING", null, "now() - interval '2 days'", null);
            stuckScan = insertArtifact(connection, workspaceId, "SCANNING", null, "now() - interval '3 hours'", "now() - interval '2 hours'");
            execute(connection, "UPDATE artifact SET scan_started_at = now() - interval '2 hours' WHERE id = " + stuckScan);
            // Uploaded days ago, scanner was down, the person came back and the scan began a moment ago.
            lateRetryScan = insertArtifact(connection, workspaceId, "SCANNING", null, "now() - interval '3 days'", "now() - interval '3 days'");
            execute(connection, "UPDATE artifact SET scan_started_at = now() - interval '20 seconds' WHERE id = " + lateRetryScan);
            forgottenQuarantine = insertArtifact(connection, workspaceId, "QUARANTINED", null, "now() - interval '3 days'", "now() - interval '3 days'");
            malware = insertArtifact(connection, workspaceId, "REJECTED", "MALWARE_DETECTED", "now() - interval '2 days'", "now() - interval '2 days'");
            unreferenced = insertArtifact(connection, workspaceId, "READY", null, "now() - interval '2 days'", "now() - interval '2 days'");
            unreferencedButRecent = insertArtifact(connection, workspaceId, "READY", null, "now() - interval '5 minutes'", "now() - interval '5 minutes'");
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO plain_text_extraction_version (workspace_id, artifact_id, parser_version, status, original_text, normalized_text)
                    VALUES (?, ?, 'fixture', 'COMPLETE', 'Private notes nobody attached.', 'Private notes nobody attached.')
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, unreferenced);
                statement.executeUpdate();
            }
        }
        for (long artifactId : List.of(malware, unreferenced, unreferencedButRecent, forgottenQuarantine)) {
            writeBlob(blobKey(artifactId));
        }

        ArtifactRetentionSweeper.Result result = artifactRetentionSweeper.sweepOnce(64);

        // Abandoned upload and forgotten quarantine are refused; the stuck scan can be retried.
        assertThat(result.uploadsExpired()).isEqualTo(3);
        assertThat(text("SELECT status || ':' || rejection_reason FROM artifact WHERE id = ?", abandoned))
                .isEqualTo("REJECTED:EXPIRED_ABANDONED_UPLOAD");
        assertThat(text("SELECT status FROM artifact WHERE id = ?", stuckScan)).isEqualTo("QUARANTINED");
        // A scan is timed from when it began, not from when the file arrived: one that started a
        // moment ago on an old upload is somebody's request in flight and is left alone.
        assertThat(text("SELECT status FROM artifact WHERE id = ?", lateRetryScan)).isEqualTo("SCANNING");
        assertThat(text("SELECT status || ':' || rejection_reason FROM artifact WHERE id = ?", forgottenQuarantine))
                .isEqualTo("REJECTED:EXPIRED_QUARANTINE");
        // A ready file nothing refers to is refused, and what was extracted from it goes with it.
        assertThat(text("SELECT status || ':' || rejection_reason FROM artifact WHERE id = ?", unreferenced))
                .isEqualTo("REJECTED:UNREFERENCED_EXPIRED");
        assertThat(count("SELECT count(*) FROM plain_text_extraction_version WHERE artifact_id = ?", unreferenced)).isZero();

        // A refused file's age is counted from when it arrived, not from when it was refused, so a
        // file that sat in quarantine for days loses its bytes in the same pass that refuses it:
        // bytes gone, the row stays as a record.
        for (long artifactId : List.of(malware, unreferenced, forgottenQuarantine)) {
            assertThat(blobStore.sizeOf(blobKey(artifactId))).as("bytes of artifact %d", artifactId).isEmpty();
            assertThat(count("SELECT count(*) FROM artifact WHERE id = ? AND payload_removed_at IS NOT NULL", artifactId)).isEqualTo(1);
        }

        // Nothing a person can still use was touched.
        assertThat(text("SELECT status FROM artifact WHERE id = ?", unreferencedButRecent)).isEqualTo("READY");
        assertThat(blobStore.sizeOf(blobKey(unreferencedButRecent))).isPresent();
        for (long artifactId : List.of(inUse.templateArtifactId(), inUse.docxArtifactId(), inUse.pdfArtifactId())) {
            assertThat(text("SELECT status FROM artifact WHERE id = ?", artifactId)).as("artifact %d", artifactId).isEqualTo("READY");
        }
        assertThat(blobStore.sizeOf(inUse.docxKey())).isPresent();
        assertThat(blobStore.sizeOf(inUse.templateKey())).isPresent();

        // A second pass removes nothing more.
        ArtifactRetentionSweeper.Result second = artifactRetentionSweeper.sweepOnce(64);
        assertThat(second.payloadsRemoved()).isZero();
        assertThat(second.uploadsExpired()).isZero();
    }

    /**
     * A reservation nobody closed belongs to a process that died mid-call.
     * Nobody knows whether the provider served it, so it is kept at its full
     * amount, never dropped; one that is merely recent is somebody's call
     * still in flight and is left alone. Audit rows go after their period.
     */
    @Test
    void staleUsageReservationsAreKeptAtFullCostAndOldAuditEventsExpire() throws Exception {
        long staleId;
        long inFlightId;
        long oldAuditId;
        long recentAuditId;
        try (Connection connection = ownerConnection()) {
            staleId = insertReturningId(connection, """
                    INSERT INTO model_usage (workspace_id, requested_by_user_id, purpose, model_name, prompt_version, rate_card,
                                             reserved_input_tokens, reserved_output_tokens, reserved_cost_usd, created_at)
                    VALUES (999002, 999002, 'ASSIST', 'm', 'p', 'r', 10, 10, 0.012345, now() - interval '2 hours') RETURNING id
                    """);
            inFlightId = insertReturningId(connection, """
                    INSERT INTO model_usage (workspace_id, requested_by_user_id, purpose, model_name, prompt_version, rate_card,
                                             reserved_input_tokens, reserved_output_tokens, reserved_cost_usd, created_at)
                    VALUES (999002, 999002, 'ASSIST', 'm', 'p', 'r', 10, 10, 0.012345, now() - interval '1 minute') RETURNING id
                    """);
            oldAuditId = insertReturningId(connection, """
                    INSERT INTO audit_event (workspace_id, actor_user_id, action, resource_type, resource_id, occurred_at)
                    VALUES (999002, 999002, 'DOCUMENT_TRASHED', 'document', 1, now() - interval '91 days') RETURNING id
                    """);
            recentAuditId = insertReturningId(connection, """
                    INSERT INTO audit_event (workspace_id, actor_user_id, action, resource_type, resource_id, occurred_at)
                    VALUES (999002, 999002, 'DOCUMENT_TRASHED', 'document', 1, now() - interval '89 days') RETURNING id
                    """);
        }

        assertThat(workerUsageRepository.retainStale(java.time.Duration.ofMinutes(30), 100)).isEqualTo(1);
        assertThat(workerUsageRepository.expireAuditEvents(java.time.Duration.ofDays(90), 100)).isEqualTo(1);

        assertThat(text("SELECT state || ':' || reserved_cost_usd FROM model_usage WHERE id = ?", staleId)).isEqualTo("RETAINED:0.012345");
        assertThat(text("SELECT state FROM model_usage WHERE id = ?", inFlightId)).isEqualTo("RESERVED");
        assertThat(count("SELECT count(*) FROM audit_event WHERE id = ?", oldAuditId)).isZero();
        assertThat(count("SELECT count(*) FROM audit_event WHERE id = ?", recentAuditId)).isEqualTo(1);
        // Running it again finds nothing more to do.
        assertThat(workerUsageRepository.retainStale(java.time.Duration.ofMinutes(30), 100)).isZero();
    }

    @Test
    void theWorkerLoginSeesNoTableAndCannotCallWhatOnlyAMemberMay() throws Exception {
        try (Connection connection = workerDataSource.getConnection()) {
            connection.setAutoCommit(false);
            for (String statement : List.of(
                    "SELECT count(*) FROM deletion_request",
                    "SELECT count(*) FROM deletion_blob_task",
                    "SELECT count(*) FROM artifact",
                    "SELECT count(*) FROM model_usage",
                    "SELECT count(*) FROM audit_event",
                    "SELECT * FROM reserve_member_model_usage(1, 'm', 'p', 'r', 1, 1, 0.01, 1, 1, 1)",
                    "SELECT * FROM model_usage_reserve(1, 1, 'ASSIST', NULL, 0, 'm', 'p', 'r', 1, 1, 0.01, 1, 1, 1, 1)",
                    "SELECT audit_append(1, 1, 'DOCUMENT_TRASHED', 'document', 1, '{}'::jsonb)",
                    "SELECT trash_document(1, 1, 30)",
                    "SELECT purge_trashed_document(1, 1)",
                    "SELECT * FROM delete_workspace(1)",
                    "SELECT retention_execute_purge(1)",
                    "SELECT retention_purge_document(1, 1, 1)")) {
                try (PreparedStatement forbidden = connection.prepareStatement(statement)) {
                    assertThatThrownBy(forbidden::execute)
                            .as(statement)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("permission denied");
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    private record Fixture(
            long workspaceId, long userId, long documentId, long templateArtifactId, long docxArtifactId, long pdfArtifactId) {

        String templateKey() {
            return blobKey(templateArtifactId);
        }

        String docxKey() {
            return blobKey(docxArtifactId);
        }

        String pdfKey() {
            return blobKey(pdfArtifactId);
        }
    }

    /** Fixture object keys are derived from the artifact id so a test can name an object without a lookup. */
    private static String blobKey(long artifactId) {
        return "retention-fixture/artifact-" + artifactId;
    }

    private Fixture seedDocumentWithACompiledFile() throws Exception {
        try (Connection connection = ownerConnection()) {
            long userId = insertReturningId(connection,
                    "INSERT INTO user_identity (issuer, subject) VALUES ('https://retention-sweep-test.invalid', '" + UUID.randomUUID() + "') RETURNING id");
            long workspaceId = insertReturningId(connection, "INSERT INTO workspace (owner_user_id) VALUES (" + userId + ") RETURNING id");
            execute(connection, "INSERT INTO workspace_member (workspace_id, user_id, role, state) VALUES (" + workspaceId + ", " + userId + ", 'OWNER', 'ACTIVE')");

            String old = "now() - interval '2 days'";
            long templateArtifactId = insertArtifact(connection, workspaceId, "READY", null, old, old);
            long extractionVersionId = insertReturningId(connection, """
                    INSERT INTO extraction_version (workspace_id, artifact_id, parser_version, status, feature_report, graph)
                    VALUES (%d, %d, 'fixture', 'COMPLETE', '[]'::jsonb, '{}'::jsonb) RETURNING id
                    """.formatted(workspaceId, templateArtifactId));
            long templateId = insertReturningId(connection,
                    "INSERT INTO template (workspace_id, display_name) VALUES (" + workspaceId + ", 'Retention fixture') RETURNING id");
            long templateVersionId = insertReturningId(connection, """
                    INSERT INTO template_version (
                        workspace_id, template_id, version_number, source_artifact_id, extraction_version_id,
                        status, field_definitions, activated_at)
                    VALUES (%d, %d, 1, %d, %d, 'ACTIVATED', '[]'::jsonb, clock_timestamp()) RETURNING id
                    """.formatted(workspaceId, templateId, templateArtifactId, extractionVersionId));
            long documentId = insertReturningId(connection, """
                    INSERT INTO document (workspace_id, title, template_id, template_version_id)
                    VALUES (%d, 'Retention fixture document', %d, %d) RETURNING id
                    """.formatted(workspaceId, templateId, templateVersionId));
            long revisionId = insertReturningId(connection, """
                    INSERT INTO document_revision (
                        workspace_id, document_id, revision_number, parent_revision_id, content, content_hash, actor_user_id, edit_reason)
                    VALUES (%d, %d, 1, NULL, '{}'::jsonb, '%s', %d, 'Retention fixture revision') RETURNING id
                    """.formatted(workspaceId, documentId, "a".repeat(64), userId));
            execute(connection, "UPDATE document SET current_revision_id = " + revisionId + " WHERE id = " + documentId);

            long docxArtifactId = insertArtifact(connection, workspaceId, "READY", null, old, old);
            long pdfArtifactId = insertArtifact(connection, workspaceId, "READY", null, old, old);
            execute(connection, """
                    INSERT INTO document_compilation (
                        workspace_id, document_id, revision_id, template_id, template_version_id,
                        docx_artifact_id, docx_sha256, pdf_artifact_id, pdf_sha256, renderer_version, integrity_findings)
                    VALUES (%d, %d, %d, %d, %d, %d, '%s', %d, '%s', 'fixture renderer', '[]'::jsonb)
                    """.formatted(workspaceId, documentId, revisionId, templateId, templateVersionId,
                    docxArtifactId, "b".repeat(64), pdfArtifactId, "c".repeat(64)));

            Fixture fixture = new Fixture(workspaceId, userId, documentId, templateArtifactId, docxArtifactId, pdfArtifactId);
            for (String key : List.of(fixture.templateKey(), fixture.docxKey(), fixture.pdfKey())) {
                writeBlob(key);
            }
            return fixture;
        }
    }

    /** What the member-facing routine leaves behind, written directly because that routine answers only the API login. */
    private long trashAsOwner(Fixture fixture, String purgeAfterSql) throws Exception {
        try (Connection connection = ownerConnection()) {
            execute(connection, "UPDATE document SET trashed_at = now() - interval '31 days' WHERE id = " + fixture.documentId());
            return insertReturningId(connection, """
                    INSERT INTO deletion_request (workspace_id, scope, target_id, state, requested_by_user_id, requested_at, purge_after)
                    VALUES (%d, 'DOCUMENT', %d, 'TRASHED', %d, now() - interval '31 days', %s) RETURNING id
                    """.formatted(fixture.workspaceId(), fixture.documentId(), fixture.userId(), purgeAfterSql));
        }
    }

    private long insertArtifact(
            Connection connection, long workspaceId, String status, String rejectionReason, String createdAtSql, String finalizedAtSql)
            throws Exception {
        long artifactId = insertReturningId(connection, """
                INSERT INTO artifact (workspace_id, blob_key, status, rejection_reason, byte_count, sha256, detected_media_type,
                                      display_filename, created_at, finalized_at)
                VALUES (%d, '%s', '%s', %s, 1, '%s', 'PLAIN_TEXT', 'fixture.txt', %s, %s) RETURNING id
                """.formatted(workspaceId, "pending-" + UUID.randomUUID(), status,
                rejectionReason == null ? "NULL" : "'" + rejectionReason + "'", "f".repeat(64), createdAtSql,
                finalizedAtSql == null ? "NULL" : finalizedAtSql));
        execute(connection, "UPDATE artifact SET blob_key = '" + blobKey(artifactId) + "' WHERE id = " + artifactId);
        return artifactId;
    }

    private void writeBlob(String objectKey) throws Exception {
        blobStore.writeAndDigest(objectKey, new ByteArrayInputStream("fixture".getBytes(StandardCharsets.UTF_8)), 1024);
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
