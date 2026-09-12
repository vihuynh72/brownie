package io.github.vihuynh72.brownie.core.compile;

/** A DOCX could not be rendered to PDF: the isolated renderer failed, timed out, or produced no usable output. */
public class DocumentRenderException extends RuntimeException {

    public DocumentRenderException(String message) {
        super(message);
    }

    public DocumentRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
