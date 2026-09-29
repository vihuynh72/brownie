package io.github.vihuynh72.brownie.api.connector.google;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.github.vihuynh72.brownie.core.action.ActionFailure;
import io.github.vihuynh72.brownie.core.action.GoogleDocContent;
import io.github.vihuynh72.brownie.core.action.WriteAnswer;
import io.github.vihuynh72.brownie.core.connector.ProviderTokenRejectedException;
import io.github.vihuynh72.brownie.core.connector.ProviderUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Google Docs stood in by WireMock: one read of one Doc, asking only for its
 * revision, title and first tab's content; its text in document order with
 * table cells included; where its body ends. And the one change: text added
 * at the end of the first tab in one request naming the approved revision,
 * with every answer sorted by what it proves.
 */
class GoogleDocsClientTest {

    private static final String ACCESS_TOKEN = "ya29.stand-in-docs-token";

    private WireMockServer google;
    private GoogleDocsClient client;

    @BeforeEach
    void startGoogle() {
        google = new WireMockServer(0);
        google.start();
        String base = google.baseUrl();
        GoogleClientSettings settings = new GoogleClientSettings("client-123", "secret", URI.create("http://localhost:8081/cb"),
                URI.create(base + "/auth"), URI.create(base + "/token"), URI.create(base + "/revoke"), URI.create(base + "/userinfo"),
                URI.create(base + "/apis"), URI.create(base + "/docs"), "https://accounts.google.com", Duration.ofSeconds(2), Duration.ofSeconds(5));
        client = new GoogleDocsClient(settings, GoogleHttp.create(Duration.ofSeconds(2), Duration.ofSeconds(5), new ObjectMapper()));
    }

    @AfterEach
    void stopGoogle() {
        google.stop();
    }

