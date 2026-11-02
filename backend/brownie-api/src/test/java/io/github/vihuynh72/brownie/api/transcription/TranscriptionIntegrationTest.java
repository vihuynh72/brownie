package io.github.vihuynh72.brownie.api.transcription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.model.Transcription;
import io.github.vihuynh72.brownie.core.model.TranscriptionGateway;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
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
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Voice notes into text: the route turns what the composer heard into words
 * the person can edit and send. The audio is never stored -- this test
 * checks the ledger saw one TRANSCRIPTION call and nothing else kept the
 * bytes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
class TranscriptionIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-voice";
    private static final String BOUNDARY = "----voiceBoundary";

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @MockitoBean
    TranscriptionGateway gateway;

    private record Member(Cookie session, long workspaceId) {}

    private static <S extends Session> S createAuthenticatedSession(
            FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(
                org.springframework.security.web.context.HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }

    private Member signIn(String subject) {
        userIdentityRepository.recordLogin(ISSUER, subject, null, null);
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow().id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-token")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .claim(IdTokenClaimNames.ISS, ISSUER)
                .claim(IdTokenClaimNames.SUB, subject)
                .build();
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "entra"));
        Session session = createAuthenticatedSession(sessionRepository, context);
        return new Member(new Cookie("SESSION",
                java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8))),
                workspaceId);
    }

    private static byte[] wav() throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/voice-jordan.wav"));
    }

    private static byte[] multipartBody(byte[] audio) {
        String head = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"voice.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n";
        byte[] tail = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] h = head.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[h.length + audio.length + tail.length];
        System.arraycopy(h, 0, out, 0, h.length);
        System.arraycopy(audio, 0, out, h.length, audio.length);
        System.arraycopy(tail, 0, out, h.length + audio.length, tail.length);
        return out;
    }

    @Test
    void aVoiceNoteComesBackAsWordsAndItsSpendIsLedgered() throws Exception {
        Member member = signIn("subject-voice-happy");
        when(gateway.transcribe(any(), anyString()))
                .thenReturn(new Transcription("My name is Jordan Rivera.", "en", 3.1));

        String body = mockMvc.perform(post("/api/v1/workspaces/{w}/transcriptions", member.workspaceId())
                        .cookie(member.session()).with(csrf())
                        .contentType("audio/wav")
                        .content(wav()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("Jordan Rivera");

        try (Connection c = DriverManager.getConnection(DB.jdbcUrl(), "postgres", "postgres_bootstrap_only");
                PreparedStatement st = c.prepareStatement(
                        "SELECT count(*) FROM model_usage WHERE purpose = 'TRANSCRIPTION' AND state = 'SETTLED'")) {
            ResultSet rows = st.executeQuery();
            rows.next();
            assertThat(rows.getLong(1)).isEqualTo(1);
        }
        // The bytes went nowhere durable: no artifact row for a voice note.
        try (Connection c = DriverManager.getConnection(DB.jdbcUrl(), "postgres", "postgres_bootstrap_only");
                PreparedStatement st = c.prepareStatement("SELECT count(*) FROM artifact")) {
            ResultSet rows = st.executeQuery();
            rows.next();
            assertThat(rows.getLong(1)).isZero();
        }
    }

    @Test
    void wrongKindTooBigAndStrangersAreRefused() throws Exception {
        Member member = signIn("subject-voice-bad");
        mockMvc.perform(post("/api/v1/workspaces/{w}/transcriptions", member.workspaceId())
                        .cookie(member.session()).with(csrf())
                        .contentType("multipart/form-data; boundary=" + BOUNDARY)
                        .content(multipartBody("not audio at all".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnprocessableEntity());
        byte[] huge = new byte[(int) TranscriptionController.MAX_AUDIO_BYTES + 1];
        huge[0] = 'R'; huge[1] = 'I'; huge[2] = 'F'; huge[3] = 'F';
        mockMvc.perform(post("/api/v1/workspaces/{w}/transcriptions", member.workspaceId())
                        .cookie(member.session()).with(csrf()).content(huge))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(413, 422));
        Member other = signIn("subject-voice-outsider");
        mockMvc.perform(post("/api/v1/workspaces/{w}/transcriptions", member.workspaceId())
                        .cookie(other.session()).with(csrf()).content(wav()))
                .andExpect(status().isForbidden());
    }
}
