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
 * document_revision_field_state} directly against the real, non-bypassing
 * runtime role -- a raw query as brownie_api, bypassing {@code
 * JdbcDocumentRepository} entirely -- the same standard {@code
 * EvidenceAndCompilationRowLevelSecurityTest} already holds this
 * codebase's other per-revision tenant tables to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class DocumentRevisionFieldStateRowLevelSecurityTest {

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
    void aDifferentWorkspacesMemberCannotReadFieldStateRowsAndCannotInsertIntoAnotherWorkspace() throws SQLException {
        UserIdentity owner = newUser("field-state-rls-owner");
        UserIdentity intruder = newUser("field-state-rls-intruder");
        Workspace ownerWorkspace = workspaceRepository.ensurePersonalWorkspace(owner.id());
        workspaceRepository.ensurePersonalWorkspace(intruder.id());
        TemplateVersion templateVersion = newActiveTemplate(ownerWorkspace.id(), owner.id());
        Document document = createDocument(ownerWorkspace.id(), owner.id(), templateVersion);
        DocumentRevision revision = revisionService
                .findRevision(ownerWorkspace.id(), owner.id(), document.id(), document.currentRevisionId())
                .orElseThrow();

        // A different workspace's member sees no field-state rows for the owner's document at all.
        assertThat(countFieldStateRowsAsUser(intruder.id(), document.id(), revision.id())).isZero();
        // The owner themself still can -- one row per field this template declares.
        assertThat(countFieldStateRowsAsUser(owner.id(), document.id(), revision.id())).isEqualTo(2);

        // A different workspace's member cannot insert a field-state row naming the owner's own workspace.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, intruder.id());
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO document_revision_field_state
                        (workspace_id, document_id, revision_id, field_id, item_index,
                         authorship, evidence_support, validation, review, lock_state)
                    VALUES (?, ?, ?, 'meeting.title', NULL, 'USER_AUTHORED', 'MISSING', 'NOT_RUN', 'UNREVIEWED', 'EDITABLE')
                    """)) {
                statement.setLong(1, ownerWorkspace.id());
                statement.setLong(2, document.id());
                statement.setLong(3, revision.id());
                assertThrows(SQLException.class, statement::executeUpdate);
            }
            connection.rollback();
        }
    }

    private int countFieldStateRowsAsUser(long userId, long documentId, long revisionId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM document_revision_field_state WHERE document_id = ? AND revision_id = ?")) {
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

    private TemplateVersion newActiveTemplate(long workspaceId, long userId) {
        long artifactId = insertArtifact(workspaceId, userId);
        ExtractionVersion extraction = extractionVersionRepository.saveComplete(
                workspaceId, userId, artifactId, "field-state-rls-test", templateGraph());
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
                statement.setString(2, "field-state-rls-test-" + UUID.randomUUID());
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
                "field-state-rls-test",
                List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }

    private UserIdentity newUser(String subjectPrefix) {
        return userIdentityRepository.recordLogin(
                "https://issuer-field-state-rls-tests", subjectPrefix + "-" + UUID.randomUUID(), null, null);
    }

    private static void setLocalContext(Connection connection, long userId) throws SQLException {
        try (PreparedStatement setContext =
                connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            setContext.setString(1, String.valueOf(userId));
            setContext.executeQuery();
        }
    }
}
