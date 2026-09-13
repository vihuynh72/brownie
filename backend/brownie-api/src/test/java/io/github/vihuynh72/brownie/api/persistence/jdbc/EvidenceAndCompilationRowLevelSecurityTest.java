package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.DocumentRevision;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the row-level security policies on {@code
 * document_revision_field_evidence} and {@code document_compilation}
 * directly against the real, non-bypassing runtime role -- a raw query as
 * brownie_api, bypassing {@code JdbcDocumentRepository}/{@code
 * JdbcCompilationRepository} entirely -- the same standard {@code
 * ArtifactRowLevelSecurityTest} and {@code WorkspaceRowLevelSecurityTest}
 * already hold every other tenant table to. Every existing test for these
 * two tables went through the application layer's own workspace-scoped
 * queries; this is the first to exercise Postgres's own policy evaluation
 * directly, so a dropped, mistyped, or overly permissive policy on either
 * table would fail here even if it happened to still pass every
 * application-level test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class EvidenceAndCompilationRowLevelSecurityTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";

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
        return Path.of("").toAbsolutePath().getParent().getParent().resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private RevisionService revisionService;

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private ExtractionVersionRepository extractionVersionRepository;

    @Test
    void aDifferentWorkspacesMemberCannotReadEvidenceRowsAndCannotInsertIntoAnotherWorkspace() throws SQLException {
        UserIdentity owner = newUser("evidence-rls-owner");
        UserIdentity intruder = newUser("evidence-rls-intruder");
        Workspace ownerWorkspace = workspaceRepository.ensurePersonalWorkspace(owner.id());
        workspaceRepository.ensurePersonalWorkspace(intruder.id());
        TemplateVersion templateVersion = newActiveTemplate(ownerWorkspace.id(), owner.id());
        long spanId = insertSourceSpan(ownerWorkspace.id(), owner.id());
        Document document = createDocumentWithEvidence(ownerWorkspace.id(), owner.id(), templateVersion, spanId);
        DocumentRevision revision = revisionService
                .findRevision(ownerWorkspace.id(), owner.id(), document.id(), document.currentRevisionId())
                .orElseThrow();

        // A different workspace's member sees no evidence rows for the owner's document at all.
        assertThat(countEvidenceRowsAsUser(intruder.id(), document.id(), revision.id())).isZero();
        // The owner themself still can.
        assertThat(countEvidenceRowsAsUser(owner.id(), document.id(), revision.id())).isEqualTo(1);

        // A different workspace's member cannot insert an evidence row naming the owner's own workspace.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, intruder.id());
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO document_revision_field_evidence
                        (workspace_id, document_id, revision_id, field_id, source_span_id)
                    VALUES (?, ?, ?, 'meeting.title', ?)
                    """)) {
                statement.setLong(1, ownerWorkspace.id());
                statement.setLong(2, document.id());
                statement.setLong(3, revision.id());
                statement.setLong(4, spanId);
                assertThrows(SQLException.class, statement::executeUpdate);
            }
            connection.rollback();
        }
    }

    @Test
    void aDifferentWorkspacesMemberCannotReadOrInsertCompilationRows() throws SQLException {
        UserIdentity owner = newUser("compilation-rls-owner");
        UserIdentity intruder = newUser("compilation-rls-intruder");
        Workspace ownerWorkspace = workspaceRepository.ensurePersonalWorkspace(owner.id());
        workspaceRepository.ensurePersonalWorkspace(intruder.id());
        TemplateVersion templateVersion = newActiveTemplate(ownerWorkspace.id(), owner.id());
        Document document = createDocument(ownerWorkspace.id(), owner.id(), templateVersion);
        DocumentRevision revision = revisionService
                .findRevision(ownerWorkspace.id(), owner.id(), document.id(), document.currentRevisionId())
                .orElseThrow();
        long docxArtifactId = insertArtifact(ownerWorkspace.id(), owner.id());
        long pdfArtifactId = insertArtifact(ownerWorkspace.id(), owner.id());
        long compilationId = insertCompilation(
                ownerWorkspace.id(), owner.id(), document, revision, templateVersion, docxArtifactId, pdfArtifactId);

        assertThat(selectCompilationIdAsUser(intruder.id(), compilationId)).isNull();
        assertThat(selectCompilationIdAsUser(owner.id(), compilationId)).isEqualTo(compilationId);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, intruder.id());
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO document_compilation
                        (workspace_id, document_id, revision_id, template_id, template_version_id,
                         docx_artifact_id, docx_sha256, pdf_artifact_id, pdf_sha256, renderer_version, integrity_findings)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'test', '[]'::jsonb)
                    """)) {
                statement.setLong(1, ownerWorkspace.id());
                statement.setLong(2, document.id());
                statement.setLong(3, revision.id());
                statement.setLong(4, document.templateId());
                statement.setLong(5, templateVersion.id());
                statement.setLong(6, docxArtifactId);
                statement.setString(7, "a".repeat(64));
                statement.setLong(8, pdfArtifactId);
                statement.setString(9, "b".repeat(64));
                assertThrows(SQLException.class, statement::executeUpdate);
            }
            connection.rollback();
        }
    }

    private int countEvidenceRowsAsUser(long userId, long documentId, long revisionId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM document_revision_field_evidence WHERE document_id = ? AND revision_id = ?")) {
                statement.setLong(1, documentId);
                statement.setLong(2, revisionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    int count = resultSet.getInt(1);
                    connection.rollback();
                    return count;
                }
            }
        }
    }

    private Long selectCompilationIdAsUser(long userId, long compilationId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT id FROM document_compilation WHERE id = ?")) {
                statement.setLong(1, compilationId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    Long result = resultSet.next() ? resultSet.getLong(1) : null;
                    connection.rollback();
                    return result;
                }
            }
        }
    }

    private long insertCompilation(
            long workspaceId,
            long userId,
            Document document,
            DocumentRevision revision,
            TemplateVersion templateVersion,
            long docxArtifactId,
            long pdfArtifactId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO document_compilation
                        (workspace_id, document_id, revision_id, template_id, template_version_id,
                         docx_artifact_id, docx_sha256, pdf_artifact_id, pdf_sha256, renderer_version, integrity_findings)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'test', '[]'::jsonb)
                    RETURNING id
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, document.id());
                statement.setLong(3, revision.id());
                statement.setLong(4, document.templateId());
                statement.setLong(5, templateVersion.id());
                statement.setLong(6, docxArtifactId);
                statement.setString(7, "a".repeat(64));
                statement.setLong(8, pdfArtifactId);
                statement.setString(9, "b".repeat(64));
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    long id = resultSet.getLong(1);
                    connection.commit();
                    return id;
                }
            }
        }
    }

    private Document createDocument(long workspaceId, long userId, TemplateVersion templateVersion) {
        return revisionService.createDocument(
                workspaceId,
                userId,
                key("create-" + UUID.randomUUID()),
                hash("create-" + UUID.randomUUID()),
                "Minutes",
                templateVersion.templateId(),
                templateVersion.id(),
                content("September minutes", LocalDate.of(2026, 9, 1)),
                Map.of(),
                "initial draft").document();
    }

    private Document createDocumentWithEvidence(long workspaceId, long userId, TemplateVersion templateVersion, long spanId) {
        return revisionService.createDocument(
                workspaceId,
                userId,
                key("create-" + UUID.randomUUID()),
                hash("create-" + UUID.randomUUID()),
                "Minutes",
                templateVersion.templateId(),
                templateVersion.id(),
                content("September minutes", LocalDate.of(2026, 9, 1)),
                Map.of("meeting.title", List.of(spanId)),
                "initial draft").document();
    }

    private TemplateVersion newActiveTemplate(long workspaceId, long userId) {
        long artifactId = insertArtifact(workspaceId, userId);
        ExtractionVersion extraction = extractionVersionRepository.saveComplete(
                workspaceId, userId, artifactId, "evidence-compilation-rls-test", templateGraph());
        Template template = templateRepository.createDraft(workspaceId, userId, "Minutes", artifactId, extraction.id());
        templateRepository.replaceDraftBindings(
                workspaceId,
                userId,
                template.id(),
                1,
                List.of(
                        field("meeting.title", FieldType.TEXT, FieldCardinality.SCALAR),
                        field("meeting.date", FieldType.DATE, FieldCardinality.SCALAR)));
        return templateRepository.activate(workspaceId, userId, template.id(), 2);
    }

    private long insertArtifact(long workspaceId, long userId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO artifact (workspace_id, blob_key, status, byte_count, detected_media_type)
                    VALUES (?, ?, 'READY', 100, 'DOCX')
                    RETURNING id
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setString(2, "evidence-compilation-rls-test-" + UUID.randomUUID());
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    long id = resultSet.getLong(1);
                    connection.commit();
                    return id;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private long insertSourceSpan(long workspaceId, long userId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            long artifactId;
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO artifact (workspace_id, blob_key, status, byte_count, detected_media_type)
                    VALUES (?, ?, 'READY', 42, 'PLAIN_TEXT')
                    RETURNING id
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setString(2, "evidence-compilation-rls-test-" + UUID.randomUUID());
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    artifactId = resultSet.getLong(1);
                }
            }
            long snapshotId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO source_snapshot (workspace_id, artifact_id, kind) VALUES (?, ?, 'PLAIN_TEXT') RETURNING id")) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, artifactId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    snapshotId = resultSet.getLong(1);
                }
            }
            long spanId;
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO source_span (workspace_id, source_snapshot_id, extraction_parser_version, locator, excerpt_hash)
                    VALUES (?, ?, 'test-parser-1', ?::jsonb, ?)
                    RETURNING id
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, snapshotId);
                statement.setString(3, "{\"kind\":\"PLAIN_TEXT\",\"startCodePoint\":0,\"endCodePointExclusive\":5}");
                statement.setString(4, "0".repeat(64));
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    spanId = resultSet.getLong(1);
                }
            }
            connection.commit();
            return spanId;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static DocumentContent content(String title, LocalDate date) {
        return new DocumentContent(Map.of(
                "meeting.title", new FieldValue.TextValue(title),
                "meeting.date", new FieldValue.DateValue(date)));
    }

    private static IdempotencyKey key(String value) {
        return new IdempotencyKey(value);
    }

    private static CanonicalRequestHash hash(String value) {
        return CanonicalRequestHash.sha256OfCanonicalText(value);
    }

    private static FieldDefinition field(String fieldId, FieldType type, FieldCardinality cardinality) {
        return new FieldDefinition(
                fieldId, type, cardinality, FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag(fieldId));
    }

    private static DocxStructuralGraph templateGraph() {
        StructuralNode title = new StructuralNode(
                "p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.title", null, List.of());
        StructuralNode date = new StructuralNode(
                "p1/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.date", null, List.of());
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(title, date));
        return new DocxStructuralGraph(
                "evidence-compilation-rls-test",
                List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }

    private UserIdentity newUser(String subjectPrefix) {
        return userIdentityRepository.recordLogin(
                "https://issuer-evidence-compilation-rls-tests", subjectPrefix + "-" + UUID.randomUUID(), null, null);
    }

    private static void setLocalContext(Connection connection, long userId) throws SQLException {
        try (PreparedStatement setContext =
                connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            setContext.setString(1, String.valueOf(userId));
            setContext.executeQuery();
        }
    }
}
