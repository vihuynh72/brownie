package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStorageException;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxParseException;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfPoint;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Reads one template version's own file afresh and projects it into a
 * {@link TemplateLayout} (see {@link TemplateLayoutProjector} for how fill
 * spots are placed). Deliberately not the graph the version was pinned to
 * when it was activated: that graph is kept exactly as it was so bindings
 * and rules keep meaning what they meant, but it records whatever an older
 * extractor read, and a page drawn from it would repeat that extractor's
 * mistakes. The same bytes read by today's extractor are what validation
 * already compares against, so the page and the checks agree. The pinned
 * graph is drawn only when today's extractor refuses a file an earlier one
 * took (a tracked paragraph mark, say): a page as that extractor read it is
 * better than none for a template that is still in use.
 *
 * <p>A PDF template's page is its pinned form reading instead ({@link
 * #pdfLayout}): a PDF is drawn from its own bytes by the viewer, and the
 * places are where its bindings say. The same reading answers where a box a
 * person points at would go ({@link #suggestBox}).
 *
 * <p>Makes no model call and changes nothing.
 */
public class TemplateLayoutService {

    private final TemplateRepository templateRepository;
    private final ArtifactService artifactService;
    private final DocxStructuralExtractor docxExtractor;
    private final PdfFormExtractionVersionRepository pdfFormExtractionVersionRepository;
    private final ExtractionVersionRepository extractionVersionRepository;

    public TemplateLayoutService(
            TemplateRepository templateRepository,
            ArtifactService artifactService,
            DocxStructuralExtractor docxExtractor,
            PdfFormExtractionVersionRepository pdfFormExtractionVersionRepository,
            ExtractionVersionRepository extractionVersionRepository) {
        this.templateRepository = templateRepository;
        this.artifactService = artifactService;
        this.docxExtractor = docxExtractor;
        this.pdfFormExtractionVersionRepository = pdfFormExtractionVersionRepository;
        this.extractionVersionRepository = extractionVersionRepository;
    }

    public TemplateLayout layout(long workspaceId, long userId, long templateId, long versionId) {
        TemplateVersion version = templateRepository.findVersion(workspaceId, userId, templateId, versionId)
                .orElseThrow(() -> new TemplateVersionNotFoundException(templateId, versionId));
        if (version.kind() == TemplateKind.PDF) {
            throw new TemplateLayoutUnavailableException(templateId, versionId, "it is a PDF form, drawn from its own pages.");
        }
        byte[] templateBytes = readSourceBytes(workspaceId, userId, version.sourceArtifactId());
        DocxStructuralGraph graph = extract(templateBytes, templateId, versionId)
                .orElseGet(() -> pinnedGraph(workspaceId, userId, version));
        return TemplateLayoutProjector.project(templateId, versionId, graph, version.fieldDefinitions());
    }

    /** A PDF template version's page view; empty for a Word one, whose page is {@link #layout}. */
    public Optional<PdfTemplateLayout> pdfLayout(long workspaceId, long userId, long templateId, long versionId) {
        TemplateVersion version = templateRepository.findVersion(workspaceId, userId, templateId, versionId)
                .orElseThrow(() -> new TemplateVersionNotFoundException(templateId, versionId));
        if (version.kind() != TemplateKind.PDF) {
            return Optional.empty();
        }
        return Optional.of(PdfTemplateLayout.of(
                templateId, versionId, version.sourceArtifactId(), pdfGraph(workspaceId, userId, version), version.fieldDefinitions()));
    }

    /**
     * Where a box goes on a PDF template's page for a person who pointed at
     * {@code point}, or who chose line {@code lineIndex} when {@code point}
     * is null ({@link PdfSpotCandidateDetector#boxSuggestion}). The same
     * page and place always give the same box.
     *
     * @throws MalformedTemplateRequestException for a page or line the PDF does not have
     * @throws TemplateLayoutUnavailableException for a Word template, which has no pages to point at
     */
    public PdfBoxSuggestion suggestBox(
            long workspaceId, long userId, long templateId, long versionId, int pageNumber, PdfPoint point, Integer lineIndex) {
        TemplateVersion version = templateRepository.findVersion(workspaceId, userId, templateId, versionId)
                .orElseThrow(() -> new TemplateVersionNotFoundException(templateId, versionId));
        if (version.kind() != TemplateKind.PDF) {
            throw new TemplateLayoutUnavailableException(templateId, versionId, "it is a Word form, which has no pages to place a box on.");
        }
        PdfFormGraph graph = pdfGraph(workspaceId, userId, version);
        PdfFormGraph.Page page = graph.pages().stream().filter(candidate -> candidate.pageNumber() == pageNumber).findFirst()
                .orElseThrow(() -> new MalformedTemplateRequestException("This PDF has no page " + pageNumber + "."));
        if (point != null) {
            if (!Double.isFinite(point.x()) || !Double.isFinite(point.y())) {
                throw new MalformedTemplateRequestException("A point on a page needs x and y in points.");
            }
            return PdfSpotCandidateDetector.boxSuggestion(graph, pageNumber, point);
        }
        if (lineIndex == null || lineIndex < 0 || lineIndex >= page.lines().size()) {
            throw new MalformedTemplateRequestException("Page " + pageNumber + " has no line " + lineIndex + ".");
        }
        return PdfSpotCandidateDetector.boxSuggestion(graph, pageNumber, lineIndex);
    }

    /**
     * Where a box goes on a PDF template's page for words a person named on
     * line {@code lineIndex}: just after the first {@code endOffset} code
     * points of the line's text ({@link
     * PdfSpotCandidateDetector#boxSuggestionAfter}). The same line and words
     * always give the same box.
     *
     * @throws MalformedTemplateRequestException for a page or line the PDF does not have
     * @throws TemplateLayoutUnavailableException for a Word template, which has no pages to point at
     */
    public PdfBoxSuggestion suggestBoxAfterWords(
            long workspaceId, long userId, long templateId, long versionId, int pageNumber, int lineIndex, int endOffset) {
        TemplateVersion version = templateRepository.findVersion(workspaceId, userId, templateId, versionId)
                .orElseThrow(() -> new TemplateVersionNotFoundException(templateId, versionId));
        if (version.kind() != TemplateKind.PDF) {
            throw new TemplateLayoutUnavailableException(templateId, versionId, "it is a Word form, which has no pages to place a box on.");
        }
        PdfFormGraph graph = pdfGraph(workspaceId, userId, version);
        PdfFormGraph.Page page = graph.pages().stream().filter(candidate -> candidate.pageNumber() == pageNumber).findFirst()
                .orElseThrow(() -> new MalformedTemplateRequestException("This PDF has no page " + pageNumber + "."));
        if (lineIndex < 0 || lineIndex >= page.lines().size()) {
            throw new MalformedTemplateRequestException("Page " + pageNumber + " has no line " + lineIndex + ".");
        }
        return PdfSpotCandidateDetector.boxSuggestionAfter(graph, pageNumber, lineIndex, endOffset);
    }

    private PdfFormGraph pdfGraph(long workspaceId, long userId, TemplateVersion version) {
        return pdfFormExtractionVersionRepository.findById(workspaceId, userId, version.pdfFormExtractionId())
                .map(PdfFormExtractionVersion::graph)
                .orElseThrow(() -> new IllegalStateException(
                        "PDF form reading " + version.pdfFormExtractionId() + " of template version " + version.id() + " no longer exists."));
    }

    /**
     * The bytes are read in full before parsing so that a store that goes
     * away mid-read is reported as the storage failure it is, and not as a
     * template that cannot be read.
     */
    private byte[] readSourceBytes(long workspaceId, long userId, long artifactId) {
        try (ReadableArtifact readable = artifactService.openContent(workspaceId, userId, artifactId)) {
            return readable.content().readAllBytes();
        } catch (IOException e) {
            throw new ArtifactStorageException("Failed to read stored content for template source artifact " + artifactId, e);
        }
    }

    /** Today's reading of the file; empty when today's extractor refuses it. */
    private Optional<DocxStructuralGraph> extract(byte[] templateBytes, long templateId, long versionId) {
        DocxExtractionOutcome outcome;
        try (InputStream in = new ByteArrayInputStream(templateBytes)) {
            outcome = docxExtractor.extract(in);
        } catch (DocxParseException | IOException e) {
            throw new TemplateLayoutUnavailableException(templateId, versionId, "its file could not be read as a Word document.", e);
        }
        return switch (outcome) {
            case DocxExtractionOutcome.Supported supported -> Optional.of(supported.graph());
            case DocxExtractionOutcome.Unsupported ignored -> Optional.empty();
        };
    }

    /** The graph the version was activated with, for a file today's extractor refuses. */
    private DocxStructuralGraph pinnedGraph(long workspaceId, long userId, TemplateVersion version) {
        return Optional.ofNullable(version.extractionVersionId())
                .flatMap(id -> extractionVersionRepository.findById(workspaceId, userId, id))
                .map(ExtractionVersion::graph)
                .orElseThrow(() -> new TemplateLayoutUnavailableException(
                        version.templateId(), version.id(), "its file uses something the page cannot show."));
    }
}
