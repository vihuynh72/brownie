package io.github.vihuynh72.brownie.core.document;

import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;

import java.io.IOException;
import java.util.Optional;

/**
 * Runs the DOCX structural extractor against a READY artifact and persists
 * exactly one immutable {@link ExtractionVersion} per (artifact, parser
 * version) pair. Depends only on {@link ArtifactService} and the two
 * interfaces above, so it has no framework or infrastructure dependency of
 * its own -- a real POI-backed extractor and JDBC repository are supplied
 * by whichever module wires this up.
 */
public class DocumentExtractionService {

    private final ArtifactService artifactService;
    private final DocxStructuralExtractor extractor;
    private final ExtractionVersionRepository extractionVersionRepository;

    public DocumentExtractionService(
            ArtifactService artifactService,
            DocxStructuralExtractor extractor,
            ExtractionVersionRepository extractionVersionRepository) {
        this.artifactService = artifactService;
        this.extractor = extractor;
        this.extractionVersionRepository = extractionVersionRepository;
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
        String parserVersion = extractor.parserVersion();
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
                outcome = extractor.extract(readable.content());
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

    /** The already-persisted result for this artifact under the extractor's current parser version, if any. */
    public Optional<ExtractionVersion> findLatest(long workspaceId, long userId, long artifactId) {
        return extractionVersionRepository.findByArtifact(workspaceId, userId, artifactId, extractor.parserVersion());
    }
}
