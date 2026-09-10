package io.github.vihuynh72.brownie.api.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the real, production {@code ArtifactContentInspector} decompression
 * bounds (500 package entries, 200 MiB uncompressed) reject a hostile
 * package through the actual HTTP upload path against real Azurite -- not
 * only through the core module's own unit test, which deliberately injects
 * small thresholds instead of these exact constants so it can run without
 * building real hundred-megabyte payloads. This class owns its own Spring
 * context specifically to raise the raw upload-size cap far enough to admit
 * a real, small, highly-compressible decompression bomb; a real ClamAV is
 * not needed since classification runs before a scan is ever attempted, so
 * the scanner is pointed at an unreachable loopback, the same way {@link
 * MalwareScannerUnavailableIntegrationTest} does when it doesn't need one
 * either.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.autoconfigure.exclude=", "brownie.artifacts.max-upload-bytes=1000000"})
@Testcontainers
class ArtifactDecompressionLimitsIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";

    // Mirrors ArtifactContentInspector's own private production constants --
    // duplicated here deliberately so this test proves the real numbers,
    // not whatever the constant happens to be renamed or changed to; if
    // the real constant and this one ever drift apart, that is exactly
    // the kind of thing this test exists to catch.
    private static final long REAL_MAX_UNCOMPRESSED_BYTES = 200L * 1024 * 1024;
    private static final int REAL_MAX_ZIP_ENTRIES = 500;

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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("brownie.storage.local-connection", AZURITE::getConnectionString);
        registry.add("brownie.security.clamav.host", () -> "127.0.0.1");
        // A real, valid loopback address with nothing listening on this
        // port -- never actually dialed by either test in this class.
        registry.add("brownie.security.clamav.port", () -> 1);
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath()
                .getParent()
                .getParent()
                .resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Test
    void aPackageWithMoreEntriesThanTheRealProductionCapIsRejected() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-entry-count-bomb");
        long workspaceId = workspaceIdFor("subject-entry-count-bomb");
        long artifactId = allocate(owner, workspaceId);
        byte[] zip = zipWithEntries(REAL_MAX_ZIP_ENTRIES + 1);

        mockMvc.perform(put(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/content",
                                workspaceId,
                                artifactId)
                        .cookie(owner)
                        .with(csrf())
                        .content(zip))
                .andExpect(status().isContentTooLarge());
    }

    @Test
    void aPackageThatExpandsPastTheRealTwoHundredMebibyteUncompressedCapIsRejected() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-decompression-bomb");
        long workspaceId = workspaceIdFor("subject-decompression-bomb");
        long artifactId = allocate(owner, workspaceId);
        // A real decompression bomb: a single highly-compressible entry
        // that DEFLATE shrinks to roughly 200 KB on the wire but which
        // expands to just over the real 200 MiB cap once read back --
        // proving the actual production constant fires during a real
        // classification pass against real Azurite, not a small
        // test-only stand-in.
        byte[] zip = zipExpandingTo(REAL_MAX_UNCOMPRESSED_BYTES + 1024);

        mockMvc.perform(put(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/content",
                                workspaceId,
                                artifactId)
                        .cookie(owner)
                        .with(csrf())
                        .content(zip))
                .andExpect(status().isContentTooLarge());
    }

    private static byte[] zipWithEntries(int count) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (int i = 0; i < count; i++) {
                zip.putNextEntry(new ZipEntry("entry-" + i + ".txt"));
                zip.write(("content " + i).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static byte[] zipExpandingTo(long targetUncompressedBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            zip.putNextEntry(new ZipEntry("bomb.txt"));
            byte[] chunk = new byte[1 << 20];
            long written = 0;
            while (written < targetUncompressedBytes) {
                int n = (int) Math.min(chunk.length, targetUncompressedBytes - written);
                zip.write(chunk, 0, n);
                written += n;
            }
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    private long allocate(Cookie sessionCookie, long workspaceId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/uploads", workspaceId)
                        .cookie(sessionCookie)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return OBJECT_MAPPER.readTree(body).get("id").asLong();
    }

    private long workspaceIdFor(String subject) {
        UserIdentity identity = userIdentityRepository
                .findByIssuerAndSubject("https://issuer-decompression-limits", subject)
                .orElseThrow();
        return workspaceRepository.ensurePersonalWorkspace(identity.id()).id();
    }

    private Cookie loginAndGetSessionCookie(String subject) {
        String issuer = "https://issuer-decompression-limits";
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

    private static <S extends Session> S createAuthenticatedSession(
            FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }
}
