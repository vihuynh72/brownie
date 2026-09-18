package io.github.vihuynh72.brownie.core.rule;

import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionNotFoundException;

import java.util.List;
import java.util.Optional;

/**
 * Proposes a rule against a template's own current draft version, after
 * validating its scope and payload against that draft's real field
 * definitions and the pinned extraction graph they were themselves bound
 * against. Depends only on the interfaces above plus {@link
 * RulePayloadValidator}'s pure logic, so it has no framework or
 * infrastructure dependency of its own, the same shape {@link
 * io.github.vihuynh72.brownie.core.template.TemplateService} already
 * follows. Read-only with respect to templates -- this never creates,
 * edits, or activates one; {@code TemplateService} still owns that.
 */
public class RuleService {

    private final RuleRepository ruleRepository;
    private final TemplateRepository templateRepository;
    private final ExtractionVersionRepository extractionVersionRepository;
    private final String schemaVersion;

    public RuleService(
            RuleRepository ruleRepository,
            TemplateRepository templateRepository,
            ExtractionVersionRepository extractionVersionRepository,
            String schemaVersion) {
        this.ruleRepository = ruleRepository;
        this.templateRepository = templateRepository;
        this.extractionVersionRepository = extractionVersionRepository;
        this.schemaVersion = schemaVersion;
    }

    /**
     * Validates {@code scope}/{@code payload} against the template's
     * current draft version, then proposes it. Throws {@link
     * RuleTemplateVersionStateException} if the template does not exist or
     * has no open draft, and {@link RuleValidationException} (naming every
     * problem at once) if the scope or payload does not hold against that
     * draft's real field definitions and extraction graph.
     */
    public RuleRevision propose(
            long workspaceId, long userId, long templateId, RuleScope scope, RulePayload payload, String humanExplanation) {
        TemplateVersion draft = requireDraftVersion(workspaceId, userId, templateId);
        DocxStructuralGraph graph = requireGraph(workspaceId, userId, draft);

        List<RuleProblem> problems = RulePayloadValidator.validate(draft.fieldDefinitions(), graph, scope, payload);
        if (!problems.isEmpty()) {
            throw new RuleValidationException(problems);
        }

        return ruleRepository.propose(
                workspaceId, userId, templateId, draft.id(), scope, payload, schemaVersion, humanExplanation);
    }

    public Optional<RuleRevision> find(long workspaceId, long userId, long templateId, long ruleId) {
        return ruleRepository.find(workspaceId, userId, templateId, ruleId);
    }

    /** Every rule revision proposed against the template's current draft version. */
    public List<RuleRevision> findForDraft(long workspaceId, long userId, long templateId) {
        TemplateVersion draft = requireDraftVersion(workspaceId, userId, templateId);
        return ruleRepository.findByTemplateVersion(workspaceId, userId, draft.id());
    }

    /**
     * Every rule revision on one version of the template, draft or
     * activated: what a document reads for the version it was created
     * from, where {@link #findForDraft} would refuse because an activated
     * template has no open draft.
     */
    public List<RuleRevision> findForVersion(long workspaceId, long userId, long templateId, long versionId) {
        TemplateVersion version = templateRepository
                .findVersion(workspaceId, userId, templateId, versionId)
                .orElseThrow(() -> new TemplateVersionNotFoundException(templateId, versionId));
        return ruleRepository.findByTemplateVersion(workspaceId, userId, version.id());
    }

    /**
     * Records which attached examples supported or contradicted an
     * already-proposed rule. Called by {@code core.example}'s own
     * example-driven proposer immediately after {@link #propose} persists
     * the rule itself -- kept as two separate calls, not one combined
     * method, since a manually proposed rule (an ordinary {@link #propose}
     * call with no caller-supplied evidence) never calls this at all.
     */
    public void recordProposalEvidence(long workspaceId, long userId, long ruleId, RuleProposalEvidence evidence) {
        ruleRepository.recordProposalEvidence(workspaceId, userId, ruleId, evidence);
    }

    /** The evidence recorded for one rule, if it was ever proposed from examples. */
    public Optional<RuleProposalEvidence> findProposalEvidence(long workspaceId, long userId, long ruleId) {
        return ruleRepository.findProposalEvidence(workspaceId, userId, ruleId);
    }

    /**
     * Accepts a proposed rule as-is. Accepting a {@code ProtectedRegion}
     * rule is this codebase's own "lock a region" action -- a person locks
     * a region by accepting exactly that proposal, not through a separate
     * mechanism.
     */
    public RuleRevision acceptRule(long workspaceId, long userId, long templateId, long ruleId) {
        return ruleRepository.decide(workspaceId, userId, templateId, ruleId, RuleRevisionStatus.ACCEPTED);
    }

    /**
     * Rejects a proposed rule. "Editing" a proposed rule is this same
     * operation composed with a fresh {@link #propose} call carrying the
     * edited payload -- a rule is immutable once created, the same way a
     * document revision is, so there is no separate in-place edit
     * mutation to call.
     */
    public RuleRevision rejectRule(long workspaceId, long userId, long templateId, long ruleId) {
        return ruleRepository.decide(workspaceId, userId, templateId, ruleId, RuleRevisionStatus.REJECTED);
    }

    private TemplateVersion requireDraftVersion(long workspaceId, long userId, long templateId) {
        return templateRepository
                .findDraftVersion(workspaceId, userId, templateId)
                .orElseThrow(() -> new RuleTemplateVersionStateException(
                        "Template " + templateId + " does not exist, or has no open draft version to propose a rule against."));
    }

    private DocxStructuralGraph requireGraph(long workspaceId, long userId, TemplateVersion draft) {
        ExtractionVersion extraction = extractionVersionRepository
                .findById(workspaceId, userId, draft.extractionVersionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Extraction version " + draft.extractionVersionId() + " referenced by template version " + draft.id()
                                + " no longer exists."));
        return extraction.graph();
    }
}
