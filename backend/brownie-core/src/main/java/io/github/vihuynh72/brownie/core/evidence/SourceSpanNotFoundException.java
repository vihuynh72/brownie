package io.github.vihuynh72.brownie.core.evidence;

/** No source span by that ID exists in the caller's workspace -- whether it never existed or belongs to someone else. */
public class SourceSpanNotFoundException extends RuntimeException {

    public SourceSpanNotFoundException(long spanId) {
        super("No source span " + spanId + " in this workspace.");
    }
}
