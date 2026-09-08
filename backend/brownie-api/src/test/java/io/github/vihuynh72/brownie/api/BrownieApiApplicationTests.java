package io.github.vihuynh72.brownie.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Loads the real application context under the "test" profile. Startup
 * itself normally goes through {@link BrownieApiApplication#main} so that
 * {@code BROWNIE_ENVIRONMENT} selects the profile; here the profile is set
 * directly since a JUnit-managed context does not go through that entry
 * point.
 *
 * <p>Flyway is excluded because its one migration is written in
 * Postgres-specific SQL ({@code BIGSERIAL}, {@code TIMESTAMPTZ}) and this
 * test intentionally runs against a disposable in-memory H2 database (the
 * {@code test} profile block in {@code application.yml}) so it stays fast
 * and Docker-free. The
 * datasource itself is not excluded: {@code PlatformProbeController}'s
 * repository needs a real one to even wire up, so this context load is
 * already the proof that it does. The real Postgres connection, using
 * real credentials against a real Postgres, is proven separately in
 * {@link io.github.vihuynh72.brownie.api.persistence.BrownieApiDatabaseIntegrationTest}.
 */
@SpringBootTest
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class BrownieApiApplicationTests {

    @Test
    void contextLoads() {
    }
}
