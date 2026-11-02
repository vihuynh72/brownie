package io.github.vihuynh72.brownie.api.detail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
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
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The saved-details API end to end, with a sealing key set. */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@DockerTest
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
class DetailsControllerIntegrationTest {

    private static final String ISSUER = "https://issuer-details";
    private static final String DETAILS_KEY = java.util.Base64.getEncoder().encodeToString(new byte[32]);

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> "brownie_api_local_only");
        registry.add("spring.flyway.url", DB::jdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> "brownie_migration_local_only");
        registry.add("brownie.details.key", () -> DETAILS_KEY);
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private io.github.vihuynh72.brownie.core.detail.SavedDetailRepository savedDetailRepository;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void theListIsOrderedByGroupThenKeyAndSensitiveRowsAreMasked() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-details-list");
        savePlain("subject-details-list", "address.line1", "Street address", "1180 Harbor Way");
        savePlain("subject-details-list", "person.full_name", "Full name", "Daniel Ortiz");
        // A sensitive row is masked: only the hint shows.
        var sealer = DetailSealer.fromBase64(DETAILS_KEY).orElseThrow();
        long user = identity("subject-details-list").id();
        long ws = workspaceRepository.ensurePersonalWorkspace(user).id();
        savedDetailRepository.upsert(user, ws, "sensitive.ssn", "Social security number",
                null, sealer.seal("123-45-6789"), "6789", true,
                io.github.vihuynh72.brownie.core.detail.DetailOrigin.MANUAL, null, null, null);

        JsonNode body = readJson(mockMvc.perform(get("/api/v1/me/details").cookie(session)).andExpect(status().isOk()).andReturn());
        assertThat(body.get("sealingAvailable").asBoolean()).isTrue();
        JsonNode details = body.get("details");
        assertThat(details).hasSize(3);
        // identity before address before sensitive (group order), keys in declaration order inside a group.
        assertThat(details.get(0).get("key").asText()).isEqualTo("person.full_name");
        assertThat(details.get(1).get("key").asText()).isEqualTo("address.line1");
        assertThat(details.get(2).get("key").asText()).isEqualTo("sensitive.ssn");
        assertThat(details.get(2).get("value").isNull()).isTrue();
        assertThat(details.get(2).get("hint").asText()).isEqualTo("6789");
        assertThat(details.get(0).get("value").asText()).isEqualTo("Daniel Ortiz");
    }

    @Test
    void aManualSaveStoresTheValueAndReportsItBack() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-manual-save");
        JsonNode saved = readJson(mockMvc.perform(put("/api/v1/me/details/contact.email")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"value\":\"daniel@example.com\"}"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(saved.get("key").asText()).isEqualTo("contact.email");
        assertThat(saved.get("value").asText()).isEqualTo("daniel@example.com");
        assertThat(saved.get("origin").asText()).isEqualTo("MANUAL");
        assertThat(saved.get("source").isNull()).isTrue();
    }

    @Test
    void aSensitiveManualSaveIsSealedAndTheResponseCarriesOnlyTheHint() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-manual-sensitive");
        JsonNode saved = readJson(mockMvc.perform(put("/api/v1/me/details/sensitive.ssn")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"value\":\"123-45-6789\"}"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(saved.get("sensitive").asBoolean()).isTrue();
        assertThat(saved.get("value").isNull()).isTrue();
        assertThat(saved.get("hint").asText()).isEqualTo("6789");
        // And the stored row really is sealed, never plain text.
        var stored = savedDetailRepository.list(identity("subject-manual-sensitive").id());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).valueText()).isNull();
        assertThat(stored.get(0).valueSealed()).isNotNull();
    }

    @Test
    void blankAndTooLongValuesAreRefused() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-bad-value");
        mockMvc.perform(put("/api/v1/me/details/contact.email")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"value\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/me/details/contact.email")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"value\":\"" + "x".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());
        // A sensitive value's bound is 200, not 2000.
        mockMvc.perform(put("/api/v1/me/details/sensitive.ssn")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"value\":\"" + "1".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownKeyIsANotFound() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-unknown-key");
        mockMvc.perform(put("/api/v1/me/details/nonsense.key")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"value\":\"anything\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteOneAndDeleteAll() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-deletes");
        savePlain("subject-deletes", "contact.email", "Email", "a@b.com");
        savePlain("subject-deletes", "person.full_name", "Full name", "A B");

        mockMvc.perform(delete("/api/v1/me/details/contact.email")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());
        // Deleting a key that has no row is also 204.
        mockMvc.perform(delete("/api/v1/me/details/contact.email")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/me/details")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());
        assertThat(savedDetailRepository.list(identity("subject-deletes").id())).isEmpty();
    }

    @Test
    void anotherPersonCannotSeeTheRows() throws Exception {
        savePlain("subject-owner", "person.full_name", "Full name", "Owner Name");
        Cookie other = loginAndGetSessionCookie("subject-other");
        JsonNode body = readJson(mockMvc.perform(get("/api/v1/me/details").cookie(other)).andExpect(status().isOk()).andReturn());
        assertThat(body.get("details")).isEmpty();
    }

    @Test
    void theIdentityPayloadIncludesTheDetailsMasked() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-me-export");
        savePlain("subject-me-export", "person.full_name", "Full name", "Daniel Ortiz");
        var sealer = DetailSealer.fromBase64(DETAILS_KEY).orElseThrow();
        long user = identity("subject-me-export").id();
        long ws = workspaceRepository.ensurePersonalWorkspace(user).id();
        savedDetailRepository.upsert(user, ws, "sensitive.ssn", "Social security number",
                null, sealer.seal("123-45-6789"), "6789", true,
                io.github.vihuynh72.brownie.core.detail.DetailOrigin.MANUAL, null, null, null);

        JsonNode me = readJson(mockMvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isOk()).andReturn());
        JsonNode details = me.get("savedDetails");
        assertThat(details).hasSize(2);
        assertThat(details.get(0).get("value").asText()).isEqualTo("Daniel Ortiz");
        assertThat(details.get(1).get("value").isNull()).isTrue();
        assertThat(details.get(1).get("hint").asText()).isEqualTo("6789");
    }

    /** Confirms what phase 1a's repository test proved: the account-deletion path takes the details. */
    @Test
    @Transactional
    void deletingTheAccountTakesTheDetailsWithIt() throws Exception {
        savePlain("subject-cascade", "person.full_name", "Full name", "Gone Soon");
        long user = identity("subject-cascade").id();
        long ws = workspaceRepository.ensurePersonalWorkspace(user).id();
        assertThat(savedDetailRepository.list(user)).hasSize(1);
        jdbcTemplate.queryForObject("SELECT set_config('app.current_user_id', ?, true)", String.class, String.valueOf(user));
        jdbcTemplate.queryForObject("SELECT delete_workspace(?)", String.class, ws);
        assertThat(savedDetailRepository.list(user)).isEmpty();
    }

    private void savePlain(String subject, String key, String label, String value) {
        long user = identity(subject).id();
        long ws = workspaceRepository.ensurePersonalWorkspace(user).id();
        savedDetailRepository.upsert(user, ws, key, label, value, null, null, false,
                io.github.vihuynh72.brownie.core.detail.DetailOrigin.MANUAL, null, null, null);
    }

    private io.github.vihuynh72.brownie.core.identity.UserIdentity identity(String subject) {
        userIdentityRepository.recordLogin(ISSUER, subject, null, null);
        return userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow();
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
        return new Cookie("SESSION", java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
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

    private JsonNode readJson(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
    }
}
