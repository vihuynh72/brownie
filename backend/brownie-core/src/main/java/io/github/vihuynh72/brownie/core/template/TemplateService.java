package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;

import java.util.List;
import java.util.Optional;

/**
 * A template's own lifecycle end to end: open a draft against an already-
 * extracted DOCX source, replace its field definitions and bindings while
 * validating each one against that source's real structure, and activate
 * an immutable version once every binding actually resolves. Depends only
 * on the interfaces above plus {@link TemplateBindingValidator}'s pure
 * logic, so it has no framework or infrastructure dependency of its own --
 * a JDBC repository and the real POI-backed extractor are supplied by
 * whichever module wires this up, the same shape {@code ArtifactService}
 * and {@code DocumentExtractionService} already follow.
 *
 * <p>This does not trigger extraction itself -- {@code POST .../extraction}
 * is always a separate, prior request. Building a template requires an
 * extraction that has already reached {@link ExtractionStatus#COMPLETE}; a
 * missing or non-COMPLETE extraction is reported back rather than run on
 * the caller's behalf.
 */
public class TemplateService {

    private final TemplateRepository templateRepository;
    private final ExtractionVersionRepository extractionVersionRepository;
    private final DocxStructuralExtractor docxExtractor;

    public TemplateService(
            TemplateRepository templateRepository,
            ExtractionVersionRepository extractionVersionRepository,
            DocxStructuralExtractor docxExtractor) {
        this.templateRepository = templateRepository;
        this.extractionVersionRepository = extractionVersionRepository;
        this.docxExtractor = docxExtractor;
    }

    /** Opens a new template with an empty first draft, pinned to {@code sourceArtifactId}'s current COMPLETE DOCX extraction. */
    public Template createDraft(long workspaceId, long userId, String displayName, long sourceArtifactId) {
        ExtractionVersion extraction = requireCompleteDocxExtraction(workspaceId, userId, sourceArtifactId);
        return templateRepository.createDraft(workspaceId, userId, displayName, sourceArtifactId, extraction.id());
    }

    public Optional<Template> find(long workspaceId, long userId, long templateId) {
        return templateRepository.find(workspaceId, userId, templateId);
    }

    public Optional<TemplateVersion> findVersion(long workspaceId, long userId, long templateId, long versionId) {
        return templateRepository.findVersion(workspaceId, userId, templateId, versionId);
    }

    public Optional<TemplateVersion> findDraftVersion(long workspaceId, long userId, long templateId) {
        return templateRepository.findDraftVersion(workspaceId, userId, templateId);
    }

    /**
     * Validates every field's binding against the draft's pinned extraction
     * graph before persisting anything -- a rejected set of bindings never
     * partially lands. {@link TemplateVersionStateConflictException}
     * (`expectedVersionNumber` is stale, or there is no open draft) and
     * {@link TemplateBindingValidationException} (one or more bindings are
     * unsupported) are both real, named outcomes here, not incidental
     * failures.
     */
    public TemplateVersion replaceDraftBindings(
            long workspaceId, long userId, long templateId, int expectedVersionNumber, List<FieldDefinition> fieldDefinitions) {
        TemplateVersion currentDraft = requireDraftVersion(workspaceId, userId, templateId);
        validateOrThrow(workspaceId, userId, currentDraft, fieldDefinitions);
        return templateRepository.replaceDraftBindings(workspaceId, userId, templateId, expectedVersionNumber, fieldDefinitions);
    }

    /**
     * Re-validates the draft's own current bindings one more time -- a
     * belt-and-suspenders check, since nothing about a pinned extraction
     * graph can change after {@link #replaceDraftBindings} already
     * validated the same list -- then activates it. Refuses an empty
     * field list: an activated template with nothing bound could never
     * actually fill a document.
     */
    public TemplateVersion activate(long workspaceId, long userId, long templateId, int expectedVersionNumber) {
        TemplateVersion currentDraft = requireDraftVersion(workspaceId, userId, templateId);
        if (currentDraft.fieldDefinitions().isEmpty()) {
            throw new TemplateVersionStateConflictException(
                    "Template " + templateId + " draft version " + currentDraft.versionNumber()
                            + " has no field definitions; a template cannot be activated with nothing bound.");
        }
        validateOrThrow(workspaceId, userId, currentDraft, currentDraft.fieldDefinitions());
        return templateRepository.activate(workspaceId, userId, templateId, expectedVersionNumber);
    }

    private void validateOrThrow(long workspaceId, long userId, TemplateVersion draft, List<FieldDefinition> fieldDefinitions) {
        DocxStructuralGraph graph = requireGraph(workspaceId, userId, draft);
        List<UnsupportedBinding> problems = TemplateBindingValidator.validate(graph, fieldDefinitions);
        if (!problems.isEmpty()) {
            throw new TemplateBindingValidationException(problems);
        }
    }

    private DocxStructuralGraph requireGraph(long workspaceId, long userId, TemplateVersion draft) {
        return extractionVersionRepository
                .findById(workspaceId, userId, draft.extractionVersionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Extraction version " + draft.extractionVersionId() + " referenced by template version "
                                + draft.id() + " no longer exists."))
                .graph();
    }

    private TemplateVersion requireDraftVersion(long workspaceId, long userId, long templateId) {
        Optional<TemplateVersion> draft = templateRepository.findDraftVersion(workspaceId, userId, templateId);
        if (draft.isPresent()) {
            return draft.get();
        }
        if (templateRepository.find(workspaceId, userId, templateId).isEmpty()) {
            throw new TemplateNotFoundException(templateId);
        }
        throw new TemplateVersionStateConflictException("Template " + templateId + " has no open draft version.");
    }

    private ExtractionVersion requireCompleteDocxExtraction(long workspaceId, long userId, long artifactId) {
        ExtractionVersion extraction = extractionVersionRepository
                .findByArtifact(workspaceId, userId, artifactId, docxExtractor.parserVersion())
                .orElseThrow(() -> new TemplateSourceNotExtractableException(artifactId, null));
        if (extraction.status() != ExtractionStatus.COMPLETE) {
            throw new TemplateSourceNotExtractableException(artifactId, extraction.status());
        }
        return extraction;
    }
}
