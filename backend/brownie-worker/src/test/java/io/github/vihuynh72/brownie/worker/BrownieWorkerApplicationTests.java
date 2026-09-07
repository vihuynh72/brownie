package io.github.vihuynh72.brownie.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Loads the real application context under the "test" profile. Startup
 * itself normally goes through {@link BrownieWorkerApplication#main} so
 * that {@code BROWNIE_ENVIRONMENT} selects the profile; here the profile
 * is set directly since a JUnit-managed context does not go through that
 * entry point.
 *
 * <p>Database autoconfiguration is excluded so this stays a fast,
 * Docker-free smoke test of the application's own wiring. The real
 * database connection, using real credentials against a real Postgres, is
 * proven separately in {@link io.github.vihuynh72.brownie.worker.persistence.BrownieWorkerDatabaseIntegrationTest}.
 */
@SpringBootTest
@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
@ActiveProfiles("test")
class BrownieWorkerApplicationTests {

    @Test
    void contextLoads() {
    }
}
