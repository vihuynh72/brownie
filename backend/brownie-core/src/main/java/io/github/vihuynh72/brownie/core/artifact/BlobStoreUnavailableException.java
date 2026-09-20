package io.github.vihuynh72.brownie.core.artifact;

import java.io.IOException;

/**
 * The store itself could not be reached: nothing is known to be wrong with
 * the object, and the same request is worth making again once the store is
 * back. Different from every other storage failure, which says something
 * about the object and is not fixed by waiting.
 */
public class BlobStoreUnavailableException extends IOException {

    public BlobStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
