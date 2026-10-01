package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.artifact.ArtifactRepository;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfFormReader;
import io.github.vihuynh72.brownie.core.document.UnusablePdfFormException;
import io.github.vihuynh72.brownie.core.prepare.PreparationNotice;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;

import java.util.List;
import java.util.Optional;

/**
 * A template's own lifecycle end to end: open a draft against an already-
 * extracted DOCX source, replace its field definitions and bindings while
 * validating each one against that source's real structure, and activate
 * an immutable version once every binding actually resolves and every
 * rule proposed against it can actually hold at once. Depends only on the
 * interfaces above plus {@link ActivationGate}'s pure checks, so it has no framework or
 * infrastructure dependency of its own -- a JDBC repository and the real
 * POI-backed extractor are supplied by whichever module wires this up, the
 * same shape {@code ArtifactService} and {@code DocumentExtractionService}
 * already follow.
 *
 * <p>This depends on {@code core.rule}, which in turn depends on {@code
 * Template}/{@code FieldDefinition} here -- a deliberate two-way
 * dependency between the two packages, not an accident: a template's own
 * activation gate is genuinely inseparable from its rules' own
 * consistency, the same way an aggregate root's invariants in this
 * codebase's other domains are never split across a boundary that would
 * let one half change without the other noticing.
 *
 * <p>This does not trigger extraction itself -- {@code POST .../extraction}
 * is always a separate, prior request. Building a template requires an
 * extraction that has already reached {@link ExtractionStatus#COMPLETE}; a
 * missing or non-COMPLETE extraction is reported back rather than run on
 * the caller's behalf.
 *
 * <p>A PDF source makes a {@link TemplateKind#PDF} template instead. Its
 * draft is pinned to the PDF's stored form reading (made when the PDF was
 * prepared as a form, never here), its bindings are checked against that
 * reading, and it has no Word structure, so nothing here that needs one
 * (browsing the structure, protected regions) applies to it.
 */
public class TemplateService {

    /** The most notices a draft keeps from the upload it was made from: more than the upload step ever writes. */
    public static final int MAX_PREPARATION_NOTICES = 40;
    /** The longest detail one notice may carry, in characters (a format's or a feature's name). */
    public static final int MAX_NOTICE_DETAIL_LENGTH = 200;

    private final TemplateRepository templateRepository;
    private final ExtractionVersionRepository extractionVersionRepository;
    private final DocxStructuralExtractor docxExtractor;
    private final RuleRepository ruleRepository;
    private final TemplateBaselineRenderer templateBaselineRenderer;
    private final TemplateBaselineRenderRepository templateBaselineRenderRepository;
    private final ArtifactRepository artifactRepository;
    private final PdfFormExtractionVersionRepository pdfFormExtractionVersionRepository;
    private final PdfFormReader pdfFormReader;

    public TemplateService(
            TemplateRepository templateRepository,
            ExtractionVersionRepository extractionVersionRepository,
            DocxStructuralExtractor docxExtractor,
            RuleRepository ruleRepository,
            TemplateBaselineRenderer templateBaselineRenderer,
            TemplateBaselineRenderRepository templateBaselineRenderRepository,
            ArtifactRepository artifactRepository,
            PdfFormExtractionVersionRepository pdfFormExtractionVersionRepository,
            PdfFormReader pdfFormReader) {
        this.templateRepository = templateRepository;
        this.extractionVersionRepository = extractionVersionRepository;
        this.docxExtractor = docxExtractor;
        this.ruleRepository = ruleRepository;
        this.templateBaselineRenderer = templateBaselineRenderer;
        this.templateBaselineRenderRepository = templateBaselineRenderRepository;
        this.artifactRepository = artifactRepository;
        this.pdfFormExtractionVersionRepository = pdfFormExtractionVersionRepository;
        this.pdfFormReader = pdfFormReader;
    }

