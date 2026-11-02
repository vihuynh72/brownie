package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.api.detail.DetailSealer;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.detail.DetailKey;
import io.github.vihuynh72.brownie.core.detail.DetailOrigin;
import io.github.vihuynh72.brownie.core.detail.SavedDetail;
import io.github.vihuynh72.brownie.core.detail.SavedDetailRepository;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
@DockerTest
// One transaction per test, rolled back at the end: TenantContext's set_config is
// transaction-scoped (is_local=true), so the RLS-scoped calls need it to see the owner.
@Transactional
class SavedDetailRepositoryIntegrationTest {

    private static final String ISSUER = "https://issuer-saved-detail";

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> "brownie_api_local_only");
        registry.add("spring.flyway.url", DB::jdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> "brownie_migration_local_only");
    }

    @Autowired
    private SavedDetailRepository savedDetailRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DetailSealer detailSealer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long ensureUser(String subject) {
        userIdentityRepository.recordLogin(ISSUER, subject, null, null);
        return userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow().id();
    }

    private long ensureWorkspace(long userId) {
        return workspaceRepository.ensurePersonalWorkspace(userId).id();
    }

    @Test
    void upsertInsertsThenUpdatesTheSameKey() {
        long user = ensureUser("subject-upsert");
        long workspace = ensureWorkspace(user);

        SavedDetail first = savedDetailRepository.upsert(
                user, workspace, "address.line1", "Street address", "1180 Harbor Way", null, null, false,
                DetailOrigin.MANUAL, null, null, null);
        assertThat(first.valueText()).isEqualTo("1180 Harbor Way");

        SavedDetail again = savedDetailRepository.upsert(
                user, workspace, "address.line1", "Street address", "1450 Mission St", null, null, false,
                DetailOrigin.AUTO, null, "Address", "Lease");
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.valueText()).isEqualTo("1450 Mission St");
        assertThat(again.sourceLabel()).isEqualTo("Address");
        assertThat(again.sourceTitle()).isEqualTo("Lease");
        assertThat(again.origin()).isEqualTo(DetailOrigin.AUTO);
        assertThat(again.confirmedAt()).isAfterOrEqualTo(first.confirmedAt());
        assertThat(savedDetailRepository.list(user)).hasSize(1);
    }

    @Test
    void aSensitiveRowKeepsOnlyTheSealedValueAndItsHint() {
        long user = ensureUser("subject-sensitive");
        long workspace = ensureWorkspace(user);

        DetailSealer sealer = DetailSealer.fromBase64(Base64.getEncoder().encodeToString(new byte[32])).orElseThrow();
        byte[] sealed = sealer.seal("123-45-6789");

        SavedDetail row = savedDetailRepository.upsert(
                user, workspace, "sensitive.ssn", "Social security number", null, sealed, "6789", true,
                DetailOrigin.MANUAL, null, null, null);
        assertThat(row.valueText()).isNull();
        assertThat(row.valueSealed()).isNotNull();
        assertThat(row.valueHint()).isEqualTo("6789");
        assertThat(row.sensitive()).isTrue();
        // And the sealed bytes still open to the value.
        assertThat(sealer.open(row.valueSealed())).isEqualTo("123-45-6789");
    }

    @Test
    void anotherOwnerCannotReadUpdateOrDeleteARow() {
        long owner = ensureUser("subject-owner");
        long ownerWorkspace = ensureWorkspace(owner);
        savedDetailRepository.upsert(
                owner, ownerWorkspace, "contact.email", "Email", "owner@example.com", null, null, false,
                DetailOrigin.MANUAL, null, null, null);

        long other = ensureUser("subject-other");
        long otherWorkspace = ensureWorkspace(other);
        // The other owner sees none of it, and their delete touches nothing.
        assertThat(savedDetailRepository.list(other)).isEmpty();
        savedDetailRepository.delete(other, "contact.email");
        assertThat(savedDetailRepository.list(owner)).hasSize(1);

        // And the same key in the other owner's rows is a different row, not an overwrite.
        savedDetailRepository.upsert(
                other, otherWorkspace, "contact.email", "Email", "other@example.com", null, null, false,
                DetailOrigin.MANUAL, null, null, null);
        List<SavedDetail> mine = savedDetailRepository.list(owner);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).valueText()).isEqualTo("owner@example.com");
        assertThat(savedDetailRepository.list(other).get(0).valueText()).isEqualTo("other@example.com");
    }

    @Test
    void deletingTheOwnerTakesTheDetailsWithIt() {
        long user = ensureUser("subject-cascade");
        long workspace = ensureWorkspace(user);
        savedDetailRepository.upsert(
                user, workspace, "person.full_name", "Full name", "Daniel Ortiz", null, null, false,
                DetailOrigin.MANUAL, null, null, null);
        assertThat(savedDetailRepository.list(user)).hasSize(1);

        // The account-deletion path: delete_workspace removes the workspace and, when the
        // person has no other one, the identity row -- cascading saved_detail with them.
        TenantContext.setCurrentUser(jdbcTemplate, user);
        jdbcTemplate.queryForObject("SELECT delete_workspace(?)", String.class, workspace);
        assertThat(savedDetailRepository.list(user)).isEmpty();
    }

    @Test
    void deleteAllClearsEveryRow() {
        long user = ensureUser("subject-delete-all");
        long workspace = ensureWorkspace(user);
        for (DetailKey key : List.of(DetailKey.PERSON_FULL_NAME, DetailKey.CONTACT_EMAIL)) {
            savedDetailRepository.upsert(
                    user, workspace, key.key(), key.label(), "a value", null, null, false,
                    DetailOrigin.MANUAL, null, null, null);
        }
        assertThat(savedDetailRepository.list(user)).hasSize(2);
        savedDetailRepository.deleteAll(user);
        assertThat(savedDetailRepository.list(user)).isEmpty();
    }
}
