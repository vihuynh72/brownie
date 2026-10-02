package io.github.vihuynh72.brownie.core.artifact;

import io.github.vihuynh72.brownie.core.prepare.ConvertibleFormat;

import java.util.Objects;

/**
 * What {@link ArtifactContentInspector} found: the media type the bytes
 * are, and, for a format that is converted before it is read, which of its
 * variants they are. Only the media type is stored with an artifact; the
 * variant is recomputed from the stored bytes when conversion needs it,
 * which is the same inspection giving the same answer.
 *
 * @param convertibleFormat present exactly when the media type's
 *     {@link WordRoute} is {@link WordRoute#CONVERT}
 */
public record ContentInspection(SupportedMediaType mediaType, ConvertibleFormat convertibleFormat) {

    public ContentInspection {
        Objects.requireNonNull(mediaType, "mediaType must not be null");
        if ((mediaType.wordRoute() == WordRoute.CONVERT) != (convertibleFormat != null)) {
            throw new IllegalArgumentException(
                    "A converted format is named exactly for a media type that is converted; got " + mediaType
                            + " with " + convertibleFormat + ".");
        }
    }

    /** A type that is read as it is, with nothing further to tell apart. */
    static ContentInspection of(SupportedMediaType mediaType) {
        return new ContentInspection(mediaType, null);
    }
}
