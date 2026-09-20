package io.github.vihuynh72.brownie.core.retention;

import java.util.Objects;

/**
 * The result of asking to delete a whole workspace. {@code requestId} names
 * the ledger entry and is present only for {@link PurgeOutcome#PURGED};
 * nobody can read that entry through the application afterwards, because
 * the membership that would authorize the read went with the workspace.
 */
public record WorkspaceDeletion(PurgeOutcome outcome, Long requestId) {

    public WorkspaceDeletion {
        Objects.requireNonNull(outcome, "outcome");
    }
}
