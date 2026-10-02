package io.github.vihuynh72.brownie.core.prepare;

/**
 * The artifact is not a file Brownie can make ready to fill: neither a
 * word-processing document nor a PDF. The {@link Code} is what a person is
 * told: a plain-text file, for one, is no form.
 */
public class NotAFillableFormException extends RuntimeException {

    public enum Code {
        NOT_A_WORD_PROCESSING_DOCUMENT
    }

    private final Code code;

    public NotAFillableFormException(Code code, long artifactId) {
        super(switch (code) {
            case NOT_A_WORD_PROCESSING_DOCUMENT -> "Artifact " + artifactId + " is not a word-processing document.";
        });
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
