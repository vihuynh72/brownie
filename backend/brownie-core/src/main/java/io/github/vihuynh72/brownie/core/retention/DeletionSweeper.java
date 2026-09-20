package io.github.vihuynh72.brownie.core.retention;

import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * One pass of everything permanent deletion needs done in the background:
 * carry out trash whose time has run out, remove the stored objects that
 * deletions queued, and close requests that have nothing left. The only
 * component that ever deletes a deleted document's objects, so there is
 * exactly one place where "the rows are gone but a file is not" gets
 * resolved.
 */
public final class DeletionSweeper {

    private static final Logger log = LoggerFactory.getLogger(DeletionSweeper.class);
    private static final int FULLY_LOGGED_ATTEMPTS = 3;

    private final DeletionSweepRepository sweepRepository;
    private final BlobStore blobStore;

    public DeletionSweeper(DeletionSweepRepository sweepRepository, BlobStore blobStore) {
        this.sweepRepository = Objects.requireNonNull(sweepRepository, "sweepRepository must not be null");
        this.blobStore = Objects.requireNonNull(blobStore, "blobStore must not be null");
    }

    /** What one pass did, as counts only. */
    public record Result(int trashPurged, int trashFailed, int objectsRemoved, int objectsFailed, int requestsVerified) {

        public boolean didAnything() {
            return trashPurged + trashFailed + objectsRemoved + objectsFailed + requestsVerified > 0;
        }
    }

    /**
     * Deletes each object first and records it second. A process death
     * between the two is safe because {@link BlobStore#delete} is a no-op
     * for an object that is already gone: the next pass repeats the delete
     * and then records it. An object that cannot be deleted right now stays
     * queued, falls behind the ones with fewer attempts, and is tried again
     * on a later pass; whatever the store throws for it, the rest of the
     * batch and the closing recount still run.
     */
    public Result sweepOnce(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive.");
        }

        int purged = 0;
        int purgeFailed = 0;
        for (ExpiredTrashResult result : sweepRepository.purgeExpiredTrash(limit)) {
            if (result.failed()) {
                purgeFailed++;
                log.error("Deletion request {} could not be carried out and will be tried again.", result.requestId());
            } else if ("PURGED".equals(result.outcome())) {
                purged++;
            }
        }

        int removed = 0;
        int removeFailed = 0;
        List<DeletionBlobTask> tasks = sweepRepository.collectPendingBlobDeletions(limit);
        for (DeletionBlobTask task : tasks) {
            try {
                blobStore.delete(task.objectKey());
                if (sweepRepository.markBlobDeleted(task.id(), task.objectKey())) {
                    removed++;
                }
            } catch (IOException | RuntimeException failure) {
                removeFailed++;
                logRemovalFailure(task, failure);
            }
        }

        int verified = 0;
        for (long requestId : sweepRepository.collectUnverifiedDeletions(limit)) {
            if (sweepRepository.verifyDeletion(requestId)) {
                verified++;
            } else {
                log.error("Deletion request {} has nothing queued but something of its target is still present.", requestId);
            }
        }
        return new Result(purged, purgeFailed, removed, removeFailed, verified);
    }

    /** The cause is worth its stack trace the first few times; after that the attempt count says everything new there is to say. */
    private static void logRemovalFailure(DeletionBlobTask task, Exception failure) {
        if (task.attemptCount() <= FULLY_LOGGED_ATTEMPTS) {
            log.warn("Could not remove a stored object for deletion request {} (attempt {}).",
                    task.deletionRequestId(), task.attemptCount(), failure);
        } else {
            log.warn("Still could not remove a stored object for deletion request {} (attempt {}): {}",
                    task.deletionRequestId(), task.attemptCount(), failure.toString());
        }
    }
}
