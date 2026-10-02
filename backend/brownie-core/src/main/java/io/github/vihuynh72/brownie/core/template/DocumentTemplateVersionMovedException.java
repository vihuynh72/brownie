package io.github.vihuynh72.brownie.core.template;

/**
 * A document's fill spots can only be changed from the template's current
 * version, so version history stays one straight line: this document is on
 * an older one (or not on the version the request names), and moves to the
 * current one first.
 */
public class DocumentTemplateVersionMovedException extends RuntimeException {

    private final long currentTemplateVersionId;

    public DocumentTemplateVersionMovedException(long documentId, long documentVersionId, long currentTemplateVersionId) {
        super("Document " + documentId + " is on template version " + documentVersionId + ", not the template's current version "
                + currentTemplateVersionId + ".");
        this.currentTemplateVersionId = currentTemplateVersionId;
    }

    public long currentTemplateVersionId() {
        return currentTemplateVersionId;
    }
}