    /**
     * Opens a new template with an empty first draft. A PDF source makes a
     * PDF template pinned to the PDF's COMPLETE form reading under the
     * current reader; any other source a Word template pinned to its current
     * COMPLETE DOCX extraction. A PDF whose reading refused the whole file is
     * refused here the same way, with the same reason.
     */
    public Template createDraft(long workspaceId, long userId, String displayName, long sourceArtifactId) {
        return createDraft(workspaceId, userId, displayName, sourceArtifactId, null);
    }

    /**
     * {@link #createDraft(long, long, String, long)}, keeping with the draft
     * what the person was told when the file was made ready to fill, so the
     * notes can be read again later. Null keeps none. The notices come from
     * the browser, which had them from the upload step, so only the shape
     * the upload step gives them is taken: at most {@value
     * #MAX_PREPARATION_NOTICES}, each with a known code and a detail of at
     * most {@value #MAX_NOTICE_DETAIL_LENGTH} characters. A count has no
     * limit beyond not being negative: the upload step counts what the file
     * holds, and a long file restyled while changes were tracked can hold
     * many thousands of tracked changes.
     *
     * @throws MalformedTemplateRequestException when a notice is not one the upload step could have given
     */
    public Template createDraft(
            long workspaceId, long userId, String displayName, long sourceArtifactId, List<PreparationNotice> preparationNotices) {
        requireUsableNotices(preparationNotices);
        if (isPdf(workspaceId, userId, sourceArtifactId)) {
            PdfFormExtractionVersion reading = requireCompletePdfFormReading(workspaceId, userId, sourceArtifactId);
            return templateRepository.createPdfDraft(workspaceId, userId, displayName, sourceArtifactId, reading.id(), preparationNotices);
        }
        ExtractionVersion extraction = requireCompleteDocxExtraction(workspaceId, userId, sourceArtifactId);
        return templateRepository.createDraft(workspaceId, userId, displayName, sourceArtifactId, extraction.id(), preparationNotices);
    }

    static void requireUsableNotices(List<PreparationNotice> notices) {
        if (notices == null) {
            return;
        }
        if (notices.size() > MAX_PREPARATION_NOTICES) {
            throw new MalformedTemplateRequestException(
                    "A template keeps at most " + MAX_PREPARATION_NOTICES + " notes about its file, not " + notices.size() + ".");
        }
        for (PreparationNotice notice : notices) {
            if (notice == null || !PreparationNotice.CODES.contains(notice.code())) {
                throw new MalformedTemplateRequestException("A note about the template's file has a code Brownie does not know.");
            }
            if (notice.detail() != null && notice.detail().codePointCount(0, notice.detail().length()) > MAX_NOTICE_DETAIL_LENGTH) {
                throw new MalformedTemplateRequestException(
                        "A note about the template's file has a detail longer than " + MAX_NOTICE_DETAIL_LENGTH + " characters.");
            }
        }
    }

    public Optional<Template> find(long workspaceId, long userId, long templateId) {
        return templateRepository.find(workspaceId, userId, templateId);
    }

    /** Every template not in the Trash Bin, in creation order: the ones a new document can be started from. */
    public List<Template> findAll(long workspaceId, long userId) {
        return templateRepository.findAll(workspaceId, userId).stream().filter(template -> template.trashedAt() == null).toList();
    }

    /**
     * Every template, those in the Trash Bin included, for a caller that
     * must know what the workspace ever had rather than what it offers now:
     * built-in provisioning, which would otherwise take a trashed built-in
     * for a missing one and create it again.
     */
    public List<Template> findAllIncludingTrashed(long workspaceId, long userId) {
        return templateRepository.findAll(workspaceId, userId);
    }

    /** The templates in the Trash Bin, the most recently trashed first. */
    public List<Template> findTrashed(long workspaceId, long userId) {
        return templateRepository.findTrashed(workspaceId, userId);
    }

