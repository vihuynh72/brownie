package io.github.vihuynh72.brownie.api.persistence.jdbc;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the artifact table's row-level security policies directly against
 * the real, non-bypassing runtime role, the same way {@code
 * WorkspaceRowLevelSecurityTest} proves workspace/workspace_member: a
 * raw query as brownie_api, bypassing JdbcArtifactRepository entirely.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class ArtifactRowLevelSecurityTest {

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
    private DataSource dataSource;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Test
    void oneUsersContextCannotReadAnotherUsersArtifactByDirectId() throws SQLException {
        UserIdentity userA = userIdentityRepository.recordLogin("https://issuer-artifact-rls", "subject-a", null, null);
        UserIdentity userB = userIdentityRepository.recordLogin("https://issuer-artifact-rls", "subject-b", null, null);
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(userB.id());
        long artifactId = insertArtifact(workspaceB.id(), userB.id());

        assertThat(selectIdAsUser(userA.id(), artifactId)).isNull();
        assertThat(selectIdAsUser(userB.id(), artifactId)).isEqualTo(artifactId);
    }

    @Test
    void noContextAtAllSeesNoArtifactRow() throws SQLException {
        UserIdentity user = userIdentityRepository.recordLogin("https://issuer-artifact-rls", "subject-c", null, null);
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());
        long artifactId = insertArtifact(workspace.id(), user.id());

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Deliberately no context set at all -- the same guarantee
            // WorkspaceRowLevelSecurityTest proves for workspace rows.
            try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM artifact WHERE id = ?")) {
                statement.setLong(1, artifactId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isFalse();
                }
            }
            connection.rollback();
        }
    }

    @Test
    void aNonMemberCannotInsertAnArtifactIntoAnotherWorkspace() throws SQLException {
        UserIdentity userA = userIdentityRepository.recordLogin("https://issuer-artifact-rls", "subject-f", null, null);
        UserIdentity userB = userIdentityRepository.recordLogin("https://issuer-artifact-rls", "subject-g", null, null);
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(userB.id());

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userA.id());
            try (PreparedStatement statement =
                    connection.prepareStatement("INSERT INTO artifact (workspace_id, blob_key) VALUES (?, ?)")) {
                statement.setLong(1, workspaceB.id());
                statement.setString(2, "test-blob-" + UUID.randomUUID());
                statement.executeUpdate();
                org.junit.jupiter.api.Assertions.fail("expected the insert to be rejected by row-level security");
            } catch (SQLException expected) {
                // An INSERT whose row fails WITH CHECK is a hard Postgres
                // error, not a silent zero-row result the way UPDATE/DELETE
                // would be -- confirmed by asserting on the actual message,
                // not just that some exception happened to be thrown.
                assertThat(expected.getMessage()).containsIgnoringCase("row-level security");
            }
            connection.rollback();
        }
    }

    private long insertArtifact(long workspaceId, long userId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO artifact (workspace_id, blob_key) VALUES (?, ?) RETURNING id")) {
                statement.setLong(1, workspaceId);
                statement.setString(2, "test-blob-" + UUID.randomUUID());
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    long id = resultSet.getLong(1);
                    connection.commit();
                    return id;
                }
            }
        }
    }

    private Long selectIdAsUser(long userId, long artifactId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM artifact WHERE id = ?")) {
                statement.setLong(1, artifactId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    Long result = resultSet.next() ? resultSet.getLong(1) : null;
                    connection.rollback();
                    return result;
                }
            }
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
