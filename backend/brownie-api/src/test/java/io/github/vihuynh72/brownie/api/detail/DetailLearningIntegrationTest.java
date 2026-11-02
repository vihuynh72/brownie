package io.github.vihuynh72.brownie.api.detail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.detail.SavedDetailRepository;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelRequest;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.DocumentMutationResult;
import io.github.vihuynh72.brownie.core.revision.DocumentRepository;
import io.github.vihuynh72.brownie.core.revision.FieldItemRef;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.revision.ReviewState;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The learn-after-approval path end to end, with a model that only answers
 * what the test says it answers.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@DockerTest
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
class DetailLearningIntegrationTest {

    private static final String ISSUER = "https://issuer-details-learn";

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> "brownie_api_local_only");
        registry.add("spring.flyway.url", DB::jdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> "brownie_migration_local_only");
        registry.add("brownie.storage.local-connection", DB::azuriteConnectionString);
        registry.add("brownie.security.clamav.host", SharedContainers::clamAvHost);
        registry.add("brownie.security.clamav.port", SharedContainers::clamAvPort);
    }

    @TestConfiguration
    static class FakeModelGatewayConfig {
        @Bean
        @Primary
        ModelGateway fakeModelGateway() {
            return new FakeModelGateway();
        }
    }

    /** Answers only what the test sets; records whether it was asked at all. */
    static class FakeModelGateway implements ModelGateway {
        int calls;
        ModelCompletion next = new ModelCompletion.MalformedOutput("", "not asked", new ModelUsage(0, 0));

        @Override
        public ModelCompletion complete(ModelRequest request) {
            calls++;
            return next;
        }

        void answer(String content) {
            next = new ModelCompletion.Success(content, new ModelUsage(10, 20));
        }

        void fail() {
            next = new ModelCompletion.MalformedOutput("", "bad", new ModelUsage(0, 0));
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
    private ArtifactService artifactService;

    @Autowired
    private DocumentExtractionService documentExtractionService;

    @Autowired
    private TemplateService templateService;

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private RevisionService revisionService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private SavedDetailRepository savedDetailRepository;

    @Autowired
    private io.github.vihuynh72.brownie.core.batch.BatchRepository batchRepository;

    @Autowired
    private ModelGateway modelGateway;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    private FakeModelGateway fake() {
        return (FakeModelGateway) modelGateway;
    }

    private io.github.vihuynh72.brownie.core.identity.UserIdentity ensureUser(String subject, String displayName) {
        var found = userIdentityRepository.findByIssuerAndSubject(ISSUER, subject);
        if (found.isPresent()) {
            return found.get();
        }
        userIdentityRepository.recordLogin(ISSUER, subject, null, displayName);
        return userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow();
    }

    /** A template with labeled fields, a document, and a revision whose fields are ACCEPTED. */
    private long[] documentWithAccepted(String subject, String[] fieldIds, String[] labels, String[] values) throws Exception {
        var user = ensureUser(subject, null);
        long userId = user.id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();

        var artifact = artifactService.initiateUpload(workspaceId, userId, "form.docx");
        artifactService.receiveContent(workspaceId, userId, artifact.id(),
                new java.io.ByteArrayInputStream(docxWithContentControls(fieldIds)));
        artifactService.finalizeUpload(workspaceId, userId, artifact.id());
        documentExtractionService.extract(workspaceId, userId, artifact.id());
        var template = templateService.createDraft(workspaceId, userId, "Application", artifact.id());
        var draftVersion = templateRepository.findDraftVersion(workspaceId, userId, template.id()).orElseThrow();
        var fields = new java.util.ArrayList<FieldDefinition>();
        for (int i = 0; i < fieldIds.length; i++) {
            fields.add(new FieldDefinition(fieldIds[i], FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                    new FieldBindingTarget.ContentControlTag(fieldIds[i]), labels[i], null, null, null));
        }
        templateRepository.replaceDraftBindings(workspaceId, userId, template.id(), draftVersion.versionNumber(), fields);
        var rebound = templateRepository.findDraftVersion(workspaceId, userId, template.id()).orElseThrow();
        templateService.activate(workspaceId, userId, template.id(), rebound.versionNumber());
        var active = templateRepository.findDraftVersion(workspaceId, userId, template.id()).orElse(rebound);

        var content = new DocumentContent(contentFields(fieldIds, values));
        DocumentMutationResult created = revisionService.createDocument(
                workspaceId, userId, new io.github.vihuynh72.brownie.core.job.IdempotencyKey("doc-" + subject + fieldIds[0]),
                io.github.vihuynh72.brownie.core.job.CanonicalRequestHash.sha256OfCanonicalText("doc-" + subject + fieldIds[0]),
                "Lease application", template.id(), active.id(), content, Map.of(), "initial");
        Document document = created.document();
        Map<FieldItemRef, ReviewState> decisions = new java.util.LinkedHashMap<>();
        for (String fieldId : fieldIds) {
            decisions.put(FieldItemRef.scalar(fieldId), ReviewState.ACCEPTED);
        }
        DocumentMutationResult reviewed = revisionService.recordReviewDecisions(
                workspaceId, userId, new io.github.vihuynh72.brownie.core.job.IdempotencyKey("rev-" + subject + fieldIds[0]),
                io.github.vihuynh72.brownie.core.job.CanonicalRequestHash.sha256OfCanonicalText("rev-" + subject + fieldIds[0]),
                document.id(), document.currentRevisionId(), decisions, "approved");
        return new long[] {document.id(), reviewed.revision().id(), artifact.id()};
    }

    private static Map<String, FieldValue> contentFields(String[] fieldIds, String[] values) {
        Map<String, FieldValue> fields = new java.util.LinkedHashMap<>();
        for (int i = 0; i < fieldIds.length; i++) {
            fields.put(fieldIds[i], new FieldValue.TextValue(values[i]));
        }
        return fields;
    }

    private static byte[] docxWithContentControls(String[] tags) throws Exception {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            for (String tag : tags) {
                var paragraph = doc.createParagraph();
                paragraph.createRun().setText("Field: ");
                var ctp = paragraph.getCTP();
                var sdt = ctp.addNewSdt();
                var sdtPr = sdt.addNewSdtPr();
                sdtPr.addNewTag().setVal(tag);
                sdtPr.addNewAlias().setVal(tag);
                var content = sdt.addNewSdtContent();
                content.addNewR().addNewT().setStringValue("[value]");
            }
            var out = new java.io.ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void selfSavesOnlyNonSensitiveKeysAndSkipsSensitiveOnes() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-self");
        String[] ids = {"full_name", "email_addr", "secret_num"};
        String[] labels = {"Full name", "Email", "Secret number"};
        String[] values = {"Daniel Ortiz", "daniel@example.com", "123-45-6789"};
        long[] ids_ = documentWithAccepted("subject-self", ids, labels, values);

        fake().answer("{\"subject\":\"SELF\",\"pairs\":["
                + "{\"label\":\"Full name\",\"key\":\"person.full_name\"},"
                + "{\"label\":\"Email\",\"key\":\"contact.email\"},"
                + "{\"label\":\"Secret number\",\"key\":\"sensitive.ssn\"}]}");

        JsonNode body = readJson(mockMvc.perform(post("/api/v1/me/details/learn")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"documentId\":" + ids_[0] + ",\"revisionId\":" + ids_[1] + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(body.get("subject").asText()).isEqualTo("SELF");
        var user = ensureUser("subject-self", null);
        var saved = savedDetailRepository.list(user.id());
        assertThat(saved.stream().map(each -> each.key()).toList())
                .containsExactlyInAnyOrder("person.full_name", "contact.email");
        // sensitive.ssn mapped by the model but never saved automatically.
        assertThat(saved.stream().noneMatch(each -> each.key().equals("sensitive.ssn"))).isTrue();
        assertThat(body.get("learningId").isNull()).isFalse();
    }

    @Test
    void aSpouseNameLabelDoesNotTriggerTheNameRule() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-spouse");
        long[] ids = documentWithAccepted("subject-spouse",
                new String[] {"spouse_name"}, new String[] {"Spouse name"}, new String[] {"Maya Chen"});
        fake().calls = 0;
        fake().answer("{\"subject\":\"SELF\",\"pairs\":[{\"label\":\"Spouse name\",\"key\":\"contact.email\"}]}");

        JsonNode body = readJson(mockMvc.perform(post("/api/v1/me/details/learn")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"documentId\":" + ids[0] + ",\"revisionId\":" + ids[1] + "}"))
                .andExpect(status().isOk()).andReturn());
        // "Spouse name" is someone else's name: the name rule never sees it, so the model is asked.
        assertThat(body.get("subject").asText()).isEqualTo("SELF");
        assertThat(fake().calls).isEqualTo(1);
    }

    @Test
    void aValueLongerThanTwoThousandCharactersIsSkipped() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-long");
        long[] ids = documentWithAccepted("subject-long",
                new String[] {"email_addr", "long_note"}, new String[] {"Email", "Long note"},
                new String[] {"daniel@example.com", "x".repeat(2500)});
        fake().answer("{\"subject\":\"SELF\",\"pairs\":["
                + "{\"label\":\"Email\",\"key\":\"contact.email\"},"
                + "{\"label\":\"Long note\",\"key\":\"contact.notes\"}]}");

        JsonNode body = readJson(mockMvc.perform(post("/api/v1/me/details/learn")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"documentId\":" + ids[0] + ",\"revisionId\":" + ids[1] + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(body.get("subject").asText()).isEqualTo("SELF");
        var user = ensureUser("subject-long", null);
        var saved = savedDetailRepository.list(user.id());
        assertThat(saved.stream().map(each -> each.key()).toList()).containsExactlyInAnyOrder("contact.email");
    }

    @Test
    void aNameMismatchIsOtherWithNoModelCall() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-other", "Daniel Ortiz");
        long[] ids = documentWithAccepted("subject-other", new String[] {"full_name"}, new String[] {"Full name"}, new String[] {"Maya Chen"});
        fake().calls = 0;

        JsonNode body = readJson(mockMvc.perform(post("/api/v1/me/details/learn")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"documentId\":" + ids[0] + ",\"revisionId\":" + ids[1] + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(body.get("subject").asText()).isEqualTo("OTHER");
        assertThat(fake().calls).isZero();
        var user = ensureUser("subject-other", "Daniel Ortiz");
        assertThat(savedDetailRepository.list(user.id())).isEmpty();
    }

    @Test
    void aModelFailureIsUnsureAndNothingIsSaved() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-unsure");
        long[] ids = documentWithAccepted("subject-unsure", new String[] {"email_addr"}, new String[] {"Email"}, new String[] {"a@b.com"});
        fake().fail();

        JsonNode body = readJson(mockMvc.perform(post("/api/v1/me/details/learn")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"documentId\":" + ids[0] + ",\"revisionId\":" + ids[1] + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(body.get("subject").asText()).isEqualTo("UNSURE");
        var user = ensureUser("subject-unsure", null);
        assertThat(savedDetailRepository.list(user.id())).isEmpty();
    }

    @Test
    void aBatchDocumentIsSkipped() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-batch");
        var user = ensureUser("subject-batch", null);
        long[] ids = documentWithAccepted("subject-batch", new String[] {"full_name"}, new String[] {"Full name"}, new String[] {"Daniel Ortiz"});
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(user.id()).id();
        Document doc = documentRepository.find(workspaceId, user.id(), ids[0]).orElseThrow();
        long artifactId = ids[2];
        var batch = batchRepository.create(
                new io.github.vihuynh72.brownie.core.batch.BatchRepository.NewBatch(
                        workspaceId, doc.templateId(), doc.templateVersionId(), artifactId,
                        "sha", io.github.vihuynh72.brownie.core.batch.TableFormat.CSV, "csv-v1", null, false,
                        io.github.vihuynh72.brownie.core.batch.DateOrder.MDY, java.util.List.of(), null, "Batch", 1,
                        new io.github.vihuynh72.brownie.core.job.IdempotencyKey("b1"),
                        io.github.vihuynh72.brownie.core.job.CanonicalRequestHash.sha256OfCanonicalText("batch").value(), user.id()),
                java.util.List.of(new io.github.vihuynh72.brownie.core.batch.BatchRepository.NewBatchRow(1,
                        io.github.vihuynh72.brownie.core.batch.BatchRowState.PENDING,
                        io.github.vihuynh72.brownie.core.batch.ComposeState.NOT_NEEDED)));
        batchRepository.markRowCreated(workspaceId, user.id(), batch.id(), 1, ids[0], ids[1], java.util.List.of());

        JsonNode body = readJson(mockMvc.perform(post("/api/v1/me/details/learn")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"documentId\":" + ids[0] + ",\"revisionId\":" + ids[1] + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(body.get("subject").asText()).isEqualTo("SKIPPED");
        assertThat(body.get("reason").asText()).isEqualTo("bulk");
        assertThat(savedDetailRepository.list(user.id())).isEmpty();
    }

    @Test
    void replacingAValueListsItInReplacedAndUndoPutsItBack() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-replace");
        var user = ensureUser("subject-replace", null);
        long ws = workspaceRepository.ensurePersonalWorkspace(user.id()).id();
        savedDetailRepository.upsert(user.id(), ws, "contact.email", "Email", "old@example.com", null, null, false,
                io.github.vihuynh72.brownie.core.detail.DetailOrigin.MANUAL, null, null, null);
        long[] ids = documentWithAccepted("subject-replace", new String[] {"email_addr"}, new String[] {"Email"}, new String[] {"new@example.com"});
        fake().answer("{\"subject\":\"SELF\",\"pairs\":[{\"label\":\"Email\",\"key\":\"contact.email\"}]}");

        JsonNode body = readJson(mockMvc.perform(post("/api/v1/me/details/learn")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"documentId\":" + ids[0] + ",\"revisionId\":" + ids[1] + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(body.get("replaced").get(0).get("key").asText()).isEqualTo("contact.email");
        long learningId = body.get("learningId").asLong();

        // The person then changes it again -- undo must leave that alone.
        savedDetailRepository.upsert(user.id(), ws, "contact.email", "Email", "newest@example.com", null, null, false,
                io.github.vihuynh72.brownie.core.detail.DetailOrigin.MANUAL, null, null, null);
        mockMvc.perform(post("/api/v1/me/details/learnings/" + learningId + "/undo")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());
        // The written value was overwritten since, so undo touched nothing.
        assertThat(savedDetailRepository.list(user.id()).get(0).valueText()).isEqualTo("newest@example.com");
    }

    @Test
    void undoRestoresWhatWasReplaced() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-undo");
        var user = ensureUser("subject-undo", null);
        long ws = workspaceRepository.ensurePersonalWorkspace(user.id()).id();
        savedDetailRepository.upsert(user.id(), ws, "contact.email", "Email", "old@example.com", null, null, false,
                io.github.vihuynh72.brownie.core.detail.DetailOrigin.MANUAL, null, null, null);
        long[] ids = documentWithAccepted("subject-undo", new String[] {"email_addr"}, new String[] {"Email"}, new String[] {"new@example.com"});
        fake().answer("{\"subject\":\"SELF\",\"pairs\":[{\"label\":\"Email\",\"key\":\"contact.email\"}]}");

        JsonNode body = readJson(mockMvc.perform(post("/api/v1/me/details/learn")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"documentId\":" + ids[0] + ",\"revisionId\":" + ids[1] + "}"))
                .andExpect(status().isOk()).andReturn());
        long learningId = body.get("learningId").asLong();
        assertThat(savedDetailRepository.list(user.id()).get(0).valueText()).isEqualTo("new@example.com");

        mockMvc.perform(post("/api/v1/me/details/learnings/" + learningId + "/undo")
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());
        assertThat(savedDetailRepository.list(user.id()).get(0).valueText()).isEqualTo("old@example.com");
    }

    @Test
    void anotherPersonsDocumentIsANotFound() throws Exception {
        Cookie other = loginAndGetSessionCookie("subject-other-2");
        long[] ids = documentWithAccepted("subject-owner-2", new String[] {"full_name"}, new String[] {"Full name"}, new String[] {"A B"});
        mockMvc.perform(post("/api/v1/me/details/learn")
                        .cookie(other)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"documentId\":" + ids[0] + ",\"revisionId\":" + ids[1] + "}"))
                .andExpect(status().isNotFound());
    }

    private Cookie loginAndGetSessionCookie(String subject) {
        return loginAndGetSessionCookie(subject, null);
    }

    private Cookie loginAndGetSessionCookie(String subject, String displayName) {
        ensureUser(subject, displayName);
        workspaceRepository.ensurePersonalWorkspace(ensureUser(subject, displayName).id());
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-token")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .claim(IdTokenClaimNames.ISS, ISSUER).claim(IdTokenClaimNames.SUB, subject).build();
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "entra"));
        Session session = createAuthenticatedSession(sessionRepository, context);
        return new Cookie("SESSION", java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
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
