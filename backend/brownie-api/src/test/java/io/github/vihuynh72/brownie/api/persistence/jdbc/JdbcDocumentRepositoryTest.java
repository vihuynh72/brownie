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
import io.github.vihuynh72.brownie.core.revision.DocumentCommandType;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.DocumentIdempotencyConflictException;
import io.github.vihuynh72.brownie.core.revision.DocumentMutationResult;
import io.github.vihuynh72.brownie.core.revision.DocumentRepository;
import io.github.vihuynh72.brownie.core.revision.DocumentRevision;
import io.github.vihuynh72.brownie.core.revision.DocumentRevisionConflictException;
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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Uses the real non-bypassing runtime role and Flyway schema to prove that
 * revision history is append-only, tenant-scoped, and guarded by the
 * document pointer rather than a best-effort application check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class JdbcDocumentRepositoryTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String WORKER_PASSWORD = "brownie_worker_local_only";

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
        return Path.of("").toAbsolutePath()
                .getParent()
                .getParent()
                .resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    @Autowired
    private RevisionService revisionService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private ExtractionVersionRepository extractionVersionRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void staleDirectAppendFailsWithoutAnOrphanAndLeavesEarlierContentUnchanged() {
        UserIdentity user = newUser("stale-append");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        TemplateVersion templateVersion = newActiveTemplate(workspace.id(), user.id());
        Document document = createDocument(workspace.id(), user.id(), templateVersion);
        DocumentRevision initial = documentRepository.findCurrentRevision(workspace.id(), user.id(), document.id()).orElseThrow();
        DocumentContent firstEditContent = content("October minutes", LocalDate.of(2026, 10, 1));

        DocumentRevision firstEdit = documentRepository.appendRevisionIdempotently(
                workspace.id(),
                user.id(),
                key("first-edit"),
                hash("first-edit"),
                document.id(),
                initial.id(),
                firstEditContent,
                Map.of(),
                "corrected title").revision();

        assertThrows(
                DocumentRevisionConflictException.class,
                () -> documentRepository.appendRevisionIdempotently(
                        workspace.id(),
                        user.id(),
                        key("stale-edit"),
                        hash("stale-edit"),
                        document.id(),
                        initial.id(),
                        content("stale title", LocalDate.of(2026, 10, 1)),
                        Map.of(),
                        "stale edit"));

        List<DocumentRevision> history = documentRepository.findHistory(workspace.id(), user.id(), document.id());
        assertThat(history).containsExactly(initial, firstEdit);
        assertThat(((FieldValue.TextValue) history.getFirst().content().fields().get("meeting.title")).value())
                .isEqualTo("September minutes");
        assertThat(documentRepository.findCurrentRevision(workspace.id(), user.id(), document.id()).orElseThrow().id())
                .isEqualTo(firstEdit.id());
    }

    @Test
    void matchingCreateRetryReturnsTheOriginalDocumentAndRevisionWithoutAnotherInsert() throws SQLException {
        UserIdentity user = newUser("create-retry");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        TemplateVersion templateVersion = newActiveTemplate(workspace.id(), user.id());
        IdempotencyKey idempotencyKey = key("create-document");
        CanonicalRequestHash requestHash = hash("create-document-body");

        DocumentMutationResult first = revisionService.createDocument(
                workspace.id(),
                user.id(),
                idempotencyKey,
                requestHash,
                "Minutes",
                templateVersion.templateId(),
                templateVersion.id(),
                content("September minutes", LocalDate.of(2026, 9, 1)),
                Map.of(),
                "initial draft");
        DocumentMutationResult replay = revisionService.createDocument(
                workspace.id(),
                user.id(),
                idempotencyKey,
                requestHash,
                "Minutes",
                templateVersion.templateId(),
                templateVersion.id(),
                content("September minutes", LocalDate.of(2026, 9, 1)),
                Map.of(),
                "initial draft");

        assertThat(replay.commandId()).isEqualTo(first.commandId());
        assertThat(replay.document().id()).isEqualTo(first.document().id());
        assertThat(replay.revision().id()).isEqualTo(first.revision().id());
        assertThat(countAsMember(user.id(), "SELECT count(*) FROM document WHERE workspace_id = " + workspace.id()))
                .isEqualTo(1);
        assertThat(countAsMember(user.id(), "SELECT count(*) FROM document_revision WHERE workspace_id = " + workspace.id()))
                .isEqualTo(1);
        assertThat(countAsMember(user.id(), "SELECT count(*) FROM document_command_receipt WHERE workspace_id = " + workspace.id()))
                .isEqualTo(1);

        assertThrows(
                DocumentIdempotencyConflictException.class,
                () -> revisionService.createDocument(
                        workspace.id(),
                        user.id(),
                        idempotencyKey,
                        hash("different-create-body"),
                        "Changed Minutes",
                        templateVersion.templateId(),
                        templateVersion.id(),
                        content("September minutes", LocalDate.of(2026, 9, 1)),
                        Map.of(),
                        "initial draft"));
    }

    @Test
    void matchingEditRetryWinsOverAStalePointerAndDoesNotAppendAgain() {
        UserIdentity user = newUser("edit-retry");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        Document document = createDocument(workspace.id(), user.id(), newActiveTemplate(workspace.id(), user.id()));
        DocumentRevision initial = documentRepository.findCurrentRevision(workspace.id(), user.id(), document.id()).orElseThrow();
        IdempotencyKey firstKey = key("first-edit");
        CanonicalRequestHash firstHash = hash("first-edit-body");

        DocumentMutationResult first = documentRepository.appendRevisionIdempotently(
                workspace.id(),
                user.id(),
                firstKey,
                firstHash,
                document.id(),
                initial.id(),
                content("October minutes", LocalDate.of(2026, 10, 1)),
                Map.of(),
                "first edit");
        DocumentMutationResult later = documentRepository.appendRevisionIdempotently(
                workspace.id(),
                user.id(),
                key("later-edit"),
                hash("later-edit-body"),
                document.id(),
                first.revision().id(),
                content("November minutes", LocalDate.of(2026, 11, 1)),
                Map.of(),
                "later edit");
        DocumentMutationResult replay = documentRepository.appendRevisionIdempotently(
                workspace.id(),
                user.id(),
                firstKey,
                firstHash,
                document.id(),
                initial.id(),
                content("October minutes", LocalDate.of(2026, 10, 1)),
                Map.of(),
                "first edit");

        assertThat(replay.commandId()).isEqualTo(first.commandId());
        assertThat(replay.revision().id()).isEqualTo(first.revision().id());
        assertThat(documentRepository.findHistory(workspace.id(), user.id(), document.id()))
                .extracting(DocumentRevision::id)
                .containsExactly(initial.id(), first.revision().id(), later.revision().id());
        assertThrows(
                DocumentIdempotencyConflictException.class,
                () -> documentRepository.appendRevisionIdempotently(
                        workspace.id(),
                        user.id(),
                        firstKey,
                        hash("different-first-edit"),
                        document.id(),
                        initial.id(),
                        content("Changed", LocalDate.of(2026, 10, 1)),
                        Map.of(),
                        "different edit"));
    }

    @Test
    void runtimeRoleCannotUpdateDocumentRowsAndThePointerFunctionRejectsAStaleExpectedRevision() throws SQLException {
        UserIdentity user = newUser("pointer-guard");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        Document document = createDocument(workspace.id(), user.id(), newActiveTemplate(workspace.id(), user.id()));
        DocumentRevision initial = documentRepository.findCurrentRevision(workspace.id(), user.id(), document.id()).orElseThrow();
        DocumentMutationResult firstEdit = documentRepository.appendRevisionIdempotently(
                workspace.id(),
                user.id(),
                key("pointer-guard-edit"),
                hash("pointer-guard-edit"),
                document.id(),
                initial.id(),
                content("October minutes", LocalDate.of(2026, 10, 1)),
                Map.of(),
                "first edit");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, user.id());
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE document SET current_revision_id = ? WHERE workspace_id = ? AND id = ?")) {
                statement.setLong(1, initial.id());
                statement.setLong(2, workspace.id());
                statement.setLong(3, document.id());
                SQLException blocked = assertThrows(SQLException.class, statement::executeUpdate);
                assertThat(blocked.getSQLState()).isEqualTo("42501");
            }
            connection.rollback();
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, user.id());
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT advance_document_current_revision(?, ?, ?, ?)")) {
                statement.setLong(1, workspace.id());
                statement.setLong(2, document.id());
                statement.setLong(3, initial.id());
                statement.setLong(4, firstEdit.revision().id());
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getBoolean(1)).isFalse();
                }
            }
            connection.commit();
        }

        assertThat(documentRepository.findCurrentRevision(workspace.id(), user.id(), document.id()).orElseThrow().id())
                .isEqualTo(firstEdit.revision().id());
    }

    @Test
    void immutableRevisionRowsRejectARealRuntimeRoleUpdate() throws SQLException {
        UserIdentity user = newUser("immutable-row");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        Document document = createDocument(workspace.id(), user.id(), newActiveTemplate(workspace.id(), user.id()));
        DocumentRevision initial = documentRepository.findCurrentRevision(workspace.id(), user.id(), document.id()).orElseThrow();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, user.id());
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE document_revision SET edit_reason = 'rewritten' WHERE workspace_id = ? AND id = ?")) {
                statement.setLong(1, workspace.id());
                statement.setLong(2, initial.id());
                assertThat(statement.executeUpdate()).isZero();
            }
            connection.commit();
        }

        assertThat(documentRepository.findRevision(workspace.id(), user.id(), document.id(), initial.id()).orElseThrow().editReason())
                .isEqualTo("initial draft");
    }

    @Test
    void parentRevisionCannotComeFromAnotherDocumentInTheSameWorkspace() throws SQLException {
        UserIdentity user = newUser("parent-scope");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        TemplateVersion templateVersion = newActiveTemplate(workspace.id(), user.id());
        Document first = createDocument(workspace.id(), user.id(), templateVersion);
        Document second = createDocument(workspace.id(), user.id(), templateVersion);
        DocumentRevision firstRevision = documentRepository.findCurrentRevision(workspace.id(), user.id(), first.id()).orElseThrow();
        DocumentRevision secondRevision = documentRepository.findCurrentRevision(workspace.id(), user.id(), second.id()).orElseThrow();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, user.id());
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO document_revision
                        (workspace_id, document_id, revision_number, parent_revision_id, content, content_hash, actor_user_id, edit_reason)
                    SELECT ?, ?, 2, ?, content, content_hash, ?, 'invalid parent'
                    FROM document_revision
                    WHERE workspace_id = ? AND document_id = ? AND id = ?
                    """)) {
                statement.setLong(1, workspace.id());
                statement.setLong(2, second.id());
                statement.setLong(3, firstRevision.id());
                statement.setLong(4, user.id());
                statement.setLong(5, workspace.id());
                statement.setLong(6, second.id());
                statement.setLong(7, secondRevision.id());
                statement.executeUpdate();
                fail("expected a parent from another document to violate the composite foreign key");
            } catch (SQLException expected) {
                assertThat(expected.getMessage()).contains("document_revision_parent_fk");
            }
            connection.rollback();
        }
    }

    @Test
    void nonmemberAndWorkerCannotReadAnotherWorkspaceDocument() throws SQLException {
        UserIdentity owner = newUser("private-owner");
        UserIdentity nonmember = newUser("nonmember");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(owner.id());
        Document document = createDocument(workspace.id(), owner.id(), newActiveTemplate(workspace.id(), owner.id()));

        assertThat(documentRepository.find(workspace.id(), nonmember.id(), document.id())).isEmpty();

        try (Connection worker = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_worker", WORKER_PASSWORD)) {
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement statement = worker.prepareStatement("SELECT id FROM document WHERE id = ?")) {
                    statement.setLong(1, document.id());
                    statement.executeQuery();
                }
            });
        }
    }

    @Test
    void evidenceRoundTripsThroughRealPostgresAndRejectsAnotherWorkspacesSpan() {
        UserIdentity owner = newUser("evidence-owner");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(owner.id());
        TemplateVersion templateVersion = newActiveTemplate(workspace.id(), owner.id());
        long titleSpanId = insertSourceSpan(workspace.id(), owner.id());
        long dateSpanId = insertSourceSpan(workspace.id(), owner.id());

        DocumentMutationResult created = revisionService.createDocument(
                workspace.id(),
                owner.id(),
                key("evidence-create"),
                hash("evidence-create"),
                "Minutes",
                templateVersion.templateId(),
                templateVersion.id(),
                content("September minutes", LocalDate.of(2026, 9, 1)),
                Map.of("meeting.title", List.of(titleSpanId), "meeting.date", List.of(dateSpanId)),
                "initial draft");

        assertThat(created.revision().evidence().get("meeting.title")).containsExactly(titleSpanId);
        assertThat(created.revision().evidence().get("meeting.date")).containsExactly(dateSpanId);
        assertThat(documentRepository.findCurrentRevision(workspace.id(), owner.id(), created.document().id())
                        .orElseThrow()
                        .evidence())
                .isEqualTo(created.revision().evidence());

        Workspace otherWorkspace = workspaceRepository.ensurePersonalWorkspace(newUser("evidence-other").id());
        long foreignSpanId = insertSourceSpan(otherWorkspace.id(), otherWorkspace.ownerUserId());

        Exception exception = assertThrows(
                Exception.class,
                () -> revisionService.applyUserEdits(
                        workspace.id(),
                        owner.id(),
                        key("evidence-foreign-span"),
                        hash("evidence-foreign-span"),
                        created.document().id(),
                        created.revision().id(),
                        List.of(new io.github.vihuynh72.brownie.core.revision.DocumentFieldEdit.SetValue(
                                "meeting.title", new FieldValue.TextValue("October minutes"))),
                        Map.of("meeting.title", List.of(foreignSpanId)),
                        "cross-workspace evidence attempt"));
        assertThat(exception).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
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
                workspaceId, userId, artifactId, "document-revision-test", templateGraph());
        Template template = templateRepository.createDraft(workspaceId, userId, "Minutes", artifactId, extraction.id());
        templateRepository.replaceDraftBindings(
                workspaceId,
                userId,
                template.id(),
                1,
                List.of(
                        field("meeting.title", FieldType.TEXT, FieldCardinality.SCALAR),
                        field("meeting.date", FieldType.DATE, FieldCardinality.SCALAR),
                        field("action.tasks", FieldType.TEXT, FieldCardinality.REPEATED)));
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
                statement.setString(2, "document-revision-test-" + UUID.randomUUID());
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    long id = resultSet.getLong(1);
                    connection.commit();
                    return id;
                }
            }
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
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
                statement.setString(2, "document-revision-evidence-test-" + UUID.randomUUID());
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
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
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
                fieldId,
                type,
                cardinality,
                FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.ContentControlTag(fieldId));
    }

    private static DocxStructuralGraph templateGraph() {
        StructuralNode title = new StructuralNode(
                "p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.title", null, List.of());
        StructuralNode date = new StructuralNode(
                "p1/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.date", null, List.of());
        StructuralNode tasks = new StructuralNode(
                "p2/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "action.tasks", null, List.of());
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(title, date, tasks));
        return new DocxStructuralGraph(
                "document-revision-test",
                List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }

    private UserIdentity newUser(String subjectPrefix) {
        return userIdentityRepository.recordLogin(
                "https://issuer-document-revision-tests", subjectPrefix + "-" + UUID.randomUUID(), null, null);
    }

    private long countAsMember(long userId, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(sql);
                    ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                long count = resultSet.getLong(1);
                connection.commit();
                return count;
            }
        }
    }

    private static void setLocalContext(Connection connection, long userId) throws SQLException {
        try (PreparedStatement setContext =
                connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            setContext.setString(1, String.valueOf(userId));
            setContext.executeQuery();
        }
    }
}
