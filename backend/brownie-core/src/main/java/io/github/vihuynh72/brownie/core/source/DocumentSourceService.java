package io.github.vihuynh72.brownie.core.source;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactRepository;
import io.github.vihuynh72.brownie.core.evidence.ResolvedEvidence;
import io.github.vihuynh72.brownie.core.evidence.SourceSpan;
import io.github.vihuynh72.brownie.core.evidence.SourceSpanNotFoundException;
import io.github.vihuynh72.brownie.core.evidence.SourceSpanRepository;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.revision.RevisionService;

import java.util.ArrayList;
import java.util.List;

/**
 * Links sources to documents. Attaching goes through {@link
 * SourceService#attachSnapshot}, so an artifact attached to a document
 * gets the same one workspace-level snapshot it would get anywhere else
 * (and its extraction is triggered the same way); this service only adds
 * the document's own link to it. Reading a cited excerpt goes the other
 * way: a span is only shown through a document whose sources include the
 * snapshot the span cites, so a span id alone never opens another
 * document's evidence. Depends on no framework of its own.
 */
public class DocumentSourceService {

    private final RevisionService revisionService;
    private final SourceService sourceService;
    private final SourceSnapshotRepository sourceSnapshotRepository;
    private final DocumentSourceRepository documentSourceRepository;
    private final SourceSpanRepository sourceSpanRepository;
    private final ArtifactRepository artifactRepository;

    public DocumentSourceService(
            RevisionService revisionService,
            SourceService sourceService,
            SourceSnapshotRepository sourceSnapshotRepository,
            DocumentSourceRepository documentSourceRepository,
            SourceSpanRepository sourceSpanRepository,
            ArtifactRepository artifactRepository) {
        this.revisionService = revisionService;
        this.sourceService = sourceService;
        this.sourceSnapshotRepository = sourceSnapshotRepository;
        this.documentSourceRepository = documentSourceRepository;
        this.sourceSpanRepository = sourceSpanRepository;
        this.artifactRepository = artifactRepository;
    }

    /** Attaches a READY artifact to a document as a source, or returns the link that already does. The document must exist in this workspace. */
    public AttachedSource attach(long workspaceId, long userId, long documentId, long artifactId) {
        requireDocument(workspaceId, userId, documentId);
        SourceSnapshot snapshot = sourceService.attachSnapshot(workspaceId, userId, artifactId);
        DocumentSource link = documentSourceRepository.link(workspaceId, userId, documentId, snapshot.id());
        return new AttachedSource(link, snapshot, displayFilenameOf(workspaceId, userId, snapshot.artifactId()));
    }

    /** Every source attached to a document, most recently attached first. The document must exist in this workspace. */
    public List<AttachedSource> list(long workspaceId, long userId, long documentId) {
        requireDocument(workspaceId, userId, documentId);
        List<AttachedSource> attached = new ArrayList<>();
        for (DocumentSource link : documentSourceRepository.findForDocument(workspaceId, userId, documentId)) {
            SourceSnapshot snapshot = sourceSnapshotRepository
                    .find(workspaceId, userId, link.sourceSnapshotId())
                    .orElseThrow(() -> new SourceSnapshotNotFoundException(link.sourceSnapshotId()));
            attached.add(new AttachedSource(link, snapshot, displayFilenameOf(workspaceId, userId, snapshot.artifactId())));
        }
        return attached;
    }

    /**
     * The excerpt one of this document's values cites. The span must exist
     * in this workspace and its snapshot must be one of this document's
     * sources; otherwise it is reported as not found, whichever of the two
     * failed, so a guessed span id learns nothing.
     */
    public DocumentEvidence excerpt(long workspaceId, long userId, long documentId, long spanId) {
        requireDocument(workspaceId, userId, documentId);
        SourceSpan span = sourceSpanRepository.find(workspaceId, userId, spanId).orElseThrow(() -> new SourceSpanNotFoundException(spanId));
        documentSourceRepository
                .find(workspaceId, userId, documentId, span.sourceSnapshotId())
                .orElseThrow(() -> new SourceSpanNotFoundException(spanId));
        ResolvedEvidence resolved = sourceService.resolveSpan(workspaceId, userId, spanId);
        SourceSnapshot snapshot = sourceSnapshotRepository
                .find(workspaceId, userId, span.sourceSnapshotId())
                .orElseThrow(() -> new SourceSnapshotNotFoundException(span.sourceSnapshotId()));
        return new DocumentEvidence(resolved.span(), resolved.excerptText(), snapshot, displayFilenameOf(workspaceId, userId, snapshot.artifactId()));
    }

    private void requireDocument(long workspaceId, long userId, long documentId) {
        revisionService.findDocument(workspaceId, userId, documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private String displayFilenameOf(long workspaceId, long userId, long artifactId) {
        return artifactRepository.find(workspaceId, userId, artifactId).map(Artifact::displayFilename).orElse(null);
    }
}
