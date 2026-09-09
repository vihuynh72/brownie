package io.github.vihuynh72.brownie.api.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactRepository;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStateConflictException;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStatus;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the whole allocate/upload/complete flow end to end against real
 * infrastructure: a real Postgres (row-level security included), a real
 * Azurite, and a real ClamAV, driven entirely through MockMvc-issued HTTP
 * requests carrying a real authenticated session and a real CSRF token,
 * the same way {@code SessionRevocationIntegrationTest} builds one -- not
 * a fabricated principal handed straight to a controller method.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.autoconfigure.exclude=", "brownie.artifacts.max-upload-bytes=2000"})
@Testcontainers
class ArtifactUploadIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("brownie")
            .withUsername("postgres")
            .withPassword(BOOTSTRAP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(initScriptPath()), "/docker-entrypoint-initdb.d/01-app-roles.sql");

    @Container
    static final AzuriteContainer AZURITE =
            new AzuriteContainer("mcr.microsoft.com/azure-storage/azurite:3.37.0");

    @Container
    static final GenericContainer<?> CLAMAV = new GenericContainer<>(DockerImageName.parse("clamav/clamav-debian:1.4"))
            .withExposedPorts(3310)
            .waitingFor(Wait.forLogMessage(".*socket found, clamd started\\.\\n", 1))
            .withStartupTimeout(java.time.Duration.ofMinutes(3));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("brownie.storage.local-connection", AZURITE::getConnectionString);
        registry.add("brownie.security.clamav.host", CLAMAV::getHost);
        registry.add("brownie.security.clamav.port", () -> CLAMAV.getMappedPort(3310));
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath()
                .getParent()
                .getParent()
                .resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    // A plain, local instance rather than an autowired bean: this Boot
    // line's web starter does not register an ObjectMapper bean of its
    // own (Spring MVC falls back to building one internally for message
    // conversion instead), so there is nothing to inject -- confirmed by
    // an UnsatisfiedDependencyException on first attempting @Autowired
    // here, not assumed. This test only needs to read a few plain fields
    // out of a response body, not the application's exact configured
    // serialization behavior.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private ArtifactRepository artifactRepository;

    @Test
    void theWholeAllocateUploadCompleteFlowWorksAgainstRealPostgresAndRealAzurite() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-owner");
        long workspaceId = workspaceIdFor("subject-owner");

        long artifactId = allocate(owner, workspaceId);

        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8); // 11 bytes, under the 20-byte test limit
        JsonNode uploadResponse = uploadContent(owner, workspaceId, artifactId, content);
        assertThat(uploadResponse.get("status").asText()).isEqualTo("UPLOADING");
        assertThat(uploadResponse.get("byteCount").asLong()).isEqualTo(content.length);
        String expectedSha256 = sha256Hex(content);
        assertThat(uploadResponse.get("sha256").asText()).isEqualTo(expectedSha256);

        JsonNode completeResponse = complete(owner, workspaceId, artifactId);
        assertThat(completeResponse.get("status").asText()).isEqualTo("READY");
        assertThat(completeResponse.get("byteCount").asLong()).isEqualTo(content.length);
        assertThat(completeResponse.get("sha256").asText()).isEqualTo(expectedSha256);

        // Idempotent: completing an already-READY artifact again succeeds
        // and returns the same, unchanged result, without scanning again
        // (nothing here asserts that directly -- ArtifactServiceTest
        // already proves the no-rescan behavior against a fake scanner
        // where the call count is actually observable).
        JsonNode secondComplete = complete(owner, workspaceId, artifactId);
        assertThat(secondComplete.get("status").asText()).isEqualTo("READY");
        assertThat(secondComplete.get("byteCount").asLong()).isEqualTo(content.length);
    }

    @Test
    void theEicarTestFileIsDetectedAndRejectedAgainstRealClamAv() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-eicar-upload");
        long workspaceId = workspaceIdFor("subject-eicar-upload");
        long artifactId = allocate(owner, workspaceId);

        // The industry-standard EICAR test string -- not real malware, but
        // recognized by every antivirus engine including ClamAV by design,
        // specifically so it's safe to use in a test exactly like this one.
        byte[] eicar = ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EI" + "CAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")
                .getBytes(StandardCharsets.US_ASCII);
        uploadContent(owner, workspaceId, artifactId, eicar);

        JsonNode completeResponse = complete(owner, workspaceId, artifactId);

        assertThat(completeResponse.get("status").asText()).isEqualTo("REJECTED");
        assertThat(completeResponse.get("rejectionReason").asText()).isEqualTo("MALWARE_DETECTED");
    }

    @Test
    void downloadServesTheExactUploadedBytesWithAnAttachmentDisposition() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-download");
        long workspaceId = workspaceIdFor("subject-download");
        JsonNode allocated = allocate(owner, workspaceId, "minutes.txt");
        long artifactId = allocated.get("id").asLong();
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        uploadContent(owner, workspaceId, artifactId, content);
        complete(owner, workspaceId, artifactId);

        var response = mockMvc.perform(get(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/download",
                                workspaceId,
                                artifactId)
                        .cookie(owner))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(response.getContentAsByteArray()).isEqualTo(content);
        assertThat(response.getContentType()).isEqualTo("text/plain");
        assertThat(response.getHeader("Content-Disposition"))
                .contains("attachment")
                .contains("filename=\"minutes.txt\"");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    void previewServesTheSameBytesWithAnInlineDisposition() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-preview");
        long workspaceId = workspaceIdFor("subject-preview");
        long artifactId = allocate(owner, workspaceId);
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        uploadContent(owner, workspaceId, artifactId, content);
        complete(owner, workspaceId, artifactId);

        var response = mockMvc.perform(get(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/preview",
                                workspaceId,
                                artifactId)
                        .cookie(owner))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(response.getContentAsByteArray()).isEqualTo(content);
        assertThat(response.getHeader("Content-Disposition")).contains("inline");
    }

    @Test
    void downloadFallsBackToAGeneratedFilenameWhenNoneWasSupplied() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-download-noname");
        long workspaceId = workspaceIdFor("subject-download-noname");
        long artifactId = allocate(owner, workspaceId);
        uploadContent(owner, workspaceId, artifactId, "hello world".getBytes(StandardCharsets.UTF_8));
        complete(owner, workspaceId, artifactId);

        var response = mockMvc.perform(get(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/download",
                                workspaceId,
                                artifactId)
                        .cookie(owner))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(response.getHeader("Content-Disposition")).contains("filename=\"artifact-" + artifactId + ".txt\"");
    }

    @Test
    void downloadBeforeTheArtifactIsReadyIsAConflict() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-download-not-ready");
        long workspaceId = workspaceIdFor("subject-download-not-ready");
        long artifactId = allocate(owner, workspaceId);
        uploadContent(owner, workspaceId, artifactId, "hello world".getBytes(StandardCharsets.UTF_8));
        // Deliberately never completed -- still UPLOADING.

        mockMvc.perform(get(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/download",
                                workspaceId,
                                artifactId)
                        .cookie(owner))
                .andExpect(status().isConflict());
    }

    @Test
    void downloadOfARejectedArtifactIsAConflictEvenThoughItsBlobStillExists() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-download-rejected");
        long workspaceId = workspaceIdFor("subject-download-rejected");
        long artifactId = allocate(owner, workspaceId);
        byte[] eicar = ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EI" + "CAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")
                .getBytes(StandardCharsets.US_ASCII);
        uploadContent(owner, workspaceId, artifactId, eicar);
        complete(owner, workspaceId, artifactId);

        mockMvc.perform(get(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/download",
                                workspaceId,
                                artifactId)
                        .cookie(owner))
                .andExpect(status().isConflict());
    }

    @Test
    void aNonMemberCannotDownloadOrPreviewSomeoneElsesArtifact() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-download-victim");
        long workspaceId = workspaceIdFor("subject-download-victim");
        long artifactId = allocate(owner, workspaceId);
        uploadContent(owner, workspaceId, artifactId, "hello world".getBytes(StandardCharsets.UTF_8));
        complete(owner, workspaceId, artifactId);
        Cookie outsider = loginAndGetSessionCookie("subject-download-outsider");

        mockMvc.perform(get(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/download",
                                workspaceId,
                                artifactId)
                        .cookie(outsider))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/preview",
                                workspaceId,
                                artifactId)
                        .cookie(outsider))
                .andExpect(status().isForbidden());

        // The legitimate owner can still read it normally.
        mockMvc.perform(get(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/download",
                                workspaceId,
                                artifactId)
                        .cookie(owner))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentScanAttemptsForTheSameArtifactHaveExactlyOneWinner() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-concurrent-scan");
        long workspaceId = workspaceIdFor("subject-concurrent-scan");
        long userId = userIdFor("subject-concurrent-scan");
        long artifactId = allocate(owner, workspaceId);
        uploadContent(owner, workspaceId, artifactId, "hello world".getBytes(StandardCharsets.UTF_8));

        // Drives the artifact to QUARANTINED directly at the repository
        // level, bypassing ArtifactService#finalizeUpload -- that method
        // scans synchronously within the same call, which would leave
        // nothing left to race once it returns.
        Artifact quarantined = artifactRepository.finalizeUpload(workspaceId, userId, artifactId);
        assertThat(quarantined.status()).isEqualTo(ArtifactStatus.QUARANTINED);

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CyclicBarrier startLine = new CyclicBarrier(attempts);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    startLine.await();
                    try {
                        artifactRepository.beginScanning(workspaceId, userId, artifactId);
                        return true;
                    } catch (ArtifactStateConflictException e) {
                        return false;
                    }
                }));
            }
            long winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    winners++;
                }
            }
            // The real, motivating bug: a lenient "apply-or-return-current-state"
            // WHERE clause let every one of these concurrent callers past the
            // gate, each free to reach and act on its own scan verdict for the
            // same artifact. Against real Postgres, the strict QUARANTINED-only
            // transition must let exactly one caller through.
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void aNonMemberCannotAllocateAnUploadInSomeoneElsesWorkspace() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-victim");
        long workspaceId = workspaceIdFor("subject-victim");
        Cookie outsider = loginAndGetSessionCookie("subject-outsider");

        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/uploads", workspaceId)
                        .cookie(outsider)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        // The legitimate owner can still allocate normally in their own workspace.
        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/uploads", workspaceId)
                        .cookie(owner)
                        .with(csrf()))
                .andExpect(status().isCreated());
    }

    @Test
    void contentOverTheConfiguredLimitIsRejected() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-large-upload");
        long workspaceId = workspaceIdFor("subject-large-upload");
        long artifactId = allocate(owner, workspaceId);

        byte[] tooLarge = "this string is definitely over the configured 2000-byte test limit, ".repeat(40)
                .getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(put(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/content",
                                workspaceId,
                                artifactId)
                        .cookie(owner)
                        .with(csrf())
                        .content(tooLarge))
                .andExpect(status().isContentTooLarge());
    }

    @Test
    void completingBeforeAnyContentIsUploadedIsAConflict() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-no-content");
        long workspaceId = workspaceIdFor("subject-no-content");
        long artifactId = allocate(owner, workspaceId);

        mockMvc.perform(post(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/complete",
                                workspaceId,
                                artifactId)
                        .cookie(owner)
                        .with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void aRealDocxIsClassifiedCorrectlyAndItsFilenameSanitized() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-docx-upload");
        long workspaceId = workspaceIdFor("subject-docx-upload");

        JsonNode allocated = allocate(owner, workspaceId, "../minutes.docx");
        assertThat(allocated.get("displayFilename").asText()).isEqualTo("minutes.docx");
        long artifactId = allocated.get("id").asLong();

        byte[] docx = minimalOoxmlPackage();
        JsonNode uploadResponse = uploadContent(owner, workspaceId, artifactId, docx);
        assertThat(uploadResponse.get("detectedMediaType").asText()).isEqualTo("DOCX");

        JsonNode completeResponse = complete(owner, workspaceId, artifactId);
        assertThat(completeResponse.get("status").asText()).isEqualTo("READY");
        assertThat(completeResponse.get("detectedMediaType").asText()).isEqualTo("DOCX");
        assertThat(completeResponse.get("displayFilename").asText()).isEqualTo("minutes.docx");
    }

    @Test
    void contentThatMatchesNoSupportedTypeIsRejectedWith415AgainstRealAzurite() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-unsupported-upload");
        long workspaceId = workspaceIdFor("subject-unsupported-upload");
        long artifactId = allocate(owner, workspaceId);

        byte[] binary = {0x01, 0x02, 0x00, 0x03, (byte) 0xFF, 0x04};
        mockMvc.perform(put(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/content",
                                workspaceId,
                                artifactId)
                        .cookie(owner)
                        .with(csrf())
                        .content(binary))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void aZipThatIsNotAnOoxmlPackageIsRejectedWith415AgainstRealAzurite() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-plain-zip-upload");
        long workspaceId = workspaceIdFor("subject-plain-zip-upload");
        long artifactId = allocate(owner, workspaceId);

        byte[] plainZip = zipOf(java.util.Map.of("readme.txt", "just a zip, not a docx"));
        mockMvc.perform(put(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/content",
                                workspaceId,
                                artifactId)
                        .cookie(owner)
                        .with(csrf())
                        .content(plainZip))
                .andExpect(status().isUnsupportedMediaType());
    }

    private static byte[] minimalOoxmlPackage() throws java.io.IOException {
        return zipOf(java.util.Map.of(
                "[Content_Types].xml",
                "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
                "word/document.xml",
                "<w:document/>"));
    }

    private static byte[] zipOf(java.util.Map<String, String> entries) throws java.io.IOException {
        var buffer = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(buffer)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes());
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private long allocate(Cookie sessionCookie, long workspaceId) throws Exception {
        return allocate(sessionCookie, workspaceId, null).get("id").asLong();
    }

    private JsonNode allocate(Cookie sessionCookie, long workspaceId, String filename) throws Exception {
        var requestBuilder = post("/api/v1/workspaces/{workspaceId}/uploads", workspaceId)
                .cookie(sessionCookie)
                .with(csrf());
        if (filename != null) {
            requestBuilder = requestBuilder
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"filename\":\"" + filename.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        }
        String body = mockMvc.perform(requestBuilder)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return OBJECT_MAPPER.readTree(body);
    }

    private JsonNode uploadContent(Cookie sessionCookie, long workspaceId, long artifactId, byte[] content)
            throws Exception {
        String body = mockMvc.perform(put(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/content",
                                workspaceId,
                                artifactId)
                        .cookie(sessionCookie)
                        .with(csrf())
                        .content(content))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return OBJECT_MAPPER.readTree(body);
    }

    private JsonNode complete(Cookie sessionCookie, long workspaceId, long artifactId) throws Exception {
        String body = mockMvc.perform(post(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/complete",
                                workspaceId,
                                artifactId)
                        .cookie(sessionCookie)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return OBJECT_MAPPER.readTree(body);
    }

    private long workspaceIdFor(String subject) {
        UserIdentity identity = userIdentityRepository
                .findByIssuerAndSubject("https://issuer-artifact-upload", subject)
                .orElseThrow();
        return workspaceRepository.ensurePersonalWorkspace(identity.id()).id();
    }

    private long userIdFor(String subject) {
        return userIdentityRepository
                .findByIssuerAndSubject("https://issuer-artifact-upload", subject)
                .orElseThrow()
                .id();
    }

    /**
     * Builds a real, authenticated session through the exact repository
     * bean the application uses at runtime, then returns the cookie a
     * browser would present for it -- Spring Security Test's own {@code
     * oidcLogin()} helper authenticates onto a throwaway servlet session
     * created before Spring Session's filter ever runs, which never
     * touches spring_session at all (see SessionRevocationIntegrationTest).
     */
    private Cookie loginAndGetSessionCookie(String subject) {
        String issuer = "https://issuer-artifact-upload";
        userIdentityRepository.recordLogin(issuer, subject, null, null);
        Workspace workspace = ensureWorkspace(issuer, subject);
        assertThat(workspace).isNotNull();

        OidcIdToken idToken = OidcIdToken.withTokenValue("test-token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim(IdTokenClaimNames.ISS, issuer)
                .claim(IdTokenClaimNames.SUB, subject)
                .build();
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "entra"));

        Session session = createAuthenticatedSession(sessionRepository, context);

        return new Cookie(
                "SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    private Workspace ensureWorkspace(String issuer, String subject) {
        UserIdentity identity = userIdentityRepository.findByIssuerAndSubject(issuer, subject).orElseThrow();
        return workspaceRepository.ensurePersonalWorkspace(identity.id());
    }

    /** A generic type parameter ties createSession() and save() to the same concrete session type, avoiding a wildcard-capture mismatch across two separate calls. */
    private static <S extends Session> S createAuthenticatedSession(
            FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }

    private static String sha256Hex(byte[] content) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(digest.digest(content));
    }
}
