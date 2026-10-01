package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfPoint;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.UnsupportedPdfFormReason;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PDF form readings against a real, disposable Postgres: the whole graph
 * round-trips exactly, a refusal keeps its reason, saving twice keeps one
 * row, and a member of another workspace can neither read nor write one.
 * The API's own role can add readings but never change or remove them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DockerTest
class JdbcPdfFormExtractionVersionRepositoryTest {

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
    private PdfFormExtractionVersionRepository repository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void aCompleteReadingRoundTripsEveryPartOfTheGraph() {
        long userId = newUser("subject-form-complete").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        PdfFormGraph graph = sampleGraph();

        PdfFormExtractionVersion saved = repository.saveComplete(workspaceId, userId, artifactId, "form-v1", graph);

        assertThat(saved.status()).isEqualTo(ExtractionStatus.COMPLETE);
        assertThat(saved.graph()).isEqualTo(graph);
        assertThat(saved.unsupportedReason()).isNull();
        assertThat(repository.findById(workspaceId, userId, saved.id())).contains(saved);
        assertThat(repository.findByArtifact(workspaceId, userId, artifactId, "form-v1")).contains(saved);
        assertThat(repository.findCompleteIdByArtifact(workspaceId, userId, artifactId, "form-v1")).contains(saved.id());
        assertThat(repository.findCompleteIdByArtifact(workspaceId, userId, artifactId, "form-v2")).isEmpty();
    }

    @Test
    void aRefusedReadingKeepsItsReasonAndDetail() {
        long userId = newUser("subject-form-refused").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        PdfFormExtractionVersion saved = repository.saveUnsupported(
                workspaceId, userId, artifactId, "form-v1", UnsupportedPdfFormReason.ENCRYPTED, "an owner password is set");

        assertThat(saved.status()).isEqualTo(ExtractionStatus.UNSUPPORTED);
        assertThat(saved.graph()).isNull();
        assertThat(saved.unsupportedReason()).isEqualTo(UnsupportedPdfFormReason.ENCRYPTED);
        assertThat(saved.unsupportedDetail()).isEqualTo("an owner password is set");
        assertThat(repository.findCompleteIdByArtifact(workspaceId, userId, artifactId, "form-v1")).isEmpty();
    }

    @Test
    void savingTwiceKeepsTheFirstRowAndANewReaderVersionAddsAnother() {
        long userId = newUser("subject-form-twice").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        PdfFormExtractionVersion first = repository.saveComplete(workspaceId, userId, artifactId, "form-v1", sampleGraph());
        PdfFormExtractionVersion again = repository.saveUnsupported(
                workspaceId, userId, artifactId, "form-v1", UnsupportedPdfFormReason.DAMAGED, "ignored");
        PdfFormExtractionVersion next = repository.saveComplete(workspaceId, userId, artifactId, "form-v2", sampleGraph());

        assertThat(again).isEqualTo(first);
        assertThat(next.id()).isNotEqualTo(first.id());
    }

