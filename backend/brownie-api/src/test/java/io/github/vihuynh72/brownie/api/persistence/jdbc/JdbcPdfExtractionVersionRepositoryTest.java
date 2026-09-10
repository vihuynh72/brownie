package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.PdfExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PdfExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfPage;
import io.github.vihuynh72.brownie.core.document.PdfStructuralGraph;
import io.github.vihuynh72.brownie.core.document.PdfTextLine;
import io.github.vihuynh72.brownie.core.document.UnsupportedPdfReason;
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
 * The PDF analog of {@code JdbcExtractionVersionRepositoryTest}: JSON
 * round-trip, insert-or-return-existing idempotency, and row-level
 * security against a real, disposable Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class JdbcPdfExtractionVersionRepositoryTest {

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
    private PdfExtractionVersionRepository pdfExtractionVersionRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void completeExtractionRoundTripsTheGraphExactly() {
        long userId = newUser("subject-pdf-complete").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        PdfStructuralGraph graph = sampleGraph();

        PdfExtractionVersion saved = pdfExtractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "v1", graph);

        assertThat(saved.status()).isEqualTo(ExtractionStatus.COMPLETE);
        assertThat(saved.graph()).isEqualTo(graph);
        assertThat(saved.unsupportedReason()).isNull();
        assertThat(saved.failureReason()).isNull();

        PdfExtractionVersion reloaded =
                pdfExtractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, "v1").orElseThrow();
        assertThat(reloaded).isEqualTo(saved);
    }

    @Test
    void unsupportedExtractionRoundTripsTheReasonAndDetailExactly() {
        long userId = newUser("subject-pdf-unsupported").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        PdfExtractionVersion saved = pdfExtractionVersionRepository.saveUnsupported(
                workspaceId, userId, artifactId, "v1", UnsupportedPdfReason.ENCRYPTED, "no password supplied");

        assertThat(saved.status()).isEqualTo(ExtractionStatus.UNSUPPORTED);
        assertThat(saved.graph()).isNull();
        assertThat(saved.unsupportedReason()).isEqualTo(UnsupportedPdfReason.ENCRYPTED);
        assertThat(saved.unsupportedDetail()).isEqualTo("no password supplied");
    }

    @Test
    void failedExtractionRecordsTheFailureReason() {
        long userId = newUser("subject-pdf-failed").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        PdfExtractionVersion saved = pdfExtractionVersionRepository.saveFailed(workspaceId, userId, artifactId, "v1", "could not parse");

        assertThat(saved.status()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(saved.graph()).isNull();
        assertThat(saved.unsupportedReason()).isNull();
        assertThat(saved.failureReason()).isEqualTo("could not parse");
    }

    @Test
    void findByArtifactIsEmptyBeforeAnyExtractionRuns() {
        long userId = newUser("subject-pdf-none").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        assertThat(pdfExtractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, "v1")).isEmpty();
    }

    @Test
    void savingTwiceUnderTheSameParserVersionConvergesOnOneRowRatherThanDuplicating() throws SQLException {
        long userId = newUser("subject-pdf-idempotent").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        PdfExtractionVersion first = pdfExtractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "v1", sampleGraph());
        PdfExtractionVersion second = pdfExtractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "v1", sampleGraph());

        assertThat(second.id()).isEqualTo(first.id());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM pdf_extraction_version WHERE artifact_id = ? AND parser_version = ?")) {
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
        long userId = newUser("subject-pdf-versioned").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        PdfExtractionVersion v1 = pdfExtractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "v1", sampleGraph());
        PdfExtractionVersion v2 = pdfExtractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "v2", sampleGraph());

        assertThat(v2.id()).isNotEqualTo(v1.id());
        assertThat(pdfExtractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, "v1")).contains(v1);
        assertThat(pdfExtractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, "v2")).contains(v2);
    }

    @Test
    void oneUsersContextCannotReadAnotherWorkspacesExtractionVersion() {
        UserIdentity userA = newUser("subject-pdf-rls-a");
        UserIdentity userB = newUser("subject-pdf-rls-b");
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(userB.id());
        long artifactId = insertArtifact(workspaceB.id(), userB.id());
        pdfExtractionVersionRepository.saveComplete(workspaceB.id(), userB.id(), artifactId, "v1", sampleGraph());

        Optional<PdfExtractionVersion> asOutsider =
                pdfExtractionVersionRepository.findByArtifact(workspaceB.id(), userA.id(), artifactId, "v1");
        assertThat(asOutsider).isEmpty();
    }

    @Test
    void aNonMemberCannotInsertAPdfExtractionVersionIntoAnotherWorkspace() throws SQLException {
        UserIdentity userA = newUser("subject-pdf-rls-insert-a");
        UserIdentity userB = newUser("subject-pdf-rls-insert-b");
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(userB.id());
        long artifactId = insertArtifact(workspaceB.id(), userB.id());

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userA.id());
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pdf_extraction_version (workspace_id, artifact_id, parser_version, status) VALUES (?, ?, 'v1', 'COMPLETE')")) {
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

    private PdfStructuralGraph sampleGraph() {
        PdfTextLine line = new PdfTextLine(0, "Meeting called to order.", 72.0, 72.0, 200.0, 12.0, false);
        PdfPage page = new PdfPage(1, 612.0, 792.0, 0, true, List.of(line));
        return new PdfStructuralGraph("test-pdf-parser-v1", List.of(page));
    }

    private UserIdentity newUser(String subject) {
        return userIdentityRepository.recordLogin("https://issuer-pdf-extraction-version-tests", subject, null, null);
    }

    private long insertArtifact(long workspaceId, long userId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO artifact (workspace_id, blob_key, status, byte_count, detected_media_type) "
                            + "VALUES (?, ?, 'READY', 100, 'PDF') RETURNING id")) {
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
