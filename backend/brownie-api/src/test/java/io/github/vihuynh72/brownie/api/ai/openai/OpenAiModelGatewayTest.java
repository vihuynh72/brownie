package io.github.vihuynh72.brownie.api.ai.openai;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.micrometer.observation.ObservationRegistry;
import io.github.vihuynh72.brownie.core.model.JsonSchema;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelMessage;
import io.github.vihuynh72.brownie.core.model.ModelMessageRole;
import io.github.vihuynh72.brownie.core.model.ModelRequest;
import io.github.vihuynh72.brownie.core.model.ModelTransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves this adapter's request/response handling against real wire
 * traffic from a stubbed OpenAI endpoint, not a mocked Java object -- the
 * exact response shapes below (the {@code refusal} field, {@code
 * finish_reason} values, the usage block) were confirmed by decompiling
 * the pinned {@code spring-ai-openai}/{@code openai-java-core} versions
 * this project depends on before writing any assertion about them, the
 * same discipline {@code ClamAvMalwareScannerTest} already applies to its
 * own real protocol. The real OpenAI network is never contacted.
 */
class OpenAiModelGatewayTest {

    private WireMockServer wireMock;
    private OpenAIClient client;
    private OpenAIClientAsync asyncClient;
    private ModelGateway gateway;

    @BeforeEach
    void startStubServer() {
        wireMock = new WireMockServer(0);
        wireMock.start();
        client = OpenAiSetup.setupSyncClient(
                wireMock.baseUrl(),
                "test-api-key",
                null,
                null,
                null,
                null,
                false,
                false,
                "gpt-test",
                Duration.ofSeconds(5),
                0,
                null,
                Map.of(),
                ObservationRegistry.NOOP,
                null,
                List.of());
        // OpenAiChatModel.Builder.build() unconditionally constructs a
        // default async client too (used for the streaming call path this
        // test never exercises) unless one is supplied here, and that
        // default construction needs the same credential the sync client
        // above already has -- confirmed by first omitting this and
        // reading the real "At least one credential source must be
        // specified" failure, not assumed from the builder's own shape.
        asyncClient = OpenAiSetup.setupAsyncClient(
                wireMock.baseUrl(),
                "test-api-key",
                null,
                null,
                null,
                null,
                false,
                false,
                "gpt-test",
                Duration.ofSeconds(5),
                0,
                null,
                Map.of(),
                ObservationRegistry.NOOP,
                null,
                List.of());
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(asyncClient)
                .options(OpenAiChatOptions.builder().model("gpt-test").build())
                .build();
        gateway = new OpenAiModelGateway(chatModel, "gpt-test");
    }

    @AfterEach
    void stopStubServer() {
        // OpenAIClient's own OkHttp transport keeps a keep-alive connection
        // pool with its own non-daemon threads that outlive this test
        // unless closed explicitly -- confirmed by first omitting this and
        // watching Surefire's fork JVM hang for 30 real seconds after the
        // whole reactor's tests otherwise finished, not assumed.
        client.close();
        asyncClient.close();
        wireMock.stop();
    }

