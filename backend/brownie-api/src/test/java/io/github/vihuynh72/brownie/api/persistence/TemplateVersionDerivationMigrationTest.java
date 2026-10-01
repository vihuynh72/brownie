package io.github.vihuynh72.brownie.api.persistence;

import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the migration that lets a template version be made from another
 * one and a document move between its template's versions, against a
 * database that already held documents before it ran: every existing
 * revision is given its document's version, a revision written without one
 * gets the document's version from the database itself, moving the current
 * revision moves the document's version only to an activated version of the
 * same template, and the new columns refuse a lineage that does not hang
 * together.
 */
@DockerTest
class TemplateVersionDerivationMigrationTest {

    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String API_PASSWORD = "brownie_api_local_only";

    static final TestDatabase DB = SharedContainers.newDatabase();

    private static Seed seed;

    @BeforeAll
    static void migrateAroundExistingDocuments() throws SQLException {
        flyway("46").migrate();
        seed = seedBeforeTheMigration();
        flyway(null).migrate();
    }

    @Test
    void everyExistingRevisionIsGivenItsDocumentsVersionAndTheColumnIsRequired() throws SQLException {
        try (Connection connection = DB.superuserConnection()) {
            assertThat(longs(connection, "SELECT template_version_id FROM document_revision WHERE document_id = " + seed.documentId()))
                    .containsOnly(seed.firstVersionId());
            assertThatThrownBy(() -> execute(connection,
                    "UPDATE document_revision SET template_version_id = NULL WHERE id = " + seed.firstRevisionId()))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("null value");
        }
    }

    @Test
    void aRevisionWrittenWithoutAVersionTakesTheDocumentsOwnAndAdvancingToItKeepsTheVersion() throws SQLException {
        Seed own = seedAfterTheMigration();
        try (Connection api = apiConnection(own.userId())) {
            long revisionId = insertRevision(api, own, 2, own.firstRevisionId(), null);
            assertThat(single(api, "SELECT template_version_id FROM document_revision WHERE id = " + revisionId))
                    .isEqualTo(own.firstVersionId());
            assertThat(advance(api, own, own.firstRevisionId(), revisionId)).isTrue();
            assertThat(single(api, "SELECT template_version_id FROM document WHERE id = " + own.documentId()))
                    .isEqualTo(own.firstVersionId());
            api.commit();
        }
    }

    @Test
    void advancingToARevisionOnAnotherActivatedVersionMovesTheDocumentWithIt() throws SQLException {
        Seed own = seedAfterTheMigration();
        try (Connection api = apiConnection(own.userId())) {
            long revisionId = insertRevision(api, own, 2, own.firstRevisionId(), own.secondVersionId());
            assertThat(advance(api, own, own.firstRevisionId(), revisionId)).isTrue();
            assertThat(single(api, "SELECT template_version_id FROM document WHERE id = " + own.documentId()))
                    .isEqualTo(own.secondVersionId());
            assertThat(single(api, "SELECT current_revision_id FROM document WHERE id = " + own.documentId()))
                    .isEqualTo(revisionId);
            api.commit();
        }
    }

    @Test
    void advancingToARevisionOnADraftOrOnAnotherTemplatesVersionIsRefused() throws SQLException {
        Seed own = seedAfterTheMigration();
        try (Connection api = apiConnection(own.userId())) {
            long onDraft = insertRevision(api, own, 2, own.firstRevisionId(), own.draftVersionId());
            assertThat(advance(api, own, own.firstRevisionId(), onDraft)).isFalse();
            long onOtherTemplate = insertRevision(api, own, 3, own.firstRevisionId(), own.otherTemplateVersionId());
            assertThat(advance(api, own, own.firstRevisionId(), onOtherTemplate)).isFalse();
            assertThat(single(api, "SELECT template_version_id FROM document WHERE id = " + own.documentId()))
                    .isEqualTo(own.firstVersionId());
            assertThat(single(api, "SELECT current_revision_id FROM document WHERE id = " + own.documentId()))
                    .isEqualTo(own.firstRevisionId());
            api.rollback();
        }
    }

    @Test
    void aDerivedVersionNeedsItsBaseKeyAndChangesTogetherAndOneKeyPerBase() throws SQLException {
        Seed own = seedAfterTheMigration();
        String key = "d".repeat(64);
        try (Connection connection = DB.superuserConnection()) {
            long derivedId = insertVersion(connection, own, own.templateId(), 5, "ACTIVATED",
                    own.firstVersionId() + ", '" + key + "', '{\"changes\":[]}'::jsonb");
            assertThat(derivedId).isPositive();

            assertThatThrownBy(() -> insertVersion(connection, own, own.templateId(), 6, "ACTIVATED",
                    own.firstVersionId() + ", NULL, '{\"changes\":[]}'::jsonb"))
                    .hasMessageContaining("template_version_derivation_together");
            assertThatThrownBy(() -> insertVersion(connection, own, own.templateId(), 7, "ACTIVATED",
                    own.firstVersionId() + ", 'not-a-hash', '{\"changes\":[]}'::jsonb"))
                    .hasMessageContaining("template_version_derivation_key_format");
            assertThatThrownBy(() -> insertVersion(connection, own, own.templateId(), 8, "ACTIVATED",
                    own.firstVersionId() + ", '" + key + "', '{\"changes\":[]}'::jsonb"))
                    .hasMessageContaining("template_version_derivation_idx");
            assertThatThrownBy(() -> insertVersion(connection, own, own.templateId(), 9, "ACTIVATED",
                    own.firstVersionId() + ", '" + "e".repeat(64) + "', '[]'::jsonb"))
                    .hasMessageContaining("template_version_derivation_object");
            // A version can only be made from a version of its own template.
            assertThatThrownBy(() -> insertVersion(connection, own, own.otherTemplateId(), 2, "ACTIVATED",
                    own.firstVersionId() + ", '" + "f".repeat(64) + "', '{\"changes\":[]}'::jsonb"))
                    .hasMessageContaining("template_version_derived_from_fk");
        }
    }

