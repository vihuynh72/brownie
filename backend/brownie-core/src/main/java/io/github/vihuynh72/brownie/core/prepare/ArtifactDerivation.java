package io.github.vihuynh72.brownie.core.prepare;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * The record that {@code outputArtifactId} was made from {@code
 * sourceArtifactId} by the recipe {@code recipeVersion}, and what making it
 * found: the spots, the notices, and who named the spots. {@code converter}
 * names the converter that turned the source into a Word document, or is
 * null when it already was one. Written once and never changed; asking for
 * the same (source, kind, recipe version) again answers with it. A {@link
 * #PDF_FORM} record is its upload's own: its output is its source.
 */
public record ArtifactDerivation(
        long id,
        long workspaceId,
        long sourceArtifactId,
        long outputArtifactId,
        String kind,
        String recipeVersion,
        String sourceFormat,
        String converter,
        NamingSource spotNaming,
        String rulesOnlyReason,
        List<FillableForm.Spot> spots,
        List<PreparationNotice> notices,
        long createdByUserId,
        OffsetDateTime createdAt) {

    /** A working copy prepared from a person's Word-type upload. */
    public static final String PREPARED = "PREPARED";

    /**
     * A PDF upload made ready as it is: no copy is made, so the output is
     * the upload itself, and the record keeps the spots found and named in
     * it, so asking again answers with the same spots without naming the
     * places again.
     */
    public static final String PDF_FORM = "PDF_FORM";

    public ArtifactDerivation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(recipeVersion, "recipeVersion");
        Objects.requireNonNull(sourceFormat, "sourceFormat");
        Objects.requireNonNull(spotNaming, "spotNaming");
        spots = List.copyOf(spots);
        notices = List.copyOf(notices);
    }
}
