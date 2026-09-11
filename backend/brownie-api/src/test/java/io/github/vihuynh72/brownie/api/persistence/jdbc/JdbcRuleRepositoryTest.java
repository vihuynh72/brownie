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
import io.github.vihuynh72.brownie.core.rule.DateFormatStyle;
import io.github.vihuynh72.brownie.core.rule.RuleCategory;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.rule.RuleRevisionStatus;
import io.github.vihuynh72.brownie.core.rule.RuleScope;
import io.github.vihuynh72.brownie.core.rule.RuleTemplateVersionStateException;
import io.github.vihuynh72.brownie.core.rule.RuleVocabulary;
import io.github.vihuynh72.brownie.core.source.SourceKind;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
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

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the JSON round-trip of {@link RuleScope} and every {@link
 * RulePayload} variant, including the {@link FieldBindingTarget} nested
 * inside a {@code ProtectedRegion}, plus row-level security -- against a
 * real, disposable Postgres, the same infrastructure pattern {@code
 * JdbcTemplateRepositoryTest} already established.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class JdbcRuleRepositoryTest {

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
        return Path.of("").toAbsolutePath()
                .getParent()
                .getParent()
                .resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    @Autowired
    private RuleRepository ruleRepository;

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private ExtractionVersionRepository extractionVersionRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void everyRulePayloadVariantAndScopeRoundTripsExactly() {
        long userId = newUser("subject-round-trip").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        TemplateVersion draft = newDraftTemplateVersion(workspaceId, userId);

        List<RulePayload> payloads = List.of(
                new RulePayload.RequiredFields(List.of("meeting.title", "meeting.date")),
                new RulePayload.MaxTextLength("meeting.title", 120),
                new RulePayload.MaxItemCount("action.items", 20),
                new RulePayload.AllowedSectionOrder(List.of("agenda", "decisions", "action items")),
                new RulePayload.DateDisplayFormat("meeting.date", DateFormatStyle.LONG),
                new RulePayload.AllowedSourceKinds("meeting.title", List.of(SourceKind.ARTIFACT)),
                new RulePayload.MissingValueBehavior(
                        "meeting.title", io.github.vihuynh72.brownie.core.rule.EmptyValueResolution.BLANK),
                new RulePayload.AllowedOverflowBehavior(
                        "meeting.title", io.github.vihuynh72.brownie.core.rule.OverflowResolution.BLOCK_EXPORT),
                new RulePayload.RepeatableRegionEmptyBehavior(
                        "action.items", io.github.vihuynh72.brownie.core.rule.EmptyValueResolution.OMIT),
                new RulePayload.ProtectedRegion(new FieldBindingTarget.ContentControlTag("meeting.title")),
                new RulePayload.ProtectedRegion(new FieldBindingTarget.StructuralNode(DocumentPartKind.MAIN_DOCUMENT, "p2")));

        for (RulePayload payload : payloads) {
            RuleScope scope = payload instanceof RulePayload.RequiredFields || payload instanceof RulePayload.AllowedSectionOrder
                    ? new RuleScope.WholeTemplate()
                    : new RuleScope.SingleField("meeting.title");

            RuleRevision saved = ruleRepository.propose(
                    workspaceId, userId, draft.templateId(), draft.id(), scope, payload, RuleVocabulary.SCHEMA_VERSION,
                    "explanation for " + payload.getClass().getSimpleName());

            assertThat(saved.category()).isEqualTo(payload.category());
            assertThat(saved.payload()).isEqualTo(payload);
            assertThat(saved.scope()).isEqualTo(scope);
            assertThat(saved.status()).isEqualTo(RuleRevisionStatus.PROPOSED);
            assertThat(saved.schemaVersion()).isEqualTo(RuleVocabulary.SCHEMA_VERSION);

            RuleRevision reloaded = ruleRepository.find(workspaceId, userId, draft.templateId(), saved.id()).orElseThrow();
            assertThat(reloaded).isEqualTo(saved);
        }

        assertThat(ruleRepository.findByTemplateVersion(workspaceId, userId, draft.id())).hasSize(payloads.size());
    }

    @Test
    void categoryIsStoredAsItsOwnColumnMatchingThePayloadsOwnCategory() {
        long userId = newUser("subject-category").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        TemplateVersion draft = newDraftTemplateVersion(workspaceId, userId);

        RuleRevision saved = ruleRepository.propose(
                workspaceId, userId, draft.templateId(), draft.id(), new RuleScope.WholeTemplate(),
                new RulePayload.RequiredFields(List.of("meeting.title")), RuleVocabulary.SCHEMA_VERSION, null);

        assertThat(saved.category()).isEqualTo(RuleCategory.VALIDATION);
    }

    @Test
    void proposingAgainstAnActivatedTemplateVersionIsRejectedAtTheDatabaseBoundary() {
        long userId = newUser("subject-active-version").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        TemplateVersion draft = newDraftTemplateVersion(workspaceId, userId);
        TemplateVersion activated = templateRepository.activate(workspaceId, userId, draft.templateId(), draft.versionNumber());

        assertThatThrownBy(() -> ruleRepository.propose(
                        workspaceId,
                        userId,
                        activated.templateId(),
                        activated.id(),
                        new RuleScope.SingleField("meeting.title"),
                        new RulePayload.MaxTextLength("meeting.title", 100),
                        RuleVocabulary.SCHEMA_VERSION,
                        null))
                .isInstanceOf(RuleTemplateVersionStateException.class);
        assertThat(ruleRepository.findByTemplateVersion(workspaceId, userId, activated.id())).isEmpty();
    }

    @Test
    void anUnknownStoredOperatorFailsClosedInsteadOfBeingReconstitutedAsARule() {
        long userId = newUser("subject-unknown-operator").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        TemplateVersion draft = newDraftTemplateVersion(workspaceId, userId);
        long ruleId = insertUnknownOperatorRow(workspaceId, userId, draft);

        assertThatThrownBy(() -> ruleRepository.find(workspaceId, userId, draft.templateId(), ruleId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown stored rule payload kind: SCRIPT");
    }

    @Test
    void oneUsersContextCannotReadAnotherWorkspacesRuleRevision() {
        var userA = newUser("subject-rls-a");
        var userB = newUser("subject-rls-b");
        long workspaceBId = workspaceRepository.ensurePersonalWorkspace(userB.id()).id();
        TemplateVersion draft = newDraftTemplateVersion(workspaceBId, userB.id());
        RuleRevision saved = ruleRepository.propose(
                workspaceBId, userB.id(), draft.templateId(), draft.id(), new RuleScope.WholeTemplate(),
                new RulePayload.RequiredFields(List.of("meeting.title")), RuleVocabulary.SCHEMA_VERSION, null);

        assertThat(ruleRepository.find(workspaceBId, userA.id(), draft.templateId(), saved.id())).isEmpty();
    }

    private TemplateVersion newDraftTemplateVersion(long workspaceId, long userId) {
        long artifactId = insertArtifact(workspaceId, userId);
        DocxStructuralGraph graph = sampleGraph();
        ExtractionVersion extraction = extractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "test-parser-v1", graph);
        Template template = templateRepository.createDraft(workspaceId, userId, "Club Minutes", artifactId, extraction.id());
        List<FieldDefinition> fields = List.of(
                new FieldDefinition(
                        "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                        new FieldBindingTarget.ContentControlTag("meeting.title")),
                new FieldDefinition(
                        "meeting.date", FieldType.DATE, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                        new FieldBindingTarget.ContentControlTag("meeting.date")),
                new FieldDefinition(
                        "action.items", FieldType.TEXT, FieldCardinality.REPEATED, FieldRequiredness.OPTIONAL,
                        new FieldBindingTarget.StructuralNode(DocumentPartKind.MAIN_DOCUMENT, "p2")));
        return templateRepository.replaceDraftBindings(workspaceId, userId, template.id(), 1, fields);
    }

    private DocxStructuralGraph sampleGraph() {
        StructuralNode titleControl =
                new StructuralNode("p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.title", null, List.of());
        StructuralNode dateControl =
                new StructuralNode("p1/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.date", null, List.of());
        StructuralNode table = new StructuralNode("p2", StructuralNodeKind.TABLE, null, null, null, null, List.of());
        StructuralNode body = new StructuralNode(
                "body", StructuralNodeKind.BODY, null, null, null, null, List.of(titleControl, dateControl, table));
        return new DocxStructuralGraph("test-parser-v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }

    private UserIdentity newUser(String subject) {
        return userIdentityRepository.recordLogin("https://issuer-rule-tests", subject, null, null);
    }

    private long insertUnknownOperatorRow(long workspaceId, long userId, TemplateVersion draft) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO rule_revision
                        (workspace_id, template_id, template_version_id, category, scope, payload, schema_version, author_user_id)
                    VALUES (?, ?, ?, 'BEHAVIOR', ?::jsonb, ?::jsonb, ?, ?)
                    RETURNING id
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, draft.templateId());
                statement.setLong(3, draft.id());
                statement.setString(4, "{\"kind\":\"WHOLE_TEMPLATE\"}");
                statement.setString(5, "{\"kind\":\"SCRIPT\",\"source\":\"not executable\"}");
                statement.setString(6, RuleVocabulary.SCHEMA_VERSION);
                statement.setLong(7, userId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    long id = resultSet.getLong(1);
                    connection.commit();
                    return id;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private long insertArtifact(long workspaceId, long userId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO artifact (workspace_id, blob_key, status, byte_count, detected_media_type) "
                            + "VALUES (?, ?, 'READY', 100, 'DOCX') RETURNING id")) {
                statement.setLong(1, workspaceId);
                statement.setString(2, "test-blob-" + UUID.randomUUID());
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    long id = resultSet.getLong(1);
                    connection.commit();
                    return id;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void setLocalContext(Connection connection, long userId) throws SQLException {
        try (PreparedStatement setContext =
                connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            setContext.setString(1, String.valueOf(userId));
            setContext.executeQuery();
        }
    }
}
