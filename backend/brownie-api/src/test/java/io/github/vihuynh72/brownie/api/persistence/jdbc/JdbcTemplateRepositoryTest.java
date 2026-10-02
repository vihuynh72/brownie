package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.template.DocxControlOrigin;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateNotFoundException;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateStatus;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStateConflictException;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStatus;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the draft/replace/activate lifecycle, the JSON round-trip of both
 * {@link FieldBindingTarget} kinds, the at-most-one-open-draft partial
 * unique index, and row-level security of {@code template}/{@code
 * template_version} against a real, disposable Postgres -- the unit-level
 * {@code TemplateServiceTest} and {@code TemplateBindingValidatorTest}
 * never touch a database at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DockerTest
class JdbcTemplateRepositoryTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", DB::jdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    }

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
    void createDraftOpensTheTemplateAndAnEmptyFirstDraftVersionTogether() {
        long userId = newUser("subject-create").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        long extractionId = insertExtraction(workspaceId, userId, artifactId);

        Template template = templateRepository.createDraft(workspaceId, userId, "Club Minutes", artifactId, extractionId);

        assertThat(template.displayName()).isEqualTo("Club Minutes");
        assertThat(template.status()).isEqualTo(TemplateStatus.DRAFT);
        assertThat(template.currentActiveVersionId()).isNull();

        TemplateVersion draft = templateRepository.findDraftVersion(workspaceId, userId, template.id()).orElseThrow();
        assertThat(draft.versionNumber()).isEqualTo(1);
        assertThat(draft.status()).isEqualTo(TemplateVersionStatus.DRAFT);
        assertThat(draft.sourceArtifactId()).isEqualTo(artifactId);
        assertThat(draft.extractionVersionId()).isEqualTo(extractionId);
        assertThat(draft.fieldDefinitions()).isEmpty();
        assertThat(draft.activatedAt()).isNull();
    }

    @Test
    void replaceDraftBindingsRoundTripsBothBindingKindsExactlyAndAdvancesTheVersionNumber() {
        long userId = newUser("subject-replace").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        long extractionId = insertExtraction(workspaceId, userId, artifactId);
        Template template = templateRepository.createDraft(workspaceId, userId, "Club Minutes", artifactId, extractionId);

        List<FieldDefinition> fields = List.of(
                new FieldDefinition(
                        "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                        new FieldBindingTarget.ContentControlTag("meeting.title")),
                new FieldDefinition(
                        "action.items", FieldType.TEXT, FieldCardinality.REPEATED, FieldRequiredness.OPTIONAL,
                        new FieldBindingTarget.StructuralNode(DocumentPartKind.MAIN_DOCUMENT, "p2")));

        TemplateVersion updated = templateRepository.replaceDraftBindings(workspaceId, userId, template.id(), 1, fields);

        assertThat(updated.versionNumber()).isEqualTo(2);
        assertThat(updated.status()).isEqualTo(TemplateVersionStatus.DRAFT);
        assertThat(updated.fieldDefinitions()).isEqualTo(fields);

        TemplateVersion reloaded = templateRepository.findDraftVersion(workspaceId, userId, template.id()).orElseThrow();
        assertThat(reloaded).isEqualTo(updated);
    }

    /**
     * A found field's label, origin, control origin and blank are stored
     * and read back; a field without them is stored as the very same JSON
     * every field was stored as before they existed, compared by Postgres
     * itself as {@code jsonb}.
     */
    @Test
    void labelsOriginsAndBlanksRoundTripAndAFieldWithoutThemIsStoredExactlyAsBefore() throws SQLException {
        long userId = newUser("subject-labels").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        long extractionId = insertExtraction(workspaceId, userId, artifactId);
        Template template = templateRepository.createDraft(workspaceId, userId, "Form", artifactId, extractionId);

        FieldDefinition plain = new FieldDefinition(
                "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                new FieldBindingTarget.ContentControlTag("meeting.title"));
        FieldDefinition found = new FieldDefinition(
                "ho.va.ten", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.ContentControlTag("ho.va.ten"),
                "H\u1ecd v\u00e0 t\u00ean", SpotOrigin.FOUND_BY_BROWNIE, DocxControlOrigin.INSERTED_BY_BROWNIE, "________");

        TemplateVersion updated = templateRepository.replaceDraftBindings(workspaceId, userId, template.id(), 1, List.of(plain, found));

        assertThat(updated.fieldDefinitions()).containsExactly(plain, found);
        assertThat(templateRepository.findDraftVersion(workspaceId, userId, template.id()).orElseThrow().fieldDefinitions())
                .containsExactly(plain, found);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT field_definitions -> 0 = ?::jsonb, field_definitions -> 1 ->> 'label' FROM template_version WHERE id = ?")) {
                statement.setString(1, "{\"fieldId\":\"meeting.title\",\"type\":\"TEXT\",\"cardinality\":\"SCALAR\","
                        + "\"requiredness\":\"REQUIRED\",\"bindingKind\":\"CONTENT_CONTROL_TAG\",\"contentControlTag\":\"meeting.title\","
                        + "\"structuralNodePart\":null,\"structuralNodeId\":null}");
                statement.setLong(2, updated.id());
                try (ResultSet row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getBoolean(1)).as("a field without the optional parts is stored exactly as before").isTrue();
                    assertThat(row.getString(2)).isEqualTo("H\u1ecd v\u00e0 t\u00ean");
                }
            }
            connection.rollback();
        }
    }

    @Test
    void replaceDraftBindingsRejectsAStaleExpectedVersionNumber() {
        long userId = newUser("subject-stale-replace").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        long extractionId = insertExtraction(workspaceId, userId, artifactId);
        Template template = templateRepository.createDraft(workspaceId, userId, "Club Minutes", artifactId, extractionId);

        assertThrows(
                TemplateVersionStateConflictException.class,
                () -> templateRepository.replaceDraftBindings(workspaceId, userId, template.id(), 7, List.of()));

        TemplateVersion stillDraft = templateRepository.findDraftVersion(workspaceId, userId, template.id()).orElseThrow();
        assertThat(stillDraft.versionNumber()).isEqualTo(1);
        assertThat(stillDraft.fieldDefinitions()).isEmpty();
    }

    @Test
    void replaceDraftBindingsOnANonexistentTemplateReportsNotFound() {
        long userId = newUser("subject-missing-template").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();

        assertThrows(
                TemplateNotFoundException.class,
                () -> templateRepository.replaceDraftBindings(workspaceId, userId, 999_999L, 1, List.of()));
    }

    @Test
    void activateFlipsBothTheVersionAndTheTemplatePointerTogether() {
        long userId = newUser("subject-activate").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        long extractionId = insertExtraction(workspaceId, userId, artifactId);
        Template template = templateRepository.createDraft(workspaceId, userId, "Club Minutes", artifactId, extractionId);
        List<FieldDefinition> fields = List.of(new FieldDefinition(
                "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                new FieldBindingTarget.ContentControlTag("meeting.title")));
        templateRepository.replaceDraftBindings(workspaceId, userId, template.id(), 1, fields);

        TemplateVersion activated = templateRepository.activate(workspaceId, userId, template.id(), 2);

        assertThat(activated.status()).isEqualTo(TemplateVersionStatus.ACTIVATED);
        assertThat(activated.activatedAt()).isNotNull();
        assertThat(activated.fieldDefinitions()).isEqualTo(fields);

        Template reloaded = templateRepository.find(workspaceId, userId, template.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(TemplateStatus.ACTIVE);
        assertThat(reloaded.currentActiveVersionId()).isEqualTo(activated.id());
        assertThat(templateRepository.findDraftVersion(workspaceId, userId, template.id())).isEmpty();
    }

    @Test
    void activateRejectsAStaleExpectedVersionNumber() {
        long userId = newUser("subject-stale-activate").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        long extractionId = insertExtraction(workspaceId, userId, artifactId);
        Template template = templateRepository.createDraft(workspaceId, userId, "Club Minutes", artifactId, extractionId);

        assertThrows(
                TemplateVersionStateConflictException.class,
                () -> templateRepository.activate(workspaceId, userId, template.id(), 5));

        TemplateVersion stillDraft = templateRepository.findDraftVersion(workspaceId, userId, template.id()).orElseThrow();
        assertThat(stillDraft.status()).isEqualTo(TemplateVersionStatus.DRAFT);
        assertThat(templateRepository.find(workspaceId, userId, template.id()).orElseThrow().status())
                .isEqualTo(TemplateStatus.DRAFT);
    }

    @Test
    void atMostOneOpenDraftPerTemplateIsEnforcedByPostgresItselfNotJustApplicationCode() throws SQLException {
        long userId = newUser("subject-one-draft").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        long extractionId = insertExtraction(workspaceId, userId, artifactId);
        Template template = templateRepository.createDraft(workspaceId, userId, "Club Minutes", artifactId, extractionId);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO template_version (workspace_id, template_id, version_number, source_artifact_id, extraction_version_id) "
                            + "VALUES (?, ?, 2, ?, ?)")) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, template.id());
                statement.setLong(3, artifactId);
                statement.setLong(4, extractionId);
                statement.executeUpdate();
                fail("expected a second DRAFT row for the same template to violate the partial unique index");
            } catch (SQLException expected) {
                assertThat(expected.getMessage()).containsIgnoringCase("duplicate key");
            }
            connection.rollback();
        }
    }

    @Test
    void oneUsersContextCannotReadAnotherWorkspacesTemplate() {
        UserIdentity userA = newUser("subject-rls-a");
        UserIdentity userB = newUser("subject-rls-b");
        Workspace workspaceB = workspaceRepository.ensurePersonalWorkspace(userB.id());
        long artifactId = insertArtifact(workspaceB.id(), userB.id());
        long extractionId = insertExtraction(workspaceB.id(), userB.id(), artifactId);
        Template template = templateRepository.createDraft(workspaceB.id(), userB.id(), "Private", artifactId, extractionId);

        assertThat(templateRepository.find(workspaceB.id(), userA.id(), template.id())).isEmpty();
    }

    /**
     * The template made last is trashed first, so an order by ID (or by
     * creation) would list it first; the Trash Bin lists the most recently
     * trashed first instead. Each trash is its own transaction, so the two
     * times differ, and the test says so rather than relying on it.
     */
    @Test
    void trashKeepsTheFirstTimeTheTrashBinListsTheMostRecentlyTrashedFirstAndRestoreBringsTheTemplateBack() {
        long userId = newUser("subject-trash").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        long extractionId = insertExtraction(workspaceId, userId, artifactId);
        Template kept = templateRepository.createDraft(workspaceId, userId, "Kept", artifactId, extractionId);
        Template older = templateRepository.createDraft(workspaceId, userId, "Made first, trashed second", artifactId, extractionId);
        Template newer = templateRepository.createDraft(workspaceId, userId, "Made second, trashed first", artifactId, extractionId);

        Template trashedFirst = templateRepository.trash(workspaceId, userId, newer.id());
        Template trashed = templateRepository.trash(workspaceId, userId, older.id());
        Template trashedAgain = templateRepository.trash(workspaceId, userId, older.id());

        assertThat(kept.trashedAt()).isNull();
        assertThat(trashed.trashedAt()).isAfter(trashedFirst.trashedAt());
        assertThat(trashedAgain).isEqualTo(trashed);
        assertThat(templateRepository.findTrashed(workspaceId, userId)).extracting(Template::id).containsExactly(older.id(), newer.id());
        assertThat(templateRepository.findAll(workspaceId, userId))
                .extracting(Template::id)
                .containsExactly(kept.id(), older.id(), newer.id());

        Template restored = templateRepository.restore(workspaceId, userId, older.id());

        assertThat(restored.trashedAt()).isNull();
        assertThat(templateRepository.restore(workspaceId, userId, older.id())).isEqualTo(restored);
        assertThat(templateRepository.findTrashed(workspaceId, userId)).extracting(Template::id).containsExactly(newer.id());
    }

    @Test
    void anotherWorkspacesTemplateCannotBeTrashedOrRestoredAndIsLeftAsItWas() {
        UserIdentity owner = newUser("subject-trash-owner");
        UserIdentity stranger = newUser("subject-trash-stranger");
        Workspace ownerWorkspace = workspaceRepository.ensurePersonalWorkspace(owner.id());
        Workspace strangerWorkspace = workspaceRepository.ensurePersonalWorkspace(stranger.id());
        long artifactId = insertArtifact(ownerWorkspace.id(), owner.id());
        long extractionId = insertExtraction(ownerWorkspace.id(), owner.id(), artifactId);
        Template template = templateRepository.createDraft(ownerWorkspace.id(), owner.id(), "Private", artifactId, extractionId);

        assertThrows(TemplateNotFoundException.class, () -> templateRepository.trash(ownerWorkspace.id(), stranger.id(), template.id()));
        assertThrows(TemplateNotFoundException.class, () -> templateRepository.trash(strangerWorkspace.id(), stranger.id(), template.id()));
        assertThat(templateRepository.find(ownerWorkspace.id(), owner.id(), template.id()).orElseThrow().trashedAt()).isNull();

        templateRepository.trash(ownerWorkspace.id(), owner.id(), template.id());

        assertThrows(TemplateNotFoundException.class, () -> templateRepository.restore(ownerWorkspace.id(), stranger.id(), template.id()));
        assertThat(templateRepository.find(ownerWorkspace.id(), owner.id(), template.id()).orElseThrow().trashedAt()).isNotNull();
        assertThat(templateRepository.findTrashed(ownerWorkspace.id(), stranger.id())).isEmpty();
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

    private long insertExtraction(long workspaceId, long userId, long artifactId) {
        ExtractionVersion extraction =
                extractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "test-parser-v1", sampleGraph());
        return extraction.id();
    }

    private DocxStructuralGraph sampleGraph() {
        StructuralNode control =
                new StructuralNode("p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.title", null, List.of());
        StructuralNode table = new StructuralNode("p2", StructuralNodeKind.TABLE, null, null, null, null, List.of());
        StructuralNode body = new StructuralNode("", StructuralNodeKind.BODY, null, null, null, null, List.of(control, table));
        return new DocxStructuralGraph("test-parser-v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }

    private UserIdentity newUser(String subject) {
        return userIdentityRepository.recordLogin("https://issuer-template-tests", subject, null, null);
    }

    private void setLocalContext(Connection connection, long userId) throws SQLException {
        try (PreparedStatement setContext =
                connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            setContext.setString(1, String.valueOf(userId));
            setContext.executeQuery();
        }
    }
}
