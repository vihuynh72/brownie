package io.github.vihuynh72.brownie.core.generation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** A controllable stand-in for a real JSON parser, mirroring {@link FakeExtractionResponseParser}'s own queue-and-record shape. */
public final class FakeCompositionResponseParser implements CompositionResponseParser {

    private final Deque<Object> queuedOutcomes = new ArrayDeque<>();

    public void enqueue(Map<String, FieldCandidate> result) {
        queuedOutcomes.addLast(result);
    }

    public void enqueueFailure(CompositionResponseParseException exception) {
        queuedOutcomes.addLast(exception);
    }

    @Override
    public Map<String, FieldCandidate> parse(String json, List<String> composableFieldIds) throws CompositionResponseParseException {
        Object outcome = queuedOutcomes.pollFirst();
        if (outcome == null) {
            throw new IllegalStateException("FakeCompositionResponseParser.parse() was called with nothing queued for it to return.");
        }
        if (outcome instanceof CompositionResponseParseException exception) {
            throw exception;
        }
        @SuppressWarnings("unchecked")
        Map<String, FieldCandidate> result = (Map<String, FieldCandidate>) outcome;
        return result;
    }
}
