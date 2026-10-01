package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.prepare.PreparationNotice;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One template's field definitions and bindings at a specific, sequential
 * {@code versionNumber}. While DRAFT, {@code fieldDefinitions} can still be
 * replaced (a new call must supply this exact {@code versionNumber} back,
 * an optimistic-concurrency guard against two editors overwriting each
 * other) -- see {@code TemplateRepository#replaceDraftBindings}. Once
 * ACTIVATED, this row never changes again; editing the template further
 * means creating a new draft version, not touching this one.
 *
 * <p>{@code extractionVersionId} pins every binding in {@code
 * fieldDefinitions} to the exact DOCX structural graph they were validated
 * against ({@link TemplateBindingValidator}) -- a binding is only ever
 * checked against, and only ever means, that one immutable extraction, not
 * whatever the source artifact's latest extraction happens to be later. A
 * {@link TemplateKind#PDF} version has no Word graph: {@code
 * pdfFormExtractionId} pins it to its PDF form reading instead, and
 * exactly one of the two is set, the one of its {@code kind}.
 *
 * <p>{@code derivedFromVersionId} names the version this one was made from
 * when a person corrected the fill spots of an open document (one added,
 * renamed or taken away), and is null for a version made from a file
 * directly. A made version is activated as it is made: it has no draft.
 *
 * <p>{@code preparationNotices} is what the person was told when the file
 * the template was made from was made ready to fill (see {@link
 * PreparationNotice}), kept so it can be read again later; a version made
 * from another carries its notices on. It is null for a version made
 * before notices were kept, and empty when the upload had nothing to say.
 */
public record TemplateVersion(
        long id,
        long workspaceId,
        long templateId,
        int versionNumber,
        long sourceArtifactId,
        TemplateKind kind,
        Long extractionVersionId,
        Long pdfFormExtractionId,
        TemplateVersionStatus status,
        List<FieldDefinition> fieldDefinitions,
        OffsetDateTime createdAt,
        OffsetDateTime activatedAt,
        Long derivedFromVersionId,
        List<PreparationNotice> preparationNotices) {

    public TemplateVersion {
        if (kind == null) {
            throw new IllegalArgumentException("A template version needs a kind.");
        }
        if (kind == TemplateKind.DOCX ? extractionVersionId == null || pdfFormExtractionId != null
                : pdfFormExtractionId == null || extractionVersionId != null) {
            throw new IllegalArgumentException("A " + kind + " template version is pinned to the reading of its own kind only.");
        }
        preparationNotices = preparationNotices == null ? null : List.copyOf(preparationNotices);
    }

    /** A version of either kind with no notices kept from the upload it was made from. */
    public TemplateVersion(
            long id,
            long workspaceId,
            long templateId,
            int versionNumber,
            long sourceArtifactId,
            TemplateKind kind,
            Long extractionVersionId,
            Long pdfFormExtractionId,
            TemplateVersionStatus status,
            List<FieldDefinition> fieldDefinitions,
            OffsetDateTime createdAt,
            OffsetDateTime activatedAt,
            Long derivedFromVersionId) {
        this(id, workspaceId, templateId, versionNumber, sourceArtifactId, kind, extractionVersionId, pdfFormExtractionId, status,
                fieldDefinitions, createdAt, activatedAt, derivedFromVersionId, null);
    }

    /** A version of either kind made from its file directly, not from another version. */
    public TemplateVersion(
            long id,
            long workspaceId,
            long templateId,
            int versionNumber,
            long sourceArtifactId,
            TemplateKind kind,
            Long extractionVersionId,
            Long pdfFormExtractionId,
            TemplateVersionStatus status,
            List<FieldDefinition> fieldDefinitions,
            OffsetDateTime createdAt,
            OffsetDateTime activatedAt) {
        this(id, workspaceId, templateId, versionNumber, sourceArtifactId, kind, extractionVersionId, pdfFormExtractionId, status,
                fieldDefinitions, createdAt, activatedAt, null);
    }

    /** A Word template version made from its file directly, pinned to its structural graph: every version made before PDF templates existed. */
    public TemplateVersion(
            long id,
            long workspaceId,
            long templateId,
            int versionNumber,
            long sourceArtifactId,
            long extractionVersionId,
            TemplateVersionStatus status,
            List<FieldDefinition> fieldDefinitions,
            OffsetDateTime createdAt,
            OffsetDateTime activatedAt) {
        this(id, workspaceId, templateId, versionNumber, sourceArtifactId, TemplateKind.DOCX, extractionVersionId, null, status,
                fieldDefinitions, createdAt, activatedAt, null);
    }
}
