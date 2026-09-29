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
    MANAGE_CONNECTIONS,
    /**
     * Approving a change in a connected account: a file saved to someone's
     * Drive, an event added to their calendar. Separate from connecting,
     * because being allowed to link an account for reading is not being
     * allowed to change what is in it.
     */
    ACT_ON_CONNECTED_ACCOUNTS
}
