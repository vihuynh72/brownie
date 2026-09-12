package io.github.vihuynh72.brownie.worker.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that the worker connection cannot use ordinary table privileges,
 * while its approved queue routines remain callable through the same
 * restricted datasource.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class BrownieWorkerDatabaseIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String WORKER_PASSWORD = "brownie_worker_local_only";
    private static final String CONFIGURATION_HASH = "c".repeat(64);

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
        registry.add("spring.datasource.username", () -> "brownie_worker");
        registry.add("spring.datasource.password", () -> WORKER_PASSWORD);
    }

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)
                .locations("filesystem:" + apiMigrationPath())
                .target("20")
                .load()
                .migrate();
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath()
                .getParent()
                .getParent()
                .resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    private static Path apiMigrationPath() {
        return Path.of("").toAbsolutePath()
                .getParent()
                .resolve("brownie-api/src/main/resources/db/migration");
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void workerRoleCannotReadOrWriteAMigrationCreatedTable() throws SQLException {
        try (Connection migrationConnection = migrationConnection();
                Statement setup = migrationConnection.createStatement()) {
            setup.execute("CREATE TABLE worker_probe (id serial PRIMARY KEY, note text)");
            try {
                assertThatThrownBy(() -> {
                            try (Connection workerConnection = dataSource.getConnection();
                                    Statement statement = workerConnection.createStatement()) {
                                statement.executeQuery("SELECT count(*) FROM worker_probe");
                            }
                        })
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
                assertThatThrownBy(() -> {
                            try (Connection workerConnection = dataSource.getConnection();
                                    Statement statement = workerConnection.createStatement()) {
                                statement.executeUpdate("INSERT INTO worker_probe (note) VALUES ('forged')");
                            }
                        })
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            } finally {
                setup.execute("DROP TABLE worker_probe");
            }
        }
    }

    @Test
    void workerRoleCanInvokeTheApprovedClaimRoutine() throws SQLException {
        long jobId = insertQueuedJob();
        try (Connection workerConnection = dataSource.getConnection();
                PreparedStatement claim = workerConnection.prepareStatement(
                        "SELECT id, state, lease_owner, fencing_token FROM public.worker_claim_next(?, ?, ?, ?)") ) {
            claim.setString(1, "integration-worker");
            claim.setLong(2, 60_000L);
            claim.setInt(3, 3);
            claim.setInt(4, 32);
            try (ResultSet result = claim.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("id")).isEqualTo(jobId);
                assertThat(result.getString("state")).isEqualTo("LEASED");
                assertThat(result.getString("lease_owner")).isEqualTo("integration-worker");
                assertThat(result.getLong("fencing_token")).isEqualTo(1L);
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void workerRoleCannotCreateOrAlterTables() {
        assertThatThrownBy(() -> {
                    try (Connection connection = dataSource.getConnection();
                            Statement statement = connection.createStatement()) {
                        statement.execute("CREATE TABLE should_never_exist (id int)");
                    }
                })
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
    }

    private long insertQueuedJob() throws SQLException {
        try (Connection connection = migrationConnection()) {
            connection.setAutoCommit(false);
            try {
                long userId = insertUser(connection);
                long workspaceId = insertWorkspace(connection, userId);
                insertMembership(connection, workspaceId, userId);
                try (PreparedStatement insert = connection.prepareStatement(
                        """
                        INSERT INTO job (
                            workspace_id, requested_by_user_id, job_type, resource_type, resource_id, resource_version,
                            stage, processing_configuration_hash, state, available_at
                        )
                        VALUES (?, ?, 'worker-test', 'queue-test-resource', 1, 1, 'execute', ?, 'QUEUED', ?)
                        RETURNING id
                        """)) {
                    insert.setLong(1, workspaceId);
                    insert.setLong(2, userId);
                    insert.setString(3, CONFIGURATION_HASH);
                    insert.setObject(4, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
                    try (ResultSet result = insert.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        long jobId = result.getLong(1);
                        connection.commit();
                        return jobId;
                    }
                }
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static long insertUser(Connection connection) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO user_identity (issuer, subject) VALUES (?, ?) RETURNING id")) {
            insert.setString(1, "https://worker-boundary-test.invalid");
            insert.setString(2, UUID.randomUUID().toString());
            try (ResultSet result = insert.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private static long insertWorkspace(Connection connection, long userId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO workspace (owner_user_id) VALUES (?) RETURNING id")) {
            insert.setLong(1, userId);
            try (ResultSet result = insert.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private static void insertMembership(Connection connection, long workspaceId, long userId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO workspace_member (workspace_id, user_id, role, state) VALUES (?, ?, 'OWNER', 'ACTIVE')")) {
            insert.setLong(1, workspaceId);
            insert.setLong(2, userId);
            insert.executeUpdate();
        }
    }

    private static Connection migrationConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
    }
}
