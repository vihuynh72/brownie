package io.github.vihuynh72.brownie.core.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A deterministic, controllable stand-in for a real provider: the caller
 * queues exactly the outcomes it wants returned, in order, and this class
 * hands them back one at a time, recording every request it actually
 * received so a test can assert what was sent. Reused across every
 * generation-side test in this codebase rather than redeclared per test
 * class, unlike the private nested {@code Fake*} doubles elsewhere, because
 * the same controllable double (queue a refusal, queue a timeout, queue a
 * malformed reply) is needed by many unrelated test classes across this
 * feature area, not just one service's own test.
 */
public final class FakeModelGateway implements ModelGateway {

    private final Deque<Object> queuedOutcomes = new ArrayDeque<>();
    private final List<ModelRequest> receivedRequests = new ArrayList<>();

    public void enqueue(ModelCompletion completion) {
        queuedOutcomes.addLast(completion);
    }

    public void enqueueFailure(ModelTransportException exception) {
        queuedOutcomes.addLast(exception);
    }

    @Override
    public ModelCompletion complete(ModelRequest request) throws ModelTransportException {
        receivedRequests.add(request);
        Object outcome = queuedOutcomes.pollFirst();
        if (outcome == null) {
            throw new IllegalStateException("FakeModelGateway.complete() was called with nothing queued for it to return.");
        }
        if (outcome instanceof ModelTransportException exception) {
            throw exception;
        }
        return (ModelCompletion) outcome;
    }

    public List<ModelRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }
}
