package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.prepare.PdfFormPreparation;
import io.github.vihuynh72.brownie.core.prepare.PdfFormPreparationService;
import io.github.vihuynh72.brownie.core.template.CandidateFieldBinding;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import java.io.InputStream;
import java.util.List;

/**
 * The file-to-template intake the form-upload route and the
 * {@code add_form_file} tool share: the same allocate-receive-scan pipeline
 * a person's upload meets, then extraction or form preparation, a draft,
 * the spots it found marked as Brownie's own, and activation -- so the
 * answer is a template {@code start_document} can use right away.
 */
class McpFormIntakeService {

    private final ArtifactService artifactService;
    private final DocumentExtractionService documentExtractionService;
    private final PdfFormPreparationService pdfFormPreparationService;
    private final TemplateService templateService;

    McpFormIntakeService(
            ArtifactService artifactService,
            DocumentExtractionService documentExtractionService,
            PdfFormPreparationService pdfFormPreparationService,
            TemplateService templateService) {
        this.artifactService = artifactService;
        this.documentExtractionService = documentExtractionService;
        this.pdfFormPreparationService = pdfFormPreparationService;
        this.templateService = templateService;
    }

    IntakeResult takeIn(long workspaceId, long userId, String fileName, InputStream content) {
        Artifact artifact = artifactService.initiateUpload(workspaceId, userId, fileName);
        artifact = artifactService.receiveContent(workspaceId, userId, artifact.id(), content);
        artifact = artifactService.finalizeUpload(workspaceId, userId, artifact.id());
        Template template;
        TemplateVersion active;
        if (artifact.detectedMediaType() == SupportedMediaType.PDF) {
            PdfFormPreparation prepared = pdfFormPreparationService.prepare(workspaceId, userId, artifact.id());
            template = templateService.createDraft(workspaceId, userId, fileName, artifact.id(), prepared.notices());
            TemplateVersion draft = requireDraft(workspaceId, userId, template.id());
            active = templateService.activate(workspaceId, userId, template.id(), draft.versionNumber(), true);
        } else {
            documentExtractionService.extract(workspaceId, userId, artifact.id());
            template = templateService.createDraft(workspaceId, userId, fileName, artifact.id());
            List<FieldDefinition> fields = templateService
                    .proposeCandidateBindings(workspaceId, userId, template.id())
                    .candidates().stream()
                    .map(this::asFoundField)
                    .toList();
            TemplateVersion draft = requireDraft(workspaceId, userId, template.id());
            if (!fields.isEmpty()) {
                draft = templateService.replaceDraftBindings(
                        workspaceId, userId, template.id(), draft.versionNumber(), fields);
            }
            active = templateService.activate(workspaceId, userId, template.id(), draft.versionNumber(), true);
        }
        return new IntakeResult(template, active);
    }

    /** A candidate found by the extraction, marked the way the web marks spots Brownie found rather than the form brought. */
    private FieldDefinition asFoundField(CandidateFieldBinding c) {
        return new FieldDefinition(
                c.fieldId(), c.type(), c.cardinality(), FieldRequiredness.OPTIONAL, c.binding(),
                null, SpotOrigin.FOUND_BY_BROWNIE, null, null);
    }

    private TemplateVersion requireDraft(long workspaceId, long userId, long templateId) {
        return templateService.findDraftVersion(workspaceId, userId, templateId)
                .orElseThrow(() -> new IllegalStateException("Fresh draft " + templateId + " is gone."));
    }

    record IntakeResult(Template template, TemplateVersion activeVersion) {}
}
