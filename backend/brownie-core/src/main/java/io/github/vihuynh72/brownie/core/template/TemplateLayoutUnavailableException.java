package io.github.vihuynh72.brownie.core.template;

/**
 * The template version exists, but its file could not be read into a page:
 * it no longer parses, it falls outside what the extractor supports, or it
 * holds far more text than any template this product fills. Nothing about
 * the template or any document changed.
 */
public class TemplateLayoutUnavailableException extends RuntimeException {

    public TemplateLayoutUnavailableException(long templateId, long versionId, String reason) {
        super("The layout of version " + versionId + " of template " + templateId + " cannot be shown: " + reason);
    }

    public TemplateLayoutUnavailableException(long templateId, long versionId, String reason, Throwable cause) {
        super("The layout of version " + versionId + " of template " + templateId + " cannot be shown: " + reason, cause);
    }
}
