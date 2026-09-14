package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.example.ExampleAlignmentStatus;
import io.github.vihuynh72.brownie.core.example.TemplateExample;
import io.github.vihuynh72.brownie.core.example.TemplateExampleRepository;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the plain-column round trip and row-level security of {@code
 * template_example} against a real, disposable Postgres, mirroring {@code
 * JdbcTemplateRepositoryTest}'s own shape. Alignment itself is {@code
 * ExampleAlignerTest}'s job (pure); this repository never computes it, only
 * persists whatever status it is handed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class JdbcTemplateExampleRepositoryTest {

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
    private TemplateExampleRepository templateExampleRepository;

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
    void attachRoundTripsExactlyAndFindByTemplateVersionOrdersByAttachmentOrder() {
        long userId = newUser("subject-attach").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long templateArtifactId = insertArtifact(workspaceId, userId);
        long templateExtractionId = insertExtraction(workspaceId, userId, templateArtifactId);
        Template template = templateRepository.createDraft(workspaceId, userId, "Club Minutes", templateArtifactId, templateExtractionId);
        long draftVersionId = templateRepository.findDraftVersion(workspaceId, userId, template.id()).orElseThrow().id();
        long exampleArtifactId1 = insertArtifact(workspaceId, userId);
        long exampleExtractionId1 = insertExtraction(workspaceId, userId, exampleArtifactId1);
        long exampleArtifactId2 = insertArtifact(workspaceId, userId);
        long exampleExtractionId2 = insertExtraction(workspaceId, userId, exampleArtifactId2);

        TemplateExample first = templateExampleRepository.attach(
                workspaceId, userId, template.id(), draftVersionId, exampleArtifactId1, exampleExtractionId1, ExampleAlignmentStatus.ALIGNED);
        TemplateExample second = templateExampleRepository.attach(
                workspaceId, userId, template.id(), draftVersionId, exampleArtifactId2, exampleExtractionId2,
                ExampleAlignmentStatus.MISMATCHED_FAMILY);

        assertThat(first.alignmentStatus()).isEqualTo(ExampleAlignmentStatus.ALIGNED);
        assertThat(first.sourceArtifactId()).isEqualTo(exampleArtifactId1);
        assertThat(first.extractionVersionId()).isEqualTo(exampleExtractionId1);
        assertThat(first.templateId()).isEqualTo(template.id());
        assertThat(first.templateVersionId()).isEqualTo(draftVersionId);
        assertThat(first.createdAt()).isNotNull();

        List<TemplateExample> found = templateExampleRepository.findByTemplateVersion(workspaceId, userId, draftVersionId);
        assertThat(found).extracting(TemplateExample::id).containsExactly(first.id(), second.id());
        assertThat(found.get(1).alignmentStatus()).isEqualTo(ExampleAlignmentStatus.MISMATCHED_FAMILY);
    }

    @Test
    void anInvalidAlignmentStatusIsRejectedByTheRealCheckConstraint() throws SQLException {
        long userId = newUser("subject-invalid-status").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long templateArtifactId = insertArtifact(workspaceId, userId);
        long templateExtractionId = insertExtraction(workspaceId, userId, templateArtifactId);
        Template template = templateRepository.createDraft(workspaceId, userId, "Club Minutes", templateArtifactId, templateExtractionId);
        long draftVersionId = templateRepository.findDraftVersion(workspaceId, userId, template.id()).orElseThrow().id();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO template_example "
                            + "(workspace_id, template_id, template_version_id, source_artifact_id, extraction_version_id, alignment_status) "
                            + "VALUES (?, ?, ?, ?, ?, 'NOT_A_REAL_STATUS')")) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, template.id());
                statement.setLong(3, draftVersionId);
                statement.setLong(4, templateArtifactId);
                statement.setLong(5, templateExtractionId);
                statement.executeUpdate();
                fail("expected the real CHECK constraint to reject an unrecognized alignment_status");
            } catch (SQLException expected) {
                assertThat(expected.getMessage()).containsIgnoringCase("check constraint");
            }
            connection.rollback();
        }
    }

    @Test
    void oneUsersContextCannotReadOrInsertAnotherWorkspacesExamples() {
        UserIdentity userA = newUser("subject-example-rls-a");
        UserIdentity userB = newUser("subject-example-rls-b");
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(userB.id());
        long templateArtifactId = insertArtifact(workspaceB.id(), userB.id());
        long templateExtractionId = insertExtraction(workspaceB.id(), userB.id(), templateArtifactId);
        Template template =
                templateRepository.createDraft(workspaceB.id(), userB.id(), "Private", templateArtifactId, templateExtractionId);
        long draftVersionId = templateRepository.findDraftVersion(workspaceB.id(), userB.id(), template.id()).orElseThrow().id();
        long exampleArtifactId = insertArtifact(workspaceB.id(), userB.id());
        long exampleExtractionId = insertExtraction(workspaceB.id(), userB.id(), exampleArtifactId);
        templateExampleRepository.attach(
                workspaceB.id(), userB.id(), template.id(), draftVersionId, exampleArtifactId, exampleExtractionId,
                ExampleAlignmentStatus.ALIGNED);

        assertThat(templateExampleRepository.findByTemplateVersion(workspaceB.id(), userA.id(), draftVersionId)).isEmpty();
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

    private long insertExtraction(long workspaceId, long userId, long artifactId) {
        ExtractionVersion extraction =
                extractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "test-parser-v1", sampleGraph());
        return extraction.id();
    }

    private DocxStructuralGraph sampleGraph() {
        StructuralNode control =
                new StructuralNode("p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.title", null, List.of());
        StructuralNode body = new StructuralNode("", StructuralNodeKind.BODY, null, null, null, null, List.of(control));
        return new DocxStructuralGraph("test-parser-v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }

    private UserIdentity newUser(String subject) {
        return userIdentityRepository.recordLogin("https://issuer-template-example-tests", subject, null, null);
    }

    private void setLocalContext(Connection connection, long userId) throws SQLException {
        try (PreparedStatement setContext =
                connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            setContext.setString(1, String.valueOf(userId));
            setContext.executeQuery();
        }
    }
}
