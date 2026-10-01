package io.github.vihuynh72.brownie.core.template;

/**
 * The template moved on to another version while a change to its fill spots
 * was being prepared (someone else changed them, in another tab or another
 * document). Versions stay in one straight line, so the change is not made
 * on top of an older one; the person reloads and makes it again.
 */
public class TemplateVersionMovedOnException extends RuntimeException {

    public TemplateVersionMovedOnException(long templateId, long baseVersionId, Long currentVersionId) {
        super("Template " + templateId + " moved on from version " + baseVersionId + " to " + currentVersionId
                + " while its fill spots were being changed.");
    }
}
