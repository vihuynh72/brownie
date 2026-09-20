package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.export.ExportApproval;
import io.github.vihuynh72.brownie.core.export.ExportApprovalRepository;
import io.github.vihuynh72.brownie.core.export.ExportFormat;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.DocumentMutationResult;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the plain-column round trip and row-level security of {@code export_approval}, mirroring {@code JdbcValidationRepositoryTest}'s own shape. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class JdbcExportApprovalRepositoryTest {

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
        return Path.of("").toAbsolutePath()
                .getParent()
                .getParent()
                .resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    @Autowired
    private ExportApprovalRepository exportApprovalRepository;

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private ExtractionVersionRepository extractionVersionRepository;

    @Autowired
    private RevisionService revisionService;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void savedApprovalRoundTripsEveryColumnAndFindLatestReturnsTheNewestOne() {
        long userId = newUser("subject-export-approval-round-trip").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        TemplateVersion activated = newActivatedTemplateVersion(workspaceId, userId);
        long documentId = newDocument(workspaceId, userId, activated);
        long revisionId = currentRevisionId(workspaceId, userId, documentId);
        long manifestId = insertValidationManifest(workspaceId, userId, documentId, revisionId, activated);

        assertThat(exportApprovalRepository.findLatest(workspaceId, userId, documentId)).isEmpty();

        ExportApproval first = exportApprovalRepository.save(
                workspaceId, userId, documentId, revisionId, activated.id(), manifestId, ExportFormat.BOTH);
        assertThat(first.format()).isEqualTo(ExportFormat.BOTH);
        assertThat(first.revisionId()).isEqualTo(revisionId);
        assertThat(first.templateVersionId()).isEqualTo(activated.id());
        assertThat(first.validationManifestId()).isEqualTo(manifestId);
        assertThat(first.actorUserId()).isEqualTo(userId);

        ExportApproval second = exportApprovalRepository.save(
                workspaceId, userId, documentId, revisionId, activated.id(), manifestId, ExportFormat.DOCX);

        ExportApproval latest = exportApprovalRepository.findLatest(workspaceId, userId, documentId).orElseThrow();
        assertThat(latest.id()).isEqualTo(second.id());
        assertThat(latest.format()).isEqualTo(ExportFormat.DOCX);
    }

    @Test
    void approvingTheLatestApprovalAgainIsOneDecisionButGoingBackToAnEarlierChoiceIsANewOne() {
        long userId = newUser("subject-export-approval-repeat").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        TemplateVersion activated = newActivatedTemplateVersion(workspaceId, userId);
        long documentId = newDocument(workspaceId, userId, activated);
        long revisionId = currentRevisionId(workspaceId, userId, documentId);
        long manifestId = insertValidationManifest(workspaceId, userId, documentId, revisionId, activated);

        ExportApproval both = exportApprovalRepository.save(
                workspaceId, userId, documentId, revisionId, activated.id(), manifestId, ExportFormat.BOTH);
        ExportApproval bothAgain = exportApprovalRepository.save(
                workspaceId, userId, documentId, revisionId, activated.id(), manifestId, ExportFormat.BOTH);
        assertThat(bothAgain.id()).isEqualTo(both.id());

        ExportApproval wordAlone = exportApprovalRepository.save(
                workspaceId, userId, documentId, revisionId, activated.id(), manifestId, ExportFormat.DOCX);
        ExportApproval backToBoth = exportApprovalRepository.save(
                workspaceId, userId, documentId, revisionId, activated.id(), manifestId, ExportFormat.BOTH);

        assertThat(backToBoth.id()).isNotIn(both.id(), wordAlone.id());
        ExportApproval latest = exportApprovalRepository.findLatest(workspaceId, userId, documentId).orElseThrow();
        assertThat(latest.id()).isEqualTo(backToBoth.id());
        assertThat(latest.format()).isEqualTo(ExportFormat.BOTH);
    }

    @Test
    void oneUsersContextCannotReadAnotherWorkspacesExportApproval() {
        UserIdentity userA = newUser("subject-export-approval-rls-a");
        UserIdentity userB = newUser("subject-export-approval-rls-b");
        long workspaceBId = workspaceRepository.ensurePersonalWorkspace(userB.id()).id();
        TemplateVersion activated = newActivatedTemplateVersion(workspaceBId, userB.id());
        long documentId = newDocument(workspaceBId, userB.id(), activated);
        long revisionId = currentRevisionId(workspaceBId, userB.id(), documentId);
        long manifestId = insertValidationManifest(workspaceBId, userB.id(), documentId, revisionId, activated);

        exportApprovalRepository.save(workspaceBId, userB.id(), documentId, revisionId, activated.id(), manifestId, ExportFormat.BOTH);

        assertThat(exportApprovalRepository.findLatest(workspaceBId, userA.id(), documentId)).isEmpty();
    }

    private TemplateVersion newActivatedTemplateVersion(long workspaceId, long userId) {
        long artifactId = insertArtifact(workspaceId, userId);
        DocxStructuralGraph graph = sampleGraph();
        ExtractionVersion extraction = extractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "test-parser-v1", graph);
        Template template = templateRepository.createDraft(workspaceId, userId, "Club Minutes", artifactId, extraction.id());
        List<FieldDefinition> fields = List.of(new FieldDefinition(
                "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                new FieldBindingTarget.ContentControlTag("meeting.title")));
        TemplateVersion draft = templateRepository.replaceDraftBindings(workspaceId, userId, template.id(), 1, fields);
        return templateRepository.activate(workspaceId, userId, template.id(), draft.versionNumber());
    }

    private long newDocument(long workspaceId, long userId, TemplateVersion templateVersion) {
        DocumentContent content = new DocumentContent(Map.of("meeting.title", new FieldValue.TextValue("Test meeting")));
        DocumentMutationResult mutation = revisionService.createDocument(
                workspaceId, userId, new io.github.vihuynh72.brownie.core.job.IdempotencyKey("create-" + UUID.randomUUID()),
                io.github.vihuynh72.brownie.core.job.CanonicalRequestHash.sha256OfCanonicalText("create-" + UUID.randomUUID()),
                "Test document", templateVersion.templateId(), templateVersion.id(), content, Map.of(), "initial draft");
        return mutation.document().id();
    }

    private long currentRevisionId(long workspaceId, long userId, long documentId) {
        return revisionService.findDocument(workspaceId, userId, documentId).orElseThrow().currentRevisionId();
    }

    private DocxStructuralGraph sampleGraph() {
        StructuralNode titleControl =
                new StructuralNode("p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.title", null, List.of());
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(titleControl));
        return new DocxStructuralGraph("test-parser-v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }

    private UserIdentity newUser(String subject) {
        return userIdentityRepository.recordLogin("https://issuer-export-approval-tests", subject, null, null);
    }

    private long insertArtifact(long workspaceId, long userId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO artifact (workspace_id, blob_key, status, byte_count, detected_media_type) "
                            + "VALUES (?, ?, 'READY', 100, 'DOCX') RETURNING id")) {
                statement.setLong(1, workspaceId);
                statement.setString(2, "test-blob-" + UUID.randomUUID());
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

    private long insertValidationManifest(long workspaceId, long userId, long documentId, long revisionId, TemplateVersion templateVersion) {
        long docxArtifactId = insertArtifact(workspaceId, userId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO validation_manifest "
                            + "(workspace_id, document_id, revision_id, template_id, template_version_id, docx_artifact_id, docx_sha256, findings) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, '[]'::jsonb) RETURNING id")) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, documentId);
                statement.setLong(3, revisionId);
                statement.setLong(4, templateVersion.templateId());
                statement.setLong(5, templateVersion.id());
                statement.setLong(6, docxArtifactId);
                statement.setString(7, "a".repeat(64));
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

    private void setLocalContext(Connection connection, long userId) throws SQLException {
        try (PreparedStatement setContext =
                connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            setContext.setString(1, String.valueOf(userId));
            setContext.executeQuery();
        }
    }
}
