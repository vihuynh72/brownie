package io.github.vihuynh72.brownie.core.retention;

import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;

import java.util.List;
import java.util.Objects;

/**
 * Trash, restore and permanent deletion as a person experiences them.
 * Turns the database routines' plain outcomes into the exceptions the rest
 * of the application already speaks, and owns the one policy number
 * involved: how many days something stays in the trash before the worker
 * deletes it for good. It deletes nothing itself and never touches blob
 * storage; permanent deletion removes database rows inside the routine it
 * calls, and the stored objects are removed afterwards by the worker's
 * sweep of the queue that routine filled.
 */
public class DeletionService {

    /** Bounds the database routine enforces too; checked here so a bad setting fails at startup, not on the first request. */
    public static final int MIN_TRASH_RETENTION_DAYS = 1;
    public static final int MAX_TRASH_RETENTION_DAYS = 365;

    private final DeletionRepository deletionRepository;
    private final int trashRetentionDays;

    public DeletionService(DeletionRepository deletionRepository, int trashRetentionDays) {
        this.deletionRepository = Objects.requireNonNull(deletionRepository, "deletionRepository");
        if (trashRetentionDays < MIN_TRASH_RETENTION_DAYS || trashRetentionDays > MAX_TRASH_RETENTION_DAYS) {
            throw new IllegalArgumentException("The trash retention must be between " + MIN_TRASH_RETENTION_DAYS
                    + " and " + MAX_TRASH_RETENTION_DAYS + " days, was " + trashRetentionDays + ".");
        }
        this.trashRetentionDays = trashRetentionDays;
    }

    public int trashRetentionDays() {
        return trashRetentionDays;
    }

    /** Idempotent: trashing a document already in the trash answers with the entry that holds it. */
    public DeletionRequest trashDocument(long workspaceId, long userId, long documentId) {
        long requestId = deletionRepository
                .trashDocument(workspaceId, userId, documentId, trashRetentionDays)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return require(workspaceId, userId, requestId);
    }

    /** Idempotent: restoring something already restored answers with the same entry. */
    public DeletionRequest restoreDocument(long workspaceId, long userId, long requestId) {
        return switch (deletionRepository.restoreDocument(workspaceId, userId, requestId)) {
            case RESTORED -> require(workspaceId, userId, requestId);
            case NOT_OPEN -> throw new DeletionStateConflictException(
                    "This was already deleted for good and cannot be restored.");
            case NOT_FOUND -> throw new DeletionRequestNotFoundException(requestId);
        };
    }

    /** Idempotent: deleting something already deleted for good answers with the same entry. */
    public DeletionRequest purgeDocument(long workspaceId, long userId, long requestId) {
        return switch (deletionRepository.purgeDocument(workspaceId, userId, requestId)) {
            case PURGED -> require(workspaceId, userId, requestId);
            case NOT_OPEN -> throw new DeletionStateConflictException(
                    "This was restored from the trash, so there is nothing to delete.");
            case JOBS_STILL_STOPPING -> throw new DeletionWaitingForRunningWorkException(
                    "A run for this document is still stopping. Try again in a moment.");
            case NOT_FOUND -> throw new DeletionRequestNotFoundException(requestId);
        };
    }

    /**
     * Deletes the caller's own workspace, entirely or not at all. There is
     * deliberately no "scheduled" outcome: while a worker still holds one of
     * the workspace's jobs nothing but that job's cancellation request has
     * changed, and the caller is told to ask again in a moment. A deletion
     * left to finish by itself would let the person sign back in to a
     * workspace that then disappears under them.
     */
    public long deleteWorkspace(long workspaceId, long userId) {
        WorkspaceDeletion deletion = deletionRepository.deleteWorkspace(workspaceId, userId);
        return switch (deletion.outcome()) {
            case PURGED -> deletion.requestId();
            case JOBS_STILL_STOPPING -> throw new DeletionWaitingForRunningWorkException(
                    "A run in this workspace is still stopping, so nothing was deleted. Try again in a moment.");
            case NOT_FOUND, NOT_OPEN -> throw new WorkspaceDeletionNotPermittedException();
        };
    }

    public DeletionRequest find(long workspaceId, long userId, long requestId) {
        return require(workspaceId, userId, requestId);
    }

    public List<DeletionRequest> findAll(long workspaceId, long userId) {
        return deletionRepository.findAll(workspaceId, userId);
    }

    private DeletionRequest require(long workspaceId, long userId, long requestId) {
        return deletionRepository
                .find(workspaceId, userId, requestId)
                .orElseThrow(() -> new DeletionRequestNotFoundException(requestId));
    }
}
