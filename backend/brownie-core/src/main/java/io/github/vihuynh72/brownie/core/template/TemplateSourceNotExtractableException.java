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
}