    @Test
    void anotherWorkspacesMemberCanNeitherReadNorAddAReading() throws SQLException {
        UserIdentity owner = newUser("subject-form-rls-owner");
        UserIdentity outsider = newUser("subject-form-rls-outsider");
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(owner.id());
        long artifactId = insertArtifact(workspace.id(), owner.id());
        PdfFormExtractionVersion saved = repository.saveComplete(workspace.id(), owner.id(), artifactId, "form-v1", sampleGraph());

        assertThat(repository.findById(workspace.id(), outsider.id(), saved.id())).isEmpty();
        assertThat(repository.findByArtifact(workspace.id(), outsider.id(), artifactId, "form-v1")).isEmpty();
        assertThat(repository.findCompleteIdByArtifact(workspace.id(), outsider.id(), artifactId, "form-v1")).isEmpty();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, outsider.id());
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO pdf_form_extraction_version (workspace_id, artifact_id, parser_version, status, unsupported_reason)"
                            + " VALUES (?, ?, 'form-v9', 'UNSUPPORTED', 'DAMAGED')")) {
                insert.setLong(1, workspace.id());
                insert.setLong(2, artifactId);
                assertThatThrownBy(insert::executeUpdate).hasMessageContaining("row-level security");
            }
            connection.rollback();
        }
    }

    @Test
    void theApiRoleCannotChangeOrRemoveAReading() throws SQLException {
        long userId = newUser("subject-form-immutable").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);
        long id = repository.saveComplete(workspaceId, userId, artifactId, "form-v1", sampleGraph()).id();

        for (String statement : List.of(
                "UPDATE pdf_form_extraction_version SET parser_version = 'changed' WHERE id = " + id,
                "DELETE FROM pdf_form_extraction_version WHERE id = " + id,
                "TRUNCATE pdf_form_extraction_version CASCADE")) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                setLocalContext(connection, userId);
                try (PreparedStatement change = connection.prepareStatement(statement)) {
                    assertThatThrownBy(change::execute).as(statement).hasMessageContaining("permission denied");
                }
                connection.rollback();
            }
        }
    }

    @Test
    void aReadingMustEitherHoldTheFormOrSayWhyNot() throws SQLException {
        long userId = newUser("subject-form-outcome").id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        long artifactId = insertArtifact(workspaceId, userId);

        for (String values : List.of(
                "'COMPLETE', NULL, NULL",
                "'UNSUPPORTED', NULL, NULL",
                "'COMPLETE', 'ENCRYPTED', '{}'::jsonb",
                "'UNSUPPORTED', 'ENCRYPTED', '{}'::jsonb",
                "'COMPLETE', NULL, '[]'::jsonb")) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                setLocalContext(connection, userId);
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO pdf_form_extraction_version (workspace_id, artifact_id, parser_version, status, unsupported_reason, graph)"
                                + " VALUES (?, ?, 'form-check', " + values + ")")) {
                    insert.setLong(1, workspaceId);
                    insert.setLong(2, artifactId);
                    assertThatThrownBy(insert::executeUpdate).as(values).hasMessageContaining("check constraint");
                }
                connection.rollback();
            }
        }
    }

    /** A graph that uses every nested kind of record once, with fractional coordinates and non-ASCII text. */
    static PdfFormGraph sampleGraph() {
        PdfFormGraph.Word label = new PdfFormGraph.Word("H\u1ecd t\u00ean:", new PdfRect(72.25, 90.5, 40.125, 11), "Helvetica", 11, 0);
        PdfFormGraph.Line line = new PdfFormGraph.Line(0, "H\u1ecd t\u00ean:", new PdfRect(72.25, 90.5, 40.125, 11), List.of(label));
        PdfFormGraph.Page page = new PdfFormGraph.Page(
                1,
                new PdfFormGraph.CropBox(0, 0, 612, 792),
                90,
                1,
                true,
                List.of(line),
                List.of(new PdfFormGraph.Rule(new PdfPoint(130, 102), new PdfPoint(500, 102))),
                List.of(new PdfRect(72, 300, 150, 25)),
                List.of(new PdfFormGraph.Image(new PdfRect(0, 0, 612, 792), List.of("JBIG2Decode"))));
        PdfFormGraph.Field field = new PdfFormGraph.Field(
                "applicant.fullName", PdfFormGraph.FieldKind.TEXT, false, true, false, false, 40, "Full name", "mm/dd/yyyy",
                List.of(new PdfFormGraph.Widget(1, new PdfRect(150, 82, 300, 20))));
        PdfFormGraph.AcroForm form = new PdfFormGraph.AcroForm(true, PdfFormGraph.XfaKind.NONE, false, List.of(field), 0);
        return new PdfFormGraph("test-form-reader-v1", List.of(page), form, new PdfFormGraph.Risks(false, false, false));
    }

    private UserIdentity newUser(String subject) {
        return userIdentityRepository.recordLogin("https://issuer-pdf-form-extraction-tests", subject, null, null);
    }

    private long insertArtifact(long workspaceId, long userId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO artifact (workspace_id, blob_key, status, byte_count, detected_media_type) "
                            + "VALUES (?, ?, 'READY', 100, 'PDF') RETURNING id")) {
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
        try (PreparedStatement setContext = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            setContext.setString(1, String.valueOf(userId));
            setContext.executeQuery();
        }
    }
}
