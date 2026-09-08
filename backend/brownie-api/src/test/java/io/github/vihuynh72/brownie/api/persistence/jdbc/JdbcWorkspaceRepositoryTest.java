package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceMember;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceMemberState;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRole;
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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the personal-workspace invariant: every user gets exactly one
 * personal workspace, created idempotently,
 * with its owner also recorded as a workspace member -- against a real,
 * disposable Postgres so the partial unique index in {@code
 * V4__create_workspace.sql} is actually exercised, not just assumed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class JdbcWorkspaceRepositoryTest {

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
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Test
    void firstCallCreatesThePersonalWorkspaceAndOwnerMembership() {
        long ownerId = newUser("subject-a").id();

        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(ownerId);

        assertThat(workspace.ownerUserId()).isEqualTo(ownerId);
        assertThat(workspace.personal()).isTrue();
        List<WorkspaceMember> memberships = workspaceRepository.findMembershipsForUser(ownerId);
        assertThat(memberships).hasSize(1);
        WorkspaceMember membership = memberships.get(0);
        assertThat(membership.workspaceId()).isEqualTo(workspace.id());
        assertThat(membership.role()).isEqualTo(WorkspaceRole.OWNER);
        assertThat(membership.state()).isEqualTo(WorkspaceMemberState.ACTIVE);
    }

    @Test
    void secondCallForTheSameOwnerReturnsTheSameWorkspaceWithoutDuplicating() {
        long ownerId = newUser("subject-b").id();

        Workspace first = workspaceRepository.ensurePersonalWorkspace(ownerId);
        Workspace second = workspaceRepository.ensurePersonalWorkspace(ownerId);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(workspaceRepository.findMembershipsForUser(ownerId)).hasSize(1);
    }

    @Test
    void differentOwnersGetDifferentPersonalWorkspaces() {
        long ownerA = newUser("subject-c").id();
        long ownerB = newUser("subject-d").id();

        Workspace workspaceA = workspaceRepository.ensurePersonalWorkspace(ownerA);
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(ownerB);

        assertThat(workspaceA.id()).isNotEqualTo(workspaceB.id());
    }

    @Test
    void revokedMembershipNoLongerGrantsARoleThroughTheRealApplicationRole() throws SQLException {
        long ownerId = newUser("subject-e").id();
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(ownerId);
        assertThat(workspaceRepository.findRole(workspace.id(), ownerId)).isPresent();

        // brownie_api has no policy letting it delete a membership row at
        // all -- there is no revoke-membership feature yet to exercise
        // instead, since only single-owner personal workspaces exist so
        // far. This reaches into the database the way an operator would
        // have to today, as brownie_migration (the table-owning role RLS
        // does not apply to), purely to set up the "membership is gone"
        // state this test actually cares about checking.
        try (Connection connection =
                        DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?")) {
            delete.setLong(1, workspace.id());
            delete.setLong(2, ownerId);
            delete.executeUpdate();
        }

        assertThat(workspaceRepository.findRole(workspace.id(), ownerId)).isEmpty();
    }

    private UserIdentity newUser(String subject) {
        return userIdentityRepository.recordLogin(
                "https://issuer-for-workspace-tests", subject, subject + "@example.com", subject);
    }
}
