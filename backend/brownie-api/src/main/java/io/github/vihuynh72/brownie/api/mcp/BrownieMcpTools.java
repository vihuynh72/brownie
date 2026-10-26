package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.export.ExportFormat;
import io.github.vihuynh72.brownie.core.export.ExportReceipt;
import io.github.vihuynh72.brownie.core.export.ExportRepository;
import io.github.vihuynh72.brownie.core.export.ExportService;
import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.mcp.McpAuditRepository;
import io.github.vihuynh72.brownie.core.mcp.McpCaller;
import io.github.vihuynh72.brownie.core.mcp.McpConfirmationHash;
import io.github.vihuynh72.brownie.core.mcp.McpDownloadFile;
import io.github.vihuynh72.brownie.core.mcp.McpDownloadLinkService;
import io.github.vihuynh72.brownie.core.mcp.McpInsufficientScopeException;
import io.github.vihuynh72.brownie.core.mcp.McpScope;
import io.github.vihuynh72.brownie.core.action.CanonicalJson;
import io.github.vihuynh72.brownie.core.question.QuestionRepository;
import io.github.vihuynh72.brownie.core.revision.Authorship;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.DocumentMutationResult;
import io.github.vihuynh72.brownie.core.revision.DocumentRevision;
import io.github.vihuynh72.brownie.core.revision.FieldItemRef;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.revision.PatchAcceptanceResult;
import io.github.vihuynh72.brownie.core.revision.PatchFieldStatus;
import io.github.vihuynh72.brownie.core.revision.PatchProposal;
import io.github.vihuynh72.brownie.core.revision.PatchProposalRepository;
import io.github.vihuynh72.brownie.core.revision.PatchProposalStatus;
import io.github.vihuynh72.brownie.core.revision.ReviewState;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.source.AttachedSource;
import io.github.vihuynh72.brownie.core.source.DocumentSourceService;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.validation.ValidationManifest;
import io.github.vihuynh72.brownie.core.validation.ValidationService;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * Brownie's MCP tool surface. Every method delegates through {@link
 * McpToolExecutor}, which records the call and wraps the outcome. The
 * workspace and person a tool acts for come from the caller in the
 * security context, never from an argument, so a call cannot point at
 * another workspace's rows; an id that names something the caller's
 * workspace does not contain answers "not found" like anything else.
 */
class BrownieMcpTools {

    /** The pasted text a source may hold: enough for a long letter, far from a dump. */
    private static final int TEXT_SOURCE_LIMIT = 100_000;

    private final McpToolExecutor executor;
    private final McpFillOrchestration fillOrchestration;
    private final TemplateService templateService;
    private final RevisionService revisionService;
    private final ArtifactService artifactService;
    private final DocumentSourceService documentSourceService;
    private final McpDownloadLinkService downloadLinkService;
    private final ExportRepository exportRepository;
    private final QuestionRepository questionRepository;
    private final PatchProposalRepository proposalRepository;
    private final ValidationService validationService;
    private final ExportService exportService;
    private final McpAuditRepository auditRepository;
    private final McpSettings settings;

    BrownieMcpTools(
            McpToolExecutor executor,
            McpFillOrchestration fillOrchestration,
            TemplateService templateService,
            RevisionService revisionService,
            ArtifactService artifactService,
            DocumentSourceService documentSourceService,
            McpDownloadLinkService downloadLinkService,
            ExportRepository exportRepository,
            QuestionRepository questionRepository,
            PatchProposalRepository proposalRepository,
            ValidationService validationService,
            ExportService exportService,
            McpAuditRepository auditRepository,
            McpSettings settings) {
        this.executor = executor;
        this.fillOrchestration = fillOrchestration;
        this.templateService = templateService;
        this.revisionService = revisionService;
        this.artifactService = artifactService;
        this.documentSourceService = documentSourceService;
        this.downloadLinkService = downloadLinkService;
        this.exportRepository = exportRepository;
        this.questionRepository = questionRepository;
        this.proposalRepository = proposalRepository;
        this.validationService = validationService;
        this.exportService = exportService;
        this.auditRepository = auditRepository;
        this.settings = settings;
    }

