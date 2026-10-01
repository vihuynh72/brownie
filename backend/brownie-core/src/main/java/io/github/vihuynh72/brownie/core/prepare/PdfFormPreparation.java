package io.github.vihuynh72.brownie.core.prepare;

import java.util.List;
import java.util.Objects;

/**
 * What preparing an uploaded PDF as a form produced: the form reading it
 * was stored as ({@code formExtractionId}, which a PDF template is pinned
 * to), the places to fill found in it, in reading order, what the person is
 * told about it, and who named the places. {@code rulesOnlyReason} is null
 * when the model named at least one place, and otherwise says why the rules
 * named them all ({@link SpotNaming#rulesOnlyReason()}).
 */
public record PdfFormPreparation(
        long sourceArtifactId,
        long formExtractionId,
        List<PreparedPdfSpot> spots,
        List<PreparationNotice> notices,
        NamingSource spotNaming,
        String rulesOnlyReason) {

    public PdfFormPreparation {
        spots = List.copyOf(spots);
        notices = List.copyOf(notices);
        Objects.requireNonNull(spotNaming, "spotNaming");
    }
}
