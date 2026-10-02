package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.ExtractionStatus;

/**
 * The artifact offered as a template's source either has never been
 * extracted (run {@code POST .../extraction} first) or its extraction did
 * not reach {@link ExtractionStatus#COMPLETE} -- there is no structural
 * graph to validate field bindings against, so no draft can be created from
 * it. {@code actualStatus} is null for "never extracted at all".
 */
public class TemplateSourceNotExtractableException extends RuntimeException {

    public TemplateSourceNotExtractableException(long artifactId, ExtractionStatus actualStatus) {
        super(actualStatus == null
                ? "Artifact " + artifactId + " has not been extracted yet; run extraction before creating a template from it."
                : "Artifact " + artifactId + " extraction status is " + actualStatus
                        + "; only a COMPLETE DOCX extraction can be used as a template source.");
    }

    private TemplateSourceNotExtractableException(String message) {
        super(message);
    }

    /** A PDF that has not been read as a form yet: there is no form reading to check its places against. */
    public static TemplateSourceNotExtractableException pdfNotPrepared(long artifactId) {
        return new TemplateSourceNotExtractableException(
                "PDF " + artifactId + " has not been prepared as a form yet; prepare it before creating a template from it.");
    }
}
