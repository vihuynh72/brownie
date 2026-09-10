package io.github.vihuynh72.brownie.core.document;

import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;

import java.io.IOException;
import java.util.Optional;

/**
 * Runs the right structural extractor for a READY artifact's own detected
 * media type and persists exactly one immutable version record per
 * (artifact, parser version) pair. Depends only on {@link ArtifactService}
 * and the extractor/repository interfaces above, so it has no framework or
 * infrastructure dependency of its own -- real POI/PDFBox-backed
 * extractors and JDBC repositories are supplied by whichever module wires
 * this up. {@code SupportedMediaType} has exactly three values and all
 * three now have a real extractor here, so {@link #extract}/{@link
 * #findLatestResult}'s {@code switch}es are genuinely exhaustive -- there
 * is no longer an "unsupported type" branch to throw from.
 *
 * <p>DOCX, PDF, and plain text are kept as genuinely separate pairs of
 * methods and repositories, each returning its own format-shaped result
 * ({@link ExtractionVersion}, {@link PdfExtractionVersion}, {@link
 * PlainTextExtractionVersion}) -- the formats' own "what went wrong"
 * shapes are different enough (DOCX rejects for a list of located
 * structural findings; a PDF is unsupported for one of two whole-document
 * reasons; plain text only ever succeeds or fails to decode) that forcing
 * one shared record would only add nullable fields whose meaning depends
 * on a format the type itself never states. {@link #extract} is the one
 * place that does need to be format-agnostic -- an HTTP route driven
 * purely by the artifact's own type, with no reason to ask its caller
 * which extractor to run -- and it stays thin by delegating straight back
 * to {@link #extractDocx}/{@link #extractPdf}/{@link #extractPlainText}.
 */
public class DocumentExtractionService {

    private final ArtifactService artifactService;
    private final DocxStructuralExtractor docxExtractor;
    private final ExtractionVersionRepository extractionVersionRepository;
    private final PdfStructuralExtractor pdfExtractor;
    private final PdfExtractionVersionRepository pdfExtractionVersionRepository;
    private final PlainTextExtractor plainTextExtractor;
    private final PlainTextExtractionVersionRepository plainTextExtractionVersionRepository;

    public DocumentExtractionService(
            ArtifactService artifactService,
            DocxStructuralExtractor docxExtractor,
            ExtractionVersionRepository extractionVersionRepository,
            PdfStructuralExtractor pdfExtractor,
            PdfExtractionVersionRepository pdfExtractionVersionRepository,
            PlainTextExtractor plainTextExtractor,
            PlainTextExtractionVersionRepository plainTextExtractionVersionRepository) {
        this.artifactService = artifactService;
        this.docxExtractor = docxExtractor;
        this.extractionVersionRepository = extractionVersionRepository;
        this.pdfExtractor = pdfExtractor;
        this.pdfExtractionVersionRepository = pdfExtractionVersionRepository;
        this.plainTextExtractor = plainTextExtractor;
        this.plainTextExtractionVersionRepository = plainTextExtractionVersionRepository;
    }

    /**
     * Extracts a READY artifact's structure with whichever extractor
     * matches its own detected media type. Opens the artifact once just to
     * read its recorded type, then delegates to {@link #extractDocx},
     * {@link #extractPdf}, or {@link #extractPlainText}, each of which
     * opens it again for the actual extraction -- {@link
     * ArtifactService#openContent} re-reads a small, already-scanned
     * object from blob storage rather than holding a resource open, so a
     * second open is a minor, acceptable cost for keeping this method a
     * thin dispatcher instead of duplicating any one format's own
     * idempotency-check-then-extract logic here. Refuses an artifact that
     * is not READY ({@link ArtifactService#openContent}).
     */
    public ExtractionResult extract(long workspaceId, long userId, long artifactId) {
        return switch (mediaTypeOf(workspaceId, userId, artifactId)) {
            case DOCX -> new ExtractionResult.Docx(extractDocx(workspaceId, userId, artifactId));
            case PDF -> new ExtractionResult.Pdf(extractPdf(workspaceId, userId, artifactId));
            case PLAIN_TEXT -> new ExtractionResult.PlainText(extractPlainText(workspaceId, userId, artifactId));
        };
    }

    /** The already-persisted result for this artifact, from whichever repository matches its own detected media type, if any. */
    public Optional<ExtractionResult> findLatestResult(long workspaceId, long userId, long artifactId) {
        return switch (mediaTypeOf(workspaceId, userId, artifactId)) {
            case DOCX -> findLatest(workspaceId, userId, artifactId).map(ExtractionResult.Docx::new);
            case PDF -> findLatestPdf(workspaceId, userId, artifactId).map(ExtractionResult.Pdf::new);
            case PLAIN_TEXT -> findLatestPlainText(workspaceId, userId, artifactId).map(ExtractionResult.PlainText::new);
        };
    }

    /** Opens the artifact only to read its recorded media type; see {@link #extract}'s own javadoc for why a second open elsewhere is an acceptable cost. */
    private SupportedMediaType mediaTypeOf(long workspaceId, long userId, long artifactId) {
        ReadableArtifact peek = artifactService.openContent(workspaceId, userId, artifactId);
        try (peek) {
            return peek.artifact().detectedMediaType();
        } catch (IOException e) {
            throw new DocxParseException("Failed to read artifact " + artifactId + " to determine its type.", e);
        }
    }

