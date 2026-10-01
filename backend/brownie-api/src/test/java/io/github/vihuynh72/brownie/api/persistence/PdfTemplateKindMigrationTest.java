package io.github.vihuynh72.brownie.api.persistence;

import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the PDF template kind migration promises about rows, checked with
 * the rows themselves: a template version is Word or PDF and is pinned to
 * the reading of its own kind only; an existing Word version reads as
 * Word; and what is made from a template may leave out the Word file only
 * as a whole (file and hash together) and only while it still names a PDF.
 * A version may keep the notes from its upload, always as a list.
 * Rows are written as the database superuser, so row-level security is out of
 * the way and only the constraints decide.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DockerTest
class PdfTemplateKindMigrationTest {

    private static final String SHA = "a".repeat(64);

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> "brownie_api_local_only");
        registry.add("spring.flyway.url", DB::jdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> "brownie_migration_local_only");
    }

    @Test
    void aTemplateVersionIsPinnedToTheReadingOfItsOwnKindOnly() throws SQLException {
        try (Connection connection = DB.superuserConnection()) {
            Fixture fixture = fixture(connection);

            long word = insertVersion(connection, fixture, 1, null, fixture.docxExtractionId, null);
            assertThat(kindOf(connection, word)).isEqualTo("DOCX");
            long pdf = insertVersion(connection, fixture, 2, "PDF", null, fixture.pdfFormExtractionId);
            assertThat(kindOf(connection, pdf)).isEqualTo("PDF");

            assertRefused(connection, fixture, 3, "PDF", fixture.docxExtractionId, null);
            assertRefused(connection, fixture, 3, "PDF", fixture.docxExtractionId, fixture.pdfFormExtractionId);
            assertRefused(connection, fixture, 3, "DOCX", null, fixture.pdfFormExtractionId);
            assertRefused(connection, fixture, 3, "DOCX", null, null);
            assertRefused(connection, fixture, 3, "XLSX", fixture.docxExtractionId, null);
        }
    }

    @Test
    void aVersionKeepsTheNotesFromItsUploadAsAListOrNoneAtAll() throws SQLException {
        try (Connection connection = DB.superuserConnection()) {
            Fixture fixture = fixture(connection);
            long before = insertVersion(connection, fixture, 1, null, fixture.docxExtractionId, null);
            long after = insertVersion(connection, fixture, 2, null, fixture.docxExtractionId, null);

            assertThat(noticesOf(connection, before)).as("a version from before notes were kept").isNull();
            execute(connection, "UPDATE template_version SET preparation_notices = '[]'::jsonb WHERE id = " + before);
            execute(connection, "UPDATE template_version SET preparation_notices ="
                    + " '[{\"code\":\"PLACES_LEFT_OUT\",\"count\":2}]'::jsonb WHERE id = " + after);
            assertThat(noticesOf(connection, before)).isEqualTo("[]");
            assertThat(noticesOf(connection, after)).isEqualTo("[{\"code\": \"PLACES_LEFT_OUT\", \"count\": 2}]");

            for (String notList : new String[] {"'{}'::jsonb", "'\"PLACES_LEFT_OUT\"'::jsonb", "'null'::jsonb"}) {
                assertThatThrownBy(() -> execute(connection,
                        "UPDATE template_version SET preparation_notices = " + notList + " WHERE id = " + before))
                        .as(notList)
                        .hasMessageContaining("template_version_preparation_notices_array");
            }
        }
    }

    @Test
    void whatIsMadeFromATemplateLeavesOutTheWordFileOnlyAsAWholeAndOnlyWithAPdf() throws SQLException {
        try (Connection connection = DB.superuserConnection()) {
            Fixture fixture = fixture(connection);
            long versionId = insertVersion(connection, fixture, 1, "PDF", null, fixture.pdfFormExtractionId);
            long documentId = insertDocument(connection, fixture, versionId);
            long revisionId = firstRevision(connection, documentId);
            long pdfArtifact = insertArtifact(connection, fixture.workspaceId);

            execute(connection, "INSERT INTO template_baseline_render (workspace_id, template_version_id, docx_artifact_id,"
                    + " pdf_artifact_id, renderer_version) VALUES (" + fixture.workspaceId + ", " + versionId + ", NULL, "
                    + pdfArtifact + ", 'filler')");
            execute(connection, compilation(fixture, documentId, revisionId, versionId, "NULL, NULL", pdfArtifact));
            assertThatThrownBy(() -> execute(connection,
                    compilation(fixture, documentId, revisionId, versionId, "NULL, '" + SHA + "'", pdfArtifact)))
                    .hasMessageContaining("document_compilation_docx_columns_together");

            long manifestId = queryLong(connection, manifest(fixture, documentId, revisionId, versionId, "NULL, NULL", pdfArtifact + ", '" + SHA + "'")
                    + " RETURNING id");
            assertThatThrownBy(() -> execute(connection,
                    manifest(fixture, documentId, revisionId, versionId, "NULL, NULL", "NULL, NULL")))
                    .hasMessageContaining("validation_manifest_names_a_file");
            assertThatThrownBy(() -> execute(connection,
                    manifest(fixture, documentId, revisionId, versionId, pdfArtifact + ", NULL", pdfArtifact + ", '" + SHA + "'")))
                    .hasMessageContaining("validation_manifest_docx_columns_together");

            long approvalId = queryLong(connection, "INSERT INTO export_approval (workspace_id, document_id, revision_id,"
                    + " template_version_id, validation_manifest_id, format, actor_user_id) VALUES (" + fixture.workspaceId + ", "
                    + documentId + ", " + revisionId + ", " + versionId + ", " + manifestId + ", 'PDF', " + fixture.userId + ") RETURNING id");
            execute(connection, receipt(fixture, documentId, revisionId, versionId, approvalId, manifestId, "NULL, NULL",
                    pdfArtifact + ", '" + SHA + "'"));
            assertThatThrownBy(() -> execute(connection, receipt(fixture, documentId, revisionId, versionId, approvalId, manifestId,
                    "NULL, NULL", "NULL, NULL")))
                    .hasMessageContaining("export_receipt_names_a_file");
            assertThatThrownBy(() -> execute(connection, receipt(fixture, documentId, revisionId, versionId, approvalId, manifestId,
                    "NULL, '" + SHA + "'", pdfArtifact + ", '" + SHA + "'")))
                    .hasMessageContaining("export_receipt_docx_columns_together");
        }
    }

    private record Fixture(long userId, long workspaceId, long templateId, long artifactId, long docxExtractionId, long pdfFormExtractionId) {
    }

    private static Fixture fixture(Connection connection) throws SQLException {
        String subject = "subject-" + UUID.randomUUID();
        long userId = queryLong(connection,
                "INSERT INTO user_identity (issuer, subject) VALUES ('https://issuer-pdf-kind-migration', '" + subject + "') RETURNING id");
        long workspaceId = queryLong(connection,
                "INSERT INTO workspace (owner_user_id) VALUES (" + userId + ") RETURNING id");
        execute(connection, "INSERT INTO workspace_member (workspace_id, user_id, role, state) VALUES (" + workspaceId + ", " + userId + ", 'OWNER', 'ACTIVE')");
        long artifactId = insertArtifact(connection, workspaceId);
        long templateId = queryLong(connection, "INSERT INTO template (workspace_id, display_name) VALUES (" + workspaceId + ", 't') RETURNING id");
        long docxExtractionId = queryLong(connection, "INSERT INTO extraction_version (workspace_id, artifact_id, parser_version, status)"
                + " VALUES (" + workspaceId + ", " + artifactId + ", 'docx', 'COMPLETE') RETURNING id");
        long pdfFormExtractionId = queryLong(connection, "INSERT INTO pdf_form_extraction_version (workspace_id, artifact_id,"
                + " parser_version, status, graph) VALUES (" + workspaceId + ", " + artifactId + ", 'form', 'COMPLETE', '{}'::jsonb) RETURNING id");
        return new Fixture(userId, workspaceId, templateId, artifactId, docxExtractionId, pdfFormExtractionId);
    }

    private static long insertVersion(Connection connection, Fixture fixture, int number, String kind, Long docxExtractionId, Long pdfFormExtractionId)
            throws SQLException {
        String columns = "workspace_id, template_id, version_number, source_artifact_id, extraction_version_id, pdf_form_extraction_id,"
                + " status, activated_at" + (kind == null ? "" : ", kind");
        String values = fixture.workspaceId + ", " + fixture.templateId + ", " + number + ", " + fixture.artifactId + ", "
                + docxExtractionId + ", " + pdfFormExtractionId + ", 'ACTIVATED', now()" + (kind == null ? "" : ", '" + kind + "'");
        return queryLong(connection, "INSERT INTO template_version (" + columns + ") VALUES (" + values + ") RETURNING id");
    }

    private static void assertRefused(Connection connection, Fixture fixture, int number, String kind, Long docxExtractionId, Long pdfFormExtractionId)
            throws SQLException {
        connection.setAutoCommit(false);
        try {
            assertThatThrownBy(() -> insertVersion(connection, fixture, number, kind, docxExtractionId, pdfFormExtractionId))
                    .as(kind + " " + docxExtractionId + " " + pdfFormExtractionId)
                    .hasMessageContaining("check constraint");
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    private static String kindOf(Connection connection, long versionId) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT kind FROM template_version WHERE id = " + versionId)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static String noticesOf(Connection connection, long versionId) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT preparation_notices FROM template_version WHERE id = " + versionId)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static long insertDocument(Connection connection, Fixture fixture, long versionId) throws SQLException {
        long documentId = queryLong(connection, "INSERT INTO document (workspace_id, template_id, template_version_id, title)"
                + " VALUES (" + fixture.workspaceId + ", " + fixture.templateId + ", " + versionId + ", 'd') RETURNING id");
        long revisionId = queryLong(connection, "INSERT INTO document_revision (workspace_id, document_id, revision_number, content,"
                + " content_hash, actor_user_id, edit_reason) VALUES (" + fixture.workspaceId + ", " + documentId + ", 1, '{}'::jsonb, '"
                + SHA + "', " + fixture.userId + ", 'first') RETURNING id");
        execute(connection, "UPDATE document SET current_revision_id = " + revisionId + " WHERE id = " + documentId);
        return documentId;
    }

    private static long firstRevision(Connection connection, long documentId) throws SQLException {
        return queryLong(connection, "SELECT current_revision_id FROM document WHERE id = " + documentId);
    }

    private static String compilation(Fixture fixture, long documentId, long revisionId, long versionId, String docx, long pdfArtifact) {
        return "INSERT INTO document_compilation (workspace_id, document_id, revision_id, template_id, template_version_id,"
                + " docx_artifact_id, docx_sha256, pdf_artifact_id, pdf_sha256, renderer_version, integrity_findings)"
                + " VALUES (" + fixture.workspaceId + ", " + documentId + ", " + revisionId + ", " + fixture.templateId + ", " + versionId
                + ", " + docx + ", " + pdfArtifact + ", '" + SHA + "', 'filler', '[]'::jsonb)";
    }

    private static String manifest(Fixture fixture, long documentId, long revisionId, long versionId, String docx, String pdf) {
        return "INSERT INTO validation_manifest (workspace_id, document_id, revision_id, template_id, template_version_id,"
                + " docx_artifact_id, docx_sha256, pdf_artifact_id, pdf_sha256, findings) VALUES ("
                + fixture.workspaceId + ", " + documentId + ", " + revisionId + ", " + fixture.templateId + ", " + versionId + ", "
                + docx + ", " + pdf + ", '[]'::jsonb)";
    }

    private static String receipt(
            Fixture fixture, long documentId, long revisionId, long versionId, long approvalId, long manifestId, String docx, String pdf) {
        return "INSERT INTO export_receipt (workspace_id, document_id, revision_id, template_version_id, export_approval_id,"
                + " validation_manifest_id, docx_artifact_id, docx_sha256, pdf_artifact_id, pdf_sha256, format, actor_user_id)"
                + " VALUES (" + fixture.workspaceId + ", " + documentId + ", " + revisionId + ", " + versionId + ", " + approvalId + ", "
                + manifestId + ", " + docx + ", " + pdf + ", 'PDF', " + fixture.userId + ")";
    }

    private static long insertArtifact(Connection connection, long workspaceId) throws SQLException {
        return queryLong(connection, "INSERT INTO artifact (workspace_id, blob_key, status, byte_count, detected_media_type) VALUES ("
                + workspaceId + ", 'blob-" + UUID.randomUUID() + "', 'READY', 100, 'PDF') RETURNING id");
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
