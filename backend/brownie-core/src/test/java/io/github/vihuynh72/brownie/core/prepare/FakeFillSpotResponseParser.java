package io.github.vihuynh72.brownie.core.prepare;

import java.util.ArrayDeque;
import java.util.Deque;

/** A controllable stand-in for the real JSON parser, in the same queue-and-return shape as {@code FakeModelGateway}. */
final class FakeFillSpotResponseParser implements FillSpotResponseParser {

    private final Deque<Object> queuedOutcomes = new ArrayDeque<>();

    void enqueue(FillSpotReply reply) {
        queuedOutcomes.addLast(reply);
    }

    void enqueueFailure(FillSpotResponseParseException exception) {
        queuedOutcomes.addLast(exception);
    }

    @Override
    public FillSpotReply parse(String json) throws FillSpotResponseParseException {
        Object outcome = queuedOutcomes.pollFirst();
        if (outcome == null) {
            throw new IllegalStateException("FakeFillSpotResponseParser.parse() was called with nothing queued for it to return.");
        }
        if (outcome instanceof FillSpotResponseParseException exception) {
            throw exception;
        }
        return (FillSpotReply) outcome;
    }
}
