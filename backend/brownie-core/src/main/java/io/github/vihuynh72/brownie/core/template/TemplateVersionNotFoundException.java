package io.github.vihuynh72.brownie.core.template;

/** No version by that ID exists for that template in the caller's workspace -- whether it never existed or belongs to a different template. */
public class TemplateVersionNotFoundException extends RuntimeException {

    public TemplateVersionNotFoundException(long templateId, long versionId) {
        super("No version " + versionId + " for template " + templateId + " in this workspace.");
    }
}
