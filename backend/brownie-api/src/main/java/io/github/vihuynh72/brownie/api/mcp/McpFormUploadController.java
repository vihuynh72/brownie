package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.mcp.ClaimedFormUpload;
import io.github.vihuynh72.brownie.core.mcp.McpAuditRepository;
import io.github.vihuynh72.brownie.core.mcp.McpFormUploadService;
import io.github.vihuynh72.brownie.core.mcp.McpToolCallOutcome;
import io.github.vihuynh72.brownie.core.mcp.McpToolCallRepository;
import io.github.vihuynh72.brownie.core.mcp.PeekedFormUpload;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The anonymous upload link a chat app hands a person so a form can reach
 * Brownie. Like the download route, the presented token is the whole
 * credential: claimed atomically by digest, so unknown, expired,
 * already-used, or revoked-grant links all answer the same 404. Once
 * claimed, the intake runs as the grant's owner -- the member identity the
 * claim returned -- and the row moves to READY with the template it made,
 * or FAILED with a reason the page can show.
 */
@RestController
@ConditionalOnProperty(name = "brownie.mcp.enabled", havingValue = "true")
class McpFormUploadController {

    private static final Logger log = LoggerFactory.getLogger(McpFormUploadController.class);

    private final McpFormUploadService uploadService;
    private final ArtifactService artifactService;
    private final TemplateService templateService;
    private final io.github.vihuynh72.brownie.core.prepare.FillableFormService fillableFormService;
    private final io.github.vihuynh72.brownie.core.template.FillSpotReviewService fillSpotReviewService;
    private final McpToolCallRepository toolCallRepository;
    private final McpAuditRepository auditRepository;

    McpFormUploadController(
            McpFormUploadService uploadService,
            ArtifactService artifactService,
            TemplateService templateService,
            io.github.vihuynh72.brownie.core.prepare.FillableFormService fillableFormService,
            io.github.vihuynh72.brownie.core.template.FillSpotReviewService fillSpotReviewService,
            McpToolCallRepository toolCallRepository,
            McpAuditRepository auditRepository) {
        this.uploadService = uploadService;
        this.artifactService = artifactService;
        this.templateService = templateService;
        this.fillableFormService = fillableFormService;
        this.fillSpotReviewService = fillSpotReviewService;
        this.toolCallRepository = toolCallRepository;
        this.auditRepository = auditRepository;
    }