    /**
     * Moves a template to the Trash Bin, which stops new documents being
     * started from it and changes nothing else: its versions, layouts and
     * rules stay readable for every document already made from it, and
     * nothing ever deletes it from there by itself, since those documents
     * depend on it. Trashing it again changes nothing.
     */
    public Template trash(long workspaceId, long userId, long templateId) {
        return templateRepository.trash(workspaceId, userId, templateId);
    }

    /** Takes a template back out of the Trash Bin, so documents can be started from it again. Restoring it again changes nothing. */
    public Template restore(long workspaceId, long userId, long templateId) {
        return templateRepository.restore(workspaceId, userId, templateId);
    }

    public Optional<TemplateVersion> findVersion(long workspaceId, long userId, long templateId, long versionId) {
        return templateRepository.findVersion(workspaceId, userId, templateId, versionId);
    }

    public Optional<TemplateVersion> findDraftVersion(long workspaceId, long userId, long templateId) {
        return templateRepository.findDraftVersion(workspaceId, userId, templateId);
    }

    /**
     * The draft's own pinned structural graph, for a person browsing it to
     * pick an exact {@link FieldBindingTarget.StructuralNode} to map a
     * field to when no usable content control exists. The same graph
     * {@link #replaceDraftBindings} and {@link #activate} already validate
     * bindings against -- reading it does not create, extract, or change
     * anything.
     */
    public DocxStructuralGraph findDraftStructuralGraph(long workspaceId, long userId, long templateId) {
        TemplateVersion draft = requireDraftVersion(workspaceId, userId, templateId);
        if (draft.kind() == TemplateKind.PDF) {
            throw new TemplateVersionStateConflictException("Template " + templateId + " is a PDF form; it has no Word structure to browse.");
        }
        return requireGraph(workspaceId, userId, draft);
    }

    /**
     * Candidate field bindings proposed from the draft's own pinned
     * structure -- a starting point a person accepts, edits, or ignores in
     * favor of manual mapping, never something applied on its own. See
     * {@link FieldBindingCandidateProposer}'s own javadoc for exactly what
     * it can and cannot infer.
     */
    public CandidateBindingReport proposeCandidateBindings(long workspaceId, long userId, long templateId) {
        TemplateVersion draft = requireDraftVersion(workspaceId, userId, templateId);
        if (draft.kind() == TemplateKind.PDF) {
            // A PDF's places are found when it is prepared as a form, not proposed from a Word structure it does not have.
            return new CandidateBindingReport(List.of(), List.of(), 0);
        }
        return FieldBindingCandidateProposer.propose(requireGraph(workspaceId, userId, draft));
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
        validateBindingsOrThrow(workspaceId, userId, currentDraft, fieldDefinitions);
        return templateRepository.replaceDraftBindings(workspaceId, userId, templateId, expectedVersionNumber, fieldDefinitions);
    }

    /**
     * Re-validates the draft's own current bindings one more time -- a
     * belt-and-suspenders check, since nothing about a pinned extraction
     * graph can change after {@link #replaceDraftBindings} already
     * validated the same list -- then validates every rule against the final
     * draft shape before checking conflicts. This catches a rule that was
     * valid before a later binding replacement removed or reshaped its target.
     * Refuses an empty field list, unless the caller says the document is
     * meant to open with no places yet (see the overload below): a template
     * with nothing bound could not otherwise fill anything. Finally proves a real sample
     * fill and render before activating -- "create a sample document with
     * synthetic content, render it, inspect the capability report, and
     * activate" run as this one method's own last precondition, not a
     * separate prior step with its own state to track. The baseline is
     * recorded only once activation itself has actually succeeded.
     */
    public TemplateVersion activate(long workspaceId, long userId, long templateId, int expectedVersionNumber) {
        return activate(workspaceId, userId, templateId, expectedVersionNumber, false);
    }

