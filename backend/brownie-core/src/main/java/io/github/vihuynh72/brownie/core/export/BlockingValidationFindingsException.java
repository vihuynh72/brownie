package io.github.vihuynh72.brownie.core.export;

/** A revision cannot be approved for export while its validation manifest still has an unresolved blocking finding. */
public class BlockingValidationFindingsException extends RuntimeException {

    public BlockingValidationFindingsException(long documentId, long validationManifestId) {
        super("Document " + documentId + "'s validation manifest " + validationManifestId
                + " has unresolved blocking findings; it cannot be approved for export.");
    }
}