    @Test
    void aWellFormedReplyIsReportedAsSuccessWithRealUsageCounts() throws ModelTransportException {
        wireMock.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(chatCompletionJson("""
                                {"role":"assistant","content":"{\\"decision\\":\\"approved\\"}"}""", "stop"))));

        ModelCompletion completion = gateway.complete(request());

        assertThat(completion).isInstanceOf(ModelCompletion.Success.class);
        ModelCompletion.Success success = (ModelCompletion.Success) completion;
        assertThat(success.content()).isEqualTo("{\"decision\":\"approved\"}");
        assertThat(success.usage().inputTokens()).isEqualTo(11);
        assertThat(success.usage().outputTokens()).isEqualTo(4);
    }

    @Test
    void aRefusalFieldOnTheMessageIsReportedAsRefusalNotSuccess() throws ModelTransportException {
        wireMock.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(chatCompletionJson(
                                "{\"role\":\"assistant\",\"content\":null,\"refusal\":\"I can't help with that.\"}", "stop"))));

        ModelCompletion completion = gateway.complete(request());

        assertThat(completion).isInstanceOf(ModelCompletion.Refusal.class);
        assertThat(((ModelCompletion.Refusal) completion).reason()).isEqualTo("I can't help with that.");
    }

    @Test
    void aLengthFinishReasonIsReportedAsIncompleteNotSuccess() throws ModelTransportException {
        wireMock.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(chatCompletionJson("{\"role\":\"assistant\",\"content\":\"{\\\"partial\\\":tr\"}", "length"))));

        ModelCompletion completion = gateway.complete(request());

        assertThat(completion).isInstanceOf(ModelCompletion.IncompleteOutput.class);
    }

    @Test
    void nonJsonContentIsReportedAsMalformedNotSuccess() throws ModelTransportException {
        wireMock.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(chatCompletionJson("{\"role\":\"assistant\",\"content\":\"not json at all\"}", "stop"))));

        ModelCompletion completion = gateway.complete(request());

        assertThat(completion).isInstanceOf(ModelCompletion.MalformedOutput.class);
    }

    @Test
    void aBadRequestResponseIsReportedAsUnsupportedParametersNotThrown() throws ModelTransportException {
        wireMock.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error":{"message":"Unsupported value","type":"invalid_request_error","code":"unsupported_value"}}""")));

        ModelCompletion completion = gateway.complete(request());

        assertThat(completion).isInstanceOf(ModelCompletion.UnsupportedParameters.class);
    }

    @Test
    void aServerErrorResponseThrowsARetryableTransportException() {
        wireMock.stubFor(post(urlPathEqualTo("/chat/completions")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> gateway.complete(request()))
                .isInstanceOf(ModelTransportException.class)
                .matches(e -> ((ModelTransportException) e).retryable());
    }

    @Test
    void anUnauthorizedResponseThrowsANonRetryableTransportException() {
        wireMock.stubFor(post(urlPathEqualTo("/chat/completions")).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> gateway.complete(request()))
                .isInstanceOf(ModelTransportException.class)
                .matches(e -> !((ModelTransportException) e).retryable());
    }

    /**
     * A genuine network failure, not a simulated one -- the same
     * deterministic "point at a real closed port" technique {@code
     * ClamAvMalwareScannerTest.anUnreachableScannerThrowsRatherThanReturningAResult}
     * already established, chosen over racing a WireMock response delay
     * against a short client timeout: an earlier attempt at exactly that
     * raced a 300ms client timeout against a 2000ms WireMock delay and the
     * call still completed successfully, meaning some layer beneath
     * {@code ClientOptions.Builder.timeout(Duration)} was not honoring it
     * the way assumed -- not chased further given a closed-port connection
     * failure exercises the identical {@code OpenAIIoException} catch
     * branch this test needs to prove, deterministically and instantly.
     */
    @Test
    void anUnreachableEndpointThrowsARetryableTransportException() {
        OpenAIClient unreachableClient = OpenAiSetup.setupSyncClient(
                "http://127.0.0.1:1", "test-api-key", null, null, null, null, false, false, "gpt-test", Duration.ofSeconds(2), 0, null,
                Map.of(), ObservationRegistry.NOOP, null, List.of());
        OpenAIClientAsync unreachableAsyncClient = OpenAiSetup.setupAsyncClient(
                "http://127.0.0.1:1", "test-api-key", null, null, null, null, false, false, "gpt-test", Duration.ofSeconds(2), 0, null,
                Map.of(), ObservationRegistry.NOOP, null, List.of());
        try {
            OpenAiChatModel unreachableModel = OpenAiChatModel.builder()
                    .openAiClient(unreachableClient)
                    .openAiClientAsync(unreachableAsyncClient)
                    .options(OpenAiChatOptions.builder().model("gpt-test").build())
                    .build();
            ModelGateway unreachableGateway = new OpenAiModelGateway(unreachableModel, "gpt-test");

            assertThatThrownBy(() -> unreachableGateway.complete(request()))
                    .isInstanceOf(ModelTransportException.class)
                    .matches(e -> ((ModelTransportException) e).retryable());
        } finally {
            unreachableClient.close();
            unreachableAsyncClient.close();
        }
    }

    private static String chatCompletionJson(String messageJson, String finishReason) {
        return """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 1730000000,
                  "model": "gpt-test",
                  "choices": [
                    {"index": 0, "message": %s, "finish_reason": "%s", "logprobs": null}
                  ],
                  "usage": {"prompt_tokens": 11, "completion_tokens": 4, "total_tokens": 15}
                }
                """.formatted(messageJson, finishReason);
    }

    private static ModelRequest request() {
        return new ModelRequest(
                "test-prompt-v1",
                List.of(new ModelMessage(ModelMessageRole.SYSTEM, "You are a test."), new ModelMessage(ModelMessageRole.USER, "Say hello.")),
                new JsonSchema("""
                        {"type":"object","properties":{"decision":{"type":"string"}},"required":["decision"],"additionalProperties":false}"""),
                64);
    }
}
