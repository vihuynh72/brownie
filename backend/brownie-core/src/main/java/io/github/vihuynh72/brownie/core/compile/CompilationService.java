package io.github.vihuynh72.brownie.core.compile;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStatus;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.revision.DocumentRevision;
import io.github.vihuynh72.brownie.core.revision.DocumentTemplateVersionUnavailableException;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Compiles one exact, already-persisted document revision into a filled
 * DOCX and a rendered PDF, using only its template version's own approved
 * bindings, then independently verifies both artifacts and records the
 * result. Runs no AI, reads no unapproved source, and freezes exactly the
 * revision named -- a later edit produces a new revision and a new,
 * separate compilation rather than mutating this one. Depends only on the
 * interfaces above, so it has no framework, POI, or renderer dependency of
 * its own; real implementations are supplied by whichever module wires
 * this up, the same dependency-inversion shape {@code
 * DocumentExtractionService} already establishes.
 */
public class CompilationService {

    private final RevisionService revisionService;
    private final TemplateRepository templateRepository;
    private final ArtifactService artifactService;
    private final TemplateFiller templateFiller;
    private final DocumentRenderer documentRenderer;
    private final CompilationRepository compilationRepository;

    public CompilationService(
            RevisionService revisionService,
            TemplateRepository templateRepository,
            ArtifactService artifactService,
            TemplateFiller templateFiller,
            DocumentRenderer documentRenderer,
            CompilationRepository compilationRepository) {
        this.revisionService = revisionService;
        this.templateRepository = templateRepository;
        this.artifactService = artifactService;
        this.templateFiller = templateFiller;
        this.documentRenderer = documentRenderer;
        this.compilationRepository = compilationRepository;
    }

    public CompilationManifest compile(long workspaceId, long userId, long documentId, long revisionId) {
        Document document = revisionService.findDocument(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        DocumentRevision revision = revisionService.findRevision(workspaceId, userId, documentId, revisionId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        TemplateVersion templateVersion = templateRepository
                .findVersion(workspaceId, userId, document.templateId(), document.templateVersionId())
                .orElseThrow(() -> new DocumentTemplateVersionUnavailableException(document.templateId(), document.templateVersionId()));
        if (templateVersion.status() != TemplateVersionStatus.ACTIVATED) {
            throw new DocumentTemplateVersionUnavailableException(document.templateId(), document.templateVersionId());
        }

        byte[] templateBytes = readTemplateBytes(workspaceId, userId, templateVersion.sourceArtifactId());
        FilledDocument filled = templateFiller.fill(templateBytes, templateVersion.fieldDefinitions(), revision.content());
        Artifact docxArtifact = storeGenerated(
                workspaceId, userId, "minutes-" + documentId + "-r" + revision.revisionNumber() + ".docx", filled.docxBytes());

        RenderedPdf rendered = documentRenderer.renderToPdf(filled.docxBytes());
        Artifact pdfArtifact = storeGenerated(
                workspaceId, userId, "minutes-" + documentId + "-r" + revision.revisionNumber() + ".pdf", rendered.pdfBytes());

        var findings = IntegrityChecker.check(filled.intendedText(), filled.reopenedBodyText(), rendered.extractedText());

        return compilationRepository.save(
                workspaceId,
                userId,
                documentId,
                revisionId,
                document.templateId(),
                templateVersion.id(),
                docxArtifact.id(),
                docxArtifact.sha256(),
                pdfArtifact.id(),
                pdfArtifact.sha256(),
                rendered.rendererVersion(),
                findings);
    }

    public java.util.Optional<CompilationManifest> findLatest(long workspaceId, long userId, long documentId, long revisionId) {
        return compilationRepository.findLatest(workspaceId, userId, documentId, revisionId);
    }

    private byte[] readTemplateBytes(long workspaceId, long userId, long artifactId) {
        ReadableArtifact readable = artifactService.openContent(workspaceId, userId, artifactId);
        try (readable) {
            return readable.content().readAllBytes();
        } catch (IOException e) {
            throw new TemplateFillException(
                    TemplateFillProblemReason.UNREADABLE_TEMPLATE,
                    "Failed to read template source artifact " + artifactId + " for compilation.", e);
        }
    }

    /**
     * Stores server-generated bytes through the same allocate/receive/scan
     * pipeline a human upload uses -- a compiled artifact is scanned and
     * gated exactly like any other, rather than granted a silent
     * exception to that rule because Brownie produced it.
     */
    private Artifact storeGenerated(long workspaceId, long userId, String filename, byte[] bytes) {
        Artifact allocated = artifactService.initiateUpload(workspaceId, userId, filename);
        try (InputStream content = new ByteArrayInputStream(bytes)) {
            artifactService.receiveContent(workspaceId, userId, allocated.id(), content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stage generated artifact bytes in memory.", e);
        }
        Artifact finalized = artifactService.finalizeUpload(workspaceId, userId, allocated.id());
        if (finalized.status() != ArtifactStatus.READY) {
            throw new GeneratedArtifactUnavailableException(
                    "Generated artifact " + finalized.id() + " did not reach READY (status " + finalized.status() + ").");
        }
        return finalized;
    }
}
