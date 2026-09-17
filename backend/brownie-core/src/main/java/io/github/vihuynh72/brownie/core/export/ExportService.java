package io.github.vihuynh72.brownie.core.export;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.validation.ValidationManifest;
import io.github.vihuynh72.brownie.core.validation.ValidationManifestNotFoundException;
import io.github.vihuynh72.brownie.core.validation.ValidationRepository;

import java.util.Optional;

/**
 * Approves one already-validated revision for export, then performs the
 * export transaction itself: reopens the exact artifacts named by the
 * approved manifest, confirms each one's own currently-stored hash still
 * matches what that manifest recorded, and issues a durable {@link
 * ExportReceipt}. Never re-renders -- the manifest's own artifacts are
 * already the exact, already-independently-verified bytes a real revision
 * produced (see {@code ValidationService}'s own javadoc); re-rendering
 * here would risk shipping bytes different from what was actually
 * reviewed and approved, defeating the entire point of binding export to
 * one exact manifest.
 */
public class ExportService {

    private final RevisionService revisionService;
    private final ValidationRepository validationRepository;
    private final ArtifactService artifactService;
    private final ExportApprovalRepository exportApprovalRepository;
    private final ExportRepository exportRepository;

    public ExportService(
            RevisionService revisionService,
            ValidationRepository validationRepository,
            ArtifactService artifactService,
            ExportApprovalRepository exportApprovalRepository,
            ExportRepository exportRepository) {
        this.revisionService = revisionService;
        this.validationRepository = validationRepository;
        this.artifactService = artifactService;
        this.exportApprovalRepository = exportApprovalRepository;
        this.exportRepository = exportRepository;
    }

    public ExportApproval approve(long workspaceId, long userId, long documentId, long validationManifestId, ExportFormat format) {
        Document document = requireDocument(workspaceId, userId, documentId);
        ValidationManifest manifest = requireManifest(workspaceId, userId, documentId, validationManifestId);
        requireCurrent(document, manifest);
        requireNoBlockingFindings(manifest);
        return exportApprovalRepository.save(
                workspaceId, userId, documentId, manifest.revisionId(), manifest.templateVersionId(), manifest.id(), format);
    }

    /**
     * Rechecks membership, current approval, and required validation
     * completeness, then reopens each artifact the approved manifest names
     * and confirms it still matches that manifest's own recorded hash
     * before issuing a receipt. The blocking-findings recheck can never
     * actually fire in practice (the bound manifest is immutable, and
     * {@link #approve} already refused it once if it had one), but is
     * kept as a real, explicit, named defense-in-depth step rather than
     * silently relying on that invariant holding forever.
     */
    public ExportReceipt export(long workspaceId, long userId, long documentId) {
        Document document = requireDocument(workspaceId, userId, documentId);
        ExportApproval approval = exportApprovalRepository.findLatest(workspaceId, userId, documentId)
                .orElseThrow(() -> new ExportNotApprovedException(documentId));
        if (approval.revisionId() != document.currentRevisionId()) {
            throw new StaleExportApprovalException(documentId, approval.revisionId(), document.currentRevisionId());
        }
        ValidationManifest manifest = requireManifest(workspaceId, userId, documentId, approval.validationManifestId());
        requireCurrent(document, manifest);
        requireNoBlockingFindings(manifest);

        Artifact docxArtifact = requireMatchingArtifact(workspaceId, userId, manifest.docxArtifactId(), manifest.docxSha256());
        Long pdfArtifactId = null;
        String pdfSha256 = null;
        if (manifest.pdfArtifactId() != null) {
            Artifact pdfArtifact = requireMatchingArtifact(workspaceId, userId, manifest.pdfArtifactId(), manifest.pdfSha256());
            pdfArtifactId = pdfArtifact.id();
            pdfSha256 = pdfArtifact.sha256();
        }

        return exportRepository.save(
                workspaceId, userId, documentId, manifest.revisionId(), manifest.templateVersionId(), approval.id(), manifest.id(),
                docxArtifact.id(), docxArtifact.sha256(), pdfArtifactId, pdfSha256, approval.format());
    }

    public Optional<ExportApproval> findLatestApproval(long workspaceId, long userId, long documentId) {
        return exportApprovalRepository.findLatest(workspaceId, userId, documentId);
    }

    public Optional<ExportReceipt> findLatestReceipt(long workspaceId, long userId, long documentId) {
        return exportRepository.findLatest(workspaceId, userId, documentId);
    }

    private Document requireDocument(long workspaceId, long userId, long documentId) {
        return revisionService.findDocument(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private ValidationManifest requireManifest(long workspaceId, long userId, long documentId, long validationManifestId) {
        return validationRepository.find(workspaceId, userId, documentId, validationManifestId)
                .orElseThrow(() -> ValidationManifestNotFoundException.forManifestId(documentId, validationManifestId));
    }

    private static void requireCurrent(Document document, ValidationManifest manifest) {
        if (manifest.revisionId() != document.currentRevisionId()) {
            throw new StaleExportApprovalException(document.id(), manifest.revisionId(), document.currentRevisionId());
        }
    }

    private static void requireNoBlockingFindings(ValidationManifest manifest) {
        if (manifest.hasUnresolvedBlocking()) {
            throw new BlockingValidationFindingsException(manifest.documentId(), manifest.id());
        }
    }

    private Artifact requireMatchingArtifact(long workspaceId, long userId, long artifactId, String expectedSha256) {
        try (ReadableArtifact readable = artifactService.openContent(workspaceId, userId, artifactId)) {
            Artifact artifact = readable.artifact();
            if (!artifact.sha256().equals(expectedSha256)) {
                throw new ExportArtifactIntegrityException(artifactId, expectedSha256, artifact.sha256());
            }
            return artifact;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to close artifact content stream for artifact " + artifactId + ".", e);
        }
    }
}
