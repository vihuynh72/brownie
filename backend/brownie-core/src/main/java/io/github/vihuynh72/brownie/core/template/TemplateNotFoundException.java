package io.github.vihuynh72.brownie.core.template;

/** No template by that ID exists in the caller's workspace -- whether it never existed or belongs to someone else. */
public class TemplateNotFoundException extends RuntimeException {

    public TemplateNotFoundException(long templateId) {
        super("No template " + templateId + " in this workspace.");
    }
}
