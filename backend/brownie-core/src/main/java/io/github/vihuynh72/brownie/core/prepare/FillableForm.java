package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;

import java.util.List;
import java.util.Objects;

/**
 * A person's upload made ready to fill: the artifact a template is built on
 * ({@code templateSourceArtifactId}: for Word, the clean working copy), how
 * it was read, the spots found and named in it, each ready to become one of
 * the template's fields as it is, and what the person should be told about
 * the making of it. {@code created} is true when this request made it and
 * false when it was already there; it is not part of what a person sees.
 *
 * <p>{@code spotNaming} is {@link NamingSource#MODEL} when the model named
 * at least one spot; otherwise {@code rulesOnlyReason} says why the rules
 * named them all.
 */
public record FillableForm(
        Kind kind,
        long sourceArtifactId,
        long templateSourceArtifactId,
        SupportedMediaType sourceFormat,
        boolean converted,
        Extraction extraction,
        List<Spot> spots,
        List<PreparationNotice> notices,
        NamingSource spotNaming,
        String rulesOnlyReason,
        boolean created) {

    /** What the template's source is: a Word document, or a PDF filled in its own way. */
    public enum Kind {
        DOCX,
        PDF
    }

    /** How the template's source was read: the stored extraction a template draft pins, and what the file keeps as it is. */
    public record Extraction(long id, String status, String parserVersion, List<KeptFeature> keptAsIs) {

        public Extraction {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(parserVersion, "parserVersion");
            keptAsIs = List.copyOf(keptAsIs);
        }
    }

    /** One kind of thing the file keeps as it is, and how often. */
    public record KeptFeature(String feature, int count) {
    }

    /**
     * One spot as the template field it becomes ({@code field}), and how it
     * was named. {@code kind} is what the rules saw there ({@code
     * UNDERSCORES}, {@code BRACKET}, ...); {@code suggestedType} is the
     * type as the namer put it, which may be one fields cannot hold yet.
     */
    public record Spot(FieldDefinition field, NamingSource namedBy, boolean requiredHint, String suggestedType, String kind) {

        public Spot {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(namedBy, "namedBy");
        }
    }

    public FillableForm {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sourceFormat, "sourceFormat");
        Objects.requireNonNull(extraction, "extraction");
        Objects.requireNonNull(spotNaming, "spotNaming");
        spots = List.copyOf(spots);
        notices = List.copyOf(notices);
    }
}
