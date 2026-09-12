package io.github.vihuynh72.brownie.core.workspace;

/** Only OWNER-level capabilities exist today; finer-grained capabilities arrive once team roles do. */
public enum WorkspaceCapability {
    MANAGE_WORKSPACE,
    MANAGE_ARTIFACTS,
    MANAGE_TEMPLATES
}
