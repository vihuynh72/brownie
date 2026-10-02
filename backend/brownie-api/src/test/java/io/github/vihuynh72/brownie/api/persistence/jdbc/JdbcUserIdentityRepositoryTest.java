package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves issuer-subject identity mapping against a real, disposable
 * Postgres, run with {@code WebEnvironment.NONE} since a repository test
 * needs no servlet context or security filter chain -- only Flyway's
 * migrations (including {@code V3__create_user_identity.sql}) and the
 * {@code brownie_api} runtime role.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DockerTest
class JdbcUserIdentityRepositoryTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", DB::jdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    }

    @Autowired
    private UserIdentityRepository repository;

    @Test
    void firstLoginCreatesTheIdentity() {
        UserIdentity created = repository.recordLogin("https://issuer-a", "subject-1", "a@example.com", "Ada");

        assertThat(created.id()).isPositive();
        assertThat(created.issuer()).isEqualTo("https://issuer-a");
        assertThat(created.subject()).isEqualTo("subject-1");
        assertThat(created.email()).isEqualTo("a@example.com");
        assertThat(created.displayName()).isEqualTo("Ada");
        assertThat(created.disabledAt()).isNull();
    }

    @Test
    void laterLoginsUpdateContactFieldsWithoutCreatingADuplicateRow() {
        UserIdentity first = repository.recordLogin("https://issuer-b", "subject-2", "old@example.com", "Old Name");
        UserIdentity second = repository.recordLogin("https://issuer-b", "subject-2", "new@example.com", "New Name");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.email()).isEqualTo("new@example.com");
        assertThat(second.displayName()).isEqualTo("New Name");
        assertThat(second.lastLoginAt()).isAfterOrEqualTo(first.lastLoginAt());
    }

    @Test
    void sameSubjectFromDifferentIssuersAreDistinctIdentities() {
        UserIdentity fromIssuerC = repository.recordLogin("https://issuer-c", "shared-subject", null, null);
        UserIdentity fromIssuerD = repository.recordLogin("https://issuer-d", "shared-subject", null, null);

        assertThat(fromIssuerC.id()).isNotEqualTo(fromIssuerD.id());
    }

    @Test
    void findByIssuerAndSubjectReturnsEmptyWhenNeverLoggedIn() {
        Optional<UserIdentity> result = repository.findByIssuerAndSubject("https://issuer-unknown", "nobody");

        assertThat(result).isEmpty();
    }
}
