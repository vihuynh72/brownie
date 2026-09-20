package io.github.vihuynh72.brownie.core.retention;

import java.util.List;

/**
 * The worker's whole view of deletion: routines it may execute, never a
 * table it may read. It receives request ids and opaque object keys and
 * nothing else.
 */
public interface DeletionSweepRepository {

    /** Carries out up to {@code limit} trash entries whose retention has run out, oldest first. */
    List<ExpiredTrashResult> purgeExpiredTrash(int limit);

    /** Hands out up to {@code limit} objects still waiting to be removed, counting the attempt. */
    List<DeletionBlobTask> collectPendingBlobDeletions(int limit);

    /** Records one object as gone; closes its request when that was the last thing left of it. */
    boolean markBlobDeleted(long taskId, String objectKey);

    /** Purged requests with no object waiting, which still need their closing recount. */
    List<Long> collectUnverifiedDeletions(int limit);

    boolean verifyDeletion(long requestId);
}
