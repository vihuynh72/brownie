package io.github.vihuynh72.brownie.api.connector.google;

import io.github.vihuynh72.brownie.core.action.GoogleDocContent;
import io.github.vihuynh72.brownie.core.action.GoogleDocs;
import io.github.vihuynh72.brownie.core.action.WriteAnswer;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ProviderUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Google Docs, asked about one Doc Brownie saved: its revision, its title,
 * and the text of its first tab, read in document order with the text inside
 * table cells, so that what a conversion kept can be checked and the end of
 * the Doc found. The answer is bounded; a Doc too large to read that way is
 * treated as one that cannot be checked now.
 *
 * <p>The one change it makes is adding text at the end of that tab: one
 * request of one insertion, which names the revision the addition was
 * approved against so that Google applies it only if the Doc is still at
 * that revision. It never deletes, replaces or restyles anything.
 */
public class GoogleDocsClient implements GoogleDocs {

    /** Room for a long Doc's structure; the text of minutes is a small part of what Docs sends back. */
    static final int DOCUMENT_ANSWER_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DEPTH = 16;
    private static final Pattern DOC_ID = Pattern.compile("^[A-Za-z0-9_-]{5,1024}$");

    private final GoogleClientSettings settings;
    private final GoogleHttp http;

    public GoogleDocsClient(GoogleClientSettings settings, GoogleHttp http) {
        this.settings = settings;
        this.http = http;
    }

    @Override
    public Optional<GoogleDocContent> read(String accessToken, String documentId) {
        if (documentId == null || !DOC_ID.matcher(documentId).matches()) {
            throw new IllegalArgumentException("Not a Google Doc id.");
        }
        URI uri = UriComponentsBuilder.fromUri(settings.docsApiBaseUri())
                .path("/v1/documents/{documentId}")
                .queryParam("fields", "documentId,revisionId,title,body/content")
                .encode()
                .buildAndExpand(documentId)
                .toUri();
        GoogleHttp.Answer answer;
        try {
            answer = http.send(http.restClient().get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON), DOCUMENT_ANSWER_BYTES);
        } catch (GoogleHttp.AnswerTooLargeException e) {
            throw new ProviderUnavailableException("The Google Doc is too large for Brownie to read back.");
        }
        if (answer.status() == 404) {
            return Optional.empty();
        }
        if (!answer.isSuccess()) {
            throw GoogleApiRefusals.of(http, answer, ConnectorAccess.DRIVE_SAVING, "Google Doc");
        }
        JsonNode document = http.json(answer);
        String id = GoogleHttp.text(document, "documentId");
        if (id == null || !id.equals(documentId)) {
            throw new ProviderUnavailableException("Google answered with a different Doc than the one asked for.");
        }
        JsonNode content = document.path("body").path("content");
        StringBuilder text = new StringBuilder();
        appendText(content, text, 0);
        int endIndex = 0;
        if (content.isArray() && !content.isEmpty()) {
            endIndex = content.get(content.size() - 1).path("endIndex").asInt(0);
        }
        return Optional.of(new GoogleDocContent(id, GoogleHttp.text(document, "revisionId"), GoogleHttp.text(document, "title"),
                text.toString(), endIndex));
    }

    @Override
    public WriteAnswer append(String accessToken, String documentId, String text, String requiredRevisionId) {
        if (documentId == null || !DOC_ID.matcher(documentId).matches()) {
            throw new IllegalArgumentException("Not a Google Doc id.");
        }
        Map<String, Object> insertion = Map.of("insertText", Map.of("text", text, "endOfSegmentLocation", Map.of("segmentId", "")));
        Map<String, Object> body = Map.of(
                "requests", List.of(insertion),
                "writeControl", Map.of("requiredRevisionId", requiredRevisionId));
        GoogleHttp.WriteExchange exchange = http.write(
                settings, GoogleWrite.DOCS_BATCH_UPDATE, documentId, accessToken, MediaType.APPLICATION_JSON,
                http.jsonBytes(body), GoogleHttp.SMALL_ANSWER_BYTES);
        if (exchange instanceof GoogleHttp.Answered answered && answered.answer().isSuccess()) {
            JsonNode applied;
            try {
                applied = http.json(answered.answer());
            } catch (ProviderUnavailableException e) {
                return new WriteAnswer.Unknown(answered.answer().status(), List.of());
            }
            if (!documentId.equals(GoogleHttp.text(applied, "documentId"))) {
                return new WriteAnswer.Unknown(answered.answer().status(), List.of());
            }
            String revision = GoogleHttp.text(applied.path("writeControl"), "requiredRevisionId");
            return new WriteAnswer.Applied(answered.answer().status(), List.of(), documentId, null, revision);
        }
        return GoogleWriteAnswers.refusal(http, exchange, "Google Doc addition");
    }

    /** Paragraphs' runs of text, and the same inside tables and a table of contents, in the order they appear. */
    private static void appendText(JsonNode elements, StringBuilder text, int depth) {
        if (!elements.isArray() || depth > MAX_DEPTH) {
            return;
        }
        for (JsonNode element : elements) {
            for (JsonNode run : element.path("paragraph").path("elements")) {
                String content = GoogleHttp.text(run.path("textRun"), "content");
                if (content != null) {
                    text.append(content);
                }
            }
            for (JsonNode row : element.path("table").path("tableRows")) {
                for (JsonNode cell : row.path("tableCells")) {
                    appendText(cell.path("content"), text, depth + 1);
                }
            }
            appendText(element.path("tableOfContents").path("content"), text, depth + 1);
        }
    }
}
