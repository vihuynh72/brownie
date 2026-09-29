package io.github.vihuynh72.brownie.api.connector.google;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.vihuynh72.brownie.core.action.ActionFailure;
import io.github.vihuynh72.brownie.core.action.NewDriveFile;
import io.github.vihuynh72.brownie.core.action.SavedDriveFile;
import io.github.vihuynh72.brownie.core.action.WriteAnswer;
import io.github.vihuynh72.brownie.core.connector.ProviderMisconfiguredException;
import io.github.vihuynh72.brownie.core.connector.ProviderTokenRejectedException;
import io.github.vihuynh72.brownie.core.connector.ProviderUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Google Drive stood in by WireMock over real HTTP: exactly what saving a
 * file sends (one request, its description first and its bytes exactly as
 * given, no folder, only the parameters Brownie allows), what each kind of
 * answer is taken to prove about the change, how a saved file is read back,
 * and what never reaches the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class GoogleDriveWriterTest {

    private static final String ACCESS_TOKEN = "ya29.stand-in-drive-saving-token";
    private static final String UPLOAD = "/upload/drive/v3/files";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final byte[] CONTENT = "PK\u0003\u0004 exact bytes of the approved minutes \r\n--not-a-boundary".getBytes(StandardCharsets.ISO_8859_1);
    private static final ObjectMapper JSON = new ObjectMapper();

    private WireMockServer google;
    private GoogleDriveWriter writer;

    @BeforeEach
    void startGoogle() {
        google = new WireMockServer(0);
        google.start();
        writer = writer(google.baseUrl(), Duration.ofSeconds(2));
    }

    @AfterEach
    void stopGoogle() {
        google.stop();
    }

    @Test
    void anIdIsReservedWithOneReadThatAsksForExactlyOne() {
        google.stubFor(get(urlPathEqualTo("/drive/v3/files/generateIds"))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                .withQueryParam("count", equalTo("1"))
                .withQueryParam("space", equalTo("drive"))
                .withQueryParam("type", equalTo("files"))
                .willReturn(okJson("{\"kind\":\"drive#generatedIds\",\"space\":\"drive\",\"ids\":[\"1AbCdEfGhIjKlMnOp_q-r\"]}")));

        assertThat(writer.reserveFileId(ACCESS_TOKEN)).isEqualTo("1AbCdEfGhIjKlMnOp_q-r");

        google.stubFor(get(urlPathEqualTo("/drive/v3/files/generateIds")).willReturn(okJson("{\"ids\":[\"../../etc\"]}")));
        assertThatThrownBy(() -> writer.reserveFileId(ACCESS_TOKEN)).isInstanceOf(ProviderUnavailableException.class);
        google.stubFor(get(urlPathEqualTo("/drive/v3/files/generateIds")).willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> writer.reserveFileId(ACCESS_TOKEN)).isInstanceOf(ProviderTokenRejectedException.class);
        google.stubFor(get(urlPathEqualTo("/drive/v3/files/generateIds")).willReturn(aResponse().withStatus(403)
                .withBody("{\"error\":{\"errors\":[{\"reason\":\"accessNotConfigured\"}]}}")));
        assertThatThrownBy(() -> writer.reserveFileId(ACCESS_TOKEN)).isInstanceOf(ProviderMisconfiguredException.class);
    }

    @Test
    void aFileIsSentInOneRequestUnderItsReservedIdWithItsExactBytesNoFolderAndOnlyTheAllowedParameters() throws Exception {
        google.stubFor(post(urlPathEqualTo(UPLOAD)).willReturn(okJson("""
                {"id":"reservedId12345","name":"Minutes.docx","mimeType":"%s",
                 "webViewLink":"https://docs.google.com/document/d/reservedId12345/edit"}
                """.formatted(DOCX))));

        WriteAnswer answer = writer.createFile(ACCESS_TOKEN, new NewDriveFile("reservedId12345", "Minutes.docx", DOCX, null, CONTENT));

        assertThat(answer).isInstanceOf(WriteAnswer.Applied.class);
        WriteAnswer.Applied applied = (WriteAnswer.Applied) answer;
        assertThat(applied.externalId()).isEqualTo("reservedId12345");
        assertThat(applied.link()).isEqualTo("https://docs.google.com/document/d/reservedId12345/edit");

        List<LoggedRequest> requests = google.findAll(anyRequestedFor(anyUrl()));
        assertThat(requests).hasSize(1);
        LoggedRequest upload = requests.getFirst();
        assertThat(upload.getMethod().getName()).isEqualTo("POST");
        assertThat(upload.getHeader("Authorization")).isEqualTo("Bearer " + ACCESS_TOKEN);
        assertThat(Map.copyOf(queryOf(upload.getUrl()))).isEqualTo(Map.of(
                "uploadType", "multipart", "ignoreDefaultVisibility", "true", "fields", "id,name,mimeType,webViewLink"));
        Matcher boundary = Pattern.compile("^multipart/related; ?boundary=\"?([^\";]+)\"?$").matcher(upload.getHeader("Content-Type"));
        assertThat(boundary.matches()).as(upload.getHeader("Content-Type")).isTrue();
        Parts parts = parts(upload.getBody(), boundary.group(1));
        assertThat(parts.firstHeaders()).isEqualTo("Content-Type: application/json; charset=UTF-8");
        JsonNode description = JSON.readTree(parts.first());
        assertThat(JSON.convertValue(description, Map.class)).as("no folder, no sharing, nothing else")
                .isEqualTo(Map.of("id", "reservedId12345", "name", "Minutes.docx", "mimeType", DOCX));
        assertThat(parts.secondHeaders()).isEqualTo("Content-Type: " + DOCX);
        assertThat(parts.second()).as("the bytes exactly as given").isEqualTo(CONTENT);
    }

    @Test
    void aConversionAsksForTheGoogleTypeAndCarriesNoIdBecauseDriveRefusesOne() throws Exception {
        google.stubFor(post(urlPathEqualTo(UPLOAD)).willReturn(okJson("""
                {"id":"convertedDoc12345","mimeType":"application/vnd.google-apps.document",
                 "webViewLink":"https://attacker.example/document/d/convertedDoc12345"}
                """)));

        WriteAnswer answer = writer.createFile(ACCESS_TOKEN,
                new NewDriveFile(null, "Minutes", DOCX, "application/vnd.google-apps.document", CONTENT));

        WriteAnswer.Applied applied = (WriteAnswer.Applied) answer;
        assertThat(applied.externalId()).isEqualTo("convertedDoc12345");
        assertThat(applied.link()).as("a page anywhere but Google's is never offered").isNull();
        LoggedRequest upload = google.findAll(postRequestedFor(urlPathEqualTo(UPLOAD))).getFirst();
        Matcher boundary = Pattern.compile("boundary=\"?([^\";]+)").matcher(upload.getHeader("Content-Type"));
        assertThat(boundary.find()).isTrue();
        Parts parts = parts(upload.getBody(), boundary.group(1));
        assertThat(JSON.convertValue(JSON.readTree(parts.first()), Map.class))
                .isEqualTo(Map.of("name", "Minutes", "mimeType", "application/vnd.google-apps.document"));
        assertThat(parts.secondHeaders()).isEqualTo("Content-Type: " + DOCX);
    }

    @Test
    void everyAnswerIsSortedByWhatItProvesAboutTheChange(CapturedOutput output) {
        assertThat(answerTo(aResponse().withStatus(409).withBody(error("duplicate")))).isInstanceOf(WriteAnswer.Exists.class);
        WriteAnswer tokenRefused = answerTo(aResponse().withStatus(401));
        assertThat(tokenRefused).isInstanceOf(WriteAnswer.NotAppliedRetryable.class);
        assertThat(((WriteAnswer.NotAppliedRetryable) tokenRefused).tokenRefused()).isTrue();
        assertThat(answerTo(aResponse().withStatus(429))).isInstanceOf(WriteAnswer.NotAppliedRetryable.class);
        WriteAnswer limited = answerTo(aResponse().withStatus(403).withBody(error("userRateLimitExceeded")));
        assertThat(limited).isInstanceOf(WriteAnswer.NotAppliedRetryable.class);
        assertThat(((WriteAnswer.NotAppliedRetryable) limited).tokenRefused()).isFalse();
        assertThat(failureOf(answerTo(aResponse().withStatus(403).withBody(error("storageQuotaExceeded"))))).isEqualTo(ActionFailure.STORAGE_FULL);
        assertThat(failureOf(answerTo(aResponse().withStatus(403).withBody(error("domainPolicy"))))).isEqualTo(ActionFailure.BLOCKED_BY_ORGANIZATION);
        assertThat(failureOf(answerTo(aResponse().withStatus(403).withBody(error("dailyLimitExceeded"))))).isEqualTo(ActionFailure.LIMIT_REACHED);
        assertThat(failureOf(answerTo(aResponse().withStatus(403).withBody(error("insufficientFilePermissions")))))
                .isEqualTo(ActionFailure.PERMISSION_REFUSED);
        assertThat(failureOf(answerTo(aResponse().withStatus(403).withBody("<html>not json</html>")))).isEqualTo(ActionFailure.PROVIDER_REFUSED);
        assertThat(failureOf(answerTo(aResponse().withStatus(400).withBody(error("badRequest"))))).isEqualTo(ActionFailure.PROVIDER_REFUSED);
        assertThat(answerTo(aResponse().withStatus(500))).as("a server error after sending").isInstanceOf(WriteAnswer.Unknown.class);
        assertThat(answerTo(aResponse().withStatus(503))).isInstanceOf(WriteAnswer.Unknown.class);
        assertThat(answerTo(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))).as("reset after sending").isInstanceOf(WriteAnswer.Unknown.class);
        assertThat(answerTo(aResponse().withFault(Fault.EMPTY_RESPONSE))).isInstanceOf(WriteAnswer.Unknown.class);
        assertThat(answerTo(aResponse().withStatus(200).withBody("<html>not json</html>"))).as("a 2xx that cannot be read")
                .isInstanceOf(WriteAnswer.Unknown.class);
        assertThat(answerTo(okJson("{\"name\":\"no id\"}"))).isInstanceOf(WriteAnswer.Unknown.class);
        assertThat(answerTo(okJson("{\"id\":\"x\"}"))).as("an id Drive would never make").isInstanceOf(WriteAnswer.Unknown.class);
        assertThat(answerTo(okJson("{\"id\":\"anotherFileId123\"}"))).as("a file under another id than the one reserved")
                .isInstanceOf(WriteAnswer.Unknown.class);

        assertThat(output.getAll()).doesNotContain(ACCESS_TOKEN).doesNotContain("exact bytes of the approved minutes");
    }

    @Test
    void anAnswerThatNeverArrivesInTimeIsUnknownButNoConnectionAtAllIsCertainlyNotSent() throws Exception {
        google.stubFor(post(urlPathEqualTo(UPLOAD)).willReturn(okJson("{\"id\":\"lateFile12345\"}").withFixedDelay(3_000)));
        GoogleDriveWriter impatient = writer(google.baseUrl(), Duration.ofMillis(500));
        assertThat(impatient.createFile(ACCESS_TOKEN, exactFile())).isInstanceOf(WriteAnswer.Unknown.class);

        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        GoogleDriveWriter nowhere = writer("http://127.0.0.1:" + closedPort, Duration.ofSeconds(2));
        WriteAnswer answer = nowhere.createFile(ACCESS_TOKEN, exactFile());
        assertThat(answer).isInstanceOf(WriteAnswer.NotAppliedRetryable.class);
        assertThat(((WriteAnswer.NotAppliedRetryable) answer).tokenRefused()).isFalse();
    }

    @Test
    void aSavedFileIsReadBackByItsIdWithOnlyTheFieldsThatAreCompared() {
        google.stubFor(get(urlPathEqualTo("/drive/v3/files/savedFile12345"))
                .withQueryParam("fields", equalTo("id,name,mimeType,trashed,parents,shared,size,md5Checksum,sha256Checksum,webViewLink"))
                .willReturn(okJson("""
                        {"id":"savedFile12345","name":"Minutes.docx","mimeType":"%s","trashed":false,"parents":["0AFolder"],
                         "shared":false,"size":"123","md5Checksum":"ABC","sha256Checksum":"def",
                         "webViewLink":"https://drive.google.com/file/d/savedFile12345/view"}
                        """.formatted(DOCX))));

        SavedDriveFile saved = writer.describeSavedFile(ACCESS_TOKEN, "savedFile12345").orElseThrow();
        assertThat(saved).isEqualTo(new SavedDriveFile("savedFile12345", "Minutes.docx", DOCX, false, 1, false, 123L, "ABC", "def",
                "https://drive.google.com/file/d/savedFile12345/view"));

        google.stubFor(get(urlPathEqualTo("/drive/v3/files/sharedFile12345")).willReturn(okJson(
                "{\"id\":\"sharedFile12345\",\"parents\":[\"a\",\"b\"]}")));
        SavedDriveFile unsure = writer.describeSavedFile(ACCESS_TOKEN, "sharedFile12345").orElseThrow();
        assertThat(unsure.shared()).as("a description that does not say is not a promise that it is not shared").isTrue();
        assertThat(unsure.parentCount()).isEqualTo(2);
        assertThat(unsure.size()).isNull();

        google.stubFor(get(urlPathEqualTo("/drive/v3/files/goneFile12345")).willReturn(aResponse().withStatus(404)));
        assertThat(writer.describeSavedFile(ACCESS_TOKEN, "goneFile12345")).isEqualTo(Optional.empty());
        assertThatThrownBy(() -> writer.describeSavedFile(ACCESS_TOKEN, "../escape")).isInstanceOf(IllegalArgumentException.class);
    }

    // --- fixtures ---

    private WriteAnswer answerTo(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        google.resetAll();
        google.stubFor(post(urlPathEqualTo(UPLOAD)).willReturn(response));
        return writer.createFile(ACCESS_TOKEN, exactFile());
    }

    private static NewDriveFile exactFile() {
        return new NewDriveFile("reservedId12345", "Minutes.docx", DOCX, null, CONTENT);
    }

    private static ActionFailure failureOf(WriteAnswer answer) {
        assertThat(answer).isInstanceOf(WriteAnswer.NotAppliedFinal.class);
        return ((WriteAnswer.NotAppliedFinal) answer).failure();
    }

    private static String error(String reason) {
        return "{\"error\":{\"code\":0,\"message\":\"free text Brownie never logs\",\"errors\":[{\"reason\":\"" + reason + "\"}]}}";
    }

    private record Parts(String firstHeaders, byte[] first, String secondHeaders, byte[] second) {
    }

    /** Splits a multipart/related body into its two parts, checking the framing byte for byte. */
    private static Parts parts(byte[] body, String boundary) {
        byte[] opening = ("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] middle = ("\r\n--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] closing = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        assertThat(Arrays.copyOfRange(body, 0, opening.length)).isEqualTo(opening);
        assertThat(Arrays.copyOfRange(body, body.length - closing.length, body.length)).isEqualTo(closing);
        int split = indexOf(body, middle, opening.length);
        assertThat(split).isPositive();
        byte[] firstPart = Arrays.copyOfRange(body, opening.length, split);
        byte[] secondPart = Arrays.copyOfRange(body, split + middle.length, body.length - closing.length);
        int firstBlank = indexOf(firstPart, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), 0);
        int secondBlank = indexOf(secondPart, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), 0);
        return new Parts(
                new String(firstPart, 0, firstBlank, StandardCharsets.US_ASCII),
                Arrays.copyOfRange(firstPart, firstBlank + 4, firstPart.length),
                new String(secondPart, 0, secondBlank, StandardCharsets.US_ASCII),
                Arrays.copyOfRange(secondPart, secondBlank + 4, secondPart.length));
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static Map<String, String> queryOf(String url) {
        Map<String, String> values = new java.util.HashMap<>();
        org.springframework.web.util.UriComponentsBuilder.fromUriString(url).build().getQueryParams()
                .forEach((name, list) -> values.put(name, java.net.URLDecoder.decode(list.getFirst(), StandardCharsets.UTF_8)));
        return values;
    }

    private static GoogleDriveWriter writer(String base, Duration uploadTimeout) {
        GoogleClientSettings settings = new GoogleClientSettings(
                "client-123",
                "stand-in-client-secret-value",
                URI.create("http://localhost:8081/api/v1/connectors/google/callback"),
                URI.create("https://accounts.google.com/o/oauth2/v2/auth"),
                URI.create(base + "/token"),
                URI.create(base + "/revoke"),
                URI.create(base + "/userinfo"),
                URI.create(base),
                URI.create(base),
                "https://accounts.google.com",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
        return new GoogleDriveWriter(settings,
                GoogleHttp.create(Duration.ofSeconds(2), Duration.ofSeconds(5), new ObjectMapper()),
                GoogleHttp.create(Duration.ofSeconds(2), uploadTimeout, new ObjectMapper()));
    }
}
