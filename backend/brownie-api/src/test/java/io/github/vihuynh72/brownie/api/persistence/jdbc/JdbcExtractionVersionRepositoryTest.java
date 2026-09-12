package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxFeatureFinding;
import io.github.vihuynh72.brownie.core.document.DocxFeatureReport;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.document.UnsupportedDocxFeature;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the JSON round-trip, insert-or-return-existing idempotency, and
 * row-level security of {@code extraction_version} against a real,
 * disposable Postgres -- the unit-level {@code PoiDocxStructuralExtractorTest}
 * never touches a database at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class JdbcExtractionVersionRepositoryTest {

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
    private ExtractionVersionRepository extractionVersionRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void completeExtractionRoundTripsTheGraphExactly() {
        long userId = newUser("subject-complete").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        DocxStructuralGraph graph = sampleGraph();

        ExtractionVersion saved = extractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "v1", graph);

        assertThat(saved.status()).isEqualTo(ExtractionStatus.COMPLETE);
        assertThat(saved.graph()).isEqualTo(graph);
        assertThat(saved.featureReport().isSupported()).isTrue();
        assertThat(saved.failureReason()).isNull();

        ExtractionVersion reloaded =
                extractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, "v1").orElseThrow();
        assertThat(reloaded).isEqualTo(saved);
    }

    @Test
    void unsupportedExtractionRoundTripsTheFeatureReportExactly() {
        long userId = newUser("subject-unsupported").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        DocxFeatureReport report = new DocxFeatureReport(List.of(
                new DocxFeatureFinding(UnsupportedDocxFeature.TRACKED_CHANGES, "word/document.xml, p0", "a tracked insertion")));

        ExtractionVersion saved = extractionVersionRepository.saveUnsupported(workspaceId, userId, artifactId, "v1", report);

        assertThat(saved.status()).isEqualTo(ExtractionStatus.UNSUPPORTED);
        assertThat(saved.graph()).isNull();
        assertThat(saved.featureReport()).isEqualTo(report);
    }

    @Test
    void failedExtractionRecordsTheFailureReason() {
        long userId = newUser("subject-failed").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        ExtractionVersion saved = extractionVersionRepository.saveFailed(workspaceId, userId, artifactId, "v1", "could not parse");

        assertThat(saved.status()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(saved.graph()).isNull();
        assertThat(saved.featureReport().isSupported()).isTrue();
        assertThat(saved.failureReason()).isEqualTo("could not parse");
    }

    @Test
    void findByArtifactIsEmptyBeforeAnyExtractionRuns() {
        long userId = newUser("subject-none").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        assertThat(extractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, "v1")).isEmpty();
    }

    @Test
    void savingTwiceUnderTheSameParserVersionConvergesOnOneRowRatherThanDuplicating() throws SQLException {
        long userId = newUser("subject-idempotent").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        ExtractionVersion first = extractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "v1", sampleGraph());
        ExtractionVersion second = extractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "v1", sampleGraph());

        assertThat(second.id()).isEqualTo(first.id());
        // A bare jdbcTemplate call here would run in its own connection/
        // transaction with no tenant context set and RLS would (correctly)
        // hide every row -- the same "no context, no visibility" property
        // ArtifactRowLevelSecurityTest demonstrates deliberately. Setting
        // the context on a manually held connection, in the same
        // transaction as the count query, is what actually proves only one
        // row exists rather than merely that both calls returned the same id.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM extraction_version WHERE artifact_id = ? AND parser_version = ?")) {
                statement.setLong(1, artifactId);
                statement.setString(2, "v1");
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    assertThat(resultSet.getInt(1)).isEqualTo(1);
                }
            }
            connection.rollback();
        }
    }

    @Test
    void aParserVersionChangeCreatesASeparateRowRatherThanReplacingTheOld() {
        long userId = newUser("subject-versioned").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        ExtractionVersion v1 = extractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "v1", sampleGraph());
        ExtractionVersion v2 = extractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "v2", sampleGraph());

        assertThat(v2.id()).isNotEqualTo(v1.id());
        assertThat(extractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, "v1")).contains(v1);
        assertThat(extractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, "v2")).contains(v2);
    }

    @Test
    void oneUsersContextCannotReadAnotherWorkspacesExtractionVersion() throws SQLException {
        UserIdentity userA = newUser("subject-rls-a");
        UserIdentity userB = newUser("subject-rls-b");
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(userB.id());
        long artifactId = insertArtifact(workspaceB.id(), userB.id());
        extractionVersionRepository.saveComplete(workspaceB.id(), userB.id(), artifactId, "v1", sampleGraph());

        Optional<ExtractionVersion> asOutsider = extractionVersionRepository.findByArtifact(workspaceB.id(), userA.id(), artifactId, "v1");
        assertThat(asOutsider).isEmpty();
    }

    @Test
    void aNonMemberCannotInsertAnExtractionVersionIntoAnotherWorkspace() throws SQLException {
        UserIdentity userA = newUser("subject-rls-insert-a");
        UserIdentity userB = newUser("subject-rls-insert-b");
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(userB.id());
        long artifactId = insertArtifact(workspaceB.id(), userB.id());

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userA.id());
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO extraction_version (workspace_id, artifact_id, parser_version, status) VALUES (?, ?, 'v1', 'COMPLETE')")) {
                statement.setLong(1, workspaceB.id());
                statement.setLong(2, artifactId);
                statement.executeUpdate();
                fail("expected the insert to be rejected by row-level security");
            } catch (SQLException expected) {
                assertThat(expected.getMessage()).containsIgnoringCase("row-level security");
            }
            connection.rollback();
        }
    }

    private DocxStructuralGraph sampleGraph() {
        StructuralNode run = new StructuralNode("p0/r0", StructuralNodeKind.RUN, null, "Meeting Minutes", null, null, List.of());
        StructuralNode paragraph =
                new StructuralNode("p0", StructuralNodeKind.PARAGRAPH, null, null, null, null, List.of(run));
        StructuralNode body = new StructuralNode("", StructuralNodeKind.BODY, null, null, null, null, List.of(paragraph));
        DocumentPart part = new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body);
        return new DocxStructuralGraph("test-parser-v1", List.of(part));
    }

    private UserIdentity newUser(String subject) {
        return userIdentityRepository.recordLogin("https://issuer-extraction-version-tests", subject, null, null);
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
