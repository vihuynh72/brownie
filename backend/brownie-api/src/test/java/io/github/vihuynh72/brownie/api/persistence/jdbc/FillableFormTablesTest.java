package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFontFamily;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.prepare.ArtifactDerivation;
import io.github.vihuynh72.brownie.core.prepare.ArtifactDerivationRepository;
import io.github.vihuynh72.brownie.core.prepare.FillableForm;
import io.github.vihuynh72.brownie.core.prepare.NamingSource;
import io.github.vihuynh72.brownie.core.prepare.PreparationNotice;
import io.github.vihuynh72.brownie.core.template.DocxControlOrigin;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.FillSpotReviewRepository;
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two tables V47 adds: what a derivation and a review store and read
 * back, that a member of one workspace can neither read nor write
 * another's, that the application's login can only add rows and never
 * change or remove one, and the constraints that hold whatever writes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DockerTest
class FillableFormTablesTest {

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
    private ArtifactDerivationRepository derivations;

    @Autowired
    private FillSpotReviewRepository reviews;

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
    void aDerivationRoundTripsAndTheFirstOfTwoRacingWritesStands() {
        Member member = member("derivation-round-trip");
        long source = insertArtifact(member);
        long output = insertArtifact(member);
        long losersOutput = insertArtifact(member);
        FillableForm.Spot spot = new FillableForm.Spot(new FieldDefinition("full.name", FieldType.TEXT, FieldCardinality.SCALAR,
                FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag("full.name"), "Full name", SpotOrigin.FOUND_BY_BROWNIE,
                DocxControlOrigin.INSERTED_BY_BROWNIE, "______"), NamingSource.RULES, false, "TEXT", "UNDERSCORES");
        List<PreparationNotice> notices = List.of(new PreparationNotice(PreparationNotice.CONVERTED, 1, "WORD_97"),
                new PreparationNotice(PreparationNotice.SPOTS_FOUND, 1, null));

        ArtifactDerivation first = derivations.insertOrGet(member.workspaceId(), member.userId(), source, output, "PREPARED", "recipe/v1",
                "DOC", "converter-v1", NamingSource.RULES, "DISABLED", List.of(spot), notices);
        ArtifactDerivation second = derivations.insertOrGet(member.workspaceId(), member.userId(), source, losersOutput, "PREPARED",
                "recipe/v1", "DOC", "converter-v1", NamingSource.MODEL, null, List.of(), List.of());

        assertThat(second).isEqualTo(first);
        assertThat(first.outputArtifactId()).isEqualTo(output);
        assertThat(first.spots()).containsExactly(spot);
        assertThat(first.notices()).isEqualTo(notices);
        assertThat(first.converter()).isEqualTo("converter-v1");
        assertThat(first.rulesOnlyReason()).isEqualTo("DISABLED");
        assertThat(first.createdByUserId()).isEqualTo(member.userId());
        assertThat(derivations.find(member.workspaceId(), member.userId(), source, "PREPARED", "recipe/v1")).contains(first);
        assertThat(derivations.find(member.workspaceId(), member.userId(), source, "PREPARED", "recipe/v2")).isEmpty();
    }

    /** A PDF's answer is kept against the PDF itself, with spots bound to the form's own fields and to boxes on its pages. */
    @Test
    void aPdfsAnswerIsKeptAgainstThePdfItselfAndItsBoxesAndFieldsRoundTrip() {
        Member member = member("derivation-pdf");
        long pdf = insertArtifact(member);
        FillableForm.Spot box = new FillableForm.Spot(new FieldDefinition("full.name", FieldType.TEXT, FieldCardinality.SCALAR,
                FieldRequiredness.OPTIONAL, new FieldBindingTarget.PageBox(1, 130.5, 88.25, 180, 14.75,
                new PdfTextStyle(PdfFontFamily.SERIF, false, 11.5), false, PdfOverflowPolicy.SHRINK_TO_FIT), "Full name",
                SpotOrigin.FOUND_BY_BROWNIE, null, null), NamingSource.MODEL, true, null, null);
        FillableForm.Spot field = new FillableForm.Spot(new FieldDefinition("email", FieldType.TEXT, FieldCardinality.SCALAR,
                FieldRequiredness.OPTIONAL, new FieldBindingTarget.AcroFormField("applicant.email"), "Email", SpotOrigin.FORM, null, null),
                NamingSource.RULES, false, null, null);

        ArtifactDerivation first = derivations.insertOrGet(member.workspaceId(), member.userId(), pdf, pdf, ArtifactDerivation.PDF_FORM,
                "pdf/v1", "PDF", null, NamingSource.MODEL, null, List.of(box, field), List.of());
        ArtifactDerivation newerReader = derivations.insertOrGet(member.workspaceId(), member.userId(), pdf, pdf,
                ArtifactDerivation.PDF_FORM, "pdf/v2", "PDF", null, NamingSource.RULES, "DISABLED", List.of(), List.of());

        assertThat(first.spots()).containsExactly(box, field);
        assertThat(derivations.find(member.workspaceId(), member.userId(), pdf, ArtifactDerivation.PDF_FORM, "pdf/v1")).contains(first);
        assertThat(newerReader.id()).as("a PDF has one answer per recipe version").isNotEqualTo(first.id());
    }

