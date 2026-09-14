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
import io.github.vihuynh72.brownie.core.template.BaselineRenderResult;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateBaselineRenderRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the plain-column round trip and row-level security of {@code template_baseline_render}, mirroring {@code JdbcTemplateExampleRepositoryTest}'s own shape. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class JdbcTemplateBaselineRenderRepositoryTest {

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
    private TemplateBaselineRenderRepository templateBaselineRenderRepository;

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
    void recordBaselineRenderRoundTripsExactlyAndOmitsFailedFieldIds() {
        long userId = newUser("subject-baseline-round-trip").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        TemplateVersion activated = newActivatedTemplateVersion(workspaceId, userId);
        long docxArtifactId = insertArtifact(workspaceId, userId);
        long pdfArtifactId = insertArtifact(workspaceId, userId);

        assertThat(templateBaselineRenderRepository.findBaselineRender(workspaceId, userId, activated.id())).isEmpty();

        templateBaselineRenderRepository.recordBaselineRender(
                workspaceId, userId, activated.id(), new BaselineRenderResult(docxArtifactId, pdfArtifactId, "renderer-v1", List.of()));

        BaselineRenderResult reloaded = templateBaselineRenderRepository.findBaselineRender(workspaceId, userId, activated.id()).orElseThrow();
        assertThat(reloaded.docxArtifactId()).isEqualTo(docxArtifactId);
        assertThat(reloaded.pdfArtifactId()).isEqualTo(pdfArtifactId);
        assertThat(reloaded.rendererVersion()).isEqualTo("renderer-v1");
        assertThat(reloaded.failedFieldIds()).isEmpty();
        assertThat(reloaded.passed()).isTrue();
    }

    @Test
    void oneUsersContextCannotReadAnotherWorkspacesBaselineRender() {
        UserIdentity userA = newUser("subject-baseline-rls-a");
        UserIdentity userB = newUser("subject-baseline-rls-b");
        long workspaceBId = workspaceRepository.ensurePersonalWorkspace(userB.id()).id();
        TemplateVersion activated = newActivatedTemplateVersion(workspaceBId, userB.id());
        long docxArtifactId = insertArtifact(workspaceBId, userB.id());
        long pdfArtifactId = insertArtifact(workspaceBId, userB.id());
        templateBaselineRenderRepository.recordBaselineRender(
                workspaceBId, userB.id(), activated.id(), new BaselineRenderResult(docxArtifactId, pdfArtifactId, "renderer-v1", List.of()));

        assertThat(templateBaselineRenderRepository.findBaselineRender(workspaceBId, userA.id(), activated.id())).isEmpty();
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

    private DocxStructuralGraph sampleGraph() {
        StructuralNode titleControl =
                new StructuralNode("p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.title", null, List.of());
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(titleControl));
        return new DocxStructuralGraph("test-parser-v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }

    private UserIdentity newUser(String subject) {
        return userIdentityRepository.recordLogin("https://issuer-baseline-render-tests", subject, null, null);
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