    @GetMapping("/api/v1/mcp-uploads/{token}")
    Map<String, Object> peek(@PathVariable String token) {
        PeekedFormUpload peeked = uploadService.peek(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (peeked.expiresAt().isBefore(OffsetDateTime.now()) && "PENDING".equals(peeked.state())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", peeked.state());
        out.put("client_name", peeked.grantName());
        out.put("expires_at", peeked.expiresAt().toString());
        return out;
    }

    // The file is the request body itself -- the same convention the
    // signed-in upload route uses, which is why the app leaves multipart
    // parsing off for everyone.
    @PostMapping("/api/v1/mcp-uploads/{token}")
    Map<String, Object> receive(
            @PathVariable String token,
            @RequestParam(required = false) String filename,
            HttpServletRequest request)
            throws IOException {
        ClaimedFormUpload claimed = uploadService.claim(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            // The same three-stage intake a person's own upload meets:
            // allocate, receive, finalize -- size and type limits, the
            // malware scan, sniffing and conversion all live inside it.
            String name = filename != null && !filename.isBlank()
                    ? filename : claimed.fileName();
            Artifact artifact = artifactService.initiateUpload(claimed.workspaceId(), claimed.userId(), name);
            artifact = artifactService.receiveContent(
                    claimed.workspaceId(), claimed.userId(), artifact.id(), request.getInputStream());
            artifact = artifactService.finalizeUpload(claimed.workspaceId(), claimed.userId(), artifact.id());
            // Then the same read the web's own upload-as-template step
            // runs: FillableFormService.prepare finds and names the spots
            // (AcroForm fields and Brownie-found boxes on a PDF; the
            // deterministic candidates plus the one bounded naming call on
            // a Word file, turned into tagged controls on a clean working
            // copy). The draft pins the prepared source, the spots go in as
            // FOUND_BY_BROWNIE just as a person's "Keep all" would leave
            // them, and the draft activates -- so the link's answer is a
            // template start_document can use now.
            io.github.vihuynh72.brownie.core.prepare.FillableForm form =
                    fillableFormService.prepare(claimed.workspaceId(), claimed.userId(), artifact.id());
            Template template = templateService.createDraft(claimed.workspaceId(), claimed.userId(), name,
                    form.templateSourceArtifactId(),
                    form.kind() == io.github.vihuynh72.brownie.core.prepare.FillableForm.Kind.PDF
                            ? form.notices() : null);
            // Each prepared spot is already the field it becomes -- binding,
            // name and all; the origin goes to FOUND_BY_BROWNIE the way a
            // person's "Keep all" over the found spots would leave it.
            java.util.List<io.github.vihuynh72.brownie.core.template.FieldDefinition> fields =
                    form.spots().stream()
                            .map(spot -> spot.field())
                            .map(fd -> new io.github.vihuynh72.brownie.core.template.FieldDefinition(
                                    fd.fieldId(), fd.type(), fd.cardinality(), fd.requiredness(),
                                    fd.binding(), fd.label(),
                                    io.github.vihuynh72.brownie.core.template.SpotOrigin.FOUND_BY_BROWNIE,
                                    fd.docxControl(), fd.blankText()))
                            .toList();
            io.github.vihuynh72.brownie.core.template.TemplateVersion draft = templateService
                    .findDraftVersion(claimed.workspaceId(), claimed.userId(), template.id())
                    .orElseThrow(() -> new IllegalStateException("Fresh draft " + template.id() + " is gone."));
            if (!fields.isEmpty()) {
                draft = templateService.replaceDraftBindings(
                        claimed.workspaceId(), claimed.userId(), template.id(), draft.versionNumber(), fields);
            }
            // A template with no places to fill is a failure worth saying
            // back, not an empty template, so the person gets told.
            io.github.vihuynh72.brownie.core.template.TemplateVersion active =
                    templateService.activate(claimed.workspaceId(), claimed.userId(), template.id(),
                            draft.versionNumber(), false);
            fillSpotReviewService.keep(claimed.workspaceId(), claimed.userId(), template.id(),
                    fields.stream().map(io.github.vihuynh72.brownie.core.template.FieldDefinition::fieldId).toList());
            uploadService.finish(claimed.workspaceId(), claimed.uploadId(), "READY", template.id(), null);
            record(claimed, McpToolCallOutcome.OK, null);
            auditRepository.formUploaded(claimed.workspaceId(), claimed.userId(), claimed.uploadId(), "READY");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("state", "READY");
            out.put("template_id", template.id());
            out.put("name", template.displayName());
            out.put("spot_count", active.fieldDefinitions().size());
            return out;
        } catch (Exception failure) {
            String reason = reason(failure);
            log.warn("mcp form upload {} failed intake: {}", claimed.uploadId(), reason);
            uploadService.finish(claimed.workspaceId(), claimed.uploadId(), "FAILED", null, reason);
            record(claimed, McpToolCallOutcome.ERROR, "INTAKE_FAILED");
            auditRepository.formUploaded(claimed.workspaceId(), claimed.userId(), claimed.uploadId(), "FAILED");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("state", "FAILED");
            out.put("reason", reason);
            return out;
        }
    }

    /** The call goes on the grant's record like a tool call: same table, same audit trail. */
    private void record(ClaimedFormUpload claimed, McpToolCallOutcome outcome, String code) {
        toolCallRepository.record(
                claimed.workspaceId(), claimed.userId(), claimed.grantId(), null,
                "mcp_form_upload", outcome, code);
    }

    private static String reason(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }
}
