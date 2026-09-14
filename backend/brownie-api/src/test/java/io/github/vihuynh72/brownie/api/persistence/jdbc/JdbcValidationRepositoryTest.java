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
import io.github.vihuynh72.brownie.core.validation.ValidationFinding;
import io.github.vihuynh72.brownie.core.validation.ValidationFindingCode;
import io.github.vihuynh72.brownie.core.validation.ValidationManifest;
import io.github.vihuynh72.brownie.core.validation.ValidationRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves the plain-column round trip, nullable PDF columns, and row-level security of {@code validation_manifest}, mirroring {@code JdbcTemplateBaselineRenderRepositoryTest}'s own shape. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class JdbcValidationRepositoryTest {

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
    private ValidationRepository validationRepository;

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
    void savedManifestRoundTripsEveryColumnIncludingNullablePdfFields() {
        long userId = newUser("subject-validation-round-trip").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        TemplateVersion activated = newActivatedTemplateVersion(workspaceId, userId);
        long documentId = newDocument(workspaceId, userId, activated);
        long revisionId = currentRevisionId(workspaceId, userId, documentId);
        long docxArtifactId = insertArtifact(workspaceId, userId);

        List<ValidationFinding> findings = List.of(
                new ValidationFinding(ValidationFindingCode.MISSING_REQUIRED_FIELD, "meeting.title", "Field meeting.title is required."),
                new ValidationFinding(ValidationFindingCode.INVALID_EVIDENCE_REFERENCE, null, "Evidence span 4 no longer resolves."));

        ValidationManifest saved = validationRepository.save(
                workspaceId, userId, documentId, revisionId, activated.templateId(), activated.id(),
                docxArtifactId, "a".repeat(64), null, null, findings);

        assertThat(saved.hasUnresolvedBlocking()).isTrue();

        ValidationManifest reloaded = validationRepository.find(workspaceId, userId, documentId, saved.id()).orElseThrow();
        assertThat(reloaded.documentId()).isEqualTo(documentId);
        assertThat(reloaded.revisionId()).isEqualTo(revisionId);
        assertThat(reloaded.templateId()).isEqualTo(activated.templateId());
        assertThat(reloaded.templateVersionId()).isEqualTo(activated.id());
        assertThat(reloaded.docxArtifactId()).isEqualTo(docxArtifactId);
        assertThat(reloaded.docxSha256()).isEqualTo("a".repeat(64));
        assertThat(reloaded.pdfArtifactId()).isNull();
        assertThat(reloaded.pdfSha256()).isNull();
        assertThat(reloaded.findings()).hasSize(2);
        assertThat(reloaded.findings().get(0).code()).isEqualTo(ValidationFindingCode.MISSING_REQUIRED_FIELD);
        assertThat(reloaded.findings().get(0).fieldId()).isEqualTo("meeting.title");
        assertThat(reloaded.findings().get(1).fieldId()).isNull();

        ValidationManifest latest = validationRepository.findLatest(workspaceId, userId, documentId, revisionId).orElseThrow();
        assertThat(latest.id()).isEqualTo(saved.id());
    }

    @Test
    void oneUsersContextCannotReadAnotherWorkspacesValidationManifest() {
        UserIdentity userA = newUser("subject-validation-rls-a");
        UserIdentity userB = newUser("subject-validation-rls-b");
        long workspaceBId = workspaceRepository.ensurePersonalWorkspace(userB.id()).id();
        TemplateVersion activated = newActivatedTemplateVersion(workspaceBId, userB.id());
        long documentId = newDocument(workspaceBId, userB.id(), activated);
        long revisionId = currentRevisionId(workspaceBId, userB.id(), documentId);
        long docxArtifactId = insertArtifact(workspaceBId, userB.id());

        ValidationManifest saved = validationRepository.save(
                workspaceBId, userB.id(), documentId, revisionId, activated.templateId(), activated.id(),
                docxArtifactId, "b".repeat(64), null, null, List.of());

        assertThat(validationRepository.find(workspaceBId, userA.id(), documentId, saved.id())).isEmpty();
        assertThat(validationRepository.findLatest(workspaceBId, userA.id(), documentId, revisionId)).isEmpty();
    }

    @Test
    void aMalformedPdfColumnPairIsRejectedByTheRealCheckConstraint() {
        long userId = newUser("subject-validation-pdf-columns").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        TemplateVersion activated = newActivatedTemplateVersion(workspaceId, userId);
        long documentId = newDocument(workspaceId, userId, activated);
        long revisionId = currentRevisionId(workspaceId, userId, documentId);
        long docxArtifactId = insertArtifact(workspaceId, userId);
        long pdfArtifactId = insertArtifact(workspaceId, userId);

        assertThatThrownBy(() -> validationRepository.save(
                workspaceId, userId, documentId, revisionId, activated.templateId(), activated.id(),
                docxArtifactId, "c".repeat(64), pdfArtifactId, null, List.of()))
                .isInstanceOf(RuntimeException.class);
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
                workspaceId, userId, new IdempotencyKey("create-" + UUID.randomUUID()),
                CanonicalRequestHash.sha256OfCanonicalText("create-" + UUID.randomUUID()),
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
        return userIdentityRepository.recordLogin("https://issuer-validation-manifest-tests", subject, null, null);
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

    private void setLocalContext(Connection connection, long userId) throws SQLException {
        try (PreparedStatement setContext =
                connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            setContext.setString(1, String.valueOf(userId));
            setContext.executeQuery();
        }
    }
}
