package io.github.vihuynh72.brownie.api.connector.google;

import io.github.vihuynh72.brownie.core.action.DriveFileWriter;
import io.github.vihuynh72.brownie.core.action.NewDriveFile;
import io.github.vihuynh72.brownie.core.action.SavedDriveFile;
import io.github.vihuynh72.brownie.core.action.WriteAnswer;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ProviderUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Google Drive, asked for three things only: an id to save one new file
 * under, the saving of that one file in a single request, and Drive's
 * description of a file Brownie saved. It never lists, searches, changes,
 * moves, shares or deletes anything, and never reads a file's content.
 *
 * <p>The file is sent as Drive's "multipart" upload: its description first
 * (its name, its type, and either the reserved id or the Google type it is
 * to be converted into), then its bytes, exactly as given. The description
 * names no folder, so the file lands at the top of My Drive, and the
 * request is one of the writes {@link GoogleWrite} allows, which asks Drive
 * to ignore any default visibility an organisation set. Uploads have a
 * deadline of their own, longer than a read's, because the whole exchange
 * must fit inside it.
 */
public class GoogleDriveWriter implements DriveFileWriter {

    /** Drive ids are letters, digits, '-' and '_'. */
    static final Pattern FILE_ID = Pattern.compile("^[A-Za-z0-9_-]{5,1024}$");
    private static final String FILE_FIELDS = "id,name,mimeType,trashed,parents,shared,size,md5Checksum,sha256Checksum,webViewLink";
    private static final Set<String> LINK_HOSTS = Set.of("docs.google.com", "drive.google.com");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GoogleClientSettings settings;
    private final GoogleHttp http;
    private final GoogleHttp uploads;

    public GoogleDriveWriter(GoogleClientSettings settings, GoogleHttp http, GoogleHttp uploads) {
        this.settings = settings;
        this.http = http;
        this.uploads = uploads;
    }

    @Override
    public String reserveFileId(String accessToken) {
        URI uri = UriComponentsBuilder.fromUri(settings.apiBaseUri())
                .path("/drive/v3/files/generateIds")
                .queryParam("count", 1)
                .queryParam("space", "drive")
                .queryParam("type", "files")
                .encode()
                .build()
                .toUri();
        GoogleHttp.Answer answer = get(uri, accessToken);
        if (!answer.isSuccess()) {
            throw GoogleApiRefusals.of(http, answer, ConnectorAccess.DRIVE_SAVING, "Drive file id");
        }
        JsonNode ids = http.json(answer).path("ids");
        String id = ids.isArray() && !ids.isEmpty() && ids.get(0).isString() ? ids.get(0).stringValue() : null;
        if (id == null || !FILE_ID.matcher(id).matches()) {
            throw new ProviderUnavailableException("Google did not reserve a usable file id.");
        }
        return id;
    }

    @Override
    public WriteAnswer createFile(String accessToken, NewDriveFile file) {
        Map<String, Object> description = new LinkedHashMap<>();
        if (file.reservedId() != null) {
            description.put("id", file.reservedId());
        }
        description.put("name", file.name());
        description.put("mimeType", file.convertTo() != null ? file.convertTo() : file.mimeType());
        String boundary = boundaryNotIn(file.content());
        byte[] body = multipart(boundary, uploads.jsonBytes(description), file.mimeType(), file.content());
        GoogleHttp.WriteExchange exchange = uploads.write(
                settings, GoogleWrite.DRIVE_CREATE_FILE, null, accessToken,
                MediaType.parseMediaType("multipart/related; boundary=" + boundary), body, GoogleHttp.SMALL_ANSWER_BYTES);
        if (exchange instanceof GoogleHttp.Answered answered && answered.answer().isSuccess()) {
            JsonNode created;
            try {
                created = uploads.json(answered.answer());
            } catch (ProviderUnavailableException e) {
                return new WriteAnswer.Unknown(answered.answer().status(), List.of());
            }
            String id = GoogleHttp.text(created, "id");
            // Drive uses a reserved id or refuses the request; an answer naming another file vouches for nothing asked for.
            if (id == null || !FILE_ID.matcher(id).matches() || (file.reservedId() != null && !file.reservedId().equals(id))) {
                return new WriteAnswer.Unknown(answered.answer().status(), List.of());
            }
            return new WriteAnswer.Applied(answered.answer().status(), List.of(), id, link(GoogleHttp.text(created, "webViewLink")), null);
        }
        return GoogleWriteAnswers.refusal(uploads, exchange, "Drive file");
    }

