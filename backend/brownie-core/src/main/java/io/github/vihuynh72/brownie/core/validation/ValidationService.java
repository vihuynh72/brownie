package io.github.vihuynh72.brownie.core.validation;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStatus;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.compile.DocumentRenderException;
import io.github.vihuynh72.brownie.core.compile.DocumentRenderer;
import io.github.vihuynh72.brownie.core.compile.DocxMetadataSanitizer;
import io.github.vihuynh72.brownie.core.compile.FilledDocument;
import io.github.vihuynh72.brownie.core.compile.GeneratedArtifactUnavailableException;
import io.github.vihuynh72.brownie.core.compile.RenderedPdf;
import io.github.vihuynh72.brownie.core.compile.TemplateFillException;
import io.github.vihuynh72.brownie.core.compile.TemplateFillProblemReason;
import io.github.vihuynh72.brownie.core.compile.TemplateFiller;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxParseException;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.evidence.SourceSpanRepository;
import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentMutationResult;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.revision.DocumentRevision;
import io.github.vihuynh72.brownie.core.revision.DocumentTemplateVersionUnavailableException;
import io.github.vihuynh72.brownie.core.revision.FieldItemRef;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.revision.ValidationState;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.rule.RuleRevisionStatus;
import io.github.vihuynh72.brownie.core.template.BaselineRenderResult;
import io.github.vihuynh72.brownie.core.template.TemplateBaselineRenderRepository;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs every independent validation layer this codebase can honestly check
 * today against one exact document revision, producing one immutable
 * {@link ValidationManifest} -- schema/requiredness and content-rule
 * checks against the revision's own typed content, evidence-reference
 * re-resolution, and, against a freshly filled and sanitized DOCX this
 * same run produces, package-integrity and protected-region checks. When
 * the template has a qualified baseline render, this same run also
 * renders the filled DOCX to PDF and compares both structurally ({@link
 * LayoutComparator}) and via a coarse rasterized page diff ({@link
 * PageRasterDiffer}) -- one complete manifest, not two separate systems, a
 * render never repeated later by export (see the class-level reasoning
 * below).
 *
 * <p>The DOCX this run fills is sanitized (see {@link DocxMetadataSanitizer})
 * before anything is checked, hashed, or stored -- so the bytes an export
 * later reuses are provably the exact bytes every check here actually
 * inspected, never bytes mutated afterward. Every per-field finding this
 * run produces is also written back onto the revision's own {@link
 * io.github.vihuynh72.brownie.core.revision.FieldState#validation()}
 * dimension, in one new revision {@link RevisionService#applyValidationResults}
 * appends -- the manifest this method returns names that new revision, not
 * whatever revision the caller passed in.
 */
public class ValidationService {

    private final RevisionService revisionService;
    private final TemplateRepository templateRepository;
    private final RuleRepository ruleRepository;
    private final SourceSpanRepository sourceSpanRepository;
    private final ArtifactService artifactService;
    private final TemplateFiller templateFiller;
    private final DocxMetadataSanitizer metadataSanitizer;
    private final DocxStructuralExtractor structuralExtractor;
    private final DocumentRenderer documentRenderer;
    private final TemplateBaselineRenderRepository templateBaselineRenderRepository;
    private final PageRasterDiffer pageRasterDiffer;
    private final ValidationRepository validationRepository;

    public ValidationService(
            RevisionService revisionService,
            TemplateRepository templateRepository,
            RuleRepository ruleRepository,
            SourceSpanRepository sourceSpanRepository,
            ArtifactService artifactService,
            TemplateFiller templateFiller,
            DocxMetadataSanitizer metadataSanitizer,
            DocxStructuralExtractor structuralExtractor,
            DocumentRenderer documentRenderer,
            TemplateBaselineRenderRepository templateBaselineRenderRepository,
            PageRasterDiffer pageRasterDiffer,
            ValidationRepository validationRepository) {
        this.revisionService = revisionService;
        this.templateRepository = templateRepository;
        this.ruleRepository = ruleRepository;
        this.sourceSpanRepository = sourceSpanRepository;
        this.artifactService = artifactService;
        this.templateFiller = templateFiller;
        this.metadataSanitizer = metadataSanitizer;
        this.structuralExtractor = structuralExtractor;
        this.documentRenderer = documentRenderer;
        this.templateBaselineRenderRepository = templateBaselineRenderRepository;
        this.pageRasterDiffer = pageRasterDiffer;
        this.validationRepository = validationRepository;
    }

    public ValidationManifest validate(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            long documentId,
            long expectedRevisionId) {
        Document document = revisionService.findDocument(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        DocumentRevision revision = revisionService.findRevision(workspaceId, userId, documentId, expectedRevisionId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        TemplateVersion templateVersion = templateRepository
                .findVersion(workspaceId, userId, document.templateId(), document.templateVersionId())
                .orElseThrow(() -> new DocumentTemplateVersionUnavailableException(document.templateId(), document.templateVersionId()));
        if (templateVersion.status() != TemplateVersionStatus.ACTIVATED) {
            throw new DocumentTemplateVersionUnavailableException(document.templateId(), document.templateVersionId());
        }

        List<RuleRevision> acceptedRules = ruleRepository.findByTemplateVersion(workspaceId, userId, templateVersion.id())
                .stream()
                .filter(rule -> rule.status() == RuleRevisionStatus.ACCEPTED)
                .toList();

        List<ValidationFinding> findings = new ArrayList<>();
        findings.addAll(DocumentValidator.checkRequiredness(revision.content(), templateVersion.fieldDefinitions(), acceptedRules));
        findings.addAll(DocumentValidator.checkContentRules(revision.content(), acceptedRules));
        findings.addAll(checkEvidenceReferences(workspaceId, userId, revision));

        byte[] templateBytes = readArtifactBytes(workspaceId, userId, templateVersion.sourceArtifactId());
        FilledDocument filled = templateFiller.fill(templateBytes, templateVersion.fieldDefinitions(), revision.content());
        byte[] sanitizedBytes = metadataSanitizer.sanitize(filled.docxBytes());

        findings.addAll(DocumentValidator.checkFieldContentInOutput(filled.intendedText(), filled.reopenedBodyText()));

        DocxExtractionOutcome filledOutcome = extract(sanitizedBytes);
        findings.addAll(packageIntegrityFindings(filledOutcome));
        DocxStructuralGraph filledGraph = graphOf(filledOutcome);
        if (filledGraph != null) {
            DocxStructuralGraph templateSourceGraph = graphOf(extract(templateBytes));
            if (templateSourceGraph != null) {
                findings.addAll(DocumentValidator.checkProtectedRegions(templateSourceGraph, filledGraph, acceptedRules));
            }
        }

        Artifact docxArtifact = storeGenerated(
                workspaceId, userId, "validated-" + documentId + "-r" + revision.revisionNumber() + ".docx", sanitizedBytes);

        Optional<BaselineRenderResult> baseline =
                templateBaselineRenderRepository.findBaselineRender(workspaceId, userId, templateVersion.id());
        Artifact pdfArtifact = null;
        if (baseline.isEmpty()) {
            findings.add(new ValidationFinding(
                    ValidationFindingCode.LAYOUT_COMPARISON_UNAVAILABLE, null,
                    "No qualified baseline render exists yet for this template version; layout comparison was not run."));
        } else if (filledGraph == null) {
            findings.add(new ValidationFinding(
                    ValidationFindingCode.LAYOUT_COMPARISON_UNAVAILABLE, null,
                    "The filled document could not be re-extracted, so layout comparison against the qualified baseline was not run."));
        } else {
            byte[] baselineDocxBytes = readArtifactBytes(workspaceId, userId, baseline.get().docxArtifactId());
            DocxStructuralGraph baselineGraph = graphOf(extract(baselineDocxBytes));
            if (baselineGraph == null) {
                findings.add(new ValidationFinding(
                        ValidationFindingCode.LAYOUT_COMPARISON_UNAVAILABLE, null,
                        "The template's own qualified baseline could not be re-extracted; layout comparison was not run."));
            } else {
                findings.addAll(LayoutComparator.compareStructure(baselineGraph, filledGraph, templateVersion.fieldDefinitions()));
            }

            try {
                RenderedPdf rendered = documentRenderer.renderToPdf(sanitizedBytes);
                pdfArtifact = storeGenerated(
                        workspaceId, userId, "validated-" + documentId + "-r" + revision.revisionNumber() + ".pdf", rendered.pdfBytes());
                byte[] baselinePdfBytes = readArtifactBytes(workspaceId, userId, baseline.get().pdfArtifactId());
                PageRasterComparison rasterComparison = pageRasterDiffer.compare(baselinePdfBytes, rendered.pdfBytes());
                findings.addAll(DocumentValidator.checkPageRaster(rasterComparison));
            } catch (DocumentRenderException e) {
                findings.add(new ValidationFinding(
                        ValidationFindingCode.LAYOUT_COMPARISON_UNAVAILABLE, null,
                        "Rendering the filled document to PDF failed, so the rasterized layout comparison was not run: " + e.getMessage()));
            }
        }

        Map<FieldItemRef, ValidationState> perFieldResults = perFieldValidationStates(revision, findings);
        DocumentMutationResult mutation = revisionService.applyValidationResults(
                workspaceId, userId, idempotencyKey, requestHash, documentId, expectedRevisionId, perFieldResults,
                "Recorded validation results.");
        DocumentRevision validatedRevision = mutation.revision();

        return validationRepository.save(
                workspaceId, userId, documentId, validatedRevision.id(), document.templateId(), templateVersion.id(),
                docxArtifact.id(), docxArtifact.sha256(),
                pdfArtifact == null ? null : pdfArtifact.id(), pdfArtifact == null ? null : pdfArtifact.sha256(),
                findings);
    }

    public Optional<ValidationManifest> findLatest(long workspaceId, long userId, long documentId, long revisionId) {
        return validationRepository.findLatest(workspaceId, userId, documentId, revisionId);
    }

    private List<ValidationFinding> checkEvidenceReferences(long workspaceId, long userId, DocumentRevision revision) {
        List<ValidationFinding> findings = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : revision.evidence().entrySet()) {
            for (Long spanId : entry.getValue()) {
                if (sourceSpanRepository.find(workspaceId, userId, spanId).isEmpty()) {
                    findings.add(new ValidationFinding(
                            ValidationFindingCode.INVALID_EVIDENCE_REFERENCE, entry.getKey(),
                            "Evidence span " + spanId + " cited by field " + entry.getKey() + " no longer resolves."));
                }
            }
        }
        return findings;
    }

    private List<ValidationFinding> packageIntegrityFindings(DocxExtractionOutcome outcome) {
        if (outcome instanceof DocxExtractionOutcome.Unsupported(var featureReport)) {
            return DocumentValidator.checkPackageIntegrity(featureReport);
        }
        if (outcome == null) {
            return List.of(new ValidationFinding(
                    ValidationFindingCode.PACKAGE_INTEGRITY_FAILURE, null,
                    "The freshly filled document could not be re-extracted for package integrity checking."));
        }
        return List.of();
    }

    private DocxExtractionOutcome extract(byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return structuralExtractor.extract(in);
        } catch (IOException | DocxParseException e) {
            return null;
        }
    }

    private static DocxStructuralGraph graphOf(DocxExtractionOutcome outcome) {
        return outcome instanceof DocxExtractionOutcome.Supported(var graph) ? graph : null;
    }

    /**
     * Every {@link FieldItemRef} the revision already carries a state for
     * gets the most severe result any finding named against its own {@code
     * fieldId} produced, or {@link ValidationState#PASSED} when none did --
     * every item within one repeated field shares its field-level result,
     * since no check in this task is yet item-granular; a named, honest
     * scope boundary, not an oversight.
     */
    private static Map<FieldItemRef, ValidationState> perFieldValidationStates(DocumentRevision revision, List<ValidationFinding> findings) {
        Map<String, ValidationState> worstByField = new LinkedHashMap<>();
        for (ValidationFinding finding : findings) {
            if (finding.fieldId() == null) {
                continue;
            }
            ValidationState mapped = switch (finding.severity()) {
                case BLOCKING -> ValidationState.BLOCKING;
                case WARNING -> ValidationState.WARNING;
                case INFORMATIONAL -> ValidationState.PASSED;
            };
            worstByField.merge(finding.fieldId(), mapped, ValidationService::moreSevere);
        }
        Map<FieldItemRef, ValidationState> results = new LinkedHashMap<>();
        for (FieldItemRef ref : revision.fieldStates().keySet()) {
            results.put(ref, worstByField.getOrDefault(ref.fieldId(), ValidationState.PASSED));
        }
        return results;
    }

    private static ValidationState moreSevere(ValidationState a, ValidationState b) {
        if (a == ValidationState.BLOCKING || b == ValidationState.BLOCKING) {
            return ValidationState.BLOCKING;
        }
        if (a == ValidationState.WARNING || b == ValidationState.WARNING) {
            return ValidationState.WARNING;
        }
        return ValidationState.PASSED;
    }

    /** Mirrors {@code CompilationService}/{@code TemplateQualificationService}'s own identical helper. */
    private byte[] readArtifactBytes(long workspaceId, long userId, long artifactId) {
        ReadableArtifact readable = artifactService.openContent(workspaceId, userId, artifactId);
        try (readable) {
            return readable.content().readAllBytes();
        } catch (IOException e) {
            throw new TemplateFillException(
                    TemplateFillProblemReason.UNREADABLE_TEMPLATE, "Failed to read template source artifact " + artifactId + " for validation.", e);
        }
    }

    /** Mirrors {@code CompilationService}/{@code TemplateQualificationService}'s own identical helper: a validated artifact is scanned and gated exactly like any other generated one. */
    private Artifact storeGenerated(long workspaceId, long userId, String filename, byte[] bytes) {
        Artifact allocated = artifactService.initiateUpload(workspaceId, userId, filename);
        try (InputStream content = new ByteArrayInputStream(bytes)) {
            artifactService.receiveContent(workspaceId, userId, allocated.id(), content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stage generated validation artifact bytes in memory.", e);
        }
        Artifact finalized = artifactService.finalizeUpload(workspaceId, userId, allocated.id());
        if (finalized.status() != ArtifactStatus.READY) {
            throw new GeneratedArtifactUnavailableException(
                    "Generated validation artifact " + finalized.id() + " did not reach READY (status " + finalized.status() + ").");
        }
        return finalized;
    }
}
