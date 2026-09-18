package io.github.vihuynh72.brownie.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application context assembles: every bean, every configuration
 * property binding, and every conditional resolves without a real
 * Postgres, identity provider, blob store, scanner, or model.
 *
 * <p>Flyway is excluded because the migrations are written in
 * Postgres-specific SQL ({@code BIGSERIAL}, {@code TIMESTAMPTZ}) and this
 * test intentionally runs against a disposable in-memory H2 database (the
 * {@code test} profile block in {@code application.yml}) so it stays fast
 * and Docker-free. The datasource itself is not excluded: every JDBC
 * repository needs a real one to even wire up, so this context load is
 * already the proof that they do. The real Postgres connection, using
 * real credentials against a real Postgres, is proven separately in
 * {@link io.github.vihuynh72.brownie.api.persistence.BrownieApiDatabaseIntegrationTest}.
 */
@SpringBootTest
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class BrownieApiApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
    }

    /** With no test-support token configured (this profile sets none), the session-seeding route must not exist at all, not merely refuse. */
    @Test
    void theTestSupportRouteDoesNotExistWithoutItsToken() {
        assertThat(context.containsBean("testSupportAuthController")).isFalse();
    }
}
