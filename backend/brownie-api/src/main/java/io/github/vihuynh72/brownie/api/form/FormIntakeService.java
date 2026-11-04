package io.github.vihuynh72.brownie.api.form;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import java.io.InputStream;
import java.util.List;

/**
 * The file-to-template intake the form-upload route, the {@code
 * add_form_file} tool and the form library share: the same
 * allocate-receive-scan pipeline a person's upload meets, then extraction
 * or form preparation, a draft, the spots it found marked as Brownie's
 * own, and activation -- so the answer is a template {@code
 * start_document} can use right away.
 */
public class FormIntakeService {

    private final ArtifactService artifactService;
    private final io.github.vihuynh72.brownie.core.prepare.FillableFormService fillableFormService;
    private final TemplateService templateService;
    private final io.github.vihuynh72.brownie.core.template.FillSpotReviewService fillSpotReviewService;

    public FormIntakeService(
            ArtifactService artifactService,
            io.github.vihuynh72.brownie.core.prepare.FillableFormService fillableFormService,
            TemplateService templateService,
            io.github.vihuynh72.brownie.core.template.FillSpotReviewService fillSpotReviewService) {
        this.artifactService = artifactService;
        this.fillableFormService = fillableFormService;
        this.templateService = templateService;
        this.fillSpotReviewService = fillSpotReviewService;
    }

    public IntakeResult takeIn(long workspaceId, long userId, String fileName, InputStream content) {
        return takeIn(workspaceId, userId, fileName, content, false);
    }

    /**
     * {@code shared} when the bytes are everyone's -- a library form's, so the
     * prepared answer may be kept for the next start anywhere. An upload or an
     * MCP claim passes {@code false}: nothing a person owns leaves their
     * workspace's own stores.
     */
    public IntakeResult takeIn(long workspaceId, long userId, String fileName, InputStream content, boolean shared) {
        Artifact artifact = artifactService.initiateUpload(workspaceId, userId, fileName);
        artifact = artifactService.receiveContent(workspaceId, userId, artifact.id(), content);
        artifact = artifactService.finalizeUpload(workspaceId, userId, artifact.id());
        // The same read the web's own upload-as-template step runs:
        // FillableFormService.prepare finds and names the spots (a PDF's
        // AcroForm fields and Brownie-found boxes; a Word file's candidates
        // plus the one bounded naming call, on a clean working copy). The
        // draft pins the prepared source and each prepared spot is already
        // the field it becomes; the origin goes to FOUND_BY_BROWNIE the way
        // a person's "Keep all" over the found spots would leave it.
        io.github.vihuynh72.brownie.core.prepare.FillableForm form =
                fillableFormService.prepare(workspaceId, userId, artifact.id(), shared);
        Template template = templateService.createDraft(workspaceId, userId, fileName,
                form.templateSourceArtifactId(),
                form.kind() == io.github.vihuynh72.brownie.core.prepare.FillableForm.Kind.PDF
                        ? form.notices() : null);
        List<FieldDefinition> fields = form.spots().stream()
                .map(io.github.vihuynh72.brownie.core.prepare.FillableForm.Spot::field)
                .map(fd -> new FieldDefinition(
                        fd.fieldId(), fd.type(), fd.cardinality(), fd.requiredness(),
                        fd.binding(), fd.label(), SpotOrigin.FOUND_BY_BROWNIE,
                        fd.docxControl(), fd.blankText()))
                .toList();
        TemplateVersion draft = requireDraft(workspaceId, userId, template.id());
        if (!fields.isEmpty()) {
            draft = templateService.replaceDraftBindings(
                    workspaceId, userId, template.id(), draft.versionNumber(), fields);
        }
        // A template with no places to fill is a failure worth saying back,
        // not an empty template.
        TemplateService.ActivationWithDrops activated = templateService.activateDroppingUnwritableFoundPlaces(
                workspaceId, userId, template.id(), draft.versionNumber());
        TemplateVersion active = activated.version();
        // Only the places that survived activation are kept: a place the baseline could not write was dropped from the version.
        fillSpotReviewService.keep(workspaceId, userId, template.id(),
                active.fieldDefinitions().stream().map(FieldDefinition::fieldId).toList());
        return new IntakeResult(template, active, activated.droppedLabels());
    }

    private TemplateVersion requireDraft(long workspaceId, long userId, long templateId) {
        return templateService.findDraftVersion(workspaceId, userId, templateId)
                .orElseThrow(() -> new IllegalStateException("Fresh draft " + templateId + " is gone."));
    }

    public record IntakeResult(Template template, TemplateVersion activeVersion, java.util.List<String> placesLeftOut) {
        IntakeResult(Template template, TemplateVersion activeVersion) {
            this(template, activeVersion, java.util.List.of());
        }
    }
}
