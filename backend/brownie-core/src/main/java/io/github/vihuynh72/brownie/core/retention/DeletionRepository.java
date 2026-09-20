package io.github.vihuynh72.brownie.core.retention;

import java.util.List;
import java.util.Optional;

/**
 * What a signed-in member may ask of the deletion ledger. Every method
 * takes workspace and actor context, and every write crosses a
 * tenant-checked database routine rather than touching a table: no runtime
 * role may insert into, update or delete from the ledger directly.
 */
public interface DeletionRepository {

    /** The open trash entry for this document, created if it was live; empty when no such document is visible to this member. */
    Optional<Long> trashDocument(long workspaceId, long userId, long documentId, int retentionDays);

    RestoreOutcome restoreDocument(long workspaceId, long userId, long requestId);

    PurgeOutcome purgeDocument(long workspaceId, long userId, long requestId);

    WorkspaceDeletion deleteWorkspace(long workspaceId, long userId);

    Optional<DeletionRequest> find(long workspaceId, long userId, long requestId);

    /** Every request of the workspace, most recent first. */
    List<DeletionRequest> findAll(long workspaceId, long userId);
}
