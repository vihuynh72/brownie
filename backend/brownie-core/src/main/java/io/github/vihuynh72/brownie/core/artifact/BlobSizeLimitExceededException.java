package io.github.vihuynh72.brownie.core.artifact;

import java.io.IOException;

/** Thrown by a {@link BlobStore} when a stream being written exceeds the caller's declared size limit. */
public class BlobSizeLimitExceededException extends IOException {

    public BlobSizeLimitExceededException(long maxBytes) {
        super("Upload exceeds the maximum allowed size of " + maxBytes + " bytes.");
    }
}
