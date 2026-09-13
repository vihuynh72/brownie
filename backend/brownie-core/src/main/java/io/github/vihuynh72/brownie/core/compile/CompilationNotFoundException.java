package io.github.vihuynh72.brownie.core.compile;

/** No compilation has been recorded yet for this exact document revision. */
public class CompilationNotFoundException extends RuntimeException {

    public CompilationNotFoundException(long documentId, long revisionId) {
        super("No compilation recorded for document " + documentId + " revision " + revisionId + ".");
    }
}