    @Test
    void anotherWorkspacesMemberCanNeitherReadNorWriteADerivationOrAReview() throws SQLException {
        Member owner = member("tables-rls-owner");
        Member outsider = member("tables-rls-outsider");
        long source = insertArtifact(owner);
        long output = insertArtifact(owner);
        derivations.insertOrGet(owner.workspaceId(), owner.userId(), source, output, "PREPARED", "recipe/v1", "DOCX", null,
                NamingSource.RULES, "NO_CANDIDATES", List.of(), List.of());
        long templateId = template(owner, "full.name");
        reviews.keep(owner.workspaceId(), owner.userId(), templateId, List.of("full.name"));

        assertThat(derivations.find(owner.workspaceId(), outsider.userId(), source, "PREPARED", "recipe/v1")).isEmpty();
        assertThat(reviews.keptFieldIds(owner.workspaceId(), outsider.userId(), templateId)).isEmpty();
        assertThat(reviews.keptFieldIds(owner.workspaceId(), owner.userId(), templateId)).containsExactly("full.name");
        assertThatThrownBy(() -> derivations.insertOrGet(owner.workspaceId(), outsider.userId(), output, source, "PREPARED", "recipe/v1",
                "DOCX", null, NamingSource.RULES, null, List.of(), List.of()))
                .rootCause().hasMessageContaining("row-level security");
        assertThatThrownBy(() -> reviews.keep(owner.workspaceId(), outsider.userId(), templateId, List.of("other")))
                .rootCause().hasMessageContaining("row-level security");
        // Rows written in someone else's name are refused too, even inside one's own workspace.
        assertThatThrownBy(() -> asApi(owner.userId(), """
                INSERT INTO fill_spot_review (workspace_id, template_id, field_id, decision, reviewed_by_user_id)
                VALUES (%d, %d, 'town', 'KEPT', %d)
                """.formatted(owner.workspaceId(), templateId, outsider.userId())))
                .hasMessageContaining("row-level security");
    }

    @Test
    void keepingAFieldTwiceChangesNothing() {
        Member member = member("review-twice");
        long templateId = template(member, "full.name");

        reviews.keep(member.workspaceId(), member.userId(), templateId, List.of("full.name"));
        reviews.keep(member.workspaceId(), member.userId(), templateId, List.of("full.name"));

        assertThat(reviews.keptFieldIds(member.workspaceId(), member.userId(), templateId)).isEqualTo(Set.of("full.name"));
        assertThat(countAsOwner("SELECT count(*) FROM fill_spot_review WHERE template_id = " + templateId)).isEqualTo(1);
    }

    @Test
    void theApplicationsLoginCannotChangeOrRemoveARowOnceWritten() {
        Member member = member("tables-no-update");
        long source = insertArtifact(member);
        long output = insertArtifact(member);
        derivations.insertOrGet(member.workspaceId(), member.userId(), source, output, "PREPARED", "recipe/v1", "DOCX", null,
                NamingSource.RULES, null, List.of(), List.of());
        long templateId = template(member, "full.name");
        reviews.keep(member.workspaceId(), member.userId(), templateId, List.of("full.name"));

        for (String statement : List.of(
                "UPDATE artifact_derivation SET recipe_version = 'x'",
                "DELETE FROM artifact_derivation",
                "TRUNCATE artifact_derivation",
                "UPDATE fill_spot_review SET decision = 'KEPT'",
                "DELETE FROM fill_spot_review",
                "TRUNCATE fill_spot_review")) {
            assertThatThrownBy(() -> asApi(member.userId(), statement)).as(statement).hasMessageContaining("permission denied");
        }
    }

