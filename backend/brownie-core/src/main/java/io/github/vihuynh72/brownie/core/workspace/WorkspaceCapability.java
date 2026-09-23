package io.github.vihuynh72.brownie.core.workspace;

/** Only OWNER-level capabilities exist today; finer-grained capabilities arrive once team roles do. */
public enum WorkspaceCapability {
    MANAGE_WORKSPACE,
    MANAGE_ARTIFACTS,
    MANAGE_TEMPLATES,
    /**
     * Connecting an outside account, such as a person's Google account, and
     * disconnecting it. Separate from managing files because being allowed to
     * work on a workspace's documents is not being allowed to reach into
     * someone's account elsewhere.
     */
    MANAGE_CONNECTIONS
}
