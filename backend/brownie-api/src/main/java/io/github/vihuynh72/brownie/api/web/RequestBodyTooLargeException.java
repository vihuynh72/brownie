package io.github.vihuynh72.brownie.api.web;

/**
 * A request body ran past the size any request this API understands could need. Unchecked so that it is not taken
 * for a failure to read from the network; whatever was reading the body may still wrap it, and the handler that
 * answers an unreadable body looks inside for it.
 */
public class RequestBodyTooLargeException extends RuntimeException {

    public RequestBodyTooLargeException(long maxBytes) {
        super("A request body may be at most " + maxBytes + " bytes.");
    }
}
