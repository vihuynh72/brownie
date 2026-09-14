package io.github.vihuynh72.brownie.core.validation;

/** No validation manifest has been recorded yet for this exact document revision, or no manifest exists with a given exact id. */
public class ValidationManifestNotFoundException extends RuntimeException {

    private ValidationManifestNotFoundException(String message) {
        super(message);
    }

    public ValidationManifestNotFoundException(long documentId, long revisionId) {
        this("No validation manifest recorded for document " + documentId + " revision " + revisionId + ".");
    }

    /** Distinct from the (documentId, revisionId) constructor above -- looked up by the manifest's own bare id, not "latest for a revision." */
    public static ValidationManifestNotFoundException forManifestId(long documentId, long validationManifestId) {
        return new ValidationManifestNotFoundException(
                "No validation manifest " + validationManifestId + " recorded for document " + documentId + ".");
    }
}
