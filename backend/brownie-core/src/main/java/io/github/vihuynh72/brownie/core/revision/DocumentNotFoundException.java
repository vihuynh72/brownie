package io.github.vihuynh72.brownie.core.revision;

/** No document by that ID is visible in the caller's workspace. */
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(long documentId) {
        super("No document " + documentId + " in this workspace.");
    }
}
