package io.github.vihuynh72.brownie.api.workspace;

import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapabilities;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRole;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * The one place a controller asks "is this actor actually allowed to do
 * this, in this workspace" -- a real database lookup (subject to the same
 * row-level security every other workspace query is), not a check against
 * whatever the caller merely claims. Denial is a thrown {@link
 * AccessDeniedException} rather than a boolean so a caller cannot forget
 * to check a return value.
 */
@Service
public class WorkspaceAuthorizationService {

    private final WorkspaceRepository workspaceRepository;

    public WorkspaceAuthorizationService(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    public void requireCapability(long userId, long workspaceId, WorkspaceCapability capability) {
        WorkspaceRole role = workspaceRepository
                .findRole(workspaceId, userId)
                .orElseThrow(() -> new AccessDeniedException("Not a member of this workspace."));
        if (!WorkspaceCapabilities.grants(role, capability)) {
            throw new AccessDeniedException("Role " + role + " does not grant " + capability + ".");
        }
    }
}
