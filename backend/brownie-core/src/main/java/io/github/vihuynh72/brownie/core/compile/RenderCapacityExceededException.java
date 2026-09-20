package io.github.vihuynh72.brownie.core.compile;

/** Every render slot stayed busy for as long as this request was prepared to wait. Nothing is wrong with the document; the same request is worth making again shortly. */
public class RenderCapacityExceededException extends RuntimeException {

    public RenderCapacityExceededException(String message) {
        super(message);
    }
}