    /**
     * Extracts a READY DOCX artifact's structure, or returns the
     * already-persisted result for this exact (artifact, parser version)
     * pair without re-parsing. Refuses an artifact that is not READY (via
     * {@link ArtifactService#openContent}) or not DOCX ({@link
     * NotDocxArtifactException}) before ever invoking the extractor. A
     * document outside the qualified subset or one that fails to parse is
     * not an error from this method's point of view -- it is a real,
     * persisted, terminal answer, returned the same way a success is.
     */
    public ExtractionVersion extractDocx(long workspaceId, long userId, long artifactId) {
        String parserVersion = docxExtractor.parserVersion();
        Optional<ExtractionVersion> existing =
                extractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, parserVersion);
        if (existing.isPresent()) {
            return existing.get();
        }

        ReadableArtifact readable = artifactService.openContent(workspaceId, userId, artifactId);
        try (readable) {
            if (readable.artifact().detectedMediaType() != SupportedMediaType.DOCX) {
                throw new NotDocxArtifactException(artifactId, readable.artifact().detectedMediaType());
            }
            DocxExtractionOutcome outcome;
            try {
                outcome = docxExtractor.extract(readable.content());
            } catch (DocxParseException e) {
                return extractionVersionRepository.saveFailed(workspaceId, userId, artifactId, parserVersion, e.getMessage());
            }
            return switch (outcome) {
                case DocxExtractionOutcome.Supported supported -> extractionVersionRepository.saveComplete(
                        workspaceId, userId, artifactId, parserVersion, supported.graph());
                case DocxExtractionOutcome.Unsupported unsupported -> extractionVersionRepository.saveUnsupported(
                        workspaceId, userId, artifactId, parserVersion, unsupported.featureReport());
            };
        } catch (IOException e) {
            throw new DocxParseException("Failed to read artifact " + artifactId + " content for extraction.", e);
        }
    }

    /**
     * Extracts a READY PDF artifact's page geometry and text, or returns
     * the already-persisted result for this exact (artifact, parser
     * version) pair without re-parsing. The PDF analog of {@link
     * #extractDocx}, with the same idempotent, always-a-real-answer
     * behavior -- encrypted or fully text-less documents are UNSUPPORTED,
     * not thrown errors.
     */
    public PdfExtractionVersion extractPdf(long workspaceId, long userId, long artifactId) {
        String parserVersion = pdfExtractor.parserVersion();
        Optional<PdfExtractionVersion> existing =
                pdfExtractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, parserVersion);
        if (existing.isPresent()) {
            return existing.get();
        }

        ReadableArtifact readable = artifactService.openContent(workspaceId, userId, artifactId);
        try (readable) {
            if (readable.artifact().detectedMediaType() != SupportedMediaType.PDF) {
                throw new NotPdfArtifactException(artifactId, readable.artifact().detectedMediaType());
            }
            PdfExtractionOutcome outcome;
            try {
                outcome = pdfExtractor.extract(readable.content());
            } catch (PdfParseException e) {
                return pdfExtractionVersionRepository.saveFailed(workspaceId, userId, artifactId, parserVersion, e.getMessage());
            }
            return switch (outcome) {
                case PdfExtractionOutcome.Supported supported -> pdfExtractionVersionRepository.saveComplete(
                        workspaceId, userId, artifactId, parserVersion, supported.graph());
                case PdfExtractionOutcome.Unsupported unsupported -> pdfExtractionVersionRepository.saveUnsupported(
                        workspaceId, userId, artifactId, parserVersion, unsupported.reason(), unsupported.detail());
            };
        } catch (IOException e) {
            throw new PdfParseException("Failed to read artifact " + artifactId + " content for extraction.", e);
        }
    }

    /**
     * Extracts a READY plain-text artifact's normalized text, or returns
     * the already-persisted result for this exact (artifact, parser
     * version) pair without re-decoding. The plain-text analog of {@link
     * #extractDocx}/{@link #extractPdf}: bytes that are not well-formed
     * UTF-8 are a real, persisted FAILED answer, not a thrown error.
     */
    public PlainTextExtractionVersion extractPlainText(long workspaceId, long userId, long artifactId) {
        String parserVersion = plainTextExtractor.parserVersion();
        Optional<PlainTextExtractionVersion> existing =
                plainTextExtractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, parserVersion);
        if (existing.isPresent()) {
            return existing.get();
        }

        ReadableArtifact readable = artifactService.openContent(workspaceId, userId, artifactId);
        try (readable) {
            if (readable.artifact().detectedMediaType() != SupportedMediaType.PLAIN_TEXT) {
                throw new NotPlainTextArtifactException(artifactId, readable.artifact().detectedMediaType());
            }
            PlainTextStructuralGraph graph;
            try {
                graph = plainTextExtractor.extract(readable.content());
            } catch (PlainTextParseException e) {
                return plainTextExtractionVersionRepository.saveFailed(workspaceId, userId, artifactId, parserVersion, e.getMessage());
            }
            return plainTextExtractionVersionRepository.saveComplete(workspaceId, userId, artifactId, parserVersion, graph);
        } catch (IOException e) {
            throw new PlainTextParseException("Failed to read artifact " + artifactId + " content for extraction.", e);
        }
    }

    /** The already-persisted DOCX result for this artifact under the DOCX extractor's current parser version, if any. */
    public Optional<ExtractionVersion> findLatest(long workspaceId, long userId, long artifactId) {
        return extractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, docxExtractor.parserVersion());
    }

    /** The already-persisted PDF result for this artifact under the PDF extractor's current parser version, if any. */
    public Optional<PdfExtractionVersion> findLatestPdf(long workspaceId, long userId, long artifactId) {
        return pdfExtractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, pdfExtractor.parserVersion());
    }

    /** The already-persisted plain-text result for this artifact under the plain-text extractor's current parser version, if any. */
    public Optional<PlainTextExtractionVersion> findLatestPlainText(long workspaceId, long userId, long artifactId) {
        return plainTextExtractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, plainTextExtractor.parserVersion());
    }
}