    @Override
    public Optional<SavedDriveFile> describeSavedFile(String accessToken, String fileId) {
        if (fileId == null || !FILE_ID.matcher(fileId).matches()) {
            throw new IllegalArgumentException("Not a Drive file id.");
        }
        URI uri = UriComponentsBuilder.fromUri(settings.apiBaseUri())
                .path("/drive/v3/files/{fileId}")
                .queryParam("fields", FILE_FIELDS)
                .encode()
                .buildAndExpand(fileId)
                .toUri();
        GoogleHttp.Answer answer = get(uri, accessToken);
        // Drive answers the same for a file that was never made and one deleted for good.
        if (answer.status() == 404) {
            return Optional.empty();
        }
        if (!answer.isSuccess()) {
            throw GoogleApiRefusals.of(http, answer, ConnectorAccess.DRIVE_SAVING, "saved Drive file");
        }
        JsonNode file = http.json(answer);
        String id = GoogleHttp.text(file, "id");
        if (id == null) {
            throw new ProviderUnavailableException("Google described a file without its id.");
        }
        JsonNode parents = file.path("parents");
        return Optional.of(new SavedDriveFile(
                id,
                GoogleHttp.text(file, "name"),
                GoogleHttp.text(file, "mimeType"),
                file.path("trashed").asBoolean(false),
                parents.isArray() ? parents.size() : 0,
                // Only a plain false counts as not shared: a missing answer is not a promise.
                !file.path("shared").isBoolean() || file.path("shared").asBoolean(),
                sizeOrNull(GoogleHttp.text(file, "size")),
                GoogleHttp.text(file, "md5Checksum"),
                GoogleHttp.text(file, "sha256Checksum"),
                link(GoogleHttp.text(file, "webViewLink"))));
    }

    private GoogleHttp.Answer get(URI uri, String accessToken) {
        try {
            return http.send(http.restClient().get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON), GoogleHttp.SMALL_ANSWER_BYTES);
        } catch (GoogleHttp.AnswerTooLargeException e) {
            throw new ProviderUnavailableException("Google's description of a file was far larger than one can be.");
        }
    }

    /** RFC 2387: the description as JSON first, then the bytes as they are. */
    static byte[] multipart(String boundary, byte[] description, String mimeType, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + description.length + 256);
        out.writeBytes(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(description);
        out.writeBytes(("\r\n--" + boundary + "\r\nContent-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    /** A random boundary that does not occur in the bytes it separates, as the format requires. */
    static String boundaryNotIn(byte[] content) {
        while (true) {
            byte[] random = new byte[16];
            RANDOM.nextBytes(random);
            String boundary = "brownie-" + HexFormat.of().formatHex(random);
            if (indexOf(content, ("--" + boundary).getBytes(StandardCharsets.US_ASCII)) < 0) {
                return boundary;
            }
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Only a Google page over https: an address from elsewhere is never offered to the person to open. */
    static String link(String candidate) {
        if (candidate == null || candidate.length() > 2048) {
            return null;
        }
        try {
            URI uri = URI.create(candidate);
            return "https".equals(uri.getScheme()) && LINK_HOSTS.contains(uri.getHost()) && uri.getUserInfo() == null ? candidate : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Long sizeOrNull(String size) {
        if (size == null) {
            return null;
        }
        try {
            return Long.parseLong(size);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
