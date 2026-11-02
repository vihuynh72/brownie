package io.github.vihuynh72.brownie.core.model;

import java.util.Objects;

/**
 * One page of a photographed or scanned form, sent to a model that can see it.
 * PNG or JPEG only, and small enough for the model to look at -- a page at the
 * size the image route makes is well under this.
 */
public record ModelImage(byte[] bytes, String mediaType) {

    public static final String MEDIA_TYPE_PNG = "image/png";
    public static final String MEDIA_TYPE_JPEG = "image/jpeg";
    public static final int MAX_BYTES = 4 * 1024 * 1024;

    public ModelImage {
        Objects.requireNonNull(bytes, "bytes must not be null");
        if (!MEDIA_TYPE_PNG.equals(mediaType) && !MEDIA_TYPE_JPEG.equals(mediaType)) {
            throw new IllegalArgumentException("an image is PNG or JPEG, not " + mediaType);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("an image fits in " + MAX_BYTES + " bytes; this one is " + bytes.length);
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
