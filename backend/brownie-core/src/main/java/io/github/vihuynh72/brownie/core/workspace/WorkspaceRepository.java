package io.github.vihuynh72.brownie.core.workspace;

import java.util.List;
import java.util.Optional;

/**
 * Every method here takes workspace (or user) context explicitly rather
 * than a bare resource ID -- the pattern every later tenant-scoped
 * repository (artifact, document, ...) must also follow.
 */
public interface WorkspaceRepository {

    /**
     * Creates the owner's one personal workspace and owner membership on
     * first call; later calls for the same owner return that same
     * workspace unchanged.
     */
    Workspace ensurePersonalWorkspace(long ownerUserId);

    List<WorkspaceMember> findMembershipsForUser(long userId);

    /** Empty when the user has no membership in that workspace at all. */
    Optional<WorkspaceRole> findRole(long workspaceId, long userId);
}
