package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStorageException;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxParseException;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads one template version's own file afresh and projects it into a
 * {@link TemplateLayout} (see {@link TemplateLayoutProjector} for how fill
 * spots are placed). Deliberately not the graph the version was pinned to
 * when it was activated: that graph is kept exactly as it was so bindings
 * and rules keep meaning what they meant, but it records whatever an older
 * extractor read, and a page drawn from it would repeat that extractor's
 * mistakes. The same bytes read by today's extractor are what validation
 * already compares against, so the page and the checks agree.
 *
 * <p>Makes no model call and changes nothing.
 */
public class TemplateLayoutService {

    private final TemplateRepository templateRepository;
    private final ArtifactService artifactService;
    private final DocxStructuralExtractor docxExtractor;

    public TemplateLayoutService(
            TemplateRepository templateRepository, ArtifactService artifactService, DocxStructuralExtractor docxExtractor) {
        this.templateRepository = templateRepository;
        this.artifactService = artifactService;
        this.docxExtractor = docxExtractor;
    }

    public TemplateLayout layout(long workspaceId, long userId, long templateId, long versionId) {
        TemplateVersion version = templateRepository.findVersion(workspaceId, userId, templateId, versionId)
                .orElseThrow(() -> new TemplateVersionNotFoundException(templateId, versionId));
        byte[] templateBytes = readSourceBytes(workspaceId, userId, version.sourceArtifactId());
        DocxStructuralGraph graph = extract(templateBytes, templateId, versionId);
        return TemplateLayoutProjector.project(templateId, versionId, graph, version.fieldDefinitions());
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

    private DocxStructuralGraph extract(byte[] templateBytes, long templateId, long versionId) {
        DocxExtractionOutcome outcome;
        try (InputStream in = new ByteArrayInputStream(templateBytes)) {
            outcome = docxExtractor.extract(in);
        } catch (DocxParseException | IOException e) {
            throw new TemplateLayoutUnavailableException(templateId, versionId, "its file could not be read as a Word document.", e);
        }
        return switch (outcome) {
            case DocxExtractionOutcome.Supported(DocxStructuralGraph graph) -> graph;
            case DocxExtractionOutcome.Unsupported ignored -> throw new TemplateLayoutUnavailableException(
                    templateId, versionId, "its file uses something the page cannot show.");
        };
    }
}
