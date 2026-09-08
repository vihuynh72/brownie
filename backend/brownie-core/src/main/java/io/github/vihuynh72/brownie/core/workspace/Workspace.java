package io.github.vihuynh72.brownie.core.workspace;

import java.time.OffsetDateTime;

/**
 * A tenant boundary. Every user receives exactly one personal workspace
 * automatically at first login -- see {@link
 * WorkspaceRepository#ensurePersonalWorkspace}; additional, non-personal
 * workspaces are a later addition once team support exists.
 */
public record Workspace(
        long id,
        long ownerUserId,
        String locale,
        String timeZone,
        WorkspaceStatus status,
        int retentionPolicyVersion,
        String quotaPolicy,
        boolean personal,
        OffsetDateTime createdAt) {
}
