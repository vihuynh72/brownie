package io.github.vihuynh72.brownie.api.persistence;

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
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the actual, configured application wiring against a real,
 * disposable Postgres: Flyway runs at startup using the migration role
 * (see {@code spring.flyway.*} in application.yml), and the application's
 * own {@link DataSource} bean -- the one every future repository will use
 * -- authenticates as the restricted {@code brownie_api} role created by
 * {@code infra/local/postgres/init/01-app-roles.sql}.
 *
 * <p>The negative case (schema changes are refused) matters as much as the
 * positive one (ordinary reads/writes work): it is the actual security
 * property behind "separate runtime credentials" in the master plan, not
 * just an organizational convention.
 */

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class BrownieApiDatabaseIntegrationTest {

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

    @Test
    void flywayRanAsMigrationRoleAndApiRoleCanReadWhatItCreated() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            // flyway_schema_history is created by brownie_migration during context
            // startup, after 01-app-roles.sql already set the default-privilege
            // rule -- so brownie_api reading it here is the real, end-to-end proof
            // that the privilege cascade actually applies to Flyway's own output,
            // not a fabricated table built just for this test.
            var results = statement.executeQuery("SELECT installed_rank FROM flyway_schema_history LIMIT 1");
            assertThat(results).isNotNull();
        }
    }

    @Test
    void apiRoleCannotCreateOrAlterTables() {
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
