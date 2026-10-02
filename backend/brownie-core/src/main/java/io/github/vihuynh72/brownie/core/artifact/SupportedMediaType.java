package io.github.vihuynh72.brownie.core.artifact;

/**
 * The only content types an uploaded artifact can be classified as.
 * Classification comes from sniffing the actual bytes, never from a
 * client-declared content type or a filename extension.
 *
 * <p>Each word-processing format is a value of its own rather than one
 * "convertible" kind: the download route serves {@link #mimeType()} as the
 * one type the bytes really are, and only DOCX is ever read for structure,
 * so every other one says through {@link #wordRoute()} how it becomes a
 * Word document first. A template and its document share a value where the
 * way they are read is the same: ODT covers an OpenDocument template (.ott)
 * too, and DOC a Word 97-2003 template (.dot); the inspection result keeps
 * the difference where conversion needs it.
 */
public enum SupportedMediaType {
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx", WordRoute.NATIVE),
    DOTX("application/vnd.openxmlformats-officedocument.wordprocessingml.template", "dotx", WordRoute.NATIVE_VARIANT),
    DOCM("application/vnd.ms-word.document.macroEnabled.12", "docm", WordRoute.NATIVE_VARIANT),
    DOTM("application/vnd.ms-word.template.macroEnabled.12", "dotm", WordRoute.NATIVE_VARIANT),
    DOC("application/msword", "doc", WordRoute.CONVERT),
    RTF("application/rtf", "rtf", WordRoute.CONVERT),
    ODT("application/vnd.oasis.opendocument.text", "odt", WordRoute.CONVERT),
    PAGES("application/vnd.apple.pages", "pages", WordRoute.CONVERT),
    PDF("application/pdf", "pdf", WordRoute.NONE),
    PLAIN_TEXT("text/plain", "txt", WordRoute.NONE);

    private final String mimeType;
    private final String defaultFileExtension;
    private final WordRoute wordRoute;

    SupportedMediaType(String mimeType, String defaultFileExtension, WordRoute wordRoute) {
        this.mimeType = mimeType;
        this.defaultFileExtension = defaultFileExtension;
        this.wordRoute = wordRoute;
    }

    /** The real MIME type to serve this artifact's bytes as -- never sniffed, always the one implied by how it was classified. */
    public String mimeType() {
        return mimeType;
    }

    /** Used only to name a downloaded file when the artifact was never given a display filename of its own. */
    public String defaultFileExtension() {
        return defaultFileExtension;
    }

    /** How a file of this type becomes a Word document Brownie can fill, if it can. */
    public WordRoute wordRoute() {
        return wordRoute;
    }
}
