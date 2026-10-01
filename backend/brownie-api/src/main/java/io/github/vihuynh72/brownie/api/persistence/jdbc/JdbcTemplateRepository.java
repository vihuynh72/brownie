package io.github.vihuynh72.brownie.api.persistence.jdbc;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.PdfFontFamily;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import io.github.vihuynh72.brownie.core.prepare.PreparationNotice;
import io.github.vihuynh72.brownie.core.template.DocxControlOrigin;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateKind;
import io.github.vihuynh72.brownie.core.template.TemplateNotFoundException;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateStatus;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStateConflictException;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@code brownie-core} keeps {@link FieldBindingTarget} a plain sealed
 * interface with no framework dependency of its own (the same
 * framework-free boundary every core package holds), so this repository --
 * not core -- owns turning it into JSON: a private {@code
 * FieldDefinitionJson} row shape with a flat {@code bindingKind}
 * discriminator, converted by hand rather than through Jackson's own
 * polymorphic-type annotations. {@link ObjectMapper} is {@code
 * tools.jackson}, not {@code com.fasterxml.jackson} -- see {@code
 * JdbcExtractionVersionRepository}'s own javadoc for why that distinction
 * matters on this classpath.
 */
@Repository
class JdbcTemplateRepository implements TemplateRepository {

