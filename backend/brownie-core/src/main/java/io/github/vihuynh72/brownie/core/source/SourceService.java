package io.github.vihuynh72.brownie.core.source;

import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PdfExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfPage;
import io.github.vihuynh72.brownie.core.document.PdfStructuralGraph;
import io.github.vihuynh72.brownie.core.document.PdfTextLine;
import io.github.vihuynh72.brownie.core.document.PlainTextExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PlainTextExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PlainTextStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.evidence.EvidenceLocator;
import io.github.vihuynh72.brownie.core.evidence.InvalidEvidenceLocatorException;
import io.github.vihuynh72.brownie.core.evidence.ResolvedEvidence;
import io.github.vihuynh72.brownie.core.evidence.SourceSpan;
import io.github.vihuynh72.brownie.core.evidence.SourceSpanNotFoundException;
import io.github.vihuynh72.brownie.core.evidence.SourceSpanRepository;
import io.github.vihuynh72.brownie.core.text.CodePoints;
import io.github.vihuynh72.brownie.core.text.NormalizedText;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Attaches artifacts as sources and creates/resolves citations into their
 * already-extracted content. Depends on {@link ArtifactService} (indirectly,
 * through {@link DocumentExtractionService}, which already enforces READY
 * status before extracting), {@link DocumentExtractionService} itself, the
 * three format-specific extraction-version repositories (needed directly
 * here, not only through {@code DocumentExtractionService}, because
 * resolving an existing span must re-read the *exact* extraction version it
 * was created against -- never "whichever is latest now"), and the
 * source/evidence repositories above. No framework or infrastructure
 * dependency of its own.
 *
 * <p>Creating a span always resolves against the artifact's *current*
 * extraction (triggering it if not already run); resolving an existing span
 * always re-reads the *exact* extraction version recorded on it. This is
 * what actually satisfies "a parser-version change creates a new extraction
 * version rather than silently replacing evidence beneath existing
 * documents": a later parser upgrade changes what a *new* span would
 * resolve against, never what an *existing* one does.
 */
public class SourceService {

    private final DocumentExtractionService documentExtractionService;
    private final ExtractionVersionRepository extractionVersionRepository;
    private final PdfExtractionVersionRepository pdfExtractionVersionRepository;
    private final PlainTextExtractionVersionRepository plainTextExtractionVersionRepository;
    private final SourceSnapshotRepository sourceSnapshotRepository;
    private final SourceSpanRepository sourceSpanRepository;

    public SourceService(
            DocumentExtractionService documentExtractionService,
            ExtractionVersionRepository extractionVersionRepository,
            PdfExtractionVersionRepository pdfExtractionVersionRepository,
            PlainTextExtractionVersionRepository plainTextExtractionVersionRepository,
            SourceSnapshotRepository sourceSnapshotRepository,
            SourceSpanRepository sourceSpanRepository) {
        this.documentExtractionService = documentExtractionService;
        this.extractionVersionRepository = extractionVersionRepository;
        this.pdfExtractionVersionRepository = pdfExtractionVersionRepository;
        this.plainTextExtractionVersionRepository = plainTextExtractionVersionRepository;
        this.sourceSnapshotRepository = sourceSnapshotRepository;
        this.sourceSpanRepository = sourceSpanRepository;
    }

    /**
     * Designates a READY artifact as a source, or returns the snapshot that
     * already does. Ensures the artifact's own extraction has actually been
     * attempted (triggering it if this is the first time), so the snapshot
     * itself is already meaningful -- not a promise that anything can be
     * cited from it yet, which depends on whether that extraction actually
     * produced usable content, checked separately when a span is created.
     */
    public SourceSnapshot attachSnapshot(long workspaceId, long userId, long artifactId) {
        Optional<SourceSnapshot> existing = sourceSnapshotRepository.findByArtifact(workspaceId, userId, artifactId);
        if (existing.isPresent()) {
            return existing.get();
        }
        documentExtractionService.extract(workspaceId, userId, artifactId);
        return sourceSnapshotRepository.create(workspaceId, userId, artifactId, SourceKind.ARTIFACT);
    }