    /**
     * {@link #activate}, where {@code allowNoPlaces} lets a draft with no
     * fields at all activate: a form Brownie found no places in still opens
     * as a document, and the person adds its spots on the page. Everything
     * else is checked the same way, the baseline render included.
     */
    public TemplateVersion activate(
            long workspaceId, long userId, long templateId, int expectedVersionNumber, boolean allowNoPlaces) {
        TemplateVersion currentDraft = requireDraftVersion(workspaceId, userId, templateId);
        List<FieldDefinition> fields = currentDraft.fieldDefinitions();
        if (!allowNoPlaces) {
            ActivationGate.requireFields("Template " + templateId + " draft version " + currentDraft.versionNumber(), fields);
        }
        // A PDF template has no Word graph: its bindings are checked against its form reading, and its rules with no graph,
        // which refuses only what would need one.
        DocxStructuralGraph graph = currentDraft.kind() == TemplateKind.DOCX ? requireGraph(workspaceId, userId, currentDraft) : null;
        ActivationGate.requireBindable(graph != null
                ? TemplateBindingValidator.validate(graph, fields)
                : TemplateBindingValidator.validate(requirePdfGraph(workspaceId, userId, currentDraft), fields));
        ActivationGate.requireRulesHold(fields, graph, ruleRepository.findByTemplateVersion(workspaceId, userId, currentDraft.id()));
        BaselineRenderResult baseline = templateBaselineRenderer.renderBaseline(workspaceId, userId, currentDraft);
        if (!baseline.passed()) {
            throw new TemplateBaselineIntegrityException(baseline.failedFieldIds());
        }
        TemplateVersion activated = templateRepository.activate(workspaceId, userId, templateId, expectedVersionNumber);
        templateBaselineRenderRepository.recordBaselineRender(workspaceId, userId, activated.id(), baseline);
        return activated;
    }

    /** The baseline render an activated version was proven against, if this version is activated. */
    public Optional<BaselineRenderResult> findBaselineRender(long workspaceId, long userId, long templateVersionId) {
        return templateBaselineRenderRepository.findBaselineRender(workspaceId, userId, templateVersionId);
    }

    private void validateBindingsOrThrow(
            long workspaceId, long userId, TemplateVersion draft, List<FieldDefinition> fieldDefinitions) {
        List<UnsupportedBinding> problems = switch (draft.kind()) {
            case DOCX -> TemplateBindingValidator.validate(requireGraph(workspaceId, userId, draft), fieldDefinitions);
            case PDF -> TemplateBindingValidator.validate(requirePdfGraph(workspaceId, userId, draft), fieldDefinitions);
        };
        if (!problems.isEmpty()) {
            throw new TemplateBindingValidationException(problems);
        }
    }

    /** The PDF form reading a PDF template version is pinned to, as {@link #requireGraph} is for a Word one. */
    private PdfFormGraph requirePdfGraph(long workspaceId, long userId, TemplateVersion version) {
        return pdfFormExtractionVersionRepository
                .findById(workspaceId, userId, version.pdfFormExtractionId())
                .map(PdfFormExtractionVersion::graph)
                .orElseThrow(() -> new IllegalStateException(
                        "PDF form reading " + version.pdfFormExtractionId() + " referenced by template version " + version.id()
                                + " no longer exists."));
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

    private boolean isPdf(long workspaceId, long userId, long artifactId) {
        return artifactRepository.find(workspaceId, userId, artifactId)
                .map(artifact -> artifact.detectedMediaType() == SupportedMediaType.PDF)
                .orElse(false);
    }

    private PdfFormExtractionVersion requireCompletePdfFormReading(long workspaceId, long userId, long artifactId) {
        PdfFormExtractionVersion reading = pdfFormExtractionVersionRepository
                .findByArtifact(workspaceId, userId, artifactId, pdfFormReader.parserVersion())
                .orElseThrow(() -> TemplateSourceNotExtractableException.pdfNotPrepared(artifactId));
        if (reading.status() != ExtractionStatus.COMPLETE) {
            throw new UnusablePdfFormException(reading.unsupportedReason(), reading.unsupportedDetail());
        }
        return reading;
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
