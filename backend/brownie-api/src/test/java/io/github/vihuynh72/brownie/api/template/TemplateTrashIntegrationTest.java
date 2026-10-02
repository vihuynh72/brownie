package io.github.vihuynh72.brownie.api.template;

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
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the Trash Bin for templates end to end against real infrastructure
 * (Postgres, Azurite, ClamAV and the isolated renderer that activating,
 * validating and exporting depend on), the same pattern {@code
 * TemplateLayoutIntegrationTest} uses: a trashed template leaves the list
 * and waits in the Trash Bin, starts no new document until it is restored,
 * takes nothing away from a document already made from it, stays out of
 * another workspace's reach, and is never provisioned a second time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
class TemplateTrashIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-template-trash";
    private static final String FLOWING = "Flowing meeting minutes";
    private static final String TABLE_LED = "Table-led meeting minutes";

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
    void aTrashedTemplateLeavesTheListWaitsInTheTrashBinNewestFirstAndComesBackWhenRestored() throws Exception {
        Cookie session = signInWithBuiltIns("subject-trash-round-trip");
        long workspaceId = workspaceOf("subject-trash-round-trip");
        long flowing = findTemplate(session, workspaceId, FLOWING)[0];
        long tableLed = findTemplate(session, workspaceId, TABLE_LED)[0];
        assertThat(listTemplates(session, workspaceId, "").get(0).get("trashedAt").isNull()).isTrue();

        JsonNode trashed = readJson(trash(session, workspaceId, flowing).andExpect(status().isOk()).andReturn());
        JsonNode trashedAgain = readJson(trash(session, workspaceId, flowing).andExpect(status().isOk()).andReturn());
        trash(session, workspaceId, tableLed).andExpect(status().isOk());

        assertThat(trashed.get("id").asLong()).isEqualTo(flowing);
        assertThat(trashed.get("displayName").asText()).isEqualTo(FLOWING);
        assertThat(trashed.get("trashedAt").isNull()).isFalse();
        assertThat(trashedAgain.get("trashedAt").asText()).isEqualTo(trashed.get("trashedAt").asText());
        assertThat(ids(listTemplates(session, workspaceId, ""))).isEmpty();
        assertThat(ids(listTemplates(session, workspaceId, "?trashed=false"))).isEmpty();
        assertThat(ids(listTemplates(session, workspaceId, "?trashed=true"))).containsExactly(tableLed, flowing);

        JsonNode restored = readJson(restore(session, workspaceId, flowing).andExpect(status().isOk()).andReturn());
        JsonNode restoredAgain = readJson(restore(session, workspaceId, flowing).andExpect(status().isOk()).andReturn());

        assertThat(restored.get("trashedAt").isNull()).isTrue();
        assertThat(restoredAgain.get("trashedAt").isNull()).isTrue();
        assertThat(restored.get("currentActiveVersionId").asLong()).isEqualTo(trashed.get("currentActiveVersionId").asLong());
        assertThat(ids(listTemplates(session, workspaceId, ""))).containsExactly(flowing);
        assertThat(ids(listTemplates(session, workspaceId, "?trashed=true"))).containsExactly(tableLed);
    }

    @Test
    void anotherWorkspacesTemplateIsNotFoundAndAnotherWorkspacesRouteIsForbidden() throws Exception {
        Cookie ownerSession = signInWithBuiltIns("subject-trash-owner");
        long ownerWorkspaceId = workspaceOf("subject-trash-owner");
        long ownersTemplate = findTemplate(ownerSession, ownerWorkspaceId, FLOWING)[0];
        Cookie strangerSession = signInWithBuiltIns("subject-trash-stranger");
        long strangerWorkspaceId = workspaceOf("subject-trash-stranger");

        trash(strangerSession, strangerWorkspaceId, ownersTemplate)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("No template " + ownersTemplate + " in this workspace."));
        restore(strangerSession, strangerWorkspaceId, ownersTemplate)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        trash(strangerSession, ownerWorkspaceId, ownersTemplate).andExpect(status().isForbidden());
        mockMvc.perform(get(templatesPath(ownerWorkspaceId) + "?trashed=true").cookie(strangerSession))
                .andExpect(status().isForbidden());

        assertThat(ids(listTemplates(ownerSession, ownerWorkspaceId, ""))).contains(ownersTemplate);
        assertThat(ids(listTemplates(ownerSession, ownerWorkspaceId, "?trashed=true"))).isEmpty();
        assertThat(ids(listTemplates(strangerSession, strangerWorkspaceId, "?trashed=true"))).isEmpty();
    }

    @Test
    void aDocumentCannotBeStartedFromATrashedTemplateUntilItIsRestored() throws Exception {
        Cookie session = signInWithBuiltIns("subject-trash-no-new-document");
        long workspaceId = workspaceOf("subject-trash-no-new-document");
        long[] template = findTemplate(session, workspaceId, FLOWING);
        String idempotencyKey = UUID.randomUUID().toString();
        trash(session, workspaceId, template[0]).andExpect(status().isOk());

        createDocument(session, workspaceId, template, idempotencyKey)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_TRASHED"))
                .andExpect(jsonPath("$.detail").value("This template is in the Trash Bin. Restore it to start a document from it."));
        assertThat(readJson(mockMvc.perform(get(documentsPath(workspaceId)).cookie(session)).andExpect(status().isOk()).andReturn()))
                .isEmpty();

        restore(session, workspaceId, template[0]).andExpect(status().isOk());

        // The same request again: the refusal kept nothing that a replay would answer with instead.
        JsonNode created = readJson(createDocument(session, workspaceId, template, idempotencyKey)
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(created.get("templateId").asLong()).isEqualTo(template[0]);
    }

    /**
     * Everything the workspace reads for a document made before its template
     * went to the Trash Bin, from the template's own version, page and rules
     * through an edit to a validated, approved export of the filled file.
     */
    @Test
    void aDocumentMadeBeforeItsTemplateWentToTheTrashBinKeepsReadingEditingAndExporting() throws Exception {
        Cookie session = signInWithBuiltIns("subject-trash-existing-document");
        long workspaceId = workspaceOf("subject-trash-existing-document");
        long[] template = findTemplate(session, workspaceId, FLOWING);
        JsonNode created = readJson(createDocument(session, workspaceId, template, UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andReturn());
        long documentId = created.get("id").asLong();
        long revisionId = created.get("currentRevision").get("id").asLong();

        trash(session, workspaceId, template[0]).andExpect(status().isOk());

        JsonNode document = readJson(mockMvc.perform(get(documentPath(workspaceId, documentId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(document.get("templateVersionId").asLong()).isEqualTo(template[1]);
        String versionPath = templatesPath(workspaceId) + "/" + template[0] + "/versions/" + template[1];
        mockMvc.perform(get(versionPath).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"));
        JsonNode layout = readJson(mockMvc.perform(get(versionPath + "/layout").cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(layout.get("versionId").asLong()).isEqualTo(template[1]);
        mockMvc.perform(get(versionPath + "/rules").cookie(session)).andExpect(status().isOk());

        long editedRevisionId = readJson(mockMvc.perform(patch(documentPath(workspaceId, documentId) + "/content")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionId + ",\"editReason\":\"Renamed after the template was trashed.\","
                                + "\"edits\":[{\"operation\":\"SET\",\"fieldId\":\"meeting.title\","
                                + "\"value\":{\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"value\":\"Renamed Sync\"}}]}"))
                .andExpect(status().isOk())
                .andReturn()).get("id").asLong();

        JsonNode manifest = readJson(mockMvc.perform(post(documentPath(workspaceId, documentId) + "/validate")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + editedRevisionId + "}"))
                .andExpect(status().isCreated())
                .andReturn());
        mockMvc.perform(post(documentPath(workspaceId, documentId) + "/export-approval")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"validationManifestId\":" + manifest.get("id").asLong() + ",\"format\":\"BOTH\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post(documentPath(workspaceId, documentId) + "/export").cookie(session).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isCompletePair").value(true));
    }

    @Test
    void aBuiltInInTheTrashBinIsNeverProvisionedAgain() throws Exception {
        Cookie session = signInWithBuiltIns("subject-trash-built-ins");
        long workspaceId = workspaceOf("subject-trash-built-ins");
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-trash-built-ins").orElseThrow().id();
        long flowing = findTemplate(session, workspaceId, FLOWING)[0];
        long tableLed = findTemplate(session, workspaceId, TABLE_LED)[0];
        trash(session, workspaceId, flowing).andExpect(status().isOk());

        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);

        assertThat(ids(listTemplates(session, workspaceId, ""))).containsExactly(tableLed);

        // Nor is a workspace whose every template is in the Trash Bin taken for one that has none.
        trash(session, workspaceId, tableLed).andExpect(status().isOk());
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);

        assertThat(ids(listTemplates(session, workspaceId, ""))).isEmpty();
        assertThat(ids(listTemplates(session, workspaceId, "?trashed=true"))).containsExactly(tableLed, flowing);
    }

    private ResultActions trash(Cookie session, long workspaceId, long templateId) throws Exception {
        return mockMvc.perform(post(templatesPath(workspaceId) + "/" + templateId + "/trash").cookie(session).with(csrf()));
    }

    private ResultActions restore(Cookie session, long workspaceId, long templateId) throws Exception {
        return mockMvc.perform(post(templatesPath(workspaceId) + "/" + templateId + "/restore").cookie(session).with(csrf()));
    }

    private ResultActions createDocument(Cookie session, long workspaceId, long[] template, String idempotencyKey) throws Exception {
        String body = "{"
                + "\"title\":\"Weekly Sync\","
                + "\"templateId\":" + template[0] + ","
                + "\"templateVersionId\":" + template[1] + ","
                + "\"fields\":{\"meeting.title\":{\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"value\":\"Weekly Sync\"},"
                + "\"meeting.date\":{\"type\":\"DATE\",\"cardinality\":\"SCALAR\",\"value\":\"2026-03-12\"}},"
                + "\"initialRevisionReason\":\"Created for a template trash test.\"}";
        return mockMvc.perform(post(documentsPath(workspaceId))
                .cookie(session)
                .with(csrf())
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content(body));
    }

    private JsonNode listTemplates(Cookie session, long workspaceId, String query) throws Exception {
        return readJson(mockMvc.perform(get(templatesPath(workspaceId) + query).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
    }

    private static List<Long> ids(JsonNode templates) {
        List<Long> ids = new ArrayList<>();
        templates.forEach(template -> ids.add(template.get("id").asLong()));
        return ids;
    }

    private long[] findTemplate(Cookie session, long workspaceId, String displayName) throws Exception {
        for (JsonNode template : listTemplates(session, workspaceId, "")) {
            if (template.get("displayName").asText().equals(displayName)) {
                return new long[] {template.get("id").asLong(), template.get("currentActiveVersionId").asLong()};
            }
        }
        throw new AssertionError(displayName + " was not provisioned.");
    }

    private Cookie signInWithBuiltIns(String subject) {
        Cookie session = loginAndGetSessionCookie(subject);
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow().id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceOf(subject), userId);
        return session;
    }

    private long workspaceOf(String subject) {
        return ensureWorkspace(subject).id();
    }

    private static String templatesPath(long workspaceId) {
        return "/api/v1/workspaces/" + workspaceId + "/templates";
    }

    private static String documentsPath(long workspaceId) {
        return "/api/v1/workspaces/" + workspaceId + "/documents";
    }

    private static String documentPath(long workspaceId, long documentId) {
        return documentsPath(workspaceId) + "/" + documentId;
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
