package io.github.vihuynh72.brownie.core.retention;

/** A rejected file whose stored bytes are due for removal, named by id and opaque object key only. */
public record RemovablePayload(long artifactId, long workspaceId, String blobKey) {

    public RemovablePayload {
        if (blobKey == null || blobKey.isBlank()) {
            throw new IllegalArgumentException("blobKey must not be blank.");
        }
    }
}
