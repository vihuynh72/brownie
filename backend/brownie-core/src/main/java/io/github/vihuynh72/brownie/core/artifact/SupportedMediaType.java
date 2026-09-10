package io.github.vihuynh72.brownie.core.artifact;

/**
 * The only content types an uploaded artifact can be classified as today.
 * Classification comes from sniffing the actual bytes, never from a
 * client-declared content type or a filename extension.
 */
public enum SupportedMediaType {
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    PDF("application/pdf", "pdf"),
    PLAIN_TEXT("text/plain", "txt");

    private final String mimeType;
    private final String defaultFileExtension;

    SupportedMediaType(String mimeType, String defaultFileExtension) {
        this.mimeType = mimeType;
        this.defaultFileExtension = defaultFileExtension;
    }

    /** The real MIME type to serve this artifact's bytes as -- never sniffed, always the one implied by how it was classified. */
    public String mimeType() {
        return mimeType;
    }

    /** Used only to name a downloaded file when the artifact was never given a display filename of its own. */
    public String defaultFileExtension() {
        return defaultFileExtension;
    }
}
