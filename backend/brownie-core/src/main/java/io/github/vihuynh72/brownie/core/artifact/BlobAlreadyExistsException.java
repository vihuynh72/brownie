package io.github.vihuynh72.brownie.core.artifact;

import java.io.IOException;

/** A create-only object write found an existing object and did not replace it. */
public final class BlobAlreadyExistsException extends IOException {

    public BlobAlreadyExistsException(String objectKey, Throwable cause) {
        super("An object already exists at " + objectKey + ".", cause);
    }
}