    /**
     * Creates a citation into a snapshot's *current* extraction, rejecting
     * a locator that does not actually resolve ({@link
     * InvalidEvidenceLocatorException}) before anything is persisted. The
     * new span is pinned to whichever extraction version this resolution
     * ran against.
     */
    public SourceSpan createSpan(long workspaceId, long userId, long sourceSnapshotId, EvidenceLocator locator) {
        SourceSnapshot snapshot = requireSnapshot(workspaceId, userId, sourceSnapshotId);
        ExcerptAndVersion resolved = resolveAgainstCurrentExtraction(workspaceId, userId, snapshot.artifactId(), locator);
        String hash = sha256Hex(resolved.excerptText());
        return sourceSpanRepository.create(workspaceId, userId, sourceSnapshotId, resolved.parserVersion(), locator, hash);
    }

    /**
     * Re-resolves an existing span's exact original excerpt against the
     * exact extraction version it was created under, and verifies the
     * result still matches the hash recorded at creation -- a defense-in-
     * depth check, not an expected event, since nothing in this system
     * mutates an extraction version once it exists.
     */
    public ResolvedEvidence resolveSpan(long workspaceId, long userId, long spanId) {
        SourceSpan span = requireSpan(workspaceId, userId, spanId);
        SourceSnapshot snapshot = requireSnapshot(workspaceId, userId, span.sourceSnapshotId());
        String excerptText =
                resolveAgainstExactExtraction(workspaceId, userId, snapshot.artifactId(), span.extractionParserVersion(), span.locator());
        if (!sha256Hex(excerptText).equals(span.excerptHash())) {
            throw new IllegalStateException(
                    "Resolved evidence for span " + spanId + " no longer matches its recorded hash; the underlying extraction data may be corrupted.");
        }
        return new ResolvedEvidence(span, excerptText);
    }

    private record ExcerptAndVersion(String excerptText, String parserVersion) {
    }

    private ExcerptAndVersion resolveAgainstCurrentExtraction(long workspaceId, long userId, long artifactId, EvidenceLocator locator) {
        return switch (locator) {
            case EvidenceLocator.Docx docx -> {
                ExtractionVersion version = documentExtractionService.extractDocx(workspaceId, userId, artifactId);
                requireComplete(version.status(), "DOCX");
                yield new ExcerptAndVersion(resolveDocx(version.graph(), docx), version.parserVersion());
            }
            case EvidenceLocator.Pdf pdf -> {
                PdfExtractionVersion version = documentExtractionService.extractPdf(workspaceId, userId, artifactId);
                requireComplete(version.status(), "PDF");
                yield new ExcerptAndVersion(resolvePdf(version.graph(), pdf), version.parserVersion());
            }
            case EvidenceLocator.PlainText plainText -> {
                PlainTextExtractionVersion version = documentExtractionService.extractPlainText(workspaceId, userId, artifactId);
                requireComplete(version.status(), "plain text");
                yield new ExcerptAndVersion(resolvePlainText(version.graph(), plainText), version.parserVersion());
            }
        };
    }

    private String resolveAgainstExactExtraction(
            long workspaceId, long userId, long artifactId, String parserVersion, EvidenceLocator locator) {
        return switch (locator) {
            case EvidenceLocator.Docx docx -> {
                ExtractionVersion version = extractionVersionRepository
                        .findByArtifact(workspaceId, userId, artifactId, parserVersion)
                        .orElseThrow(() -> new InvalidEvidenceLocatorException(
                                "No DOCX extraction version '" + parserVersion + "' found for artifact " + artifactId + "."));
                requireComplete(version.status(), "DOCX");
                yield resolveDocx(version.graph(), docx);
            }
            case EvidenceLocator.Pdf pdf -> {
                PdfExtractionVersion version = pdfExtractionVersionRepository
                        .findByArtifact(workspaceId, userId, artifactId, parserVersion)
                        .orElseThrow(() -> new InvalidEvidenceLocatorException(
                                "No PDF extraction version '" + parserVersion + "' found for artifact " + artifactId + "."));
                requireComplete(version.status(), "PDF");
                yield resolvePdf(version.graph(), pdf);
            }
            case EvidenceLocator.PlainText plainText -> {
                PlainTextExtractionVersion version = plainTextExtractionVersionRepository
                        .findByArtifact(workspaceId, userId, artifactId, parserVersion)
                        .orElseThrow(() -> new InvalidEvidenceLocatorException(
                                "No plain-text extraction version '" + parserVersion + "' found for artifact " + artifactId + "."));
                requireComplete(version.status(), "plain text");
                yield resolvePlainText(version.graph(), plainText);
            }
        };
    }

