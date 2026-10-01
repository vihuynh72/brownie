package io.github.vihuynh72.brownie.core.revision;

/** A document was asked to move to the template version it is already on. */
public class DocumentAlreadyOnTemplateVersionException extends RuntimeException {

    public DocumentAlreadyOnTemplateVersionException(long documentId, long templateVersionId) {
        super("Document " + documentId + " is already on template version " + templateVersionId + ".");
    }
}
