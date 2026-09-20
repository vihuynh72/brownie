package io.github.vihuynh72.brownie.core.retention;

/**
 * One stored object a permanent deletion still has to remove. The key is
 * opaque to the worker that receives it: a workspace number plus a random
 * or job-derived suffix, never a title or a filename.
 */
public record DeletionBlobTask(long id, long workspaceId, long deletionRequestId, String objectKey, int attemptCount) {

    public DeletionBlobTask {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank.");
        }
    }
}
