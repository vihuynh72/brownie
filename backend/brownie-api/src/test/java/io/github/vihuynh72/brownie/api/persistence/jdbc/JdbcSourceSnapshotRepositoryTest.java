package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.source.SourceKind;
import io.github.vihuynh72.brownie.core.source.SourceSnapshot;
import io.github.vihuynh72.brownie.core.source.SourceSnapshotRepository;
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

/** Round-trip, insert-or-return-existing idempotency under a real UNIQUE(artifact_id) constraint, and row-level security, against a real, disposable Postgres. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class JdbcSourceSnapshotRepositoryTest {

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
    private SourceSnapshotRepository sourceSnapshotRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void createsAndReloadsASnapshotByIdAndByArtifact() {
        long userId = newUser("subject-snapshot-create").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        SourceSnapshot created = sourceSnapshotRepository.create(workspaceId, userId, artifactId, SourceKind.ARTIFACT);

        assertThat(created.artifactId()).isEqualTo(artifactId);
        assertThat(created.kind()).isEqualTo(SourceKind.ARTIFACT);
        assertThat(sourceSnapshotRepository.find(workspaceId, userId, created.id())).contains(created);
        assertThat(sourceSnapshotRepository.findByArtifact(workspaceId, userId, artifactId)).contains(created);
    }

    @Test
    void creatingTwiceForTheSameArtifactReturnsTheSameRowRatherThanDuplicating() throws SQLException {
        long userId = newUser("subject-snapshot-idempotent").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        SourceSnapshot first = sourceSnapshotRepository.create(workspaceId, userId, artifactId, SourceKind.ARTIFACT);
        SourceSnapshot second = sourceSnapshotRepository.create(workspaceId, userId, artifactId, SourceKind.ARTIFACT);

        assertThat(second.id()).isEqualTo(first.id());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT count(*) FROM source_snapshot WHERE artifact_id = ?")) {
                statement.setLong(1, artifactId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    assertThat(resultSet.getInt(1)).isEqualTo(1);
                }
            }
            connection.rollback();
        }
    }

    @Test
    void findByArtifactIsEmptyBeforeAnySnapshotExists() {
        long userId = newUser("subject-snapshot-none").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        assertThat(sourceSnapshotRepository.findByArtifact(workspaceId, userId, artifactId)).isEmpty();
    }

    @Test
    void oneUsersContextCannotReadAnotherWorkspacesSnapshot() {
        UserIdentity userA = newUser("subject-snapshot-rls-a");
        UserIdentity userB = newUser("subject-snapshot-rls-b");
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(userB.id());
        long artifactId = insertArtifact(workspaceB.id(), userB.id());
        SourceSnapshot snapshot = sourceSnapshotRepository.create(workspaceB.id(), userB.id(), artifactId, SourceKind.ARTIFACT);

        Optional<SourceSnapshot> asOutsider = sourceSnapshotRepository.find(workspaceB.id(), userA.id(), snapshot.id());
        assertThat(asOutsider).isEmpty();
    }

    @Test
    void aNonMemberCannotInsertASnapshotIntoAnotherWorkspace() throws SQLException {
        UserIdentity userA = newUser("subject-snapshot-rls-insert-a");
        UserIdentity userB = newUser("subject-snapshot-rls-insert-b");
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(userB.id());
        long artifactId = insertArtifact(workspaceB.id(), userB.id());

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userA.id());
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO source_snapshot (workspace_id, artifact_id, kind) VALUES (?, ?, 'ARTIFACT')")) {
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
        return userIdentityRepository.recordLogin("https://issuer-source-snapshot-tests", subject, null, null);
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
