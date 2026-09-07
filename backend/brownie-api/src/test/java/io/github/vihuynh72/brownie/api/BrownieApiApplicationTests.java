package io.github.vihuynh72.brownie.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Loads the real application context under the "test" profile. Startup
 * itself normally goes through {@link BrownieApiApplication#main} so that
 * {@code BROWNIE_ENVIRONMENT} selects the profile; here the profile is set
 * directly since a JUnit-managed context does not go through that entry
 * point.
 *
 * <p>Database and Flyway autoconfiguration are excluded so this stays a
 * fast, Docker-free smoke test of the application's own wiring. The real
 * database connection, using real credentials against a real Postgres, is
 * proven separately in {@link io.github.vihuynh72.brownie.api.persistence.BrownieApiDatabaseIntegrationTest}.
 */
@SpringBootTest
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@ActiveProfiles("test")
class BrownieApiApplicationTests {

    @Test
    void contextLoads() {
    }
}