    private static final String TEMPLATE_COLUMNS =
            "id, workspace_id, display_name, status, current_active_version_id, created_at, trashed_at";
    static final String VERSION_COLUMNS =
            "id, workspace_id, template_id, version_number, source_artifact_id, kind, extraction_version_id, pdf_form_extraction_id, status,"
                    + " field_definitions, created_at, activated_at, derived_from_version_id, preparation_notices";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcTemplateRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // The calls with no notices are the interface's, overridden only so they open their transaction through this bean's
    // proxy: a call from the interface's default to the one below would run without one, and lose the tenant context.
    @Override
    @Transactional
    public Template createDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long extractionVersionId) {
        return createDraft(workspaceId, userId, displayName, sourceArtifactId, extractionVersionId, null);
    }

    @Override
    @Transactional
    public Template createPdfDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long pdfFormExtractionId) {
        return createPdfDraft(workspaceId, userId, displayName, sourceArtifactId, pdfFormExtractionId, null);
    }

    @Override
    @Transactional
    public Template createDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long extractionVersionId,
                                List<PreparationNotice> preparationNotices) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        long templateId = jdbcTemplate.queryForObject(
                "INSERT INTO template (workspace_id, display_name) VALUES (?, ?) RETURNING id",
                Long.class,
                workspaceId,
                displayName);
        jdbcTemplate.update(
                """
                INSERT INTO template_version
                    (workspace_id, template_id, version_number, source_artifact_id, extraction_version_id, field_definitions,
                     preparation_notices)
                VALUES (?, ?, 1, ?, ?, '[]'::jsonb, ?::jsonb)
                """,
                workspaceId,
                templateId,
                sourceArtifactId,
                extractionVersionId,
                noticesToJson(preparationNotices));
        return find(workspaceId, userId, templateId)
                .orElseThrow(() -> new IllegalStateException("Template " + templateId + " vanished after creating it."));
    }

    @Override
    @Transactional
    public Template createPdfDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long pdfFormExtractionId,
                                   List<PreparationNotice> preparationNotices) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        long templateId = jdbcTemplate.queryForObject(
                "INSERT INTO template (workspace_id, display_name) VALUES (?, ?) RETURNING id",
                Long.class,
                workspaceId,
                displayName);
        jdbcTemplate.update(
                """
                INSERT INTO template_version
                    (workspace_id, template_id, version_number, source_artifact_id, kind, pdf_form_extraction_id, field_definitions,
                     preparation_notices)
                VALUES (?, ?, 1, ?, 'PDF', ?, '[]'::jsonb, ?::jsonb)
                """,
                workspaceId,
                templateId,
                sourceArtifactId,
                pdfFormExtractionId,
                noticesToJson(preparationNotices));
        return find(workspaceId, userId, templateId)
                .orElseThrow(() -> new IllegalStateException("Template " + templateId + " vanished after creating it."));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Template> find(long workspaceId, long userId, long templateId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + TEMPLATE_COLUMNS + " FROM template WHERE workspace_id = ? AND id = ?",
                        this::mapTemplate,
                        workspaceId,
                        templateId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Template> findAll(long workspaceId, long userId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + TEMPLATE_COLUMNS + " FROM template WHERE workspace_id = ? ORDER BY created_at, id",
                this::mapTemplate,
                workspaceId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Template> findTrashed(long workspaceId, long userId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + TEMPLATE_COLUMNS + " FROM template WHERE workspace_id = ? AND trashed_at IS NOT NULL"
                        + " ORDER BY trashed_at DESC, id DESC",
                this::mapTemplate,
                workspaceId);
    }

    /**
     * Sets the time only while it is unset, so trashing again keeps the time
     * the template first went to the Trash Bin and the order the Trash Bin
     * lists it in. Either way the row is read back, which also tells a
     * template already in the Trash Bin apart from one that is not there.
     */
    @Override
    @Transactional
    public Template trash(long workspaceId, long userId, long templateId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                "UPDATE template SET trashed_at = now() WHERE workspace_id = ? AND id = ? AND trashed_at IS NULL",
                workspaceId,
                templateId);
        return find(workspaceId, userId, templateId).orElseThrow(() -> new TemplateNotFoundException(templateId));
    }

    @Override
    @Transactional
    public Template restore(long workspaceId, long userId, long templateId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                "UPDATE template SET trashed_at = NULL WHERE workspace_id = ? AND id = ? AND trashed_at IS NOT NULL",
                workspaceId,
                templateId);
        return find(workspaceId, userId, templateId).orElseThrow(() -> new TemplateNotFoundException(templateId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TemplateVersion> findDraftVersion(long workspaceId, long userId, long templateId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + VERSION_COLUMNS + " FROM template_version WHERE workspace_id = ? AND template_id = ? AND status = 'DRAFT'",
                        this::mapVersion,
                        workspaceId,
                        templateId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TemplateVersion> findVersion(long workspaceId, long userId, long templateId, long versionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + VERSION_COLUMNS + " FROM template_version WHERE workspace_id = ? AND template_id = ? AND id = ?",
                        this::mapVersion,
                        workspaceId,
                        templateId,
                        versionId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public TemplateVersion replaceDraftBindings(
            long workspaceId, long userId, long templateId, int expectedVersionNumber, List<FieldDefinition> fieldDefinitions) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        List<TemplateVersion> updated = jdbcTemplate.query(
                """
                UPDATE template_version
                SET field_definitions = ?::jsonb, version_number = version_number + 1
                WHERE workspace_id = ? AND template_id = ? AND version_number = ? AND status = 'DRAFT'
                RETURNING """
                        + " " + VERSION_COLUMNS,
                this::mapVersion,
                toJson(fieldDefinitions),
                workspaceId,
                templateId,
                expectedVersionNumber);
        return requireUpdated(updated, workspaceId, userId, templateId, expectedVersionNumber);
    }

    @Override
    @Transactional
    public TemplateVersion activate(long workspaceId, long userId, long templateId, int expectedVersionNumber) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        List<TemplateVersion> updated = jdbcTemplate.query(
                """
                UPDATE template_version
                SET status = 'ACTIVATED', activated_at = now()
                WHERE workspace_id = ? AND template_id = ? AND version_number = ? AND status = 'DRAFT'
                RETURNING """
                        + " " + VERSION_COLUMNS,
                this::mapVersion,
                workspaceId,
                templateId,
                expectedVersionNumber);
        TemplateVersion activated = requireUpdated(updated, workspaceId, userId, templateId, expectedVersionNumber);
        jdbcTemplate.update(
                "UPDATE template SET status = 'ACTIVE', current_active_version_id = ? WHERE workspace_id = ? AND id = ?",
                activated.id(),
                workspaceId,
                templateId);
        return activated;
    }

    /** Both write methods above share this: an empty result means the atomic guard failed, and the caller needs to know exactly why. */
    private TemplateVersion requireUpdated(
            List<TemplateVersion> updated, long workspaceId, long userId, long templateId, int expectedVersionNumber) {
        if (!updated.isEmpty()) {
            return updated.get(0);
        }
        if (find(workspaceId, userId, templateId).isEmpty()) {
            throw new TemplateNotFoundException(templateId);
        }
        Optional<TemplateVersion> currentDraft = findDraftVersion(workspaceId, userId, templateId);
        if (currentDraft.isEmpty()) {
            throw new TemplateVersionStateConflictException("Template " + templateId + " has no open draft version.");
        }
        throw new TemplateVersionStateConflictException(
                "Template " + templateId + " draft is at version " + currentDraft.get().versionNumber()
                        + ", not the expected " + expectedVersionNumber + ".");
    }

    private Template mapTemplate(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        // wasNull() reflects only the most recent get*() call, so it must
        // be read into a local right here -- any rs.get*() call for
        // another column in between (even as a later constructor argument
        // evaluated after this one) would silently overwrite it first.
        long activeVersionId = rs.getLong("current_active_version_id");
        boolean noActiveVersion = rs.wasNull();
        return new Template(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getString("display_name"),
                TemplateStatus.valueOf(rs.getString("status")),
                noActiveVersion ? null : activeVersionId,
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("trashed_at", OffsetDateTime.class));
    }

    TemplateVersion mapVersion(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long derivedFromVersionId = rs.getLong("derived_from_version_id");
        boolean notDerived = rs.wasNull();
        return new TemplateVersion(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("template_id"),
                rs.getInt("version_number"),
                rs.getLong("source_artifact_id"),
                TemplateKind.valueOf(rs.getString("kind")),
                rs.getObject("extraction_version_id", Long.class),
                rs.getObject("pdf_form_extraction_id", Long.class),
                TemplateVersionStatus.valueOf(rs.getString("status")),
                fromJson(rs.getString("field_definitions")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("activated_at", OffsetDateTime.class),
                notDerived ? null : derivedFromVersionId,
                noticesFromJson(rs.getString("preparation_notices")));
    }

    /** The notices as stored: a JSON array of {code, count, detail}, detail left out while null; SQL NULL for none kept. */
    String noticesToJson(List<PreparationNotice> notices) {
        if (notices == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(notices.stream().map(NoticeJson::from).toList());
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize preparation notices to JSON.", e);
        }
    }

    private List<PreparationNotice> noticesFromJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return List.of(objectMapper.readValue(json, NoticeJson[].class)).stream().map(NoticeJson::toDomain).toList();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize stored preparation notices JSON.", e);
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

    String toJson(List<FieldDefinition> fieldDefinitions) {
        try {
            return objectMapper.writeValueAsString(fieldDefinitions.stream().map(FieldDefinitionJson::from).toList());
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize field definitions to JSON.", e);
        }
    }

    List<FieldDefinition> fromJson(String json) {
        try {
            FieldDefinitionJson[] rows = objectMapper.readValue(json, FieldDefinitionJson[].class);
            return List.of(rows).stream().map(FieldDefinitionJson::toDomain).toList();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize stored field definitions JSON.", e);
        }
    }

    /**
     * The JSON row shape for one {@link FieldDefinition}; only one of {@code contentControlTag} or ({@code structuralNodePart}, {@code structuralNodeId}) is set, chosen by {@code bindingKind}.
     * The last four properties came later and are left out entirely while null, unlike the binding's own, so a field without
     * them is stored exactly as every field was before they existed, and a row stored then reads back with all four null.
     * A PDF binding ({@code ACROFORM_FIELD}, {@code PAGE_BOX}) came later still: its {@code acroFormField} or {@code pageBox}
     * is likewise left out while null, so a Word field's row is unchanged by it.
     */
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
            @JsonInclude(JsonInclude.Include.NON_NULL) PageBoxJson pageBox) {

        static FieldDefinitionJson from(FieldDefinition field) {
            String origin = field.origin() == null ? null : field.origin().name();
            String docxControl = field.docxControl() == null ? null : field.docxControl().name();
            return switch (field.binding()) {
                case FieldBindingTarget.ContentControlTag(String tag) -> new FieldDefinitionJson(
                        field.fieldId(), field.type().name(), field.cardinality().name(), field.requiredness().name(),
                        "CONTENT_CONTROL_TAG", tag, null, null, field.label(), origin, docxControl, field.blankText(), null, null);
                case FieldBindingTarget.StructuralNode(DocumentPartKind part, String nodeId) -> new FieldDefinitionJson(
                        field.fieldId(), field.type().name(), field.cardinality().name(), field.requiredness().name(),
                        "STRUCTURAL_NODE", null, part.name(), nodeId, field.label(), origin, docxControl, field.blankText(), null, null);
                case FieldBindingTarget.AcroFormField(String name) -> new FieldDefinitionJson(
                        field.fieldId(), field.type().name(), field.cardinality().name(), field.requiredness().name(),
                        "ACROFORM_FIELD", null, null, null, field.label(), origin, docxControl, field.blankText(), name, null);
                case FieldBindingTarget.PageBox box -> new FieldDefinitionJson(
                        field.fieldId(), field.type().name(), field.cardinality().name(), field.requiredness().name(),
                        "PAGE_BOX", null, null, null, field.label(), origin, docxControl, field.blankText(), null, PageBoxJson.from(box));
            };
        }

        FieldDefinition toDomain() {
            FieldBindingTarget binding = switch (bindingKind) {
                case "CONTENT_CONTROL_TAG" -> new FieldBindingTarget.ContentControlTag(contentControlTag);
                case "STRUCTURAL_NODE" -> new FieldBindingTarget.StructuralNode(DocumentPartKind.valueOf(structuralNodePart), structuralNodeId);
                case "ACROFORM_FIELD" -> new FieldBindingTarget.AcroFormField(acroFormField);
                case "PAGE_BOX" -> pageBox.toDomain();
                default -> throw new IllegalStateException("Unknown stored binding kind: " + bindingKind);
            };
            return new FieldDefinition(
                    fieldId, FieldType.valueOf(type), FieldCardinality.valueOf(cardinality), FieldRequiredness.valueOf(requiredness), binding,
                    label,
                    origin == null ? null : SpotOrigin.valueOf(origin),
                    docxControl == null ? null : DocxControlOrigin.valueOf(docxControl),
                    blankText);
        }
    }

    /**
     * A {@link FieldBindingTarget.PageBox} as stored: the box in points, the text style by its family's name, and the overflow choice.
     * A PDF upload's kept spots store their boxes the same way ({@code JdbcArtifactDerivationRepository}).
     */
    record PageBoxJson(
            int page, double x, double y, double width, double height, String font, boolean bold, double sizePt, boolean multiline,
            String overflow) {

        static PageBoxJson from(FieldBindingTarget.PageBox box) {
            return new PageBoxJson(
                    box.page(), box.x(), box.y(), box.width(), box.height(), box.style().family().name(), box.style().bold(),
                    box.style().sizePt(), box.multiline(), box.overflow().name());
        }

        FieldBindingTarget.PageBox toDomain() {
            return new FieldBindingTarget.PageBox(
                    page, x, y, width, height, new PdfTextStyle(PdfFontFamily.valueOf(font), bold, sizePt), multiline,
                    PdfOverflowPolicy.valueOf(overflow));
        }
    }
}
