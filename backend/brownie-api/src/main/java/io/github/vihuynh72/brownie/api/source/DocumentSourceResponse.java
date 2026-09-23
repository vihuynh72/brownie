package io.github.vihuynh72.brownie.api.source;

import io.github.vihuynh72.brownie.core.source.AttachedSource;
import io.github.vihuynh72.brownie.core.source.SourceOrigin;
import io.github.vihuynh72.brownie.core.source.SourceSnapshot;

import java.time.OffsetDateTime;

/**
 * One of a document's sources as the API reports it. {@code id} is the
 * workspace-level snapshot's id, the same value {@code
 * SourceController.SnapshotResponse} reports for it. {@code origin} says where
 * a copied source came from and is null for an upload.
 */
public record DocumentSourceResponse(
        long id,
        long artifactId,
        String displayFilename,
        String kind,
        OffsetDateTime fetchedAt,
        OffsetDateTime attachedAt,
        OriginResponse origin) {

    public static DocumentSourceResponse from(AttachedSource attached) {
        SourceSnapshot snapshot = attached.snapshot();
        return new DocumentSourceResponse(
                snapshot.id(),
                snapshot.artifactId(),
                attached.displayFilename(),
                snapshot.kind().name(),
                snapshot.fetchedAt(),
                attached.link().attachedAt(),
                snapshot.origin() == null ? null : OriginResponse.from(snapshot));
    }

    /**
     * What a page says about where a copy came from: the provider, what it
     * was called there, where it can be opened there, when the provider says
     * it last changed, and how Brownie changed its form. The provider's own
     * identifiers for it, and which of the person's connections it was read
     * through, are not part of it.
     */
    public record OriginResponse(String provider, String title, String link, OffsetDateTime modifiedAt, String conversion) {

        static OriginResponse from(SourceSnapshot snapshot) {
            SourceOrigin origin = snapshot.origin();
            String provider = switch (snapshot.kind()) {
                case GOOGLE_CALENDAR -> "GOOGLE";
                case ARTIFACT -> throw new IllegalArgumentException("An upload has no origin elsewhere.");
            };
            return new OriginResponse(
                    provider,
                    origin.title(),
                    origin.link(),
                    origin.modifiedAt(),
                    origin.conversion() == null ? null : origin.conversion().name());
        }
    }
}
