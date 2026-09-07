package io.github.vihuynh72.brownie.worker.persistence;

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
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the worker's own configured {@link DataSource} bean -- worker
 * never runs Flyway, so unlike the API module there is no migration
 * bookkeeping table to read here -- authenticates as the restricted
 * {@code brownie_worker} role created by
 * {@code infra/local/postgres/init/01-app-roles.sql}, distinct from the
 * API module's {@code brownie_api} credential, and is equally forbidden
 * from schema changes.
 *
 * <p>Since there is no Flyway-created table to piggyback on here, this
 * test creates and drops its own throwaway table through a direct,
 * migration-role connection -- test scaffolding only, never a Flyway
 * migration -- purely to prove the default-privilege grant reaches
 * {@code brownie_worker} too.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class BrownieWorkerDatabaseIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String WORKER_PASSWORD = "brownie_worker_local_only";
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
        registry.add("spring.datasource.username", () -> "brownie_worker");
        registry.add("spring.datasource.password", () -> WORKER_PASSWORD);
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath()
                .getParent()
                .getParent()
                .resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void workerRoleCanReadWriteATableTheMigrationRoleCreated() throws SQLException {
        try (Connection migrationConnection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                Statement setup = migrationConnection.createStatement()) {
            setup.execute("CREATE TABLE worker_probe (id serial PRIMARY KEY, note text)");
            try {
                try (Connection workerConnection = dataSource.getConnection();
                        Statement workerStatement = workerConnection.createStatement()) {
                    workerStatement.execute("INSERT INTO worker_probe (note) VALUES ('from worker test')");
                    var results = workerStatement.executeQuery("SELECT count(*) AS total FROM worker_probe");
                    results.next();
                    assertEquals(1, results.getInt("total"));
                }
            } finally {
                setup.execute("DROP TABLE worker_probe");
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
}
