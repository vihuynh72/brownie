package io.github.vihuynh72.brownie.core.source;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Where a copied source came from, fixed at the moment it was copied: the
 * connection and the person's own choice it was read through, the provider's
 * identifier for it, the provider's version then, when the provider says it
 * last changed, its name there, where it can be opened there, and whether
 * its form was changed on the way in ({@code conversion} is null when not).
 * A later, changed version of the same thing is a different snapshot with a
 * different origin; this one never changes.
 */
public record SourceOrigin(
        long connectionId,
        long grantId,
        String externalId,
        String revision,
        OffsetDateTime modifiedAt,
        String title,
        String link,
        SourceConversion conversion) {

    public SourceOrigin {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(revision, "revision");
        if (link != null && !link.startsWith("https://")) {
            throw new IllegalArgumentException("A source's link must be an https address at its provider.");
        }
    }
}
