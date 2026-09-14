package io.github.vihuynh72.brownie.core.example;

import java.time.OffsetDateTime;

/**
 * One completed document offered as an example of one specific template
 * draft version -- immutable once attached, the same way a {@code
 * TemplateVersion} row is immutable once activated. {@code
 * templateVersionId} pins this example's own {@code alignmentStatus} to the
 * exact field definitions it was actually compared against: if the draft is
 * later replaced with different bindings, this example's own alignment does
 * not silently follow along. Re-attaching the same source artifact against
 * the new draft (a fresh {@link TemplateExample} row) is how a person gets
 * a fresh comparison, not a hidden re-alignment of this one.
 */
public record TemplateExample(
        long id,
        long workspaceId,
        long templateId,
        long templateVersionId,
        long sourceArtifactId,
        long extractionVersionId,
        ExampleAlignmentStatus alignmentStatus,
        OffsetDateTime createdAt) {
}
