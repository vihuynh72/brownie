package io.github.vihuynh72.brownie.api.persistence.jdbc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.prepare.FillableForm;
import io.github.vihuynh72.brownie.core.prepare.NamingSource;
import io.github.vihuynh72.brownie.core.prepare.PrepareCacheRepository;
import io.github.vihuynh72.brownie.core.prepare.PreparationNotice;
import io.github.vihuynh72.brownie.core.template.DocxControlOrigin;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** {@link PrepareCacheRepository} on {@code prepare_cache}; spots and notices round-trip as JSON. */
@Repository
class JdbcPrepareCacheRepository implements PrepareCacheRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RowMapper<CachedPreparation> rowMapper = (rs, n) -> new CachedPreparation(
            rs.getString("source_sha256"),
            rs.getString("recipe_version"),
            rs.getString("source_format"),
            rs.getBytes("output_bytes"),
            readSpots(rs.getString("spots")),
            readNotices(rs.getString("notices")),
            rs.getString("converter"),
            NamingSource.valueOf(rs.getString("spot_naming")),
            rs.getString("rules_only_reason"));

    JdbcPrepareCacheRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CachedPreparation> find(String sourceSha256, String recipeVersion) {
        List<CachedPreparation> results = jdbcTemplate.query(
                "SELECT source_sha256, recipe_version, source_format, output_bytes, spots, notices, converter, "
                        + "spot_naming, rules_only_reason FROM prepare_cache "
                        + "WHERE source_sha256 = ? AND recipe_version = ?",
                rowMapper,
                sourceSha256, recipeVersion);
        return results.stream().findFirst();
    }

    @Override
    public void remember(CachedPreparation prepared) {
        jdbcTemplate.update(
                """
                INSERT INTO prepare_cache
                    (source_sha256, recipe_version, source_format, output_bytes, spots, notices, converter,
                     spot_naming, rules_only_reason)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                ON CONFLICT (source_sha256, recipe_version) DO NOTHING
                """,
                prepared.sourceSha256(), prepared.recipeVersion(), prepared.sourceFormat(), prepared.outputBytes(),
                writeSpots(prepared.spots()), writeNotices(prepared.notices()), prepared.converter(),
                prepared.spotNaming().name(), prepared.rulesOnlyReason());
    }

    // ---- JSON round-trips -----------------------------------------------------------------------------------

    private List<FillableForm.Spot> readSpots(String json) {
        try {
            SpotJson[] rows = objectMapper.readValue(json, SpotJson[].class);
            return List.of(rows).stream().map(SpotJson::toDomain).toList();
        } catch (Exception e) {
            throw new IllegalStateException("A cached preparation's spots could not be read.", e);
        }
    }

    private String writeSpots(List<FillableForm.Spot> spots) {
        try {
            return objectMapper.writeValueAsString(spots.stream().map(SpotJson::from).toList());
        } catch (Exception e) {
            throw new IllegalStateException("A cached preparation's spots could not be written.", e);
        }
    }

    private List<PreparationNotice> readNotices(String json) {
        try {
            NoticeJson[] rows = objectMapper.readValue(json, NoticeJson[].class);
            return List.of(rows).stream().map(r -> new PreparationNotice(r.code(), r.count(), r.detail())).toList();
        } catch (Exception e) {
            throw new IllegalStateException("A cached preparation's notices could not be read.", e);
        }
    }

    private String writeNotices(List<PreparationNotice> notices) {
        try {
            return objectMapper.writeValueAsString(
                    notices.stream().map(r -> new NoticeJson(r.code(), r.count(), r.detail())).toList());
        } catch (Exception e) {
            throw new IllegalStateException("A cached preparation's notices could not be written.", e);
        }
    }

    private record SpotJson(
            FieldDefinitionJson field,
            String namedBy,
            boolean requiredHint,
            @JsonInclude(JsonInclude.Include.NON_NULL) String suggestedType,
            @JsonInclude(JsonInclude.Include.NON_NULL) String kind) {

        static SpotJson from(FillableForm.Spot spot) {
            return new SpotJson(
                    FieldDefinitionJson.from(spot.field()), spot.namedBy().name(), spot.requiredHint(),
                    spot.suggestedType(), spot.kind());
        }

        FillableForm.Spot toDomain() {
            return new FillableForm.Spot(
                    field.toDomain(), NamingSource.valueOf(namedBy), requiredHint, suggestedType, kind);
        }
    }

    private record NoticeJson(String code, int count, String detail) {
    }

    /** The same JSON row shape {@link JdbcTemplateRepository} keeps one {@link FieldDefinition} in. */
    private record FieldDefinitionJson(
            String fieldId,
            String type,
            String cardinality,
            String requiredness,
            String bindingKind,
            String contentControlTag,
            String structuralNodePart,
            String structuralNodeId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String label,
            @JsonInclude(JsonInclude.Include.NON_NULL) String origin,
            @JsonInclude(JsonInclude.Include.NON_NULL) String docxControl,
            @JsonInclude(JsonInclude.Include.NON_NULL) String blankText,
            @JsonInclude(JsonInclude.Include.NON_NULL) String acroFormField,
            @JsonInclude(JsonInclude.Include.NON_NULL) JdbcTemplateRepository.PageBoxJson pageBox) {

        static FieldDefinitionJson from(FieldDefinition f) {
            String origin = f.origin() == null ? null : f.origin().name();
            String docxControl = f.docxControl() == null ? null : f.docxControl().name();
            return switch (f.binding()) {
                case FieldBindingTarget.ContentControlTag(String tag) -> new FieldDefinitionJson(
                        f.fieldId(), f.type().name(), f.cardinality().name(), f.requiredness().name(),
                        "CONTENT_CONTROL_TAG", tag, null, null, f.label(), origin, docxControl, f.blankText(), null, null);
                case FieldBindingTarget.StructuralNode(DocumentPartKind part, String nodeId) -> new FieldDefinitionJson(
                        f.fieldId(), f.type().name(), f.cardinality().name(), f.requiredness().name(),
                        "STRUCTURAL_NODE", null, part.name(), nodeId, f.label(), origin, docxControl, f.blankText(), null, null);
                case FieldBindingTarget.AcroFormField(String name) -> new FieldDefinitionJson(
                        f.fieldId(), f.type().name(), f.cardinality().name(), f.requiredness().name(),
                        "ACROFORM_FIELD", null, null, null, f.label(), origin, docxControl, f.blankText(), name, null);
                case FieldBindingTarget.PageBox box -> new FieldDefinitionJson(
                        f.fieldId(), f.type().name(), f.cardinality().name(), f.requiredness().name(),
                        "PAGE_BOX", null, null, null, f.label(), origin, docxControl, f.blankText(), null,
                        JdbcTemplateRepository.PageBoxJson.from(box));
            };
        }

        FieldDefinition toDomain() {
            FieldBindingTarget binding = switch (bindingKind) {
                case "CONTENT_CONTROL_TAG" -> new FieldBindingTarget.ContentControlTag(contentControlTag);
                case "STRUCTURAL_NODE" -> new FieldBindingTarget.StructuralNode(
                        DocumentPartKind.valueOf(structuralNodePart), structuralNodeId);
                case "ACROFORM_FIELD" -> new FieldBindingTarget.AcroFormField(acroFormField);
                case "PAGE_BOX" -> pageBox.toDomain();
                default -> throw new IllegalStateException("Unknown stored binding kind: " + bindingKind);
            };
            return new FieldDefinition(
                    fieldId, FieldType.valueOf(type), FieldCardinality.valueOf(cardinality),
                    FieldRequiredness.valueOf(requiredness), binding, label,
                    origin == null ? null : SpotOrigin.valueOf(origin),
                    docxControl == null ? null : DocxControlOrigin.valueOf(docxControl),
                    blankText);
        }
    }
}
