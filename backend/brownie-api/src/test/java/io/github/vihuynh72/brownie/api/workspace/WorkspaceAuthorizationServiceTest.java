package io.github.vihuynh72.brownie.api.workspace;

import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceMember;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceAuthorizationServiceTest {

    @Test
    void allowsAnOwnerToManageTheirOwnWorkspace() {
        WorkspaceAuthorizationService service =
                new WorkspaceAuthorizationService(fakeRepository(Map.of(1L, WorkspaceRole.OWNER)));

        assertThatCode(() -> service.requireCapability(7L, 1L, WorkspaceCapability.MANAGE_WORKSPACE))
                .doesNotThrowAnyException();
    }

    @Test
    void deniesSomeoneWithNoMembershipInTheWorkspace() {
        WorkspaceAuthorizationService service = new WorkspaceAuthorizationService(fakeRepository(Map.of()));

        assertThatThrownBy(() -> service.requireCapability(7L, 1L, WorkspaceCapability.MANAGE_WORKSPACE))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static WorkspaceRepository fakeRepository(Map<Long, WorkspaceRole> rolesByWorkspaceId) {
        return new WorkspaceRepository() {
            @Override
            public Workspace ensurePersonalWorkspace(long ownerUserId) {
                throw new UnsupportedOperationException("not needed by this test");
            }

            @Override
            public List<WorkspaceMember> findMembershipsForUser(long userId) {
                throw new UnsupportedOperationException("not needed by this test");
            }

            @Override
            public Optional<WorkspaceRole> findRole(long workspaceId, long userId) {
                return Optional.ofNullable(rolesByWorkspaceId.get(workspaceId));
            }
        };
    }
}
