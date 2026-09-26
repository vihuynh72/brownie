package io.github.vihuynh72.brownie.api.connector.google;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleApiRefusalsTest {

    private final GoogleHttp http = GoogleHttp.create(Duration.ofSeconds(1), Duration.ofSeconds(1), new ObjectMapper());

    @Test
    void theReasonsAreReadFromBothOfGooglesErrorFormsInOrder() {
        assertThat(GoogleApiRefusals.reasonsOf(http, answer("""
                {"error":{"code":403,"message":"Free text that is never kept.",
                  "errors":[{"domain":"global","reason":"appNotAuthorizedToFile"}],
                  "status":"PERMISSION_DENIED",
                  "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}
                """)))
                .containsExactly("appNotAuthorizedToFile", "ACCESS_TOKEN_SCOPE_INSUFFICIENT", "PERMISSION_DENIED");
    }

    @Test
    void onlyPlainWordsAreKeptAndAtMostEightOfThem() {
        assertThat(GoogleApiRefusals.reasonsOf(http, answer("""
                {"error":{"errors":[{"reason":"<b>"},{"reason":"two words"},{"reason":"fine"}],"status":"also_fine"}}
                """)))
                .containsExactly("fine", "also_fine");

        StringBuilder many = new StringBuilder("{\"error\":{\"errors\":[");
        for (int i = 0; i < 12; i++) {
            many.append(i == 0 ? "" : ",").append("{\"reason\":\"reason").append((char) ('a' + i)).append("\"}");
        }
        assertThat(GoogleApiRefusals.reasonsOf(http, answer(many.append("]}}").toString()))).hasSize(8);
    }

    @Test
    void anAnswerThatIsNotJsonOrHasNoErrorGivesNoReasons() {
        assertThat(GoogleApiRefusals.reasonsOf(http, answer("<html>Forbidden</html>"))).isEmpty();
        assertThat(GoogleApiRefusals.reasonsOf(http, answer("{}"))).isEmpty();
        assertThat(GoogleApiRefusals.reasonsOf(http, answer(""))).isEmpty();
    }

    private static GoogleHttp.Answer answer(String body) {
        return new GoogleHttp.Answer(403, MediaType.APPLICATION_JSON, body.getBytes(StandardCharsets.UTF_8));
    }
}
