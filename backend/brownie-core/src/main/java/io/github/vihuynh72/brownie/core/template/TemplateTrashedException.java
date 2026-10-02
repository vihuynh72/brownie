package io.github.vihuynh72.brownie.core.template;

/** A new document was asked for from a template that is in the Trash Bin; documents already made from it are unaffected. */
public class TemplateTrashedException extends RuntimeException {

    public TemplateTrashedException() {
        super("This template is in the Trash Bin. Restore it to start a document from it.");
    }
}
