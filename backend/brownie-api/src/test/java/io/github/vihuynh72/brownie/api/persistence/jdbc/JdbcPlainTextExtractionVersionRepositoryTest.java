package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.PlainTextExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PlainTextExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PlainTextStructuralGraph;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The plain-text analog of {@code JdbcExtractionVersionRepositoryTest}/
 * {@code JdbcPdfExtractionVersionRepositoryTest}: round-trip and
 * insert-or-return-existing idempotency, and row-level security, against
 * a real, disposable Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class JdbcPlainTextExtractionVersionRepositoryTest {

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
    private PlainTextExtractionVersionRepository plainTextExtractionVersionRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void completeExtractionRoundTripsTheOriginalAndNormalizedTextExactly() {
        long userId = newUser("subject-text-complete").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        PlainTextStructuralGraph graph = new PlainTextStructuralGraph("v1", "line one\r\nline two", "line one\nline two");

        PlainTextExtractionVersion saved =
                plainTextExtractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "v1", graph);

        assertThat(saved.status()).isEqualTo(ExtractionStatus.COMPLETE);
        assertThat(saved.graph().originalText()).isEqualTo("line one\r\nline two");
        assertThat(saved.graph().normalizedText()).isEqualTo("line one\nline two");
        assertThat(saved.failureReason()).isNull();

        PlainTextExtractionVersion reloaded =
                plainTextExtractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, "v1").orElseThrow();
        assertThat(reloaded).isEqualTo(saved);
    }

    @Test
    void anEmptyOriginalTextIsDistinctFromNoGraphAtAll() {
        long userId = newUser("subject-text-empty").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        PlainTextStructuralGraph graph = new PlainTextStructuralGraph("v1", "", "");

        PlainTextExtractionVersion saved =
                plainTextExtractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "v1", graph);

        assertThat(saved.status()).isEqualTo(ExtractionStatus.COMPLETE);
        assertThat(saved.graph()).isNotNull();
        assertThat(saved.graph().originalText()).isEmpty();
    }

    @Test
    void failedExtractionRecordsTheFailureReasonAndNoGraph() {
        long userId = newUser("subject-text-failed").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        PlainTextExtractionVersion saved =
                plainTextExtractionVersionRepository.saveFailed(workspaceId, userId, artifactId, "v1", "not valid UTF-8");

        assertThat(saved.status()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(saved.graph()).isNull();
        assertThat(saved.failureReason()).isEqualTo("not valid UTF-8");
    }

    @Test
    void findByArtifactIsEmptyBeforeAnyExtractionRuns() {
        long userId = newUser("subject-text-none").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        assertThat(plainTextExtractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, "v1")).isEmpty();
    }

    @Test
    void savingTwiceUnderTheSameParserVersionConvergesOnOneRowRatherThanDuplicating() throws SQLException {
        long userId = newUser("subject-text-idempotent").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        PlainTextStructuralGraph graph = new PlainTextStructuralGraph("v1", "text", "text");

        PlainTextExtractionVersion first =
                plainTextExtractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "v1", graph);
        PlainTextExtractionVersion second =
                plainTextExtractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "v1", graph);

        assertThat(second.id()).isEqualTo(first.id());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM plain_text_extraction_version WHERE artifact_id = ? AND parser_version = ?")) {
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
        long userId = newUser("subject-text-versioned").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        PlainTextStructuralGraph graph = new PlainTextStructuralGraph("v1", "text", "text");

        PlainTextExtractionVersion v1 = plainTextExtractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "v1", graph);
        PlainTextExtractionVersion v2 = plainTextExtractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "v2", graph);

        assertThat(v2.id()).isNotEqualTo(v1.id());
        assertThat(plainTextExtractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, "v1")).contains(v1);
        assertThat(plainTextExtractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, "v2")).contains(v2);
    }

    @Test
    void oneUsersContextCannotReadAnotherWorkspacesExtractionVersion() {
        UserIdentity userA = newUser("subject-text-rls-a");
        UserIdentity userB = newUser("subject-text-rls-b");
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(userB.id());
        long artifactId = insertArtifact(workspaceB.id(), userB.id());
        plainTextExtractionVersionRepository.saveComplete(
                workspaceB.id(), userB.id(), artifactId, "v1", new PlainTextStructuralGraph("v1", "text", "text"));

        Optional<PlainTextExtractionVersion> asOutsider =
                plainTextExtractionVersionRepository.findByArtifact(workspaceB.id(), userA.id(), artifactId, "v1");
        assertThat(asOutsider).isEmpty();
    }

    @Test
    void aNonMemberCannotInsertAPlainTextExtractionVersionIntoAnotherWorkspace() throws SQLException {
        UserIdentity userA = newUser("subject-text-rls-insert-a");
        UserIdentity userB = newUser("subject-text-rls-insert-b");
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(userB.id());
        long artifactId = insertArtifact(workspaceB.id(), userB.id());

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userA.id());
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO plain_text_extraction_version (workspace_id, artifact_id, parser_version, status) VALUES (?, ?, 'v1', 'COMPLETE')")) {
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

    private UserIdentity newUser(String subject) {
        return userIdentityRepository.recordLogin("https://issuer-plain-text-extraction-version-tests", subject, null, null);
    }

    private long insertArtifact(long workspaceId, long userId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO artifact (workspace_id, blob_key, status, byte_count, detected_media_type) "
                            + "VALUES (?, ?, 'READY', 100, 'PLAIN_TEXT') RETURNING id")) {
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
