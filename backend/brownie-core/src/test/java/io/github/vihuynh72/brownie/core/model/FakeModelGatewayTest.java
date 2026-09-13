package io.github.vihuynh72.brownie.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FakeModelGatewayTest {

    @Test
    void returnsQueuedCompletionsInOrderAndRecordsWhatItReceived() throws ModelTransportException {
        FakeModelGateway gateway = new FakeModelGateway();
        ModelCompletion.Success first = new ModelCompletion.Success("{\"a\":1}", new ModelUsage(10, 2));
        ModelCompletion.Refusal second = new ModelCompletion.Refusal("no", new ModelUsage(10, 1));
        gateway.enqueue(first);
        gateway.enqueue(second);

        ModelCompletion actualFirst = gateway.complete(request("v1"));
        ModelCompletion actualSecond = gateway.complete(request("v2"));

        assertSame(first, actualFirst);
        assertSame(second, actualSecond);
        List<ModelRequest> received = gateway.receivedRequests();
        assertEquals(List.of("v1", "v2"), received.stream().map(ModelRequest::promptVersion).toList());
    }

    @Test
    void queuedFailuresAreThrownRatherThanReturned() {
        FakeModelGateway gateway = new FakeModelGateway();
        ModelTransportException failure = new ModelTransportException("simulated outage", true, null);
        gateway.enqueueFailure(failure);

        ModelTransportException thrown = assertThrows(ModelTransportException.class, () -> gateway.complete(request("v1")));
        assertSame(failure, thrown);
    }

    @Test
    void callingWithNothingQueuedFailsLoudlyRatherThanReturningNull() {
        FakeModelGateway gateway = new FakeModelGateway();

        assertThrows(IllegalStateException.class, () -> gateway.complete(request("v1")));
    }

    private static ModelRequest request(String promptVersion) {
        return new ModelRequest(
                promptVersion,
                List.of(new ModelMessage(ModelMessageRole.USER, "hello")),
                new JsonSchema("{\"type\":\"object\"}"),
                16);
    }
}
