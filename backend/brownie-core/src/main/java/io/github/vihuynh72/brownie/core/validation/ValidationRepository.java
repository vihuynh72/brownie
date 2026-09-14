package io.github.vihuynh72.brownie.core.validation;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped persistence for a document revision's validation manifests. */
public interface ValidationRepository {

    ValidationManifest save(
            long workspaceId,
            long userId,
            long documentId,
            long revisionId,
            long templateId,
            long templateVersionId,
            long docxArtifactId,
            String docxSha256,
            Long pdfArtifactId,
            String pdfSha256,
            List<ValidationFinding> findings);

    Optional<ValidationManifest> find(long workspaceId, long userId, long documentId, long manifestId);

    Optional<ValidationManifest> findLatest(long workspaceId, long userId, long documentId, long revisionId);
}
