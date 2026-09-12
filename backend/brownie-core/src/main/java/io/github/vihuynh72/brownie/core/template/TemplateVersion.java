package io.github.vihuynh72.brownie.core.template;

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
 * whatever the source artifact's latest extraction happens to be later.
 */
public record TemplateVersion(
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
}
