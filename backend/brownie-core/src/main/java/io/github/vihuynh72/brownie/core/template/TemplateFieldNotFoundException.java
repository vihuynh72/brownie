package io.github.vihuynh72.brownie.core.template;

/** The template has no field by that ID in its open draft or its active version. */
public class TemplateFieldNotFoundException extends RuntimeException {

    public TemplateFieldNotFoundException(long templateId, String fieldId) {
        super("Template " + templateId + " has no field \"" + fieldId + "\".");
    }
}
