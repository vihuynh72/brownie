package io.github.vihuynh72.brownie.api.persistence.jdbc;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * A derivation is written once, insert-or-return-existing on its recipe
 * key, the way {@code JdbcExtractionVersionRepository} writes an
 * extraction: two first requests racing for the same upload both compute a
 * copy, and both come back with the one row that was committed first. The
 * spots are stored as the fields they become, in the same JSON shape a
 * template version stores its fields in, plus how each was named.
 */
@Repository
class JdbcArtifactDerivationRepository implements ArtifactDerivationRepository {

    private static final String SELECT_COLUMNS = "id, workspace_id, source_artifact_id, output_artifact_id, kind, recipe_version, "
            + "source_format, converter, spot_naming, rules_only_reason, spots, notices, created_by_user_id, created_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcArtifactDerivationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ArtifactDerivation> find(long workspaceId, long userId, long sourceArtifactId, String kind, String recipeVersion) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return select(workspaceId, sourceArtifactId, kind, recipeVersion);
    }

    @Override
    @Transactional
    public ArtifactDerivation insertOrGet(
            long workspaceId,
            long userId,
            long sourceArtifactId,
            long outputArtifactId,
            String kind,
            String recipeVersion,
            String sourceFormat,
            String converter,
            NamingSource spotNaming,
            String rulesOnlyReason,
            List<FillableForm.Spot> spots,
            List<PreparationNotice> notices) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                """
                INSERT INTO artifact_derivation
                    (workspace_id, source_artifact_id, output_artifact_id, kind, recipe_version, source_format, converter,
                     spot_naming, rules_only_reason, spots, notices, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                ON CONFLICT (source_artifact_id, kind, recipe_version) DO NOTHING
                """,
                workspaceId, sourceArtifactId, outputArtifactId, kind, recipeVersion, sourceFormat, converter, spotNaming.name(),
                rulesOnlyReason, write(spots.stream().map(SpotJson::from).toList()), write(notices.stream().map(NoticeJson::from).toList()),
                userId);
        return select(workspaceId, sourceArtifactId, kind, recipeVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "No derivation of artifact " + sourceArtifactId + " is readable right after it was written."));
    }

    private Optional<ArtifactDerivation> select(long workspaceId, long sourceArtifactId, String kind, String recipeVersion) {
        return jdbcTemplate.query(
                        "SELECT " + SELECT_COLUMNS + " FROM artifact_derivation "
                                + "WHERE workspace_id = ? AND source_artifact_id = ? AND kind = ? AND recipe_version = ?",
                        this::mapRow, workspaceId, sourceArtifactId, kind, recipeVersion)
                .stream()
                .findFirst();
    }

    private ArtifactDerivation mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ArtifactDerivation(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("source_artifact_id"),
                rs.getLong("output_artifact_id"),
                rs.getString("kind"),
                rs.getString("recipe_version"),
                rs.getString("source_format"),
                rs.getString("converter"),
                NamingSource.valueOf(rs.getString("spot_naming")),
                rs.getString("rules_only_reason"),
                List.of(read(rs.getString("spots"), SpotJson[].class)).stream().map(SpotJson::toDomain).toList(),
                List.of(read(rs.getString("notices"), NoticeJson[].class)).stream().map(NoticeJson::toDomain).toList(),
                rs.getLong("created_by_user_id"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize a derivation to JSON.", e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize a stored derivation's JSON.", e);
        }
    }

    /**
     * One spot: the field it becomes and how it was named. A Word spot is
     * bound by its control's tag; a PDF upload's spot by one of the form's
     * own fields or by a box on a page, stored as a template version stores
     * it, and left out while null so a Word spot's row is unchanged by it.
     */
    private record SpotJson(
            String fieldId,
            String type,
            String cardinality,
            String requiredness,
            String contentControlTag,
            @JsonInclude(JsonInclude.Include.NON_NULL) String label,
            @JsonInclude(JsonInclude.Include.NON_NULL) String origin,
            @JsonInclude(JsonInclude.Include.NON_NULL) String docxControl,
            @JsonInclude(JsonInclude.Include.NON_NULL) String blankText,
            String namedBy,
            boolean requiredHint,
            String suggestedType,
            String foundAs,
            @JsonInclude(JsonInclude.Include.NON_NULL) String acroFormField,
            @JsonInclude(JsonInclude.Include.NON_NULL) JdbcTemplateRepository.PageBoxJson pageBox) {

        static SpotJson from(FillableForm.Spot spot) {
            FieldDefinition field = spot.field();
            String tag = null;
            String acroFormField = null;
            JdbcTemplateRepository.PageBoxJson pageBox = null;
            switch (field.binding()) {
                case FieldBindingTarget.ContentControlTag(String controlTag) -> tag = controlTag;
                case FieldBindingTarget.AcroFormField(String name) -> acroFormField = name;
                case FieldBindingTarget.PageBox box -> pageBox = JdbcTemplateRepository.PageBoxJson.from(box);
                case FieldBindingTarget.StructuralNode node ->
                        throw new IllegalArgumentException("A found spot is bound by a control's tag, a form field or a box on a page.");
            }
            return new SpotJson(field.fieldId(), field.type().name(), field.cardinality().name(), field.requiredness().name(), tag,
                    field.label(), field.origin() == null ? null : field.origin().name(),
                    field.docxControl() == null ? null : field.docxControl().name(), field.blankText(), spot.namedBy().name(),
                    spot.requiredHint(), spot.suggestedType(), spot.kind(), acroFormField, pageBox);
        }

        FillableForm.Spot toDomain() {
            FieldBindingTarget binding = acroFormField != null ? new FieldBindingTarget.AcroFormField(acroFormField)
                    : pageBox != null ? pageBox.toDomain()
                    : new FieldBindingTarget.ContentControlTag(contentControlTag);
            FieldDefinition field = new FieldDefinition(fieldId, FieldType.valueOf(type), FieldCardinality.valueOf(cardinality),
                    FieldRequiredness.valueOf(requiredness), binding, label,
                    origin == null ? null : SpotOrigin.valueOf(origin), docxControl == null ? null : DocxControlOrigin.valueOf(docxControl),
                    blankText);
            return new FillableForm.Spot(field, NamingSource.valueOf(namedBy), requiredHint, suggestedType, foundAs);
        }
    }

    private record NoticeJson(String code, int count, @JsonInclude(JsonInclude.Include.NON_NULL) String detail) {

        static NoticeJson from(PreparationNotice notice) {
            return new NoticeJson(notice.code(), notice.count(), notice.detail());
        }

        PreparationNotice toDomain() {
            return new PreparationNotice(code, count, detail);
        }
    }
}