    @McpTool(name = BrownieMcpToolScopes.LIST_TEMPLATES,
            description = "List this workspace's templates with their fill spots, so a caller can pick what to fill.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    McpSchema.CallToolResult listTemplates() {
        return executor.run(BrownieMcpToolScopes.LIST_TEMPLATES, null, () -> {
            McpCaller caller = caller();
            require(caller, McpScope.READ);
            List<Map<String, Object>> templates = new ArrayList<>();
            for (Template template : templateService.findAll(caller.workspaceId(), caller.userId())) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("template_id", template.id());
                entry.put("name", template.displayName());
                entry.put("template_version_id", template.currentActiveVersionId());
                if (template.currentActiveVersionId() != null) {
                    templateService
                            .findVersion(caller.workspaceId(), caller.userId(), template.id(), template.currentActiveVersionId())
                            .ifPresent(version -> {
                                entry.put("version", version.versionNumber());
                                entry.put("fill_spots", fillSpots(version));
                            });
                }
                templates.add(entry);
            }
            return new McpToolExecutor.ToolResult(Map.of("templates", templates), templates.size() + " template(s).");
        });
    }
    @McpTool(name = BrownieMcpToolScopes.START_DOCUMENT,
            description = "Create a new document from a template's active version.")
    McpSchema.CallToolResult startDocument(
            @McpToolParam(description = "The template id") long template_id,
            @McpToolParam(required = false, description = "The document's title; the template's name when left out") String title) {
        return executor.run(BrownieMcpToolScopes.START_DOCUMENT, null, () -> {
            McpCaller caller = caller();
            require(caller, McpScope.FILL);
            Template template = templateService
                    .find(caller.workspaceId(), caller.userId(), template_id)
                    .orElseThrow(() -> new McpToolRefusal(McpToolRefusal.NOT_FOUND, "No template " + template_id + "."));
            Long activeVersionId = template.currentActiveVersionId();
            if (activeVersionId == null) {
                throw new McpToolRefusal(McpToolRefusal.NOT_FOUND,
                        "Template " + template_id + " has no activated version to start from.");
            }
            String documentTitle = title != null && !title.isBlank() ? title.trim() : template.displayName();
            DocumentMutationResult mutation = revisionService.createDocument(
                    caller.workspaceId(),
                    caller.userId(),
                    new IdempotencyKey("mcp-start-" + UUID.randomUUID()),
                    requestHash("start_document", "template_id", template_id),
                    documentTitle,
                    template_id,
                    activeVersionId,
                    DocumentContent.empty(),
                    Map.of(),
                    "Started through a chat app");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("document_id", mutation.document().id());
            out.put("title", mutation.document().title());
            out.put("fill_spots", templateService
                    .findVersion(caller.workspaceId(), caller.userId(), template_id, activeVersionId)
                    .map(BrownieMcpTools::fillSpots)
                    .orElse(List.of()));
            out.put("open_in_brownie", settings.documentUrl(mutation.document().id()));
            return new McpToolExecutor.ToolResult(out, "Started \"" + mutation.document().title() + "\".",
                    mutation.document().id());
        });
    }

    @McpTool(name = BrownieMcpToolScopes.GET_DOCUMENT,
            description = "Show one document: its labelled fields with values, authorship and review state, "
                    + "how many questions are open, and the pending proposal's id if one is waiting.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    McpSchema.CallToolResult getDocument(
            @McpToolParam(description = "The document id") long document_id) {
        return executor.run(BrownieMcpToolScopes.GET_DOCUMENT, document_id, () -> {
            McpCaller caller = caller();
            require(caller, McpScope.READ);
            Document document = requireDocument(caller, document_id);
            DocumentRevision revision = revisionService
                    .findRevision(caller.workspaceId(), caller.userId(), document_id, document.currentRevisionId())
                    .orElseThrow(() -> new McpToolRefusal(McpToolRefusal.NOT_FOUND, "The document's current revision is gone."));
            TemplateVersion documentVersion = templateService
                    .findVersion(caller.workspaceId(), caller.userId(), document.templateId(), revision.templateVersionId())
                    .orElse(null);
            Map<String, String> labels = labels(documentVersion);
            Map<String, List<Map<String, Object>>> statesByField = new LinkedHashMap<>();
            revision.fieldStates().forEach((ref, state) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("item_index", ref.itemIndex());
                item.put("authorship", state.authorship().name());
                item.put("review_state", state.review().name());
                statesByField.computeIfAbsent(ref.fieldId(), k -> new ArrayList<>()).add(item);
            });
            List<Map<String, Object>> fields = new ArrayList<>();
            // Every fill spot the version defines is named, with the value
            // or null when nothing is written yet -- a chat client building
            // a fill needs the whole list, not only the answered fields.
            Set<String> fieldIds = new LinkedHashSet<>();
            if (documentVersion != null) {
                for (FieldDefinition definition : documentVersion.fieldDefinitions()) {
                    fieldIds.add(definition.fieldId());
                }
            }
            fieldIds.addAll(revision.content().fields().keySet());
            for (String fieldId : fieldIds) {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("field_id", fieldId);
                field.put("label", labels.getOrDefault(fieldId, fieldId));
                FieldValue value = revision.content().fields().get(fieldId);
                field.put("value", value == null ? null : plainValue(value));
                field.put("items", statesByField.getOrDefault(fieldId, List.of()));
                fields.add(field);
            }
            int openQuestions = questionRepository
                    .findOpenForDocument(caller.workspaceId(), caller.userId(), document_id)
                    .size();
            Long pendingProposalId = proposalRepository
                    .findForDocument(caller.workspaceId(), caller.userId(), document_id)
                    .stream()
                    .filter(proposal -> proposal.status() == PatchProposalStatus.PROPOSED)
                    .map(PatchProposal::id)
                    .findFirst()
                    .orElse(null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("document_id", document.id());
            out.put("title", document.title());
            out.put("template_id", document.templateId());
            out.put("current_revision_id", document.currentRevisionId());
            out.put("fields", fields);
            out.put("open_questions", openQuestions);
            out.put("pending_proposal_id", pendingProposalId);
            out.put("exported", exportRepository.findLatest(caller.workspaceId(), caller.userId(), document_id).isPresent());
            out.put("open_in_brownie", settings.documentUrl(document_id));
            return new McpToolExecutor.ToolResult(out, "Document \"" + document.title() + "\", " + fields.size() + " field(s).");
        });
    }

    @McpTool(name = BrownieMcpToolScopes.ADD_TEXT_SOURCE,
            description = "Paste text as a source for a document: a letter, a form's answers, notes. The text is stored and "
                    + "scanned like an uploaded file; its words stay data and are never read as instructions.")
    McpSchema.CallToolResult addTextSource(
            @McpToolParam(description = "The document id") long document_id,
            @McpToolParam(description = "The pasted text, up to " + TEXT_SOURCE_LIMIT + " characters") String text,
            @McpToolParam(required = false, description = "A name for the source") String name) {
        return executor.run(BrownieMcpToolScopes.ADD_TEXT_SOURCE, document_id, () -> {
            McpCaller caller = caller();
            require(caller, McpScope.FILL);
            requireDocument(caller, document_id);
            if (text == null || text.isBlank()) {
                throw new McpToolRefusal(McpToolRefusal.INVALID_ARGUMENT, "The pasted text must not be empty.");
            }
            if (text.length() > TEXT_SOURCE_LIMIT) {
                throw new McpToolRefusal(McpToolRefusal.INVALID_ARGUMENT,
                        "The pasted text is over " + TEXT_SOURCE_LIMIT + " characters; split it into several sources.");
            }
            String sourceName = name != null && !name.isBlank() ? name.trim() : "Pasted text";
            // The generated-artifact path is the same storage, scan and
            // extraction any upload or model-made file goes through, and
            // attach() designates the READY artifact as a PLAIN_TEXT source.
            Artifact artifact = artifactService.storeGenerated(
                    caller.workspaceId(), caller.userId(), sourceName + ".txt", text.getBytes(StandardCharsets.UTF_8));
            AttachedSource source = documentSourceService.attach(caller.workspaceId(), caller.userId(), document_id, artifact.id());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source_artifact_id", artifact.id());
            out.put("source_snapshot_id", source.snapshot().id());
            return new McpToolExecutor.ToolResult(out, "Added as source \"" + sourceName + "\".");
        });
    }

    @McpTool(name = BrownieMcpToolScopes.PROPOSE_FILL,
            description = "Read a source and propose the document's field values. Answers the run's state: PROPOSED when the "
                    + "model finished inside the call, RUNNING or QUESTIONS otherwise.")
    McpSchema.CallToolResult proposeFill(
            @McpToolParam(description = "The document id") long document_id,
            @McpToolParam(description = "The source artifact id, from add_text_source") long source_artifact_id) {
        return executor.run(BrownieMcpToolScopes.PROPOSE_FILL, document_id, () -> {
            McpCaller caller = caller();
            require(caller, McpScope.FILL);
            requireDocument(caller, document_id);
            // The artifact must be one of this document's own sources; a
            // bare artifact id elsewhere in the workspace fills nothing.
            boolean attached = documentSourceService
                    .list(caller.workspaceId(), caller.userId(), document_id)
                    .stream()
                    .anyMatch(source -> source.snapshot().artifactId() == source_artifact_id);
            if (!attached) {
                throw new McpToolRefusal(McpToolRefusal.NOT_FOUND,
                        "Artifact " + source_artifact_id + " is not a source of document " + document_id + ".");
            }
            Map<String, Object> state =
                    fillOrchestration.proposeFill(caller, document_id, source_artifact_id);
            return new McpToolExecutor.ToolResult(state, "Fill run " + state.get("run_id") + ": " + state.get("state") + ".");
        });
    }

    @McpTool(name = BrownieMcpToolScopes.GET_FILL_STATUS,
            description = "Check a fill run: RUNNING, QUESTIONS, PROPOSED with the payload hash to confirm, or FAILED.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    McpSchema.CallToolResult getFillStatus(
            @McpToolParam(description = "The document id") long document_id,
            @McpToolParam(description = "The run id, from propose_fill") long run_id) {
        return executor.run(BrownieMcpToolScopes.GET_FILL_STATUS, document_id, () -> {
            McpCaller caller = caller();
            require(caller, McpScope.FILL);
            Map<String, Object> state = fillOrchestration.fillStatus(caller, document_id, run_id);
            return new McpToolExecutor.ToolResult(state, "Run " + run_id + ": " + state.get("state") + ".");
        });
    }

    @McpTool(name = BrownieMcpToolScopes.ANSWER_QUESTION,
            description = "Answer one of the run's open questions; answering the last one resumes the run.")
    McpSchema.CallToolResult answerQuestion(
            @McpToolParam(description = "The question id, from a QUESTIONS status") long question_id,
            @McpToolParam(description = "The answer text") String answer) {
        return executor.run(BrownieMcpToolScopes.ANSWER_QUESTION, null, () -> {
            McpCaller caller = caller();
            require(caller, McpScope.FILL);
            Map<String, Object> state = fillOrchestration.answerQuestion(caller, question_id, answer);
            return new McpToolExecutor.ToolResult(state, "Answer recorded.");
        });
    }

    @McpTool(name = BrownieMcpToolScopes.CONFIRM_FILL,
            description = "Accept a proposed fill: the payload hash must still name this proposal, then its values become the "
                    + "document's next revision as an accepted, reviewed fill.",
            annotations = @McpTool.McpAnnotations(destructiveHint = true))
    McpSchema.CallToolResult confirmFill(
            @McpToolParam(description = "The proposal id, from a PROPOSED status") long proposal_id,
            @McpToolParam(description = "The payload hash shown for this proposal") String payload_hash) {
        McpCaller caller = caller();
        // The call's record names the document the proposal belongs to;
        // a proposal id that names nothing still gets its refusal
        // recorded, just without a document.
        Long proposalDocumentId = proposalRepository
                .findById(caller.workspaceId(), caller.userId(), proposal_id)
                .map(PatchProposal::documentId)
                .orElse(null);
        return executor.run(BrownieMcpToolScopes.CONFIRM_FILL, proposalDocumentId, () -> {
            require(caller, McpScope.FILL);
            PatchProposal proposal = proposalRepository
                    .findById(caller.workspaceId(), caller.userId(), proposal_id)
                    .orElseThrow(() -> new McpToolRefusal(McpToolRefusal.NOT_FOUND,
                            "No proposal " + proposal_id + " in this workspace."));
            long documentId = proposal.documentId();
            Document document = requireDocument(caller, documentId);
            if (proposal.status() != PatchProposalStatus.PROPOSED) {
                throw new McpToolRefusal(McpToolRefusal.PROPOSAL_NOT_OPEN,
                        "Proposal " + proposal_id + " is already settled; get a fresh proposal before confirming.");
            }
            if (!McpConfirmationHash.sha256Hex(proposal).equals(payload_hash)) {
                throw new McpToolRefusal(McpToolRefusal.PAYLOAD_CHANGED,
                        "The payload hash does not name this proposal as it stands; check the fill's status again and confirm the hash it shows.");
            }
            PatchAcceptanceResult acceptance = revisionService.acceptPatch(
                    caller.workspaceId(),
                    caller.userId(),
                    new IdempotencyKey("mcp-confirm-" + UUID.randomUUID()),
                    requestHash("confirm_fill", "proposal_id", proposal_id),
                    documentId,
                    proposal_id,
                    document.currentRevisionId(),
                    "Confirmed in " + caller.clientName());
            if (acceptance.mutation().isPresent()) {
                // The applied fields' own review states turn ACCEPTED in
                // one revision, the same single durable step a batch
                // accept takes; a conflicting or locked field the patch
                // left alone keeps its review untouched.
                Set<String> applied = acceptance.comparison().applicableFields();
                DocumentRevision appliedRevision = acceptance.mutation().get().revision();
                Map<FieldItemRef, ReviewState> decisions = appliedRevision.fieldStates().keySet().stream()
                        .filter(ref -> applied.contains(ref.fieldId()))
                        .collect(Collectors.toMap(ref -> ref, ref -> ReviewState.ACCEPTED, (a, b) -> a, LinkedHashMap::new));
                if (!decisions.isEmpty()) {
                    revisionService.recordReviewDecisions(
                            caller.workspaceId(),
                            caller.userId(),
                            new IdempotencyKey("mcp-confirm-review-" + UUID.randomUUID()),
                            requestHash("confirm_fill", "review_for_proposal_id", proposal_id),
                            documentId,
                            appliedRevision.id(),
                            decisions,
                            "Confirmed in " + caller.clientName());
                }
            }
            auditRepository.fillConfirmed(caller.workspaceId(), caller.userId(), documentId, proposal_id);
            List<String> skipped = acceptance.comparison().fieldStatuses().entrySet().stream()
                    .filter(entry -> entry.getValue() != PatchFieldStatus.CLEAN)
                    .map(entry -> entry.getKey() + " (" + entry.getValue().name().toLowerCase(java.util.Locale.ROOT) + ")")
                    .toList();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("document_id", documentId);
            out.put("revision_id", revisionService
                    .findDocument(caller.workspaceId(), caller.userId(), documentId)
                    .map(Document::currentRevisionId)
                    .orElse(null));
            out.put("applied_fields", List.copyOf(acceptance.comparison().applicableFields()));
            out.put("skipped_fields", skipped);
            out.put("open_in_brownie", settings.documentUrl(documentId));
            return new McpToolExecutor.ToolResult(out, "Fill confirmed in " + caller.clientName() + ".");
        });
    }

    @McpTool(name = BrownieMcpToolScopes.EXPORT_DOCUMENT,
            description = "Validate and export the document as docx, pdf or both. Refused while a proposal is pending or an "
                    + "AI-composed field is still unaccepted, and refused with its blocking findings if validation finds any.",
            annotations = @McpTool.McpAnnotations(destructiveHint = true))
    McpSchema.CallToolResult exportDocument(
            @McpToolParam(description = "The document id") long document_id,
            @McpToolParam(description = "\"docx\", \"pdf\" or \"both\"") String format) {
        return executor.run(BrownieMcpToolScopes.EXPORT_DOCUMENT, document_id, () -> {
            McpCaller caller = caller();
            require(caller, McpScope.EXPORT);
            Document document = requireDocument(caller, document_id);
            String normalized = format == null ? "" : format.trim().toLowerCase(java.util.Locale.ROOT);
            ExportFormat exportFormat = switch (normalized) {
                case "docx" -> ExportFormat.DOCX;
                case "pdf" -> ExportFormat.PDF;
                case "both" -> ExportFormat.BOTH;
                default -> throw new McpToolRefusal(McpToolRefusal.INVALID_ARGUMENT,
                        "Format must be \"docx\", \"pdf\" or \"both\".");
            };
            if (proposalRepository.findForDocument(caller.workspaceId(), caller.userId(), document_id).stream()
                    .anyMatch(proposal -> proposal.status() == PatchProposalStatus.PROPOSED)) {
                throw new McpToolRefusal(McpToolRefusal.PROPOSAL_PENDING,
                        "A proposed fill is still waiting on document " + document_id + "; confirm it or leave it before exporting.");
            }
            DocumentRevision revision = revisionService
                    .findRevision(caller.workspaceId(), caller.userId(), document_id, document.currentRevisionId())
                    .orElseThrow(() -> new McpToolRefusal(McpToolRefusal.NOT_FOUND, "The document's current revision is gone."));
            Map<String, String> labels = labels(
                    templateService
                            .findVersion(caller.workspaceId(), caller.userId(), document.templateId(), revision.templateVersionId())
                            .orElse(null));
            List<String> unreviewed = revision.fieldStates().entrySet().stream()
                    .filter(entry -> entry.getValue().authorship() == Authorship.AI_COMPOSED
                            && entry.getValue().review() != ReviewState.ACCEPTED)
                    .map(entry -> labels.getOrDefault(entry.getKey().fieldId(), entry.getKey().fieldId()))
                    .distinct()
                    .toList();
            if (!unreviewed.isEmpty()) {
                throw new McpToolRefusal(McpToolRefusal.UNREVIEWED_FIELDS,
                        "These fields were proposed and never confirmed: " + String.join(", ", unreviewed) + ".");
            }
            ValidationManifest manifest = validationService.validate(
                    caller.workspaceId(),
                    caller.userId(),
                    new IdempotencyKey("mcp-validate-" + UUID.randomUUID()),
                    requestHash("export_document", "document_id", document_id),
                    document_id,
                    document.currentRevisionId());
            if (manifest.hasUnresolvedBlocking()) {
                List<String> blockers = manifest.findings().stream()
                        .filter(finding -> finding.severity() == io.github.vihuynh72.brownie.core.validation.ValidationSeverity.BLOCKING)
                        .map(finding -> finding.message())
                        .toList();
                throw new McpToolRefusal(McpToolRefusal.VALIDATION_BLOCKING,
                        "Validation says: " + String.join(" ", blockers));
            }
            exportService.approve(caller.workspaceId(), caller.userId(), document_id, manifest.id(), exportFormat);
            ExportReceipt receipt = exportService.export(caller.workspaceId(), caller.userId(), document_id);
            auditRepository.exported(caller.workspaceId(), caller.userId(), document_id, receipt.id());
            Map<String, Object> files = new LinkedHashMap<>();
            files.put("docx", receipt.docxArtifactId() != null);
            files.put("pdf", receipt.pdfArtifactId() != null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("receipt_id", receipt.id());
            out.put("files", files);
            return new McpToolExecutor.ToolResult(out, "Export recorded as receipt " + receipt.id() + ".");
        });
    }

    @McpTool(name = BrownieMcpToolScopes.GET_EXPORT_LINK,
            description = "Get a single-use download link, good for ten minutes, for one file of an export receipt.")
    McpSchema.CallToolResult getExportLink(
            @McpToolParam(description = "The export receipt id, from export_document") long receipt_id,
            @McpToolParam(description = "\"docx\" or \"pdf\"") String file) {
        return executor.run(BrownieMcpToolScopes.GET_EXPORT_LINK, null, () -> {
            McpCaller caller = caller();
            require(caller, McpScope.EXPORT);
            ExportReceipt receipt = exportRepository
                    .find(caller.workspaceId(), caller.userId(), receipt_id)
                    .orElseThrow(() -> new McpToolRefusal(McpToolRefusal.NOT_FOUND, "No export receipt " + receipt_id + "."));
            String normalized = file == null ? "" : file.trim().toLowerCase(java.util.Locale.ROOT);
            McpDownloadFile downloadFile = switch (normalized) {
                case "docx" -> McpDownloadFile.DOCX;
                case "pdf" -> McpDownloadFile.PDF;
                default -> throw new McpToolRefusal(McpToolRefusal.INVALID_ARGUMENT, "File must be \"docx\" or \"pdf\".");
            };
            McpDownloadLinkService.IssuedMcpDownload issued =
                    downloadLinkService.issue(caller.workspaceId(), caller.userId(), caller.grantId(), receipt, downloadFile);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("url", settings.downloadUrl(issued.token()));
            out.put("expires_at", issued.expiresAt().toString());
            return new McpToolExecutor.ToolResult(out, "One-time link, expires " + issued.expiresAt() + ".",
                    receipt.documentId());
        });
    }

    private McpCaller caller() {
        return McpCallerContext.require();
    }

    private Document requireDocument(McpCaller caller, long documentId) {
        return revisionService
                .findDocument(caller.workspaceId(), caller.userId(), documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private static void require(McpCaller caller, McpScope scope) {
        if (!caller.has(scope)) {
            throw new McpInsufficientScopeException(scope);
        }
    }

    /** A stable request hash for a key-minted mutation, so the command's idempotency record can still tell calls apart. */
    private static CanonicalRequestHash requestHash(String tool, String key, long id) {
        return CanonicalRequestHash.sha256OfCanonicalText(CanonicalJson.write(Map.of("tool", tool, key, id)));
    }

    private static List<Map<String, Object>> fillSpots(TemplateVersion version) {
        return version.fieldDefinitions().stream()
                .map(field -> {
                    Map<String, Object> spot = new LinkedHashMap<String, Object>();
                    spot.put("field_id", field.fieldId());
                    spot.put("label", field.displayLabel());
                    spot.put("type", field.type().name());
                    spot.put("required", field.requiredness() == FieldRequiredness.REQUIRED);
                    return spot;
                })
                .toList();
    }

    private static Map<String, String> labels(TemplateVersion version) {
        if (version == null) {
            return Map.of();
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (FieldDefinition field : version.fieldDefinitions()) {
            labels.put(field.fieldId(), field.displayLabel());
        }
        return labels;
    }

    private static Object plainValue(FieldValue value) {
        return switch (value) {
            case FieldValue.TextValue text -> text.value();
            case FieldValue.DateValue date -> date.value().toString();
            case FieldValue.RepeatedTextValue texts -> texts.values();
            case FieldValue.RepeatedDateValue dates -> dates.values().stream().map(Object::toString).toList();
        };
    }
}
