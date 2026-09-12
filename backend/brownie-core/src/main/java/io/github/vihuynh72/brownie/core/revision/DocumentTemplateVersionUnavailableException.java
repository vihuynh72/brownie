package io.github.vihuynh72.brownie.core.revision;

/** The requested template version is absent, hidden, or not active. */
public class DocumentTemplateVersionUnavailableException extends RuntimeException {

    public DocumentTemplateVersionUnavailableException(long templateId, long templateVersionId) {
        super("Template " + templateId + " version " + templateVersionId + " is not available for a document.");
    }
}
