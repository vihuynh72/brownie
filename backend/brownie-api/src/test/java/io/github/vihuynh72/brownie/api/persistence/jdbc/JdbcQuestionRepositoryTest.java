package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.question.Question;
import io.github.vihuynh72.brownie.core.question.QuestionCandidateOption;
import io.github.vihuynh72.brownie.core.question.QuestionNotFoundException;
import io.github.vihuynh72.brownie.core.question.QuestionReason;
import io.github.vihuynh72.brownie.core.question.QuestionRepository;
import io.github.vihuynh72.brownie.core.question.QuestionStatus;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves {@link JdbcQuestionRepository} end to end against real Postgres,
 * including its own row-level security -- the same standard {@code
 * EvidenceAndCompilationRowLevelSecurityTest} already holds every other
 * tenant table to, folded into one file here since this table has no
 * other repository test yet to keep separate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class JdbcQuestionRepositoryTest {

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

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath().getParent().getParent().resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private RevisionService revisionService;

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private ExtractionVersionRepository extractionVersionRepository;

    @Test
    void createdQuestionRoundTripsWithItsCandidatesThroughARealRead() {
        UserIdentity owner = newUser("question-crud-owner");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(owner.id());
        TemplateVersion templateVersion = newActiveTemplate(workspace.id(), owner.id());
        Document document = createDocument(workspace.id(), owner.id(), templateVersion);

        Question created = questionRepository.create(
                workspace.id(), owner.id(), document.id(), "meeting.title", QuestionReason.CONFLICT,
                List.of(new QuestionCandidateOption("Weekly Sync", List.of()), new QuestionCandidateOption("Weekly Sync v2", List.of(9L))));

        Question found = questionRepository.find(workspace.id(), owner.id(), created.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(QuestionStatus.OPEN);
        assertThat(found.reason()).isEqualTo(QuestionReason.CONFLICT);
        assertThat(found.candidates()).hasSize(2);
        assertThat(found.candidates().get(1).evidenceSpanIds()).containsExactly(9L);
        assertThat(found.answerValue()).isNull();
    }

    @Test
    void findOpenForDocumentOnlyReturnsOpenQuestions() {
        UserIdentity owner = newUser("question-open-owner");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(owner.id());
        TemplateVersion templateVersion = newActiveTemplate(workspace.id(), owner.id());
        Document document = createDocument(workspace.id(), owner.id(), templateVersion);
        Question stillOpen = questionRepository.create(workspace.id(), owner.id(), document.id(), "meeting.title", QuestionReason.MISSING_REQUIRED, List.of());
        Question toAnswer = questionRepository.create(workspace.id(), owner.id(), document.id(), "meeting.date", QuestionReason.MISSING_REQUIRED, List.of());
        questionRepository.answer(workspace.id(), owner.id(), toAnswer.id(), "2026-03-05");

        List<Question> open = questionRepository.findOpenForDocument(workspace.id(), owner.id(), document.id());

        assertThat(open).extracting(Question::id).containsExactly(stillOpen.id());
    }

    @Test
    void answeringSetsTheFullAnswerAndCannotBeRepeated() {
        UserIdentity owner = newUser("question-answer-owner");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(owner.id());
        TemplateVersion templateVersion = newActiveTemplate(workspace.id(), owner.id());
        Document document = createDocument(workspace.id(), owner.id(), templateVersion);
        Question question = questionRepository.create(workspace.id(), owner.id(), document.id(), "meeting.title", QuestionReason.MISSING_REQUIRED, List.of());

        Question answered = questionRepository.answer(workspace.id(), owner.id(), question.id(), "Weekly Sync");

        assertThat(answered.status()).isEqualTo(QuestionStatus.ANSWERED);
        assertThat(answered.answerValue()).isEqualTo("Weekly Sync");
        assertThat(answered.answeredByUserId()).isEqualTo(owner.id());
        assertThat(answered.answeredAt()).isNotNull();

        assertThrows(QuestionNotFoundException.class, () -> questionRepository.answer(workspace.id(), owner.id(), question.id(), "Weekly Sync v2"));
    }

    @Test
    void answeringANonexistentQuestionThrows() {
        UserIdentity owner = newUser("question-missing-owner");
        workspaceRepository.ensurePersonalWorkspace(owner.id());

        assertThrows(QuestionNotFoundException.class, () -> questionRepository.answer(1, owner.id(), 999_999, "anything"));
    }

    @Test
    void aDifferentWorkspacesMemberCannotSeeOrAnswerAnotherWorkspacesQuestion() {
        UserIdentity owner = newUser("question-rls-owner");
        UserIdentity intruder = newUser("question-rls-intruder");
        Workspace ownerWorkspace = workspaceRepository.ensurePersonalWorkspace(owner.id());
        workspaceRepository.ensurePersonalWorkspace(intruder.id());
        TemplateVersion templateVersion = newActiveTemplate(ownerWorkspace.id(), owner.id());
        Document document = createDocument(ownerWorkspace.id(), owner.id(), templateVersion);
        Question question = questionRepository.create(
                ownerWorkspace.id(), owner.id(), document.id(), "meeting.title", QuestionReason.MISSING_REQUIRED, List.of());

        assertThat(questionRepository.find(ownerWorkspace.id(), intruder.id(), question.id())).isEmpty();
        assertThat(questionRepository.find(ownerWorkspace.id(), owner.id(), question.id())).isPresent();
        assertThrows(
                QuestionNotFoundException.class,
                () -> questionRepository.answer(ownerWorkspace.id(), intruder.id(), question.id(), "hijacked"));
    }

    private Document createDocument(long workspaceId, long userId, TemplateVersion templateVersion) {
        return revisionService.createDocument(
                        workspaceId,
                        userId,
                        new IdempotencyKey("create-" + UUID.randomUUID()),
                        CanonicalRequestHash.sha256OfCanonicalText("create-" + UUID.randomUUID()),
                        "Minutes",
                        templateVersion.templateId(),
                        templateVersion.id(),
                        new DocumentContent(Map.of(
                                "meeting.title", new FieldValue.TextValue("September minutes"),
                                "meeting.date", new FieldValue.DateValue(LocalDate.of(2026, 9, 1)))),
                        Map.of(),
                        "initial draft")
                .document();
    }

    private TemplateVersion newActiveTemplate(long workspaceId, long userId) {
        long artifactId = insertArtifact(workspaceId, userId);
        ExtractionVersion extraction =
                extractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "question-repo-test", templateGraph());
        Template template = templateRepository.createDraft(workspaceId, userId, "Minutes", artifactId, extraction.id());
        templateRepository.replaceDraftBindings(
                workspaceId,
                userId,
                template.id(),
                1,
                List.of(
                        field("meeting.title", FieldType.TEXT, FieldCardinality.SCALAR),
                        field("meeting.date", FieldType.DATE, FieldCardinality.SCALAR)));
        return templateRepository.activate(workspaceId, userId, template.id(), 2);
    }

    @Autowired
    private javax.sql.DataSource dataSource;

    private long insertArtifact(long workspaceId, long userId) {
        // A minimal, directly-inserted READY artifact row: this test only
        // needs a real artifact ID to satisfy the template's own foreign
        // key, not a real upload/scan pipeline. The tenant context and the
        // insert must run on the same connection/transaction -- set_config
        // with is_local=true does not survive a separate pooled connection,
        // unlike a real @Transactional repository method where Spring ties
        // every JdbcTemplate call to one bound connection.
        try (java.sql.Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (java.sql.PreparedStatement setContext = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
                setContext.setString(1, String.valueOf(userId));
                setContext.executeQuery();
            }
            try (java.sql.PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO artifact (workspace_id, blob_key, status, byte_count, detected_media_type) "
                            + "VALUES (?, ?, 'READY', 100, 'DOCX') RETURNING id")) {
                statement.setLong(1, workspaceId);
                statement.setString(2, "question-repo-test-" + UUID.randomUUID());
                try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    long id = resultSet.getLong(1);
                    connection.commit();
                    return id;
                }
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static DocxStructuralGraph templateGraph() {
        StructuralNode title = new StructuralNode("p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.title", null, List.of());
        StructuralNode date = new StructuralNode("p1/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.date", null, List.of());
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(title, date));
        return new DocxStructuralGraph("question-repo-test", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }

    private static FieldDefinition field(String fieldId, FieldType type, FieldCardinality cardinality) {
        return new FieldDefinition(fieldId, type, cardinality, FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag(fieldId));
    }

    private UserIdentity newUser(String subjectPrefix) {
        return userIdentityRepository.recordLogin("https://issuer-question-repo-tests", subjectPrefix + "-" + UUID.randomUUID(), null, null);
    }
}
