package io.github.vihuynh72.brownie.core.workspace;

import java.time.OffsetDateTime;

/**
 * A user's membership in a workspace, identified by the (workspace, user)
 * pair itself rather than a surrogate ID -- the simplest case of the
 * composite-key, workspace-scoped pattern every later tenant-owned table
 * follows.
 */
public record WorkspaceMember(
        long workspaceId,
        long userId,
        WorkspaceRole role,
        WorkspaceMemberState state,
        OffsetDateTime createdAt) {
}
