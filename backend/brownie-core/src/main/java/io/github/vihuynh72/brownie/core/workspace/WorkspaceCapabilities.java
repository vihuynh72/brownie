package io.github.vihuynh72.brownie.core.workspace;

/**
 * Pure decision logic, no data access: which capabilities a given role
 * grants. Only OWNER exists today and it grants everything that exists
 * today -- this is still the real extension point later roles plug into,
 * not a placeholder deferring the decision.
 */
public final class WorkspaceCapabilities {

    private WorkspaceCapabilities() {
    }

    public static boolean grants(WorkspaceRole role, WorkspaceCapability capability) {
        return switch (role) {
            case OWNER -> true;
        };
    }
}
