package io.github.vihuynh72.brownie.core.generation;

import io.github.vihuynh72.brownie.core.template.FieldDefinition;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** A controllable stand-in for a real JSON parser, mirroring {@code FakeModelGateway}'s own queue-and-record shape. */
public final class FakeExtractionResponseParser implements ExtractionResponseParser {

    private final Deque<Object> queuedOutcomes = new ArrayDeque<>();

    public void enqueue(ExtractionResult result) {
        queuedOutcomes.addLast(result);
    }

    public void enqueueFailure(ExtractionResponseParseException exception) {
        queuedOutcomes.addLast(exception);
    }

    @Override
    public ExtractionResult parse(String json, List<FieldDefinition> scalarFields, List<FieldDefinition> repeatedFields)
            throws ExtractionResponseParseException {
        Object outcome = queuedOutcomes.pollFirst();
        if (outcome == null) {
            throw new IllegalStateException("FakeExtractionResponseParser.parse() was called with nothing queued for it to return.");
        }
        if (outcome instanceof ExtractionResponseParseException exception) {
            throw exception;
        }
        return (ExtractionResult) outcome;
    }
}
