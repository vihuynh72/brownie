package io.github.vihuynh72.brownie.api.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.api.template.BuiltInTemplateProvisioningService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The composer end to end through real HTTP against real Postgres,
 * Azurite and ClamAV, with the model replaced by a fake that answers the
 * two prompt versions the composer sends -- every command's interpretation
 * shows its scope before anything runs, a change becomes a real proposal
 * that the ordinary accept route applies, a rewrite goes through exactly
 * one model call, an explanation needs a validated revision and reports
 * the finding it covers, free text is answered with what Brownie can do,
 * a stale revision is refused with 412, and another workspace sees 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
@Import(AssistIntegrationTest.FakeModelGatewayConfig.class)
class AssistIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-assist";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("brownie")
            .withUsername("postgres")
            .withPassword(BOOTSTRAP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(initScriptPath()), "/docker-entrypoint-initdb.d/01-app-roles.sql");

    @Container
    static final AzuriteContainer AZURITE = new AzuriteContainer("mcr.microsoft.com/azure-storage/azurite:3.37.0");

    @Container
    static final GenericContainer<?> CLAMAV = new GenericContainer<>(org.testcontainers.utility.DockerImageName.parse("clamav/clamav-debian:1.4"))
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
        return Path.of("").toAbsolutePath().getParent().getParent().resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    static final AtomicInteger MODEL_CALLS = new AtomicInteger();

    @TestConfiguration
    static class FakeModelGatewayConfig {
        @Bean
        @Primary
        ModelGateway fakeModelGateway() {
            return request -> {
                MODEL_CALLS.incrementAndGet();
                String reply = switch (request.promptVersion()) {
                    case AssistService.REWRITE_PROMPT_VERSION -> "{\"value\":\"Spring planning\"}";
                    case AssistService.EXPLAIN_PROMPT_VERSION ->
                            "{\"explanation\":\"The meeting date is required and is still empty; type the date in the Meeting date field.\"}";
                    default -> throw new AssertionError("Unexpected prompt version " + request.promptVersion());
                };
                return new ModelCompletion.Success(reply, new ModelUsage(40, 12));
            };
        }
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

    @Autowired
    private BuiltInTemplateProvisioningService builtInTemplateProvisioningService;

    @Test
    void theComposerInterpretsThenExecutesEachBoundedCommandAndRefusesTheRest() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-assist-owner");
        long workspaceId = ensureWorkspace("subject-assist-owner").id();
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-assist-owner").orElseThrow().id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);
        JsonNode flowing = findByDisplayName(
                readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/templates").cookie(session))
                        .andExpect(status().isOk())
                        .andReturn()),
                "Flowing meeting minutes");
        long documentId = createMinimalDocument(session, workspaceId, flowing.get("id").asLong(), flowing.get("currentActiveVersionId").asLong());
        long revisionId = currentRevisionId(session, workspaceId, documentId);

        // Free text: not a command, answered with what Brownie can do, nothing executed.
        JsonNode none = interpret(session, workspaceId, documentId, "write me a poem about the club");
        assertThat(none.get("kind").asText()).isEqualTo("NONE");
        assertThat(none.get("executable").asBoolean()).isFalse();
        assertThat(none.get("help")).hasSize(4);
        mockMvc.perform(post(assistPath(workspaceId, documentId) + "/execute").cookie(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"text\":\"write me a poem about the club\",\"expectedRevisionId\":" + revisionId + "}"))
                .andExpect(status().isBadRequest());

        // A change: the scope names the field and its current (empty) value; executing proposes, accepting applies.
        JsonNode change = interpret(session, workspaceId, documentId, "change the meeting title to Spring Planning Meeting");
        assertThat(change.get("kind").asText()).isEqualTo("CHANGE_FIELD");
        assertThat(change.get("executable").asBoolean()).isTrue();
        assertThat(change.get("usesModel").asBoolean()).isFalse();
        assertThat(change.get("scope").get("fieldId").asText()).isEqualTo("meeting.title");
        assertThat(change.get("scope").get("label").asText()).isEqualTo("Meeting title");
        assertThat(change.get("scope").get("currentValue").isNull()).isTrue();
        assertThat(change.get("summary").asText()).contains("Change Meeting title to \"Spring Planning Meeting\"");

        JsonNode executed = execute(session, workspaceId, documentId, "change the meeting title to Spring Planning Meeting", revisionId);
        assertThat(executed.get("kind").asText()).isEqualTo("CHANGE_FIELD");
        JsonNode proposal = executed.get("proposal");
        assertThat(proposal.get("proposedValues").get("meeting.title").get("value").asText()).isEqualTo("Spring Planning Meeting");
        assertThat(currentRevisionId(session, workspaceId, documentId)).isEqualTo(revisionId);

        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/patch-proposals/" + proposal.get("id").asLong() + "/accept")
                        .cookie(session).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.revision.fields['meeting.title'].value").value("Spring Planning Meeting"));
        long revisionAfterChange = currentRevisionId(session, workspaceId, documentId);
        assertThat(revisionAfterChange).isNotEqualTo(revisionId);

        // A date that is not a date is refused before any proposal exists.
        JsonNode badDate = interpret(session, workspaceId, documentId, "set meeting date to next Tuesday");
        assertThat(badDate.get("executable").asBoolean()).isFalse();
        assertThat(badDate.get("summary").asText()).contains("2026-04-09");

        // The revision the person looked at has moved on: refused, nothing proposed.
        mockMvc.perform(post(assistPath(workspaceId, documentId) + "/execute").cookie(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"text\":\"change meeting title to Stale\",\"expectedRevisionId\":" + revisionId + "}"))
                .andExpect(status().isPreconditionFailed());

        // A rewrite: one model call, proposed from the current text, again nothing applied until accepted.
        int callsBefore = MODEL_CALLS.get();
        JsonNode shorten = interpret(session, workspaceId, documentId, "shorten the meeting title");
        assertThat(shorten.get("kind").asText()).isEqualTo("REWRITE_FIELD");
        assertThat(shorten.get("usesModel").asBoolean()).isTrue();
        assertThat(shorten.get("scope").get("currentValue").asText()).isEqualTo("Spring Planning Meeting");
        assertThat(MODEL_CALLS.get()).isEqualTo(callsBefore);
        JsonNode rewritten = execute(session, workspaceId, documentId, "shorten the meeting title", revisionAfterChange);
        assertThat(rewritten.get("proposal").get("proposedValues").get("meeting.title").get("value").asText()).isEqualTo("Spring planning");
        assertThat(MODEL_CALLS.get()).isEqualTo(callsBefore + 1);
        assertThat(currentRevisionId(session, workspaceId, documentId)).isEqualTo(revisionAfterChange);

        // An explanation needs a validated revision; then it covers the finding it names.
        JsonNode unvalidated = interpret(session, workspaceId, documentId, "explain this finding");
        assertThat(unvalidated.get("executable").asBoolean()).isFalse();
        assertThat(unvalidated.get("summary").asText()).contains("Validate this revision first");
        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/validate")
                        .cookie(session).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionAfterChange + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasUnresolvedBlocking").value(true));
        long validatedRevisionId = currentRevisionId(session, workspaceId, documentId);
        JsonNode explain = interpret(session, workspaceId, documentId, "why is the meeting date blocking export?");
        assertThat(explain.get("kind").asText()).isEqualTo("EXPLAIN_FINDING");
        assertThat(explain.get("executable").asBoolean()).isTrue();
        assertThat(explain.get("scope").get("fieldId").asText()).isEqualTo("meeting.date");
        assertThat(explain.get("scope").get("findingMessage").asText()).isNotBlank();
        JsonNode explained = execute(session, workspaceId, documentId, "why is the meeting date blocking export?", validatedRevisionId);
        assertThat(explained.get("explanation").asText()).contains("Meeting date");
        assertThat(explained.get("proposal").isNull()).isTrue();

        // Another workspace, through its own document id or the owner's: 404 either way.
        Cookie intruderSession = loginAndGetSessionCookie("subject-assist-intruder");
        long intruderWorkspaceId = ensureWorkspace("subject-assist-intruder").id();
        mockMvc.perform(post(assistPath(intruderWorkspaceId, documentId) + "/interpret").cookie(intruderSession).with(csrf())
                        .contentType("application/json").content("{\"text\":\"change meeting title to Mine\"}"))
                .andExpect(status().isNotFound());
    }

    private JsonNode interpret(Cookie session, long workspaceId, long documentId, String text) throws Exception {
        return readJson(mockMvc.perform(post(assistPath(workspaceId, documentId) + "/interpret").cookie(session).with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(java.util.Map.of("text", text))))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode execute(Cookie session, long workspaceId, long documentId, String text, long expectedRevisionId) throws Exception {
        return readJson(mockMvc.perform(post(assistPath(workspaceId, documentId) + "/execute").cookie(session).with(csrf())
                        .contentType("application/json")
                        .content(OBJECT_MAPPER.writeValueAsString(java.util.Map.of("text", text, "expectedRevisionId", expectedRevisionId))))
                .andExpect(status().isOk())
                .andReturn());
    }

    private long currentRevisionId(Cookie session, long workspaceId, long documentId) throws Exception {
        return readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId).cookie(session))
                .andExpect(status().isOk())
                .andReturn()).get("currentRevision").get("id").asLong();
    }

    private static String assistPath(long workspaceId, long documentId) {
        return "/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/assist";
    }

    private static JsonNode findByDisplayName(JsonNode templatesResponse, String displayName) {
        for (JsonNode template : templatesResponse) {
            if (template.get("displayName").asText().equals(displayName)) {
                return template;
            }
        }
        throw new AssertionError("No template named \"" + displayName + "\" in " + templatesResponse);
    }

    private long createMinimalDocument(Cookie session, long workspaceId, long templateId, long templateVersionId) throws Exception {
        String body = "{"
                + "\"title\":\"Weekly Sync\","
                + "\"templateId\":" + templateId + ","
                + "\"templateVersionId\":" + templateVersionId + ","
                + "\"fields\":{},"
                + "\"initialRevisionReason\":\"Created for a composer test.\"}";
        JsonNode created = readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/documents")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn());
        return created.get("id").asLong();
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
