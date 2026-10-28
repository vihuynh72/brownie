package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.api.generation.GenerationOrchestrationService;
import io.github.vihuynh72.brownie.api.generation.GenerationOrchestrationService.GenerationRunView;
import io.github.vihuynh72.brownie.core.action.CanonicalJson;
import io.github.vihuynh72.brownie.core.generation.GenerationRun;
import io.github.vihuynh72.brownie.core.generation.GenerationRunRepository;
import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.CommandReceipt;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.job.Job;
import io.github.vihuynh72.brownie.core.job.JobCommandRepository;
import io.github.vihuynh72.brownie.core.job.JobEventRepository;
import io.github.vihuynh72.brownie.core.job.JobNotFoundException;
import io.github.vihuynh72.brownie.core.job.JobState;
import io.github.vihuynh72.brownie.core.mcp.McpAppliedRunRepository;
import io.github.vihuynh72.brownie.core.mcp.McpCaller;
import io.github.vihuynh72.brownie.core.mcp.McpConfirmationHash;
import io.github.vihuynh72.brownie.core.question.Question;
import io.github.vihuynh72.brownie.core.question.QuestionService;
import io.github.vihuynh72.brownie.core.question.QuestionStatus;
import io.github.vihuynh72.brownie.core.revision.PatchProposal;
import io.github.vihuynh72.brownie.core.revision.PatchProposalRepository;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.source.DocumentEvidence;
import io.github.vihuynh72.brownie.core.source.DocumentSourceService;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The run-state machine behind {@code propose_fill}, {@code
 * get_fill_status} and {@code answer_question}. A proposal a call reports
 * is the run's one application: the first status answer that finds a
 * finished run applies its result and records the pairing, so every later
 * answer -- a retry, a second status call -- names the same proposal
 * rather than starting over.
 *
 * <p>Waiting is bounded polling inside the request: up to
 * {@value #POLL_BUDGET_MS} ms, so a chat client gets a finished answer
 * when the model is quick and a {@code RUNNING} it can poll when not.
 * A second call for a document and source whose run is still going joins
 * it rather than starting a twin: a JSON-RPC request id is only unique
 * per connection, so it cannot key idempotent starts -- the document and
 * artifact name the work, and a call that finds no live run starts one
 * with a fresh key.
 */
class McpFillOrchestration {

    private static final long POLL_BUDGET_MS = 40_000;
    private static final long POLL_SLEEP_MS = 500;

    private final GenerationOrchestrationService orchestrationService;
    private final GenerationRunRepository runRepository;
    private final JobCommandRepository jobRepository;
    private final io.github.vihuynh72.brownie.core.job.JobEventRepository jobEventRepository;
    private final QuestionService questionService;
    private final PatchProposalRepository proposalRepository;
    private final McpAppliedRunRepository appliedRunRepository;
    private final RevisionService revisionService;
    private final TemplateService templateService;
    private final DocumentSourceService documentSourceService;
    private final McpSettings settings;

    McpFillOrchestration(
            GenerationOrchestrationService orchestrationService,
            GenerationRunRepository runRepository,
            JobCommandRepository jobRepository,
            io.github.vihuynh72.brownie.core.job.JobEventRepository jobEventRepository,
            QuestionService questionService,
            PatchProposalRepository proposalRepository,
            McpAppliedRunRepository appliedRunRepository,
            RevisionService revisionService,
            TemplateService templateService,
            DocumentSourceService documentSourceService,
            McpSettings settings) {
        this.orchestrationService = orchestrationService;
        this.runRepository = runRepository;
        this.jobRepository = jobRepository;
        this.jobEventRepository = jobEventRepository;
        this.questionService = questionService;
        this.proposalRepository = proposalRepository;
        this.appliedRunRepository = appliedRunRepository;
        this.revisionService = revisionService;
        this.templateService = templateService;
        this.documentSourceService = documentSourceService;
        this.settings = settings;
    }

    /** Starts extraction for the document from the named source artifact, then waits for it within the call's budget. */
    Map<String, Object> proposeFill(McpCaller caller, long documentId, long sourceArtifactId) {
        long workspaceId = caller.workspaceId();
        long userId = caller.userId();
        // A document whose template has no places to fill cannot take a
        // proposal; say so up front rather than spending a run on it.
        var spotlessDoc = revisionService.findDocument(workspaceId, userId, documentId);
        if (spotlessDoc.isPresent()) {
            var spotlessRev = revisionService.findRevision(
                    workspaceId, userId, documentId, spotlessDoc.get().currentRevisionId());
            if (spotlessRev.isPresent()) {
                var spotlessVersion = templateService.findVersion(
                        workspaceId, userId, spotlessDoc.get().templateId(), spotlessRev.get().templateVersionId());
                if (spotlessVersion.isPresent() && spotlessVersion.get().fieldDefinitions().isEmpty()) {
                    throw new McpToolRefusal(McpToolRefusal.NO_FILL_SPOTS,
                            "This document's template has no places to fill, so there is nothing to propose.");
                }
            }
        }
        // A run already in flight for this document and source is the
        // answer: retried and repeated calls join it instead of starting
        // another reading of the same source over the same document.
        GenerationRun inFlight = orchestrationService.listRuns(workspaceId, userId, documentId).stream()
                .filter(view -> view.run().sourceArtifactId() == sourceArtifactId)
                .filter(view -> !view.job().state().isTerminal()
                        && view.job().state() != JobState.CANCEL_REQUESTED)
                .map(GenerationRunView::run)
                .findFirst()
                .orElse(null);
        if (inFlight != null) {
            return runState(caller, documentId, inFlight, POLL_BUDGET_MS);
        }
        IdempotencyKey idempotencyKey = new IdempotencyKey("mcp-fill-" + UUID.randomUUID());
        CanonicalRequestHash requestHash = CanonicalRequestHash.sha256OfCanonicalText(CanonicalJson.write(Map.of(
                "tool", BrownieMcpToolScopes.PROPOSE_FILL,
                "document", documentId,
                "source", sourceArtifactId)));
        CommandReceipt receipt =
                orchestrationService.startExtraction(workspaceId, userId, documentId, sourceArtifactId, idempotencyKey, requestHash);
        GenerationRun run = runRepository
                .findByJob(workspaceId, userId, receipt.jobId())
                .orElseThrow(() -> new IllegalStateException("Extraction job " + receipt.jobId() + " has no recorded run."));
        return runState(caller, documentId, run, POLL_BUDGET_MS);
    }

    /** The run's current state, applying a finished result exactly once. Never starts anything. */
    Map<String, Object> fillStatus(McpCaller caller, long documentId, long runId) {
        GenerationRun run = findRun(caller, documentId, runId);
        return runState(caller, documentId, run, 0);
    }

    /**
     * Records the answer to a run's open question, and when that was the
     * last one resumes the job -- the same resume the web flow requests.
     * Answers the run's state once resumed, or after recording.
     */
    Map<String, Object> answerQuestion(McpCaller caller, long questionId, String answer) {
        if (answer == null || answer.isBlank()) {
            throw new McpToolRefusal(McpToolRefusal.INVALID_ARGUMENT, "An answer must not be blank.");
        }
        long workspaceId = caller.workspaceId();
        long userId = caller.userId();
        Question question = questionService.answer(workspaceId, userId, questionId, answer);
        if (question.generationRunId() == null) {
            return Map.of("state", "ANSWERED", "question_id", questionId);
        }
        long documentId = question.documentId();
        GenerationRun run = findRun(caller, documentId, question.generationRunId());
        boolean openLeft = orchestrationService
                .openQuestions(workspaceId, userId, documentId, run.jobId())
                .stream()
                .anyMatch(q -> q.status() == QuestionStatus.OPEN);
        if (!openLeft) {
            IdempotencyKey resumeKey = new IdempotencyKey("mcp-resume-" + UUID.randomUUID());
            CanonicalRequestHash resumeHash = CanonicalRequestHash.sha256OfCanonicalText(CanonicalJson.write(Map.of(
                    "tool", BrownieMcpToolScopes.ANSWER_QUESTION, "job", run.jobId())));
            orchestrationService.resumeAfterQuestions(workspaceId, userId, documentId, run.jobId(), resumeKey, resumeHash);
        }
        return runState(caller, documentId, run, 0);
    }

    private GenerationRun findRun(McpCaller caller, long documentId, long runId) {
        // Asked through the document-scoped listing so a run that belongs
        // to another document -- even in this workspace -- is "not found",
        // not misrouted.
        return orchestrationService.listRuns(caller.workspaceId(), caller.userId(), documentId).stream()
                .map(GenerationRunView::run)
                .filter(run -> run.id() == runId)
                .findFirst()
                .orElseThrow(() -> new McpToolRefusal(McpToolRefusal.NOT_FOUND, "No run " + runId + " on document " + documentId + "."));
    }

    private Map<String, Object> runState(McpCaller caller, long documentId, GenerationRun run, long budgetMs) {
        long workspaceId = caller.workspaceId();
        long userId = caller.userId();
        long deadline = System.nanoTime() + Duration.ofMillis(budgetMs).toNanos();
        while (true) {
            Job job = jobRepository
                    .find(workspaceId, userId, run.jobId())
                    .orElseThrow(() -> new JobNotFoundException(run.jobId()));
            switch (job.state()) {
                case SUCCEEDED -> {
                    return proposedState(caller, documentId, run);
                }
                case FAILED, DEAD, CANCELLED -> {
                    Map<String, Object> failed = new LinkedHashMap<>();
                    failed.put("state", "FAILED");
                    failed.put("run_id", run.id());
                    failed.put("code", job.state().name());
                    failed.put("open_in_brownie", settings.documentUrl(documentId));
                    // The job's own last words, when it left any, in the person's language.
                    jobEventRepository.latestForJob(workspaceId, caller.userId(), job.id())
                            .map(io.github.vihuynh72.brownie.core.job.JobEvent::safeMessage)
                            .filter(message -> !message.isBlank())
                            .ifPresent(message -> failed.put("reason", message));
                    return failed;
                }
                case WAITING_FOR_INPUT -> {
                    List<Question> open = orchestrationService.openQuestions(workspaceId, userId, documentId, run.jobId());
                    if (!open.isEmpty()) {
                        return questionsState(caller, documentId, run, open);
                    }
                }
                default -> {
                }
            }
            if (System.nanoTime() >= deadline) {
                Map<String, Object> running = new LinkedHashMap<>();
                running.put("state", "RUNNING");
                running.put("run_id", run.id());
                running.put("open_in_brownie", settings.documentUrl(documentId));
                return running;
            }
            try {
                Thread.sleep(POLL_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the run to finish.", e);
            }
        }
    }

    /**
     * A finished run's result as a proposal: applied and recorded the
     * first time, read back every time after -- the pairing is durable,
     * so a retried or repeated status can never apply it twice.
     */
    private Map<String, Object> proposedState(McpCaller caller, long documentId, GenerationRun run) {
        long workspaceId = caller.workspaceId();
        long userId = caller.userId();
        Long proposalId = appliedRunRepository.findProposal(workspaceId, userId, run.id()).orElse(null);
        if (proposalId == null) {
            PatchProposal applied = orchestrationService
                    .applyResultAsPatchProposal(workspaceId, userId, documentId, run.jobId())
                    .proposal();
            appliedRunRepository.record(workspaceId, userId, run.id(), documentId, applied.id());
            // A raced twin apply may have lost its own insert; the recorded
            // proposal -- whoever's -- is the one answer.
            proposalId = appliedRunRepository.findProposal(workspaceId, userId, run.id()).orElse(applied.id());
        }
        PatchProposal proposal = proposalRepository
                .findById(workspaceId, userId, proposalId)
                .orElseThrow(() -> new McpToolRefusal(McpToolRefusal.NOT_FOUND, "The recorded proposal is gone."));

        Map<String, String> labels = labelsFor(caller, documentId, proposal);
        List<Map<String, Object>> summary = new ArrayList<>();
        proposal.proposedValues().forEach((fieldId, value) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field_id", fieldId);
            entry.put("label", labels.getOrDefault(fieldId, fieldId));
            entry.put("proposed_value", plainValue(value));
            entry.put("evidence_excerpt", evidenceExcerpt(caller, documentId, proposal.proposedEvidence().get(fieldId)));
            summary.add(entry);
        });

        Map<String, Object> proposed = new LinkedHashMap<>();
        proposed.put("state", "PROPOSED");
        proposed.put("run_id", run.id());
        proposed.put("proposal_id", proposal.id());
        proposed.put("payload_hash", McpConfirmationHash.sha256Hex(proposal));
        proposed.put("summary", summary);
        proposed.put("open_in_brownie", settings.documentUrl(documentId));
        return proposed;
    }

    private Map<String, Object> questionsState(McpCaller caller, long documentId, GenerationRun run, List<Question> open) {
        Map<String, String> labels = labelsFor(caller, documentId, null);
        List<Map<String, Object>> questions = open.stream()
                .map(question -> {
                    Map<String, Object> q = new LinkedHashMap<>();
                    q.put("question_id", question.id());
                    q.put("field_id", question.fieldId());
                    q.put("label", labels.getOrDefault(question.fieldId(), question.fieldId()));
                    q.put("reason", question.reason().name());
                    q.put("options", question.candidates().stream().map(candidate -> {
                        Map<String, Object> option = new LinkedHashMap<>();
                        option.put("value", candidate.value());
                        return option;
                    }).toList());
                    return (Map<String, Object>) q;
                })
                .toList();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("state", "QUESTIONS");
        state.put("run_id", run.id());
        state.put("questions", questions);
        state.put("open_in_brownie", settings.documentUrl(documentId));
        return state;
    }

    private Map<String, String> labelsFor(McpCaller caller, long documentId, PatchProposal proposal) {
        // Labels come from the version the proposal (or document) is based
        // on; a field that names no definition keeps its id as its label.
        long workspaceId = caller.workspaceId();
        long userId = caller.userId();
        var document = revisionService.findDocument(workspaceId, userId, documentId);
        if (document.isEmpty()) {
            return Map.of();
        }
        long templateVersionId = proposal != null
                ? revisionService
                        .findRevision(workspaceId, userId, documentId, proposal.baseRevisionId())
                        .map(revision -> revision.templateVersionId())
                        .orElse(0L)
                : document.get().templateVersionId();
        if (templateVersionId == 0) {
            return Map.of();
        }
        return templateService.findVersion(workspaceId, userId, document.get().templateId(), templateVersionId)
                .map(version -> version.fieldDefinitions().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                FieldDefinition::fieldId,
                                FieldDefinition::displayLabel,
                                (a, b) -> a)))
                .orElse(Map.of());
    }

    /** The source text a proposed value cites, bounded and inert: it is data in a field, never words the tool acts on. */
    private String evidenceExcerpt(McpCaller caller, long documentId, List<Long> spanIds) {
        if (spanIds == null || spanIds.isEmpty()) {
            return null;
        }
        try {
            DocumentEvidence evidence = documentSourceService.excerpt(
                    caller.workspaceId(), caller.userId(), documentId, spanIds.get(0));
            String text = evidence.excerptText();
            return text.length() <= 300 ? text : text.substring(0, 300);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A field value as a client sees it: the string, or the list of strings for a repeated field. */
    private static Object plainValue(io.github.vihuynh72.brownie.core.revision.FieldValue value) {
        return switch (value) {
            case io.github.vihuynh72.brownie.core.revision.FieldValue.TextValue text -> text.value();
            case io.github.vihuynh72.brownie.core.revision.FieldValue.DateValue date -> date.value().toString();
            case io.github.vihuynh72.brownie.core.revision.FieldValue.RepeatedTextValue texts -> texts.values();
            case io.github.vihuynh72.brownie.core.revision.FieldValue.RepeatedDateValue dates ->
                dates.values().stream().map(Object::toString).toList();
        };
    }
}
