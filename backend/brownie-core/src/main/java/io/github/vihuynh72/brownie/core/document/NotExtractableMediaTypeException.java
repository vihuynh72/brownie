package io.github.vihuynh72.brownie.core.document;

import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;

/**
 * The artifact is READY and a word-processing document Brownie accepts,
 * but not one it reads for structure: only a Word document is, so a
 * template, a macro-enabled file or another word processor's file becomes a
 * Word working copy first, and that copy is what is read.
 */
public class NotExtractableMediaTypeException extends RuntimeException {

    private final SupportedMediaType mediaType;

    public NotExtractableMediaTypeException(long artifactId, SupportedMediaType mediaType) {
        super("Artifact " + artifactId + " is " + mediaType + ", which is read only through a Word working copy made from it.");
        this.mediaType = mediaType;
    }

    public SupportedMediaType mediaType() {
        return mediaType;
    }
}