    @Test
    void theTablesOwnRulesHoldWhateverWritesThem() throws SQLException {
        Member member = member("tables-checks");
        long source = insertArtifact(member);
        long output = insertArtifact(member);
        long templateId = template(member, "full.name");
        String derivation = "INSERT INTO artifact_derivation (workspace_id, source_artifact_id, output_artifact_id, kind, recipe_version, "
                + "source_format, spot_naming, spots, notices, created_by_user_id) VALUES (%d, %d, %d, %s, 'r', 'DOCX', %s, %s, '[]', %d)";
        String review = "INSERT INTO fill_spot_review (workspace_id, template_id, field_id, decision, reviewed_by_user_id) "
                + "VALUES (%d, %d, %s, %s, %d)";

        for (String statement : List.of(
                derivation.formatted(member.workspaceId(), source, output, "'COPIED'", "'RULES'", "'[]'", member.userId()),
                derivation.formatted(member.workspaceId(), source, source, "'PREPARED'", "'RULES'", "'[]'", member.userId()),
                derivation.formatted(member.workspaceId(), source, output, "'PDF_FORM'", "'RULES'", "'[]'", member.userId()),
                derivation.formatted(member.workspaceId(), source, output, "'PREPARED'", "'GUESSED'", "'[]'", member.userId()),
                derivation.formatted(member.workspaceId(), source, output, "'PREPARED'", "'RULES'", "'{}'", member.userId()),
                derivation.formatted(member.workspaceId() + 1000, source, output, "'PREPARED'", "'RULES'", "'[]'", member.userId()),
                review.formatted(member.workspaceId(), templateId, "'Full Name'", "'KEPT'", member.userId()),
                review.formatted(member.workspaceId(), templateId, "'" + "a".repeat(65) + "'", "'KEPT'", member.userId()),
                review.formatted(member.workspaceId(), templateId, "'full.name'", "'REMOVED'", member.userId()))) {
            assertThatThrownBy(() -> asOwner(statement)).as(statement).isInstanceOf(SQLException.class);
        }
        asOwner(derivation.formatted(member.workspaceId(), source, output, "'PREPARED'", "'RULES'", "'[]'", member.userId()));
        assertThatThrownBy(() -> asOwner(derivation.formatted(member.workspaceId(), source, output, "'PREPARED'", "'MODEL'", "'[]'",
                member.userId()))).as("one row per source, kind and recipe").isInstanceOf(SQLException.class);
        long otherSource = insertArtifact(member);
        assertThatThrownBy(() -> asOwner(derivation.formatted(member.workspaceId(), otherSource, output, "'PREPARED'", "'RULES'", "'[]'",
                member.userId()))).as("a working copy is made from one upload").isInstanceOf(SQLException.class);
    }

    // ---------------------------------------------------------------- helpers

    private record Member(long userId, long workspaceId) {
    }

    private Member member(String subject) {
        long userId = userIdentityRepository.recordLogin("https://issuer-fillable-form-tables", subject, null, null).id();
        return new Member(userId, workspaceRepository.ensurePersonalWorkspace(userId).id());
    }

    private long template(Member member, String fieldId) {
        long artifactId = insertArtifact(member);
        StructuralNode control = new StructuralNode("p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, fieldId, null, List.of());
        StructuralNode paragraph = new StructuralNode("p0", StructuralNodeKind.PARAGRAPH, null, null, null, null, List.of(control));
        StructuralNode body = new StructuralNode("", StructuralNodeKind.BODY, null, null, null, null, List.of(paragraph));
        DocxStructuralGraph graph = new DocxStructuralGraph("fixture", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
        ExtractionVersion extraction = extractionVersionRepository.saveComplete(member.workspaceId(), member.userId(), artifactId, "fixture", graph);
        Template template = templateRepository.createDraft(member.workspaceId(), member.userId(), "Form", artifactId, extraction.id());
        templateRepository.replaceDraftBindings(member.workspaceId(), member.userId(), template.id(), 1, List.of(new FieldDefinition(
                fieldId, FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag(fieldId))));
        return template.id();
    }

    private long insertArtifact(Member member) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
                context.setString(1, String.valueOf(member.userId()));
                context.executeQuery();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO artifact (workspace_id, blob_key, status, byte_count, detected_media_type) "
                            + "VALUES (?, ?, 'READY', 100, 'DOCX') RETURNING id")) {
                statement.setLong(1, member.workspaceId());
                statement.setString(2, "test-blob-" + UUID.randomUUID());
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    long id = resultSet.getLong(1);
                    connection.commit();
                    return id;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** As the application's own login, in one member's name. */
    private void asApi(long userId, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
                    context.setString(1, String.valueOf(userId));
                    context.executeQuery();
                }
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.execute();
                }
            } finally {
                connection.rollback();
            }
        }
    }

    /** As the table owner, which row-level security does not limit: what the constraints alone allow. */
    private static void asOwner(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(DB.jdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private static long countAsOwner(String sql) {
        try (Connection connection = DriverManager.getConnection(DB.jdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
