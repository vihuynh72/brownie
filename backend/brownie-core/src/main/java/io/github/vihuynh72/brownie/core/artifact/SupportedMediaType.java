package io.github.vihuynh72.brownie.core.artifact;

/**
 * The only content types an uploaded artifact can be classified as today.
 * Classification comes from sniffing the actual bytes, never from a
 * client-declared content type or a filename extension.
 */
public enum SupportedMediaType {
    DOCX,
    PDF,
    PLAIN_TEXT
}
