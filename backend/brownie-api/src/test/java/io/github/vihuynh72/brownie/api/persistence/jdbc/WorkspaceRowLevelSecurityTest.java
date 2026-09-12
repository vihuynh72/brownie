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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the row-level security policies themselves, independent of
 * whether any repository method happens to add a correct WHERE clause: a
 * raw query as the real, non-bypassing runtime role, with one user's
 * context set, cannot read another user's row even when it names that
 * row's ID directly, and a query with no context set at all sees nothing
 * rather than everything.
 *
 * <p>Each test holds one raw {@link Connection} for both the {@code
 * set_config} call and the query that follows, rather than going through
 * {@link org.springframework.jdbc.core.JdbcTemplate} for either: a pooled
 * {@code JdbcTemplate} call borrows and returns a connection per call with
 * no guarantee two calls share one, and {@code is_local=true} context
 * only survives for the transaction on the exact connection that set it
 * -- the same guarantee {@code JdbcWorkspaceRepository} gets for free from
 * {@code @Transactional}, made explicit here instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class WorkspaceRowLevelSecurityTest {

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
    void oneUsersContextCannotReadAnotherUsersMembershipRowByDirectId() throws SQLException {
        UserIdentity userA = userIdentityRepository.recordLogin("https://issuer-rls", "subject-a", null, null);
        UserIdentity userB = userIdentityRepository.recordLogin("https://issuer-rls", "subject-b", null, null);
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(userB.id());

        assertThat(queryAsUser(
                        userA.id(),
                        "SELECT workspace_id FROM workspace_member WHERE workspace_id = ?",
                        workspaceB.id()))
                .isNull();
    }

    @Test
    void oneUsersContextCannotReadAnotherUsersWorkspaceRowByDirectId() throws SQLException {
        UserIdentity userA = userIdentityRepository.recordLogin("https://issuer-rls", "subject-c", null, null);
        UserIdentity userB = userIdentityRepository.recordLogin("https://issuer-rls", "subject-d", null, null);
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(userB.id());

        assertThat(queryAsUser(userA.id(), "SELECT id FROM workspace WHERE id = ?", workspaceB.id()))
                .isNull();
    }

    @Test
    void aConnectionThatPreviouslySetContextSeesNothingOnceThatTransactionIsOver() throws SQLException {
        UserIdentity userA = userIdentityRepository.recordLogin("https://issuer-rls", "subject-f", null, null);
        UserIdentity userB = userIdentityRepository.recordLogin("https://issuer-rls", "subject-g", null, null);
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(userB.id());

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userA.id());
            connection.rollback();

            // The same physical connection, handed back to a pool and
            // reused for a brand new, unrelated transaction -- exactly
            // what every request after the first on this connection looks
            // like. Nothing sets a context here on purpose: this is what
            // distinguishes a real fix from one that only looks right,
            // since Postgres answers current_setting(...) differently for
            // "never touched in this session" versus "was set earlier and
            // reset," and only the first of those is NULL.
            try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM workspace WHERE id = ?")) {
                statement.setLong(1, workspaceB.id());
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isFalse();
                }
            }
            connection.rollback();
        }
    }

    @Test
    void noContextAtAllSeesNothingRatherThanEverything() throws SQLException {
        UserIdentity user = userIdentityRepository.recordLogin("https://issuer-rls", "subject-e", null, null);
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(user.id());

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Deliberately no set_config call: a fresh transaction on a
            // connection that just came from the pool, exactly what the
            // next unrelated request would see before any repository
            // method runs on it.
            try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM workspace WHERE id = ?")) {
                statement.setLong(1, workspace.id());
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isFalse();
                }
            }
            connection.rollback();
        }
    }

    private Long queryAsUser(long userId, String sql, long parameter) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, parameter);
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
