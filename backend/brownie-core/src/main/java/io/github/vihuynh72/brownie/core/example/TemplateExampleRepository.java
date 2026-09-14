package io.github.vihuynh72.brownie.core.example;

import java.util.List;

/** Every method takes workspace and user context explicitly rather than a bare example ID, the same tenant-scoped pattern {@code TemplateRepository} and {@code RuleRepository} follow. */
public interface TemplateExampleRepository {

    TemplateExample attach(
            long workspaceId,
            long userId,
            long templateId,
            long templateVersionId,
            long sourceArtifactId,
            long extractionVersionId,
            ExampleAlignmentStatus alignmentStatus);

    /** Every example attached to one template version, in the order attached -- both alignment statuses, not only {@code ALIGNED}. */
    List<TemplateExample> findByTemplateVersion(long workspaceId, long userId, long templateVersionId);
}
