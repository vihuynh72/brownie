package io.github.vihuynh72.brownie.core.example;

import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.rule.RuleService;
import io.github.vihuynh72.brownie.core.template.TemplateNotFoundException;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateSourceNotExtractableException;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStateConflictException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Attaches a completed example to a template's own current draft version
 * and aligns it eagerly, the same "compare examples only after the
 * template's relationship to its own fields is established" ordering this
 * plan's teaching sequence describes. Depends only on the interfaces above
 * plus {@link ExampleAligner}'s pure logic, the same framework-free shape
 * {@link io.github.vihuynh72.brownie.core.template.TemplateService} and
 * {@link io.github.vihuynh72.brownie.core.rule.RuleService} already follow.
 * Read-only with respect to templates -- this never creates, binds, or
 * activates one.
 */
public class TemplateExampleService {

    private final TemplateExampleRepository templateExampleRepository;
    private final TemplateRepository templateRepository;
    private final ExtractionVersionRepository extractionVersionRepository;
    private final DocxStructuralExtractor docxExtractor;
    private final RuleService ruleService;

    public TemplateExampleService(
            TemplateExampleRepository templateExampleRepository,
            TemplateRepository templateRepository,
            ExtractionVersionRepository extractionVersionRepository,
            DocxStructuralExtractor docxExtractor,
            RuleService ruleService) {
        this.templateExampleRepository = templateExampleRepository;
        this.templateRepository = templateRepository;
        this.extractionVersionRepository = extractionVersionRepository;
        this.docxExtractor = docxExtractor;
        this.ruleService = ruleService;
    }

    /**
     * Aligns {@code exampleSourceArtifactId}'s current COMPLETE DOCX
     * extraction against the template's current draft version's own field
     * definitions, then persists the result either way -- a {@code
     * MISMATCHED_FAMILY} example is still recorded, not rejected, so a
     * person can see and act on the mismatch rather than being told
     * nothing happened.
     *
     * @throws TemplateNotFoundException if no such template exists in this workspace
     * @throws TemplateVersionStateConflictException if the template has no open draft
     * @throws NoComparableFieldBindingsException if the draft has no {@code ContentControlTag}-bound field yet
     * @throws TemplateSourceNotExtractableException if the example artifact has no COMPLETE DOCX extraction
     */
    public TemplateExample attachExample(long workspaceId, long userId, long templateId, long exampleSourceArtifactId) {
        TemplateVersion draft = requireDraftVersion(workspaceId, userId, templateId);
        ExtractionVersion exampleExtraction = requireCompleteDocxExtraction(workspaceId, userId, exampleSourceArtifactId);
        ExampleAlignmentStatus status = ExampleAligner.align(templateId, draft.fieldDefinitions(), exampleExtraction.graph());
        return templateExampleRepository.attach(
                workspaceId, userId, templateId, draft.id(), exampleSourceArtifactId, exampleExtraction.id(), status);
    }

    /** Every example attached to the template's current draft version. */
    public List<TemplateExample> findForDraft(long workspaceId, long userId, long templateId) {
        TemplateVersion draft = requireDraftVersion(workspaceId, userId, templateId);
        return templateExampleRepository.findByTemplateVersion(workspaceId, userId, draft.id());
    }

    /**
     * Compares every {@code ALIGNED} example currently attached to the
     * draft and proposes a rule for each pattern {@link
     * ExampleRuleProposer} finds, persisting each one through {@link
     * RuleService#propose} plus its own {@link
     * RuleService#recordProposalEvidence}. A {@code MISMATCHED_FAMILY}
     * example is never read as evidence -- its own content does not
     * describe this template, so a pattern found only in it would not be
     * a real pattern of this template's own examples. Returns an empty
     * list, not an error, when there is nothing to propose (no aligned
     * examples yet, or none of them show a recognizable pattern).
     */
    public List<RuleRevision> proposeRulesFromExamples(long workspaceId, long userId, long templateId) {
        TemplateVersion draft = requireDraftVersion(workspaceId, userId, templateId);
        List<TemplateExample> aligned = templateExampleRepository.findByTemplateVersion(workspaceId, userId, draft.id()).stream()
                .filter(example -> example.alignmentStatus() == ExampleAlignmentStatus.ALIGNED)
                .toList();
        if (aligned.isEmpty()) {
            return List.of();
        }
        Map<TemplateExample, DocxStructuralGraph> graphsByExample = new LinkedHashMap<>();
        for (TemplateExample example : aligned) {
            DocxStructuralGraph graph = extractionVersionRepository
                    .findById(workspaceId, userId, example.extractionVersionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Extraction version " + example.extractionVersionId() + " referenced by template example "
                                    + example.id() + " no longer exists."))
                    .graph();
            graphsByExample.put(example, graph);
        }

        List<RuleRevision> created = new ArrayList<>();
        for (ExampleRuleProposer.ProposedRule proposed : ExampleRuleProposer.propose(draft.fieldDefinitions(), graphsByExample)) {
            String explanation = "Proposed from " + proposed.evidence().supportingExampleCount() + " aligned example(s).";
            RuleRevision revision =
                    ruleService.propose(workspaceId, userId, templateId, proposed.scope(), proposed.payload(), explanation);
            ruleService.recordProposalEvidence(workspaceId, userId, revision.id(), proposed.evidence());
            created.add(revision);
        }
        return created;
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
