package io.github.vihuynh72.brownie.worker.retention;

import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.retention.DeletionArchiver;
import io.github.vihuynh72.brownie.core.retention.DeletionLedgerArchive;
import io.github.vihuynh72.brownie.core.retention.DeletionSweeper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import io.github.vihuynh72.brownie.worker.persistence.JdbcDeletionArchiveRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.containers.Container.ExecResult;
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

/**
 * The sequence a restore has to follow, with a real backup and a real
 * restore: a backup is taken, a workspace and a document are deleted for
 * good afterwards, the database is put back to the backup, and the record
 * kept outside the database is applied before anything else happens. The
 * test is that what was deleted is gone again, that nothing else is, and
 * that an entry can never reach something newer that was later given the
 * same id.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DeletionLedgerReplayIntegrationTest {

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
    private DeletionArchiver deletionArchiver;

    @Autowired
    private DeletionLedgerArchive deletionLedgerArchive;

    @Autowired
    private BlobStore blobStore;

    @Test
    void whatWasDeletedAfterABackupIsDeletedAgainWhenThatBackupIsRestoredAndNothingElseIs() throws Exception {
        Fixture kept = seedDocumentWithACompiledFile();
        Fixture doomedWorkspace = seedDocumentWithACompiledFile();
        Fixture doomedDocument = seedDocumentWithACompiledFile();

        run("pg_dump", "-U", "postgres", "-d", "brownie", "--format=custom", "--file=/tmp/before-the-deletions.dump");

        // After the backup: a whole workspace and one document are deleted for good, and the worker finishes both.
        long workspaceRequestId;
        try (Connection connection = ownerConnection()) {
            workspaceRequestId = insertReturningId(connection, """
                    INSERT INTO deletion_request (workspace_id, scope, target_id, state, requested_by_user_id, requested_at, purge_after)
                    VALUES (%d, 'WORKSPACE', %d, 'TRASHED', %d, clock_timestamp(), clock_timestamp()) RETURNING id
                    """.formatted(doomedWorkspace.workspaceId(), doomedWorkspace.workspaceId(), doomedWorkspace.userId()));
        }
        long documentRequestId = trashJustNowAsOwner(doomedDocument);
        assertThat(deletionSweeper.sweepOnce(32).trashPurged()).isEqualTo(2);
        assertThat(deletionArchiver.archiveOnce(32)).isEqualTo(new DeletionArchiver.ArchiveResult(2, 0));
        deletionSweeper.sweepOnce(32);
        assertThat(text("SELECT state FROM deletion_request WHERE id = ?", workspaceRequestId)).isEqualTo("VERIFIED");
        assertThat(text("SELECT state FROM deletion_request WHERE id = ?", documentRequestId)).isEqualTo("VERIFIED");
        assertThat(count("SELECT count(*) FROM workspace WHERE id = ?", doomedWorkspace.workspaceId())).isZero();
        assertThat(count("SELECT count(*) FROM user_identity WHERE id = ?", doomedWorkspace.userId())).isZero();
        assertThat(count("SELECT count(*) FROM document WHERE id = ?", doomedDocument.documentId())).isZero();
        assertThat(blobStore.sizeOf(doomedWorkspace.docxKey())).isEmpty();

        // The database is lost and put back from the backup. Stored files are not rolled back with it.
        run("pg_restore", "-U", "postgres", "-d", "brownie", "--clean", "--if-exists", "/tmp/before-the-deletions.dump");

        // The backup knows nothing of either deletion: both are back, and the ledger has forgotten them.
        assertThat(count("SELECT count(*) FROM workspace WHERE id = ?", doomedWorkspace.workspaceId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_identity WHERE id = ?", doomedWorkspace.userId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM document WHERE id = ?", doomedDocument.documentId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM deletion_request WHERE workspace_id IN (?, ?)",
                doomedWorkspace.workspaceId(), doomedDocument.workspaceId())).isZero();

        // Before anyone is let in, what is recorded outside the database is applied to it.
        DeletionArchiver replayer = new DeletionArchiver(new JdbcDeletionArchiveRepository(freshWorkerJdbc()), deletionLedgerArchive);
        DeletionArchiver.ReplayResult replay = replayer.replayAll();

        assertThat(replay.replayed()).isEqualTo(2);
        assertThat(replay.complete()).isTrue();
        assertThat(count("SELECT count(*) FROM workspace WHERE id = ?", doomedWorkspace.workspaceId())).isZero();
        assertThat(count("SELECT count(*) FROM workspace_member WHERE workspace_id = ?", doomedWorkspace.workspaceId())).isZero();
        assertThat(count("SELECT count(*) FROM document WHERE workspace_id = ?", doomedWorkspace.workspaceId())).isZero();
        assertThat(count("SELECT count(*) FROM artifact WHERE workspace_id = ?", doomedWorkspace.workspaceId())).isZero();
        assertThat(count("SELECT count(*) FROM user_identity WHERE id = ?", doomedWorkspace.userId())).isZero();
        assertThat(count("SELECT count(*) FROM document WHERE id = ?", doomedDocument.documentId())).isZero();
        assertThat(count("SELECT count(*) FROM document_revision WHERE document_id = ?", doomedDocument.documentId())).isZero();
        // What the document never owned, and everything nobody deleted, is exactly as the backup had it.
        assertThat(count("SELECT count(*) FROM template WHERE workspace_id = ?", doomedDocument.workspaceId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM document WHERE id = ?", kept.documentId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM document_compilation WHERE document_id = ?", kept.documentId())).isEqualTo(1);
        assertThat(blobStore.sizeOf(kept.docxKey())).isPresent();
        // The restored rows named stored files; those are queued again, so a blob store that had also been
        // rolled back would be cleaned by the ordinary sweep. It is on the audit record as the system's act.
        assertThat(count("""
                SELECT count(*) FROM deletion_blob_task t JOIN deletion_request r ON r.id = t.deletion_request_id
                WHERE r.workspace_id = ? AND r.scope = 'WORKSPACE' AND r.state = 'PURGED'
                """, doomedWorkspace.workspaceId())).isGreaterThanOrEqualTo(3);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'WORKSPACE_DELETED' AND actor_user_id IS NULL AND workspace_id = ?",
                doomedWorkspace.workspaceId())).isEqualTo(1);

        // Applying it all again changes nothing.
        DeletionArchiver.ReplayResult again = replayer.replayAll();
        assertThat(again.replayed()).isZero();
        assertThat(again.absent()).isEqualTo(again.entries());

        // A restore winds the id sequences back too, so later a new document can be given a deleted one's id, and if
        // an older backup is then restored over that, an entry can even name an id that belonged to something older.
        // An entry names its document by id and moment of creation together; this one shares only the id. It is
        // backdated here to before the deletion was asked for, which a rule based on age alone would have let through.
        try (Connection connection = ownerConnection()) {
            execute(connection, """
                    INSERT INTO document (id, workspace_id, title, template_id, template_version_id, created_at)
                    SELECT %d, t.workspace_id, 'Written after the restore', t.id, v.id, now() - interval '1 day'
                    FROM template t JOIN template_version v ON v.template_id = t.id
                    WHERE t.workspace_id = %d
                    """.formatted(doomedDocument.documentId(), doomedDocument.workspaceId()));
        }
        assertThat(replayer.replayAll().replayed()).isZero();
        assertThat(text("SELECT title FROM document WHERE id = ?", doomedDocument.documentId())).isEqualTo("Written after the restore");

        // The replayed deletions are themselves recorded and closed by the worker's ordinary passes.
        new DeletionArchiver(new JdbcDeletionArchiveRepository(freshWorkerJdbc()), deletionLedgerArchive).archiveOnce(32);
        assertThat(count("SELECT count(*) FROM deletion_request WHERE workspace_id = ? AND archived_at IS NULL",
                doomedWorkspace.workspaceId())).isZero();
    }

    @Test
    void theRoutinesBelongToTheWorkerAloneAndRefuseAnEntryThatDoesNotSayWhatItIs() throws Exception {
        JdbcTemplate worker = freshWorkerJdbc().getObject();
        for (String malformed : List.of(
                "SELECT public.worker_replay_deletion('EVERYTHING', 1, 1, 1, now(), now())",
                "SELECT public.worker_replay_deletion('WORKSPACE', 1, 2, 1, now(), now())",
                "SELECT public.worker_replay_deletion('DOCUMENT', 1, 1, 1, NULL, now())")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> worker.queryForObject(malformed, String.class))
                    .as(malformed)
                    .satisfiesAnyOf(
                            failure -> assertThat(failure.getMessage()).contains("names a scope"),
                            failure -> assertThat(failure.getMessage()).contains("targets its own workspace"));
        }
        // Nothing to apply to is an answer, not an error.
        assertThat(worker.queryForObject("SELECT public.worker_replay_deletion('DOCUMENT', 987654, 987654, 1, now(), now())", String.class))
                .isEqualTo("ABSENT");
        // An entry that cannot say which thing it removed is applied to nothing.
        assertThat(worker.queryForObject("SELECT public.worker_replay_deletion('DOCUMENT', 1, 1, 1, now(), NULL)", String.class))
                .isEqualTo("ABSENT");

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_api", "brownie_api_local_only")) {
            for (String statement : List.of(
                    "SELECT public.worker_replay_deletion('DOCUMENT', 1, 1, 1, now(), now())",
                    "SELECT * FROM public.worker_collect_unarchived_deletions(10)",
                    "SELECT public.worker_mark_deletion_archived(1)",
                    "UPDATE deletion_request SET archived_at = now()")) {
                try (PreparedStatement forbidden = connection.prepareStatement(statement)) {
                    org.assertj.core.api.Assertions.assertThatThrownBy(forbidden::execute)
                            .as(statement)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("permission denied");
                }
            }
        }
    }

    /** The restore replaces every table and routine, so the replay runs on connections opened after it, as a freshly started worker's would be. */
    private static ObjectProvider<JdbcTemplate> freshWorkerJdbc() {
        JdbcTemplate template = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "brownie_worker", WORKER_PASSWORD));
        return new ObjectProvider<>() {
            @Override
            public JdbcTemplate getObject() {
                return template;
            }

            @Override
            public JdbcTemplate getIfAvailable() {
                return template;
            }
        };
    }

    private static void run(String... command) throws Exception {
        ExecResult result = POSTGRES.execInContainer(command);
        assertThat(result.getExitCode()).as(String.join(" ", command) + "\n" + result.getStderr()).isZero();
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
                    "INSERT INTO user_identity (issuer, subject) VALUES ('https://deletion-replay-test.invalid', '" + UUID.randomUUID() + "') RETURNING id");
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
    /**
     * In the trash as of this moment and already due. The time it was asked for has to be a real one, after the
     * document was made: an archived deletion is only ever applied to something that existed when it was asked for.
     */
    private long trashJustNowAsOwner(Fixture fixture) throws Exception {
        try (Connection connection = ownerConnection()) {
            execute(connection, "UPDATE document SET trashed_at = clock_timestamp() WHERE id = " + fixture.documentId());
            return insertReturningId(connection, """
                    INSERT INTO deletion_request (workspace_id, scope, target_id, state, requested_by_user_id, requested_at, purge_after)
                    VALUES (%d, 'DOCUMENT', %d, 'TRASHED', %d, clock_timestamp(), clock_timestamp()) RETURNING id
                    """.formatted(fixture.workspaceId(), fixture.documentId(), fixture.userId()));
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
