package io.github.vihuynh72.brownie.api.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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

/**
 * The form library end to end: the catalog answers anonymously, and a start
 * puts the fetched form through the same intake an upload takes -- the
 * stubbed fetcher hands over a real PDF, and the document that comes back
 * has the places the form's own fields made.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
class FormsControllerIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-forms-controller";

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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final StubFetcher STUB = new StubFetcher();

    /** The fetcher never leaves the process: the bytes are the test's own W-9, or the run's one failure. */
    @TestConfiguration
    static class StubFetchConfig {
        @Bean
        @Primary
        CatalogFormFetcher stubFetcher() {
            return STUB;
        }
    }

    static final class StubFetcher implements CatalogFormFetcher {
        boolean fail;
        int fetches;

        @Override
        public byte[] fetch(FormLibraryCatalog.Entry entry) throws IOException {
            if (fail) {
                throw new IOException("the agency is down");
            }
            fetches++;
            try (var in = getClass().getResourceAsStream("/w9.pdf")) {
                return in.readAllBytes();
            }
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private io.github.vihuynh72.brownie.core.template.TemplateService templateService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.BeforeEach
    void clearKeptCopies() {
        // Each test owns the kept-bytes and prepared-answer caches: a row another
        // test left could serve a start that should have fetched, or skip a
        // prepare that should have run.
        jdbcTemplate.update("DELETE FROM catalog_form_source");
        jdbcTemplate.update("DELETE FROM prepare_cache");
    }

    @Test
    void theCatalogAnswersWithoutASession() throws Exception {
        JsonNode body = readJson(mockMvc.perform(get("/api/v1/forms").param("q", "w-9"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(body.get("forms")).isNotEmpty();
        assertThat(body.get("forms").get(0).get("id").asText()).isEqualTo("irs-w9");
        assertThat(body.get("forms").get(0).get("agency").asText()).contains("Internal Revenue");

        JsonNode all = readJson(mockMvc.perform(get("/api/v1/forms"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(all.get("forms").size()).isLessThanOrEqualTo(20);
        assertThat(all.get("forms").get(0).get("id").asText()).isEqualTo("irs-w9");
    }

    @Test
    void aStartRunsTheFetchThroughTheRealIntake() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-forms-start");
        STUB.fail = false;

        JsonNode body = readJson(mockMvc.perform(post("/api/v1/forms/irs-w9/start")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andReturn());
        long documentId = body.get("documentId").asLong();
        assertThat(documentId).isPositive();

        // The document is the W-9, places and all: the intake bound the form's own fields into
        // the template version the document was started from.
        var user = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-forms-start").orElseThrow();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(user.id()).id();
        var document = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId)
                        .cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        var version = templateService
                .findVersion(workspaceId, user.id(),
                        document.get("templateId").asLong(), document.get("templateVersionId").asLong())
                .orElseThrow();
        assertThat(version.fieldDefinitions().size()).isGreaterThan(10);
    }

    @Test
    void aSecondStartUsesTheReadyCopyAndDoesNotFetchAgain() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-forms-ready");
        STUB.fail = false;
        int before = STUB.fetches;

        mockMvc.perform(post("/api/v1/forms/irs-w9/start")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());
        JsonNode second = readJson(mockMvc.perform(post("/api/v1/forms/irs-w9/start")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andReturn());

        // The first start fetched and prepared; the second cloned the ready copy, so the
        // fetcher ran only once even though a fresh document was made each time.
        assertThat(STUB.fetches).isEqualTo(before + 1);
        assertThat(second.get("documentId").asLong()).isPositive();
    }

    @Test
    void anUnknownIdAnswers404() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-forms-404");
        mockMvc.perform(post("/api/v1/forms/no-such-form/start")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aFetchFailureAnswers502WithTheAgencyNamed() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-forms-502");
        STUB.fail = true;
        try {
            mockMvc.perform(post("/api/v1/forms/irs-w9/start")
                            .cookie(session)
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.detail").value(
                            "Brownie couldn't download this form from Internal Revenue Service right now. Try again, or upload your copy."));
        } finally {
            STUB.fail = false;
        }
    }

    private JsonNode readJson(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private Cookie loginAndGetSessionCookie(String subject) {
        userIdentityRepository.recordLogin(ISSUER, subject, null, null);
        Workspace workspace = ensureWorkspace(subject);
        assertThat(workspace).isNotNull();

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
        return new Cookie(
                "SESSION", java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    private Workspace ensureWorkspace(String subject) {
        var identity = userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow();
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
