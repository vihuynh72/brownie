package io.github.vihuynh72.brownie.api.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The batch download streams through {@code StreamingResponseBody}, which
 * Spring MVC runs as an async request, so the request stays open as long
 * as the archive takes to write. These tests run a REAL Tomcat
 * ({@code RANDOM_PORT}) because the container's async timeout -- not
 * MockMvc, which never enforces one -- is what bounds that: Tomcat's
 * Connector default is 30 seconds, and this class pins
 * {@code spring.mvc.async.request-timeout} to 2 seconds so a slow stored
 * read provokes the cut-off in seconds rather than half a minute. The
 * configured 30-minute production bound only exists to stop a stuck
 * client holding a thread for ever.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.autoconfigure.exclude=", "spring.mvc.async.request-timeout=2s"})
@DockerTest
class BatchExportDownloadTimeoutIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-batch-export-timeout";

    /** How long each read of a stored file pauses; zero means reads pass straight through. */
    private static final AtomicLong READ_DELAY_MILLIS = new AtomicLong(0);

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", DB::jdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("brownie.storage.local-connection", DB::azuriteConnectionString);
        registry.add("brownie.security.clamav.host", SharedContainers::clamAvHost);
        registry.add("brownie.security.clamav.port", SharedContainers::clamAvPort);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @MockitoSpyBean
    private ArtifactService artifactService;

    private record Member(Cookie session, long workspaceId, long userId) {}

    private record Certificate(long artifactId, long templateId, long versionId, Map<String, String> fieldIds) {

        String fieldId(String formField) {
            return fieldIds.get(formField);
        }
    }

    /** Wraps every stored-file read in a pausing stream, so the archive writes slower than the async timeout. */
    @BeforeEach
    void slowStoredReads() {
        Mockito.doAnswer(invocation -> {
            ReadableArtifact real = (ReadableArtifact) invocation.callRealMethod();
            long delay = READ_DELAY_MILLIS.get();
            if (delay <= 0) {
                return real;
            }
            return new ReadableArtifact(real.artifact(), new PausingInputStream(real.content(), delay));
        }).when(artifactService).openContent(anyLong(), anyLong(), anyLong());
    }

    @AfterEach
    void restoreReadSpeed() {
        READ_DELAY_MILLIS.set(0);
    }

    /**
     * Half a second per stored-bytes read: a ~200 KB PDF needs dozens of
     * reads, so the archive is still mid-first-entry when the 2-second
     * async bound fires and the container ends the request.
     */
    @Test
    void aDownloadLongerThanTheAsyncTimeoutIsCutOff() throws Exception {
        Member member = signIn("subject-timeout-cut");
        long batchId = completedExportBatch(member);
        long exportId = completedExport(member, batchId);
        READ_DELAY_MILLIS.set(500);

        DownloadAttempt attempt = downloadOverHttp(member, batchId, exportId);
        assertThat(attempt.completeArchive())
                .as("a download whose streaming outlives the async timeout must not arrive whole "
                        + "(status %s, %s bytes, %s)", attempt.status(), attempt.bodyLength(), attempt.failure())
                .isFalse();
    }

    /** Ordinary-speed reads stay under the bound: the archive arrives whole and every entry's CRC proves out. */
    @Test
    void aDownloadInsideTheAsyncTimeoutArrivesWhole() throws Exception {
        Member member = signIn("subject-timeout-whole");
        long batchId = completedExportBatch(member);
        long exportId = completedExport(member, batchId);

        DownloadAttempt attempt = downloadOverHttp(member, batchId, exportId);
        assertThat(attempt.failure()).isNull();
        assertThat(attempt.status()).isEqualTo(200);

        Path zipPath = Files.createTempFile("batch-export-", ".zip");
        try {
            Files.write(zipPath, attempt.body());
            List<String> names = new ArrayList<>();
            try (ZipFile zip = new ZipFile(zipPath.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    // Reading an entry to its end is what checks its CRC against the central directory.
                    try (InputStream content = zip.getInputStream(entry)) {
                        content.readAllBytes();
                    }
                    names.add(entry.getName());
                }
            }
            assertThat(names).containsExactlyInAnyOrder(
                    "002 Participation certificate, Ana Lima.pdf",
                    "003 Participation certificate, Ben Okafor.pdf",
                    "rows.csv");
        } finally {
            Files.deleteIfExists(zipPath);
        }
    }

    /** The download over real HTTP, so Tomcat's async machinery -- not MockMvc's unbounded mock -- bounds it. */
    private DownloadAttempt downloadOverHttp(Member member, long batchId, long exportId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + batchPath(member, batchId)
                        + "/exports/" + exportId + "/download"))
                .header("Cookie", member.session().getName() + "=" + member.session().getValue())
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new DownloadAttempt(response.statusCode(), response.body(), null);
        } catch (Exception e) {
            return new DownloadAttempt(-1, null, e);
        }
    }

    private record DownloadAttempt(int status, byte[] body, Exception failure) {

        int bodyLength() {
            return body == null ? -1 : body.length;
        }

        /** Whether {@code body} is a whole readable archive: opens as ZIP64/central directory and every entry reads out. */
        boolean completeArchive() {
            if (body == null) {
                return false;
            }
            try {
                Path zipPath = Files.createTempFile("batch-export-attempt-", ".zip");
                try {
                    Files.write(zipPath, body);
                    try (ZipFile zip = new ZipFile(zipPath.toFile())) {
                        var entries = zip.entries();
                        while (entries.hasMoreElements()) {
                            try (InputStream content = zip.getInputStream(entries.nextElement())) {
                                content.readAllBytes();
                            }
                        }
                    }
                } finally {
                    Files.deleteIfExists(zipPath);
                }
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    /** Every read sleeps, so a stored file takes far longer than the async bound to hand over. */
    private static final class PausingInputStream extends FilterInputStream {

        private final long delayMillis;

        PausingInputStream(InputStream in, long delayMillis) {
            super(in);
            this.delayMillis = delayMillis;
        }

        @Override
        public int read() throws IOException {
            pause();
            return super.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            pause();
            return super.read(b, off, len);
        }

        private void pause() throws IOException {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while pausing", e);
            }
        }
    }

    private long completedExportBatch(Member member) throws Exception {
        Certificate certificate = certificate(member);
        return createBatch(member,
                upload(member, smallCsv(), "members.csv"), certificate, 0).get("id").asLong();
    }

    private long completedExport(Member member, long batchId) throws Exception {
        fillAll(member, batchId);
        checkAll(member, batchId);
        long exportId = createExport(member, batchId, "PDF", "exp-" + UUID.randomUUID()).get("id").asLong();
        JsonNode advanced = advanceAll(member, batchId, exportId);
        assertThat(advanced.get("state").asText()).isEqualTo("COMPLETE");
        return exportId;
    }

    private Member signIn(String subject) {
        long userId = userIdentityRepository.recordLogin(ISSUER, subject, null, null).id();
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(userId);
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim(IdTokenClaimNames.ISS, ISSUER)
                .claim(IdTokenClaimNames.SUB, subject)
                .build();
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "entra"));
        Session session = createAuthenticatedSession(sessionRepository, context);
        Cookie cookie = new Cookie("SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
        return new Member(cookie, workspace.id(), userId);
    }

    private static <S extends Session> S createAuthenticatedSession(FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }

    private long upload(Member member, byte[] bytes, String filename) throws Exception {
        long artifactId = readJson(mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/uploads")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"filename\":\"" + filename + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mockMvc.perform(put("/api/v1/workspaces/" + member.workspaceId() + "/uploads/" + artifactId + "/content")
                        .cookie(member.session()).with(csrf()).contentType("application/octet-stream").content(bytes))
                .andExpect(status().isOk());
        MvcResult completed = mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/uploads/" + artifactId
                        + "/complete").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(readJson(completed).get("status").asText()).isEqualTo("READY");
        return artifactId;
    }

    private Certificate certificate(Member member) throws Exception {
        long artifactId = upload(member, publicFixture("pdf/certificate-of-participation.pdf"), "certificate.pdf");
        JsonNode form = readJson(mockMvc.perform(post(fillableFormPath(member, artifactId)).cookie(member.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
        long templateId = readJson(mockMvc.perform(post(templatesPath(member)).cookie(member.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"displayName\":\"Participation certificate\",\"sourceArtifactId\":" + artifactId + "}"))
                .andExpect(status().isCreated()).andReturn()).get("template").get("id").asLong();
        ObjectNode body = JSON.createObjectNode().put("expectedVersionNumber", 1);
        ArrayNode fields = body.putArray("fields");
        Map<String, String> fieldIds = new LinkedHashMap<>();
        for (JsonNode spot : form.get("spots")) {
            JsonNode binding = spot.get("binding");
            String key = binding.hasNonNull("acroFormField") ? binding.get("acroFormField").asText() : spot.get("fieldId").asText();
            fieldIds.put(key, spot.get("fieldId").asText());
            ObjectNode field = fields.addObject();
            for (String name : List.of("fieldId", "label", "type", "cardinality", "requiredness", "origin", "docxControl", "blankText")) {
                if (spot.hasNonNull(name)) {
                    field.set(name, spot.get(name));
                }
            }
            field.set("binding", binding);
        }
        mockMvc.perform(put(templatesPath(member) + "/" + templateId + "/draft/bindings").cookie(member.session()).with(csrf())
                        .contentType("application/json").content(JSON.writeValueAsString(body)))
                .andExpect(status().isOk());
        MvcResult activation = mockMvc.perform(post(templatesPath(member) + "/" + templateId + "/versions")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"expectedVersionNumber\":2}"))
                .andReturn();
        assertThat(activation.getResponse().getStatus())
                .as(activation.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
        long versionId = readJson(activation).get("id").asLong();
        return new Certificate(artifactId, templateId, versionId, fieldIds);
    }

    private static byte[] smallCsv() {
        return ("Member name,Award,Date awarded,Club,Citation notes\n"
                + "Ana Lima,Star Contributor,2025-03-04,North Chapter,For the newsletter drive\n"
                + "Ben Okafor,Service Pin,2025-04-05,Harbour Circle,For the winter fair\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String mappingJson(Certificate certificate) {
        StringBuilder json = new StringBuilder("[");
        String[][] formColumns = {
                {"member.name", "0"}, {"award.title", "1"}, {"award.date", "2"}, {"club.name", "3"}, {"citation", "4"}};
        for (String[] formColumn : formColumns) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append("{\"fieldId\":\"").append(certificate.fieldId(formColumn[0]))
                    .append("\",\"column\":").append(formColumn[1]).append('}');
        }
        return json.append(']').toString();
    }

    private JsonNode createBatch(Member member, long tableId, Certificate certificate, int titleColumn) throws Exception {
        return readJson(mockMvc.perform(post(batchesPath(member)).cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", "batch-" + UUID.randomUUID()).contentType("application/json")
                        .content("{\"templateId\":" + certificate.templateId() + ",\"templateVersionId\":" + certificate.versionId()
                                + ",\"artifactId\":" + tableId + ",\"mapping\":" + mappingJson(certificate)
                                + ",\"titleColumn\":" + titleColumn + "}"))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode fillAll(Member member, long batchId) throws Exception {
        JsonNode step;
        int calls = 0;
        do {
            step = readJson(mockMvc.perform(post(batchPath(member, batchId) + "/fill")
                            .cookie(member.session()).with(csrf()))
                    .andExpect(status().isOk()).andReturn());
            assertThat(++calls).as("fill loops").isLessThanOrEqualTo(20);
        } while (step.get("remaining").asInt() > 0);
        return step;
    }

    private JsonNode checkAll(Member member, long batchId) throws Exception {
        JsonNode step;
        int calls = 0;
        do {
            step = readJson(mockMvc.perform(post(batchPath(member, batchId) + "/check")
                            .cookie(member.session()).with(csrf()))
                    .andExpect(status().isOk()).andReturn());
            assertThat(++calls).as("check loops").isLessThanOrEqualTo(40);
        } while (step.get("remaining").asInt() > 0);
        return step;
    }

    private JsonNode createExport(Member member, long batchId, String format, String key) throws Exception {
        return readJson(mockMvc.perform(post(batchPath(member, batchId) + "/exports")
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"format\":\"" + format + "\"}"))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode advanceAll(Member member, long batchId, long exportId) throws Exception {
        JsonNode step;
        int calls = 0;
        do {
            step = readJson(mockMvc.perform(post(batchPath(member, batchId) + "/exports/" + exportId + "/advance")
                            .cookie(member.session()).with(csrf()))
                    .andExpect(status().isOk()).andReturn());
            assertThat(++calls).as("advance loops").isLessThanOrEqualTo(60);
        } while (step.get("remaining").asInt() > 0);
        return step;
    }

    private static String batchesPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/batches";
    }

    private static String batchPath(Member member, long batchId) {
        return batchesPath(member) + "/" + batchId;
    }

    private static String fillableFormPath(Member member, long artifactId) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/artifacts/" + artifactId + "/fillable-form";
    }

    private static String templatesPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/templates";
    }

    private static JsonNode readJson(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static byte[] publicFixture(String name) throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("fixtures/public"))) {
            current = current.getParent();
        }
        assertThat(current).as("the repository root, from the test's working directory").isNotNull();
        return Files.readAllBytes(current.resolve("fixtures/public").resolve(name));
    }
}