    private static void requireComplete(ExtractionStatus status, String formatLabel) {
        if (status != ExtractionStatus.COMPLETE) {
            throw new InvalidEvidenceLocatorException(
                    "This artifact's " + formatLabel + " extraction is " + status + ", not COMPLETE; nothing can be cited from it.");
        }
    }

    // Package-private (not private): each of these resolves a locator
    // against an already-fetched, hand-buildable graph with no dependency
    // on ArtifactService/DocumentExtractionService at all, so the tricky
    // per-format resolution logic (node search, substring safety, error
    // messages) is directly unit-testable without assembling a full fake
    // extraction pipeline.
    static String resolveDocx(DocxStructuralGraph graph, EvidenceLocator.Docx locator) {
        DocumentPart part = graph.parts().stream()
                .filter(p -> p.partName().equals(locator.partName()))
                .findFirst()
                .orElseThrow(() -> new InvalidEvidenceLocatorException("No part named '" + locator.partName() + "' in this document's extraction."));
        StructuralNode node = findNode(part.root(), locator.nodeId())
                .orElseThrow(() -> new InvalidEvidenceLocatorException(
                        "No node '" + locator.nodeId() + "' in part '" + locator.partName() + "'."));
        String text = node.text();
        if (text == null) {
            throw new InvalidEvidenceLocatorException("Node '" + locator.nodeId() + "' carries no text of its own to cite.");
        }
        return safeSubstring(text, locator.startCodePoint(), locator.endCodePointExclusive(), locator);
    }

    private static Optional<StructuralNode> findNode(StructuralNode current, String targetNodeId) {
        if (current.nodeId().equals(targetNodeId)) {
            return Optional.of(current);
        }
        for (StructuralNode child : current.children()) {
            Optional<StructuralNode> found = findNode(child, targetNodeId);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    static String resolvePdf(PdfStructuralGraph graph, EvidenceLocator.Pdf locator) {
        PdfPage page = graph.pages().stream()
                .filter(p -> p.pageNumber() == locator.pageNumber())
                .findFirst()
                .orElseThrow(() -> new InvalidEvidenceLocatorException("No page " + locator.pageNumber() + " in this document's extraction."));
        PdfTextLine line = page.lines().stream()
                .filter(l -> l.lineIndex() == locator.lineIndex())
                .findFirst()
                .orElseThrow(() -> new InvalidEvidenceLocatorException(
                        "No line " + locator.lineIndex() + " on page " + locator.pageNumber() + "."));
        return safeSubstring(line.text(), locator.startCodePoint(), locator.endCodePointExclusive(), locator);
    }

    static String resolvePlainText(PlainTextStructuralGraph graph, EvidenceLocator.PlainText locator) {
        NormalizedText normalized = NormalizedText.normalizeLineEndings(graph.originalText());
        try {
            return normalized.originalSpanFor(locator.startCodePoint(), locator.endCodePointExclusive()).text();
        } catch (IllegalArgumentException e) {
            throw new InvalidEvidenceLocatorException("Invalid code point range for plain text: " + e.getMessage());
        }
    }

    private static String safeSubstring(String text, int startCodePoint, int endCodePointExclusive, Object locator) {
        try {
            return CodePoints.substring(text, startCodePoint, endCodePointExclusive);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new InvalidEvidenceLocatorException(
                    "Invalid code point range [" + startCodePoint + ", " + endCodePointExclusive + ") for " + locator + ".");
        }
    }

    private SourceSnapshot requireSnapshot(long workspaceId, long userId, long snapshotId) {
        return sourceSnapshotRepository
                .find(workspaceId, userId, snapshotId)
                .orElseThrow(() -> new SourceSnapshotNotFoundException(snapshotId));
    }

    private SourceSpan requireSpan(long workspaceId, long userId, long spanId) {
        return sourceSpanRepository.find(workspaceId, userId, spanId).orElseThrow(() -> new SourceSpanNotFoundException(spanId));
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a JDK-guaranteed algorithm; this should be unreachable.", e);
        }
    }
}
