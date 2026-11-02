package io.github.vihuynh72.brownie.api.form;

/** The agency's site did not give the fetcher the form's bytes. */
public final class FormFetchException extends RuntimeException {

    public FormFetchException(String agency, Throwable cause) {
        super("Brownie couldn't download this form from " + agency
                + " right now. Try again, or upload your copy.", cause);
    }

    public FormFetchException(String agency, String detail) {
        super("Brownie couldn't download this form from " + agency
                + " right now. Try again, or upload your copy." + (detail == null ? "" : " (" + detail + ")"));
    }
}