    @Test
    void aDocIsReadAtItsOwnAddressAsItsRevisionTitleAndTextInOrderWithTableCells() {
        google.stubFor(get(urlPathEqualTo("/docs/v1/documents/savedDoc12345"))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                .withQueryParam("fields", equalTo("documentId,revisionId,title,body/content"))
                .willReturn(okJson("""
                        {"documentId":"savedDoc12345","revisionId":"ALm37BV-rev","title":"Minutes",
                         "body":{"content":[
                           {"endIndex":1,"sectionBreak":{}},
                           {"endIndex":17,"paragraph":{"elements":[{"textRun":{"content":"Spring Budget "}},{"textRun":{"content":"Planning\\n"}}]}},
                           {"endIndex":60,"table":{"tableRows":[{"tableCells":[
                              {"content":[{"paragraph":{"elements":[{"textRun":{"content":"Ana\\n"}}]}}]},
                              {"content":[{"paragraph":{"elements":[{"textRun":{"content":"Book the room\\n"}}]}}]}]}]}},
                           {"endIndex":75,"paragraph":{"elements":[{"textRun":{"content":"March 5, 2026\\n"}}]}}]}}
                        """)));

        GoogleDocContent doc = client.read(ACCESS_TOKEN, "savedDoc12345").orElseThrow();

        assertThat(doc.revisionId()).isEqualTo("ALm37BV-rev");
        assertThat(doc.title()).isEqualTo("Minutes");
        assertThat(doc.text()).isEqualTo("Spring Budget Planning\nAna\nBook the room\nMarch 5, 2026\n");
        assertThat(doc.endIndex()).isEqualTo(75);
        assertThat(google.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
    }

    @Test
    void aMissingDocIsEmptyAndEveryOtherAnswerIsARefusal() {
        google.stubFor(get(urlPathEqualTo("/docs/v1/documents/goneDoc12345")).willReturn(aResponse().withStatus(404)));
        assertThat(client.read(ACCESS_TOKEN, "goneDoc12345")).isEmpty();

        google.stubFor(get(urlPathEqualTo("/docs/v1/documents/otherDoc12345")).willReturn(okJson("{\"documentId\":\"notTheOneAsked\"}")));
        assertThatThrownBy(() -> client.read(ACCESS_TOKEN, "otherDoc12345")).isInstanceOf(ProviderUnavailableException.class);

        google.stubFor(get(urlPathEqualTo("/docs/v1/documents/refusedDoc12345")).willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> client.read(ACCESS_TOKEN, "refusedDoc12345")).isInstanceOf(ProviderTokenRejectedException.class);

        google.stubFor(get(urlPathEqualTo("/docs/v1/documents/hugeDoc12345"))
                .willReturn(okJson("{\"documentId\":\"hugeDoc12345\",\"title\":\"" + "x".repeat(GoogleDocsClient.DOCUMENT_ANSWER_BYTES) + "\"}")));
        assertThatThrownBy(() -> client.read(ACCESS_TOKEN, "hugeDoc12345")).isInstanceOf(ProviderUnavailableException.class);

        assertThatThrownBy(() -> client.read(ACCESS_TOKEN, "../escape")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAdditionIsOneInsertionAtTheEndNamingTheApprovedRevisionAndNothingElse() throws Exception {
        google.stubFor(post(urlPathEqualTo("/docs/v1/documents/savedDoc12345:batchUpdate"))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                .willReturn(okJson("{\"documentId\":\"savedDoc12345\",\"replies\":[{}],\"writeControl\":{\"requiredRevisionId\":\"ALm37-after\"}}")));

        WriteAnswer answer = client.append(ACCESS_TOKEN, "savedDoc12345", "\nRevised minutes", "ALm37-before");

        WriteAnswer.Applied applied = (WriteAnswer.Applied) answer;
        assertThat(applied.externalId()).isEqualTo("savedDoc12345");
        assertThat(applied.resultRevision()).isEqualTo("ALm37-after");
        List<com.github.tomakehurst.wiremock.verification.LoggedRequest> sent =
                google.findAll(postRequestedFor(urlPathEqualTo("/docs/v1/documents/savedDoc12345:batchUpdate")));
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().getUrl()).doesNotContain("?");
        assertThat(new ObjectMapper().readValue(sent.getFirst().getBodyAsString(), Map.class)).isEqualTo(Map.of(
                "requests", List.of(Map.of("insertText", Map.of("text", "\nRevised minutes", "endOfSegmentLocation", Map.of("segmentId", "")))),
                "writeControl", Map.of("requiredRevisionId", "ALm37-before")));
    }

    @Test
    void everyAnswerToAnAdditionIsSortedByWhatItProves() {
        assertThat(appendAnswer(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":400,\"message\":\"free text\",\"status\":\"INVALID_ARGUMENT\"}}")))
                .isInstanceOfSatisfying(WriteAnswer.NotAppliedFinal.class, refused -> assertThat(refused.failure()).isEqualTo(ActionFailure.PROVIDER_REFUSED));
        assertThat(appendAnswer(aResponse().withStatus(401))).isInstanceOf(WriteAnswer.NotAppliedRetryable.class);
        assertThat(appendAnswer(aResponse().withStatus(500))).isInstanceOf(WriteAnswer.Unknown.class);
        assertThat(appendAnswer(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))).isInstanceOf(WriteAnswer.Unknown.class);
        assertThat(appendAnswer(okJson("{\"documentId\":\"anotherDoc12345\"}"))).isInstanceOf(WriteAnswer.Unknown.class);
        assertThatThrownBy(() -> client.append(ACCESS_TOKEN, "../escape", "\nx", "rev")).isInstanceOf(IllegalArgumentException.class);
    }

    private WriteAnswer appendAnswer(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        google.resetAll();
        google.stubFor(post(urlPathEqualTo("/docs/v1/documents/savedDoc12345:batchUpdate")).willReturn(response));
        return client.append(ACCESS_TOKEN, "savedDoc12345", "\nRevised minutes", "ALm37-before");
    }
}