    @Test
    void theTwoNewAuditedActionsAreAcceptedAndAnUnknownOneIsNot() throws SQLException {
        Seed own = seedAfterTheMigration();
        try (Connection connection = DB.superuserConnection()) {
            for (String action : new String[] {"TEMPLATE_VERSION_DERIVED", "DOCUMENT_TEMPLATE_VERSION_CHANGED", "EXTERNAL_ACTION_FINISHED"}) {
                execute(connection, """
                        INSERT INTO audit_event (workspace_id, actor_user_id, action, resource_type, resource_id, details)
                        VALUES (%d, %d, '%s', 'document', %d, '{}'::jsonb)
                        """.formatted(own.workspaceId(), own.userId(), action, own.documentId()));
            }
            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO audit_event (workspace_id, actor_user_id, action, resource_type, resource_id, details)
                    VALUES (%d, %d, 'TEMPLATE_VERSION_INVENTED', 'document', %d, '{}'::jsonb)
                    """.formatted(own.workspaceId(), own.userId(), own.documentId())))
                    .hasMessageContaining("audit_event_action_known");
        }
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(DB.jdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private record Seed(
            long userId,
            long workspaceId,
            long templateId,
            long firstVersionId,
            long secondVersionId,
            long draftVersionId,
            long otherTemplateId,
            long otherTemplateVersionId,
            long documentId,
            long firstRevisionId) {
    }

    /** Written before the migration exists, so without the column it adds; the backfill must give these rows their version. */
    private static Seed seedBeforeTheMigration() throws SQLException {
        try (Connection connection = DB.superuserConnection()) {
            Seed base = seedTemplatesAndDocument(connection, false);
            long secondRevisionId = returningId(connection, """
                    INSERT INTO document_revision (
                        workspace_id, document_id, revision_number, parent_revision_id, content, content_hash, actor_user_id, edit_reason)
                    VALUES (%d, %d, 2, %d, '{}'::jsonb, '%s', %d, 'Second fixture revision') RETURNING id
                    """.formatted(base.workspaceId(), base.documentId(), base.firstRevisionId(), "b".repeat(64), base.userId()));
            execute(connection, "UPDATE document SET current_revision_id = " + secondRevisionId + " WHERE id = " + base.documentId());
            return base;
        }
    }

    private static Seed seedAfterTheMigration() throws SQLException {
        try (Connection connection = DB.superuserConnection()) {
            return seedTemplatesAndDocument(connection, true);
        }
    }

    private static Seed seedTemplatesAndDocument(Connection connection, boolean migrated) throws SQLException {
        long userId = returningId(connection,
                "INSERT INTO user_identity (issuer, subject) VALUES ('https://derivation-migration.invalid', '" + UUID.randomUUID() + "') RETURNING id");
        long workspaceId = returningId(connection, "INSERT INTO workspace (owner_user_id) VALUES (" + userId + ") RETURNING id");
        execute(connection, "INSERT INTO workspace_member (workspace_id, user_id, role, state) VALUES ("
                + workspaceId + ", " + userId + ", 'OWNER', 'ACTIVE')");
        long artifactId = returningId(connection, """
                INSERT INTO artifact (workspace_id, blob_key, status, byte_count, sha256, detected_media_type, display_filename, finalized_at)
                VALUES (%d, '%s', 'READY', 1, '%s', 'DOCX', 'form.docx', now()) RETURNING id
                """.formatted(workspaceId, "derivation-migration/" + UUID.randomUUID(), "f".repeat(64)));
        long extractionId = returningId(connection, """
                INSERT INTO extraction_version (workspace_id, artifact_id, parser_version, status, feature_report, graph)
                VALUES (%d, %d, 'fixture', 'COMPLETE', '[]'::jsonb, '{}'::jsonb) RETURNING id
                """.formatted(workspaceId, artifactId));
        long templateId = returningId(connection,
                "INSERT INTO template (workspace_id, display_name) VALUES (" + workspaceId + ", 'Migration fixture') RETURNING id");
        long otherTemplateId = returningId(connection,
                "INSERT INTO template (workspace_id, display_name) VALUES (" + workspaceId + ", 'Other fixture') RETURNING id");
        Seed partial = new Seed(userId, workspaceId, templateId, 0, 0, 0, otherTemplateId, 0, 0, 0);
        long firstVersionId = insertPlainVersion(connection, partial, artifactId, extractionId, templateId, 1, "ACTIVATED");
        long secondVersionId = insertPlainVersion(connection, partial, artifactId, extractionId, templateId, 2, "ACTIVATED");
        long draftVersionId = insertPlainVersion(connection, partial, artifactId, extractionId, templateId, 3, "DRAFT");
        long otherVersionId = insertPlainVersion(connection, partial, artifactId, extractionId, otherTemplateId, 1, "ACTIVATED");
        long documentId = returningId(connection, """
                INSERT INTO document (workspace_id, title, template_id, template_version_id)
                VALUES (%d, 'Migration fixture document', %d, %d) RETURNING id
                """.formatted(workspaceId, templateId, firstVersionId));
        long firstRevisionId = returningId(connection, """
                INSERT INTO document_revision (
                    workspace_id, document_id, revision_number, parent_revision_id, content, content_hash, actor_user_id, edit_reason)
                VALUES (%d, %d, 1, NULL, '{}'::jsonb, '%s', %d, 'First fixture revision') RETURNING id
                """.formatted(workspaceId, documentId, "a".repeat(64), userId));
        execute(connection, "UPDATE document SET current_revision_id = " + firstRevisionId + " WHERE id = " + documentId);
        if (migrated) {
            assertThat(single(connection, "SELECT template_version_id FROM document_revision WHERE id = " + firstRevisionId))
                    .isEqualTo(firstVersionId);
        }
        return new Seed(userId, workspaceId, templateId, firstVersionId, secondVersionId, draftVersionId, otherTemplateId,
                otherVersionId, documentId, firstRevisionId);
    }

    private static long insertPlainVersion(
            Connection connection, Seed seed, long artifactId, long extractionId, long templateId, int number, String status)
            throws SQLException {
        return returningId(connection, """
                INSERT INTO template_version (
                    workspace_id, template_id, version_number, source_artifact_id, extraction_version_id, status, field_definitions, activated_at)
                VALUES (%d, %d, %d, %d, %d, '%s', '[]'::jsonb, %s) RETURNING id
                """.formatted(seed.workspaceId(), templateId, number, artifactId, extractionId, status,
                "ACTIVATED".equals(status) ? "now()" : "NULL"));
    }

    /** {@code lineage} is the SQL for the three lineage columns, in order. */
    private static long insertVersion(Connection connection, Seed seed, long templateId, int number, String status, String lineage)
            throws SQLException {
        return returningId(connection, """
                INSERT INTO template_version (
                    workspace_id, template_id, version_number, source_artifact_id, extraction_version_id, status, field_definitions,
                    activated_at, derived_from_version_id, derivation_key, derivation)
                SELECT workspace_id, %d, %d, source_artifact_id, extraction_version_id, '%s', '[]'::jsonb, now(), %s
                FROM template_version WHERE id = %d
                RETURNING id
                """.formatted(templateId, number, status, lineage, seed.firstVersionId()));
    }

    private static long insertRevision(Connection api, Seed seed, int number, long parentId, Long templateVersionId) throws SQLException {
        return returningId(api, """
                INSERT INTO document_revision (
                    workspace_id, document_id, revision_number, parent_revision_id, content, content_hash, actor_user_id, edit_reason,
                    template_version_id)
                VALUES (%d, %d, %d, %d, '{}'::jsonb, '%s', %d, 'Next fixture revision', %s) RETURNING id
                """.formatted(seed.workspaceId(), seed.documentId(), number, parentId, "c".repeat(64), seed.userId(),
                templateVersionId == null ? "NULL" : templateVersionId.toString()));
    }

    private static boolean advance(Connection api, Seed seed, long expectedRevisionId, long nextRevisionId) throws SQLException {
        try (PreparedStatement statement = api.prepareStatement("SELECT advance_document_current_revision(?, ?, ?, ?)")) {
            statement.setLong(1, seed.workspaceId());
            statement.setLong(2, seed.documentId());
            statement.setLong(3, expectedRevisionId);
            statement.setLong(4, nextRevisionId);
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getBoolean(1);
            }
        }
    }

    /** The application's own login, acting for {@code userId} in one open transaction, with row-level security in force. */
    private static Connection apiConnection(long userId) throws SQLException {
        Connection connection = DriverManager.getConnection(DB.jdbcUrl(), "brownie_api", API_PASSWORD);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT set_config('app.current_user_id', '" + userId + "', true)").close();
        }
        return connection;
    }

    private static long returningId(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet results = statement.executeQuery(sql)) {
            results.next();
            return results.getLong(1);
        }
    }

    private static long single(Connection connection, String sql) throws SQLException {
        return returningId(connection, sql);
    }

    private static java.util.List<Long> longs(Connection connection, String sql) throws SQLException {
        java.util.List<Long> values = new java.util.ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet results = statement.executeQuery(sql)) {
            while (results.next()) {
                values.add(results.getLong(1));
            }
        }
        return values;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
