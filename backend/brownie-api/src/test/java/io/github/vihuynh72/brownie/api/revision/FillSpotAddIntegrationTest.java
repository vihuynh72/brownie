package io.github.vihuynh72.brownie.api.revision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.api.template.BuiltInTemplateProvisioningService;
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
import org.springframework.test.web.servlet.MvcResult;
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
 * Adding a fill spot where a person points, end to end with the real Word
 * editor: the spot is made in the form's file, the new version's page shows
 * it, a value typed into it is in the compiled file, and taking it away
 * again puts the line back exactly as it was. Renaming and taking away a
 * spot the form came with, which edit no file, are proven by {@link
 * FillSpotIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
class FillSpotAddIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-fill-spot-add";

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
    void aSpotAddedAtTheEndOfALineIsInTheFormItsValueCompilesAndTakingItAwayPutsTheLineBack() throws Exception {
        Cookie session = signInWithBuiltIns("subject-fill-add");
        long workspaceId = workspaceOf("subject-fill-add");
        long documentId = createMinimalDocument(session, workspaceId);
        JsonNode before = document(session, workspaceId, documentId);
        long templateId = before.get("templateId").asLong();
        long firstVersionId = before.get("templateVersionId").asLong();
        JsonNode layout = layout(session, workspaceId, templateId, firstVersionId);
        JsonNode line = firstAnchorableLineWithTextAndNoSpot(layout);
        int length = anchorTextLength(line);
        String anchor = "{\"part\":\"MAIN_DOCUMENT\",\"paragraphNodeId\":\"" + line.get("nodeId").asText()
                + "\",\"placement\":\"AT\",\"start\":" + length + ",\"end\":" + length
                + ",\"anchorTextHash\":\"" + line.get("anchorTextHash").asText()
                + "\",\"parserVersion\":\"" + layout.get("parserVersion").asText() + "\"}";

        JsonNode added = readJson(changeFillSpots(session, workspaceId, documentId, UUID.randomUUID().toString(),
                        fillSpots(before.get("currentRevision").get("id").asLong(), firstVersionId,
                                "{\"kind\":\"ADD\",\"label\":\"Company\",\"type\":\"TEXT\",\"anchor\":" + anchor + "}"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(added.get("fieldIds").get(0).asText()).isEqualTo("company");
        JsonNode company = fieldOf(added.get("templateVersion"), "company");
        assertThat(company.get("label").asText()).isEqualTo("Company");
        assertThat(company.get("origin").asText()).isEqualTo("ADDED_BY_PERSON");
        assertThat(company.get("docxControl").asText()).isEqualTo("INSERTED_BY_BROWNIE");
        long secondVersionId = added.get("templateVersion").get("id").asLong();
        assertThat(spotsOf(layout(session, workspaceId, templateId, secondVersionId))).contains("company");

        long filledRevisionId = readJson(mockMvc.perform(patch(documentPath(workspaceId, documentId) + "/content")
                        .cookie(session).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + added.get("revision").get("id").asLong() + ",\"editReason\":\"Named the company.\","
                                + "\"edits\":[{\"operation\":\"SET\",\"fieldId\":\"company\",\"value\":{\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"value\":\"Acme Ltd\"}}]}"))
                .andExpect(status().isOk())
                .andReturn()).get("id").asLong();
        JsonNode compiled = readJson(mockMvc.perform(post(documentPath(workspaceId, documentId) + "/revisions/" + filledRevisionId + "/compile")
                        .cookie(session).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(compiled.get("templateVersionId").asLong()).isEqualTo(secondVersionId);
        assertThat(compiled.get("allIntegrityChecksPassed").asBoolean()).isTrue();

        JsonNode removed = readJson(changeFillSpots(session, workspaceId, documentId, UUID.randomUUID().toString(),
                        fillSpots(filledRevisionId, secondVersionId, "{\"kind\":\"REMOVE\",\"fieldId\":\"company\"}"))
                .andExpect(status().isOk())
                .andReturn());
        long thirdVersionId = removed.get("templateVersion").get("id").asLong();
        JsonNode after = layout(session, workspaceId, templateId, thirdVersionId);
        assertThat(spotsOf(after)).doesNotContain("company");
        assertThat(lineById(after, line.get("nodeId").asText()).get("anchorTextHash").asText()).isEqualTo(line.get("anchorTextHash").asText());

        String staleAnchor = anchor.replace(line.get("anchorTextHash").asText(), "0000000000000000");
        changeFillSpots(session, workspaceId, documentId, UUID.randomUUID().toString(),
                        fillSpots(removed.get("revision").get("id").asLong(), thirdVersionId,
                                "{\"kind\":\"ADD\",\"label\":\"Company\",\"anchor\":" + staleAnchor + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILL_SPOT_ANCHOR_STALE"));
    }

    private JsonNode layout(Cookie session, long workspaceId, long templateId, long versionId) throws Exception {
        return readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/templates/" + templateId + "/versions/" + versionId + "/layout")
                        .cookie(session))
                .andExpect(status().isOk())
                .andReturn());
    }

    private static List<JsonNode> paragraphs(JsonNode blocks, List<JsonNode> into) {
        for (JsonNode block : blocks) {
            if ("PARAGRAPH".equals(block.get("kind").asText())) {
                into.add(block);
            } else {
                for (JsonNode row : block.get("rows")) {
                    for (JsonNode cell : row.get("cells")) {
                        paragraphs(cell.get("blocks"), into);
                    }
                }
            }
        }
        return into;
    }

    private static List<JsonNode> mainParagraphs(JsonNode layout) {
        return paragraphs(layout.get("parts").get(0).get("blocks"), new ArrayList<>());
    }

    private static JsonNode firstAnchorableLineWithTextAndNoSpot(JsonNode layout) {
        for (JsonNode paragraph : mainParagraphs(layout)) {
            boolean hasSpot = false;
            for (JsonNode inline : paragraph.get("inlines")) {
                hasSpot |= "FILL_SPOT".equals(inline.get("kind").asText());
            }
            if (paragraph.get("anchorable").asBoolean() && !hasSpot && anchorTextLength(paragraph) > 0) {
                return paragraph;
            }
        }
        throw new AssertionError("No line to add a spot to: " + layout);
    }

    private static JsonNode lineById(JsonNode layout, String nodeId) {
        return mainParagraphs(layout).stream().filter(paragraph -> paragraph.get("nodeId").asText().equals(nodeId)).findFirst().orElseThrow();
    }

    private static int anchorTextLength(JsonNode paragraph) {
        int end = 0;
        for (JsonNode inline : paragraph.get("inlines")) {
            if ("TEXT".equals(inline.get("kind").asText()) && inline.hasNonNull("anchorStart")) {
                String text = inline.get("text").asText();
                end = inline.get("anchorStart").asInt() + text.codePointCount(0, text.length());
            }
        }
        return end;
    }

    private static List<String> spotsOf(JsonNode layout) {
        List<String> spots = new ArrayList<>();
        for (JsonNode paragraph : mainParagraphs(layout)) {
            for (JsonNode inline : paragraph.get("inlines")) {
                if ("FILL_SPOT".equals(inline.get("kind").asText())) {
                    spots.add(inline.get("fieldId").asText());
                }
            }
        }
        return spots;
    }

    private static String fillSpots(long expectedRevisionId, long templateVersionId, String change) {
        return "{\"expectedRevisionId\":" + expectedRevisionId + ",\"templateVersionId\":" + templateVersionId + ",\"changes\":[" + change + "]}";
    }

    private ResultActions changeFillSpots(Cookie session, long workspaceId, long documentId, String key, String body) throws Exception {
        return mockMvc.perform(post(documentPath(workspaceId, documentId) + "/fill-spots")
                .cookie(session)
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body));
    }

    private static JsonNode fieldOf(JsonNode version, String fieldId) {
        for (JsonNode field : version.get("fields")) {
            if (field.get("fieldId").asText().equals(fieldId)) {
                return field;
            }
        }
        return null;
    }

    private JsonNode document(Cookie session, long workspaceId, long documentId) throws Exception {
        return readJson(mockMvc.perform(get(documentPath(workspaceId, documentId)).cookie(session)).andExpect(status().isOk()).andReturn());
    }

    private long createMinimalDocument(Cookie session, long workspaceId) throws Exception {
        long[] template = findFlowingTemplateAndActiveVersion(session, workspaceId);
        String body = "{"
                + "\"title\":\"Weekly Sync\","
                + "\"templateId\":" + template[0] + ","
                + "\"templateVersionId\":" + template[1] + ","
                + "\"fields\":{\"meeting.title\":{\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"value\":\"Weekly Sync\"},"
                + "\"meeting.date\":{\"type\":\"DATE\",\"cardinality\":\"SCALAR\",\"value\":\"2026-03-12\"}},"
                + "\"initialRevisionReason\":\"Created for a fill spot test.\"}";
        return readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/documents")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asLong();
    }

    private long[] findFlowingTemplateAndActiveVersion(Cookie session, long workspaceId) throws Exception {
        JsonNode templates = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/templates").cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        for (JsonNode template : templates) {
            if (template.get("displayName").asText().equals("Flowing meeting minutes")) {
                return new long[] {template.get("id").asLong(), template.get("currentActiveVersionId").asLong()};
            }
        }
        throw new AssertionError("Flowing meeting minutes template was not provisioned: " + templates);
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

    private static String documentPath(long workspaceId, long documentId) {
        return "/api/v1/workspaces/" + workspaceId + "/documents/" + documentId;
    }

    private static JsonNode readJson(MvcResult result) throws Exception {
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
        return new Cookie("SESSION", java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    private Workspace ensureWorkspace(String subject) {
        var identity = userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow();
        return workspaceRepository.ensurePersonalWorkspace(identity.id());
    }

    private static <S extends Session> S createAuthenticatedSession(FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }
}
