package io.github.vihuynh72.brownie.core.compile;

/**
 * Strips non-printing OOXML metadata a filled document should never carry
 * forward: author identity, machine-specific application details, and
 * arbitrary custom properties. A real implementation is supplied by
 * whichever module wires this up, the same dependency-inversion shape
 * {@link TemplateFiller}/{@link DocumentRenderer} already use.
 */
public interface DocxMetadataSanitizer {

    byte[] sanitize(byte[] docxBytes);
}
