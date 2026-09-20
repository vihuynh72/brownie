package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.api.job.CanonicalRequestHasher;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStorageException;
import io.github.vihuynh72.brownie.core.artifact.BlobAlreadyExistsException;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.generation.ExtractionInputBundle;
import io.github.vihuynh72.brownie.core.generation.ExtractionPromptBuilder;
import io.github.vihuynh72.brownie.core.generation.ExtractionResult;
import io.github.vihuynh72.brownie.core.generation.ExtractionService;
import io.github.vihuynh72.brownie.core.generation.FieldCandidate;
import io.github.vihuynh72.brownie.core.generation.GenerationJobTypes;
import io.github.vihuynh72.brownie.core.generation.GenerationRun;
import io.github.vihuynh72.brownie.core.generation.GenerationRunRepository;
import io.github.vihuynh72.brownie.core.generation.LabeledExcerpt;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.UsageService;
import io.github.vihuynh72.brownie.core.generation.RepeatedItemCandidate;
import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.CommandReceipt;
import io.github.vihuynh72.brownie.core.job.EnqueueJobCommand;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.job.Job;
import io.github.vihuynh72.brownie.core.job.JobCommandRepository;
import io.github.vihuynh72.brownie.core.job.JobNotFoundException;
import io.github.vihuynh72.brownie.core.job.JobOutputArtifactRepository;
import io.github.vihuynh72.brownie.core.job.JobStage;
import io.github.vihuynh72.brownie.core.job.JobTarget;
import io.github.vihuynh72.brownie.core.job.JobType;
import io.github.vihuynh72.brownie.core.job.ResumeJobCommand;
import io.github.vihuynh72.brownie.core.question.DetectedQuestionsBundle;
import io.github.vihuynh72.brownie.core.question.Question;
import io.github.vihuynh72.brownie.core.question.QuestionService;
import io.github.vihuynh72.brownie.core.question.QuestionStatus;
import io.github.vihuynh72.brownie.core.question.ResolvedAnswerBundle;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.revision.DocumentRevision;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.revision.PatchProposal;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.source.DocumentSourceRepository;
import io.github.vihuynh72.brownie.core.source.SourceService;
import io.github.vihuynh72.brownie.core.source.SourceSnapshot;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Starts the first real generation stage -- grounded fact extraction --
 * for a document, without ever putting a paid model call on this request's
 * own thread. This service does the "gathering" work that needs real
 * tenant access (reading the document's template fields and the attached
 * source's cited excerpts), freezes both into one immutable, content-
 * addressed bundle in blob storage, then enqueues a durable job naming
 * only that bundle's own hash -- never source text, never field content --
 * for the trusted worker to pick up. The worker (see {@code
 * GenerationExtractionJobProcessor}) has no tenant-database access at all;
 * it reads this exact bundle back by the identical hash and calls the
 * model, entirely independent of this process.
 *
 * <p>Starting an extraction also records a {@link GenerationRun} for the
 * document in the same transaction as the job it enqueues, and links the
 * source to the document. Every method that takes a job ID first proves,
 * through that tenant-scoped run record, that the job is a run of this
 * exact document in this workspace, before touching any blob the job
 * names: the pending-questions and resolved-answers objects are keyed by
 * workspace and job, so without that check a caller could read another
 * workspace's staged candidate values, or overwrite its answers, simply
 * by guessing numbers.
 */
@Service
public class GenerationOrchestrationService {

    /** Re-exposed for this package's own callers; the shared, cross-module value lives in {@link GenerationJobTypes} so the worker can reference it without ever depending on this module. */
    public static final String EXTRACTION_JOB_TYPE = GenerationJobTypes.EXTRACTION_JOB_TYPE;
    public static final String EXTRACTION_RESULT_OUTPUT_KIND = GenerationJobTypes.EXTRACTION_RESULT_OUTPUT_KIND;

    private static final Logger log = LoggerFactory.getLogger(GenerationOrchestrationService.class);
    private static final String EXTRACTING_STAGE = "extracting";
    private static final String DOCUMENT_RESOURCE_TYPE = "document";
    private static final long MAX_BUNDLE_BYTES = 2_000_000;
    /** The least a run's first request can hold: one input token and the most an extraction may write back. */
    private static final BigDecimal SMALLEST_FIRST_REQUEST_USD =
            ModelPricing.gpt5Mini().estimateCost(1, ExtractionService.MAX_OUTPUT_TOKENS);
    private static final int SKIPPED_ITEM_DESCRIPTION_MAX_LENGTH = 120;

    /**
     * Which of a template's own scalar TEXT fields get re-synthesized by
     * {@code CompositionService} rather than trusted as extraction's own
     * more literal first pass -- a real, deliberately narrow, hardcoded
     * convention rather than a general per-template composability model,
     * matching the one composable field ({@code meeting.decisions}) that
     * actually exists in the built-in templates today. Whatever later
     * wants a caller-chosen or per-template set of composable fields
     * replaces this constant, not the pipeline built around it.
     */
    private static final Set<String> COMPOSABLE_FIELD_IDS = Set.of("meeting.decisions");

    private final RevisionService revisionService;
    private final TemplateService templateService;
    private final SourceService sourceService;
    private final ExtractionService extractionService;
    private final QuestionService questionService;
    private final ArtifactService artifactService;
    private final JobCommandRepository jobCommandRepository;
    private final JobOutputArtifactRepository jobOutputArtifactRepository;
    private final GenerationRunRepository generationRunRepository;
    private final DocumentSourceRepository documentSourceRepository;
    private final BlobStore blobStore;
    private final CanonicalRequestHasher canonicalRequestHasher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final UsageService usageService;
    private final String modelName;

    public GenerationOrchestrationService(
            RevisionService revisionService,
            TemplateService templateService,
            SourceService sourceService,
            ExtractionService extractionService,
            QuestionService questionService,
            ArtifactService artifactService,
            JobCommandRepository jobCommandRepository,
            JobOutputArtifactRepository jobOutputArtifactRepository,
            GenerationRunRepository generationRunRepository,
            DocumentSourceRepository documentSourceRepository,
            BlobStore blobStore,
            CanonicalRequestHasher canonicalRequestHasher,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            UsageService usageService,
            @Value("${brownie.ai.openai.model}") String modelName) {
        this.revisionService = revisionService;
        this.templateService = templateService;
        this.sourceService = sourceService;
        this.extractionService = extractionService;
        this.questionService = questionService;
        this.artifactService = artifactService;
        this.jobCommandRepository = jobCommandRepository;
        this.jobOutputArtifactRepository = jobOutputArtifactRepository;
        this.generationRunRepository = generationRunRepository;
        this.documentSourceRepository = documentSourceRepository;
        this.blobStore = blobStore;
        this.canonicalRequestHasher = canonicalRequestHasher;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.usageService = usageService;
        this.modelName = modelName;
    }

    public CommandReceipt startExtraction(
            long workspaceId,
            long userId,
            long documentId,
            long sourceArtifactId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash) {
        Document document = revisionService
                .findDocument(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        // Asked before anything is attached, segmented, staged or queued: the
        // ledger refuses each request when it is about to be sent, but a run
        // that cannot make even its first one should not be started at all.
        // A replay of a start that was already accepted is answered with
        // that start, whatever has been spent since.
        if (!jobCommandRepository.enqueueWasAccepted(workspaceId, userId, idempotencyKey)) {
            usageService.requireAllowanceFor(workspaceId, userId, SMALLEST_FIRST_REQUEST_USD);
        }
        TemplateVersion templateVersion = templateService
                .findVersion(workspaceId, userId, document.templateId(), document.templateVersionId())
                .orElseThrow(() -> new TemplateVersionNotFoundException(document.templateId(), document.templateVersionId()));
        DocumentContent existingContent = revisionService
                .findRevision(workspaceId, userId, documentId, document.currentRevisionId())
                .map(DocumentRevision::content)
                .orElseGet(DocumentContent::empty);
        SourceSnapshot snapshot = sourceService.attachSnapshot(workspaceId, userId, sourceArtifactId);
        documentSourceRepository.link(workspaceId, userId, documentId, snapshot.id());
        List<LabeledExcerpt> excerpts = extractionService.segmentAndCiteSource(workspaceId, userId, snapshot);
        List<String> composableFieldIds = templateVersion.fieldDefinitions().stream()
                .map(FieldDefinition::fieldId)
                .filter(COMPOSABLE_FIELD_IDS::contains)
                .toList();
        ExtractionInputBundle bundle = ExtractionInputBundle.from(templateVersion.fieldDefinitions(), excerpts, existingContent, composableFieldIds);

        CanonicalRequestHash bundleHash = canonicalRequestHasher.hash(bundle);
        boolean bundleWrittenByThisCall = writeBundleIfAbsent(workspaceId, bundleHash, bundle);

        // The job and the run that owns it are one fact: a job with no run
        // could never be read back through a document, and a run naming a
        // job that was never enqueued would wait forever, so neither is
        // committed without the other. A replay under the same idempotency
        // key returns the same job and therefore the same run. Recording
        // the run also takes the document's row lock and refuses a document
        // that went to the trash since it was read above, so no job is ever
        // committed for one.
        CommandReceipt receipt;
        try {
            receipt = enqueueWithRun(workspaceId, userId, documentId, document, snapshot, idempotencyKey, requestHash, bundleHash);
        } catch (RuntimeException failure) {
            if (bundleWrittenByThisCall) {
                deleteUnownedBundle(workspaceId, bundleHash);
            }
            throw failure;
        }
        // The bundle holds the source's text, and permanent deletion finds a
        // bundle only through the run that names it. A replayed start cites
        // freshly made spans, so it wrote a bundle no run will ever name;
        // that one is removed here rather than left where nothing reaches it.
        if (bundleWrittenByThisCall && !runNamesBundle(workspaceId, userId, receipt.jobId(), bundleHash)) {
            deleteUnownedBundle(workspaceId, bundleHash);
        }
        return receipt;
    }

    private CommandReceipt enqueueWithRun(
            long workspaceId,
            long userId,
            long documentId,
            Document document,
            SourceSnapshot snapshot,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            CanonicalRequestHash bundleHash) {
        return transactionTemplate.execute(status -> {
            CommandReceipt receipt = jobCommandRepository.enqueue(
                    workspaceId,
                    userId,
                    new EnqueueJobCommand(
                            idempotencyKey,
                            requestHash,
                            new JobType(EXTRACTION_JOB_TYPE),
                            new JobTarget(DOCUMENT_RESOURCE_TYPE, documentId, document.currentRevisionId()),
                            new JobStage(EXTRACTING_STAGE),
                            bundleHash,
                            OffsetDateTime.now()));
            generationRunRepository.record(
                    workspaceId,
                    userId,
                    new GenerationRunRepository.NewGenerationRun(
                            documentId,
                            document.currentRevisionId(),
                            document.templateVersionId(),
                            snapshot.id(),
                            receipt.jobId(),
                            bundleHash.value(),
                            modelName,
                            ExtractionPromptBuilder.PROMPT_VERSION));
            return receipt;
        });
    }

    private boolean runNamesBundle(long workspaceId, long userId, long jobId, CanonicalRequestHash bundleHash) {
        return generationRunRepository
                .findByJob(workspaceId, userId, jobId)
                .map(run -> run.bundleHash().equals(bundleHash.value()))
                .orElse(false);
    }

    /** Best effort: a bundle that could not be removed here is reported, since nothing else will ever look for it. */
    private void deleteUnownedBundle(long workspaceId, CanonicalRequestHash bundleHash) {
        String objectKey = GenerationJobTypes.inputBundleObjectKey(workspaceId, bundleHash.value());
        try {
            blobStore.delete(objectKey);
        } catch (IOException e) {
            log.error("Could not remove generation input bundle {} that no run names.", objectKey, e);
        }
    }

    /**
     * The published result of one of this document's own runs. Goes through
     * the same run check as every other read of a run, so a job that is not
     * this document's, and any job of a document in the trash, is not found.
     */
    public long requireResultArtifactId(long workspaceId, long userId, long documentId, long jobId) {
        requireRun(workspaceId, userId, documentId, jobId);
        return jobOutputArtifactRepository
                .findArtifactId(workspaceId, userId, jobId, GenerationJobTypes.EXTRACTION_RESULT_OUTPUT_KIND)
                .orElseThrow(() -> new GenerationResultNotFoundException(jobId));
    }

    /**
     * Every run started for this document, most recent first, each paired
     * with the current state of the job that carries it and the published
     * result artifact once there is one. The document itself must exist in
     * this workspace.
     */
    public List<GenerationRunView> listRuns(long workspaceId, long userId, long documentId) {
        revisionService.findDocument(workspaceId, userId, documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        List<GenerationRunView> views = new ArrayList<>();
        for (GenerationRun run : generationRunRepository.findForDocument(workspaceId, userId, documentId)) {
            Job job = jobCommandRepository
                    .find(workspaceId, userId, run.jobId())
                    .orElseThrow(() -> new IllegalStateException("Generation run " + run.id() + " names job " + run.jobId() + ", which does not exist."));
            Optional<Long> resultArtifactId =
                    jobOutputArtifactRepository.findArtifactId(workspaceId, userId, run.jobId(), GenerationJobTypes.EXTRACTION_RESULT_OUTPUT_KIND);
            views.add(new GenerationRunView(run, job, resultArtifactId.orElse(null)));
        }
        return views;
    }

    /** One run as a caller sees it: the record itself, the job carrying it right now, and its published result artifact, if any yet. */
    public record GenerationRunView(GenerationRun run, Job job, Long resultArtifactId) {
    }

    /**
     * The document's open questions, materializing them from the worker's
     * own {@link DetectedQuestionsBundle} the first time they are asked
     * for. The worker cannot insert a real, answerable {@code Question}
     * row itself -- it has no tenant-database access at all -- so it
     * stages its detected questions at a deterministic, job-scoped blob key
     * instead ({@code GenerationExtractionJobProcessor}'s own counterpart
     * to this method); persisting from it here, exactly once, is what
     * turns that staged data into something a person can actually answer.
     * Scoped to this run and to the job's current attempt: once the
     * attempt's questions exist as rows, this never re-reads the blob or
     * persists again (a reload after every question was answered returns
     * an empty list, not a second copy of the same questions), and a
     * bundle staged by a superseded attempt is ignored rather than put
     * in front of a person.
     */
    public List<Question> openQuestions(long workspaceId, long userId, long documentId, long jobId) {
        GenerationRun run = requireRun(workspaceId, userId, documentId, jobId);
        Job job = requireJob(workspaceId, userId, jobId);
        List<Question> forRun = questionService.allQuestionsForRun(workspaceId, userId, run.id());
        boolean materializedForThisAttempt = forRun.stream()
                .anyMatch(question -> question.attemptFencingToken() != null && question.attemptFencingToken() == job.fencingToken());
        if (materializedForThisAttempt) {
            return forRun.stream().filter(question -> question.status() == QuestionStatus.OPEN).toList();
        }
        DetectedQuestionsBundle pending = readPendingQuestions(workspaceId, jobId);
        if (pending == null || pending.questions().isEmpty()) {
            return List.of();
        }
        if (pending.fencingToken() != job.fencingToken()) {
            log.info("Ignoring pending questions staged by attempt {} of job {}; the current attempt is {}.",
                    pending.fencingToken(), jobId, job.fencingToken());
            return List.of();
        }
        return questionService.persistDetected(workspaceId, userId, documentId, run.id(), job.fencingToken(), pending.questions());
    }

    /**
     * Freezes every currently answered question for this document into a
     * {@link ResolvedAnswerBundle} at a deterministic, job-scoped blob key,
     * then requests the job's resume -- the worker reads this exact bundle
     * back on its next claim ({@code GenerationExtractionJobProcessor}) so
     * an already-settled field is never re-asked. A question left OPEN
     * (the person has not answered everything yet) is simply not included;
     * the worker's own re-detection naturally raises it again if so, the
     * same durable wait as before.
     */
    public CommandReceipt resumeAfterQuestions(
            long workspaceId, long userId, long documentId, long jobId, IdempotencyKey idempotencyKey, CanonicalRequestHash requestHash) {
        GenerationRun run = requireRun(workspaceId, userId, documentId, jobId);
        List<ResolvedAnswerBundle.ResolvedAnswer> answers = questionService.allQuestionsForRun(workspaceId, userId, run.id()).stream()
                .filter(question -> question.status() == QuestionStatus.ANSWERED)
                .map(ResolvedAnswerBundle.ResolvedAnswer::from)
                .toList();
        writeResolvedAnswers(workspaceId, jobId, new ResolvedAnswerBundle(answers));
        return jobCommandRepository.requestResume(workspaceId, userId, new ResumeJobCommand(idempotencyKey, requestHash, jobId));
    }

    /**
     * Turns a finished job's own published result into a real, reviewable
     * {@link PatchProposal} against the document's current revision --
     * frozen against whichever revision is current right now, not whatever
     * it was when generation started, the same base-revision discipline
     * {@code RevisionService#proposePatch} already requires of every
     * proposal. A resolved scalar field is proposed as-is; an unresolved
     * one (still missing after every question this run could raise) is
     * left for a person to fill in directly. The result's repeated rows
     * (action items) are proposed too, as the template's own parallel
     * repeated fields -- but only a row every one of whose fields resolved,
     * since the content model has no way to store a row with an unknown
     * owner or due date; each row left out is reported by name in the
     * returned {@link GenerationApplyOutcome} rather than dropped silently.
     */
    public GenerationApplyOutcome applyResultAsPatchProposal(long workspaceId, long userId, long documentId, long jobId) {
        requireRun(workspaceId, userId, documentId, jobId);
        Document document = revisionService
                .findDocument(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        TemplateVersion templateVersion = templateService
                .findVersion(workspaceId, userId, document.templateId(), document.templateVersionId())
                .orElseThrow(() -> new TemplateVersionNotFoundException(document.templateId(), document.templateVersionId()));
        Map<String, FieldType> scalarTypeByFieldId = new LinkedHashMap<>();
        List<FieldDefinition> repeatedFields = new ArrayList<>();
        for (FieldDefinition field : templateVersion.fieldDefinitions()) {
            if (field.cardinality() == FieldCardinality.REPEATED) {
                repeatedFields.add(field);
            } else {
                scalarTypeByFieldId.put(field.fieldId(), field.type());
            }
        }

        long artifactId = jobOutputArtifactRepository
                .findArtifactId(workspaceId, userId, jobId, GenerationJobTypes.EXTRACTION_RESULT_OUTPUT_KIND)
                .orElseThrow(() -> new GenerationResultNotFoundException(jobId));
        ExtractionResult result = readResult(workspaceId, userId, artifactId);

        Map<String, FieldValue> proposedValues = new LinkedHashMap<>();
        Map<String, List<Long>> proposedEvidence = new LinkedHashMap<>();
        result.scalarCandidates().forEach((fieldId, candidate) -> {
            FieldValue value = toScalarFieldValue(candidate, scalarTypeByFieldId.get(fieldId));
            if (value == null) {
                return;
            }
            proposedValues.put(fieldId, value);
            if (!candidate.evidenceSpanIds().isEmpty()) {
                proposedEvidence.put(fieldId, candidate.evidenceSpanIds());
            }
        });

        RepeatedProposal repeated = proposeRepeatedItems(result.repeatedItems(), repeatedFields);
        proposedValues.putAll(repeated.values());
        proposedEvidence.putAll(repeated.evidence());

        if (proposedValues.isEmpty()) {
            throw new GenerationResultEmptyException(jobId);
        }

        PatchProposal proposal = revisionService.proposePatch(
                workspaceId, userId, documentId, document.currentRevisionId(), proposedValues, proposedEvidence);
        return new GenerationApplyOutcome(proposal, repeated.proposedItemCount(), repeated.skipped());
    }

    /**
     * The job must be a recorded run in this workspace (the repository
     * lookup is tenant-scoped) of this exact document -- a run that
     * belongs to some other document, even one in the same workspace, is
     * reported as not found rather than letting its staged questions,
     * answers, or result be read through a document it was never about.
     */
    private GenerationRun requireRun(long workspaceId, long userId, long documentId, long jobId) {
        // A run is read through its document, so a document that is in the
        // trash takes its runs out of reach with it.
        revisionService.findDocument(workspaceId, userId, documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        return generationRunRepository
                .findByJob(workspaceId, userId, jobId)
                .filter(run -> run.documentId() == documentId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
    }

    private Job requireJob(long workspaceId, long userId, long jobId) {
        return jobCommandRepository.find(workspaceId, userId, jobId).orElseThrow(() -> new JobNotFoundException(jobId));
    }

    private ExtractionResult readResult(long workspaceId, long userId, long artifactId) {
        try (ReadableArtifact readable = artifactService.openContent(workspaceId, userId, artifactId)) {
            return objectMapper.readValue(readable.content(), ExtractionResult.class);
        } catch (IOException e) {
            throw new ArtifactStorageException("Could not read published generation result artifact " + artifactId + ".", e);
        }
    }

    /** {@code null} for an unresolved candidate, or a field this template version does not define as a scalar. */
    private static FieldValue toScalarFieldValue(FieldCandidate candidate, FieldType type) {
        if (candidate.unresolved() || candidate.value() == null || type == null) {
            return null;
        }
        return switch (type) {
            case TEXT -> new FieldValue.TextValue(candidate.value());
            // Already validated parseable by the extraction response parser before this candidate ever existed.
            case DATE -> new FieldValue.DateValue(LocalDate.parse(candidate.value()));
        };
    }

    /**
     * Builds the template's repeated fields as parallel lists, one entry
     * per fully resolved row, in the result's own row order. Every row
     * contributes to every repeated field or to none of them, so the lists
     * always agree on length -- the invariant the template filler enforces
     * when it later clones one table row per item.
     */
    private static RepeatedProposal proposeRepeatedItems(List<RepeatedItemCandidate> items, List<FieldDefinition> repeatedFields) {
        if (repeatedFields.isEmpty() || items.isEmpty()) {
            return RepeatedProposal.none();
        }
        Map<String, List<String>> textsByField = new LinkedHashMap<>();
        Map<String, LinkedHashSet<Long>> evidenceByField = new LinkedHashMap<>();
        for (FieldDefinition field : repeatedFields) {
            textsByField.put(field.fieldId(), new ArrayList<>());
            evidenceByField.put(field.fieldId(), new LinkedHashSet<>());
        }

        List<GenerationApplyOutcome.SkippedRepeatedItem> skipped = new ArrayList<>();
        int proposedItemCount = 0;
        for (int index = 0; index < items.size(); index++) {
            RepeatedItemCandidate item = items.get(index);
            List<String> unresolvedFieldIds = new ArrayList<>();
            for (FieldDefinition field : repeatedFields) {
                if (usableRepeatedValue(item.fields().get(field.fieldId()), field.type()) == null) {
                    unresolvedFieldIds.add(field.fieldId());
                }
            }
            if (!unresolvedFieldIds.isEmpty()) {
                skipped.add(new GenerationApplyOutcome.SkippedRepeatedItem(index, unresolvedFieldIds, describe(item, repeatedFields)));
                continue;
            }
            for (FieldDefinition field : repeatedFields) {
                FieldCandidate candidate = item.fields().get(field.fieldId());
                textsByField.get(field.fieldId()).add(usableRepeatedValue(candidate, field.type()));
                evidenceByField.get(field.fieldId()).addAll(candidate.evidenceSpanIds());
            }
            proposedItemCount++;
        }
        if (proposedItemCount == 0) {
            return new RepeatedProposal(Map.of(), Map.of(), 0, skipped);
        }

        Map<String, FieldValue> values = new LinkedHashMap<>();
        Map<String, List<Long>> evidence = new LinkedHashMap<>();
        for (FieldDefinition field : repeatedFields) {
            List<String> texts = textsByField.get(field.fieldId());
            values.put(field.fieldId(), switch (field.type()) {
                case TEXT -> new FieldValue.RepeatedTextValue(texts);
                case DATE -> new FieldValue.RepeatedDateValue(texts.stream().map(LocalDate::parse).toList());
            });
            LinkedHashSet<Long> spanIds = evidenceByField.get(field.fieldId());
            if (!spanIds.isEmpty()) {
                evidence.put(field.fieldId(), List.copyOf(spanIds));
            }
        }
        return new RepeatedProposal(values, evidence, proposedItemCount, skipped);
    }

    /**
     * The candidate's value if it can actually be stored for this field
     * type, otherwise {@code null}: absent from the row, marked unresolved
     * by the model, blank, or -- for a DATE field -- not a parseable ISO
     * date. The extraction parser already normalizes every date candidate
     * it accepts, so the parse guard here only matters for a result that
     * reached storage some other way; it is cheap insurance, not a second
     * validation layer.
     */
    private static String usableRepeatedValue(FieldCandidate candidate, FieldType type) {
        if (candidate == null || candidate.unresolved() || candidate.value() == null || candidate.value().isBlank()) {
            return null;
        }
        if (type == FieldType.DATE) {
            try {
                return LocalDate.parse(candidate.value()).toString();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return candidate.value();
    }

    /** The row's first resolved TEXT value (for the built-ins, the task itself), bounded, so a skipped row can be recognised without the raw result. */
    private static String describe(RepeatedItemCandidate item, List<FieldDefinition> repeatedFields) {
        for (FieldDefinition field : repeatedFields) {
            if (field.type() != FieldType.TEXT) {
                continue;
            }
            String text = usableRepeatedValue(item.fields().get(field.fieldId()), field.type());
            if (text != null) {
                return text.length() > SKIPPED_ITEM_DESCRIPTION_MAX_LENGTH
                        ? text.substring(0, SKIPPED_ITEM_DESCRIPTION_MAX_LENGTH) + "…"
                        : text;
            }
        }
        return null;
    }

    private record RepeatedProposal(
            Map<String, FieldValue> values,
            Map<String, List<Long>> evidence,
            int proposedItemCount,
            List<GenerationApplyOutcome.SkippedRepeatedItem> skipped) {

        static RepeatedProposal none() {
            return new RepeatedProposal(Map.of(), Map.of(), 0, List.of());
        }
    }

    private DetectedQuestionsBundle readPendingQuestions(long workspaceId, long jobId) {
        String objectKey = GenerationJobTypes.pendingQuestionsObjectKey(workspaceId, jobId);
        try {
            if (blobStore.sizeOf(objectKey).isEmpty()) {
                return null;
            }
            try (InputStream content = blobStore.openStream(objectKey)) {
                return objectMapper.readValue(content, DetectedQuestionsBundle.class);
            }
        } catch (IOException e) {
            throw new ArtifactStorageException("Could not read pending generation questions " + objectKey + ".", e);
        }
    }

    private void writeResolvedAnswers(long workspaceId, long jobId, ResolvedAnswerBundle answers) {
        String objectKey = GenerationJobTypes.resolvedAnswersObjectKey(workspaceId, jobId);
        try {
            byte[] json = objectMapper.writeValueAsBytes(answers);
            blobStore.writeAndDigest(objectKey, new ByteArrayInputStream(json), MAX_BUNDLE_BYTES);
        } catch (IOException e) {
            throw new ArtifactStorageException("Could not write resolved generation answers " + objectKey + ".", e);
        }
    }

    /**
     * Content-addressed by the bundle's own hash: a retried or duplicate
     * start request that produces byte-identical excerpts and fields
     * reuses the object already staged for it rather than writing a
     * second copy, the same convergence guarantee {@link
     * BlobStore#writeNewAndDigest} already gives {@code
     * JobOutputPublisher}'s own staged worker outputs.
     */
    private boolean writeBundleIfAbsent(long workspaceId, CanonicalRequestHash bundleHash, ExtractionInputBundle bundle) {
        String objectKey = GenerationJobTypes.inputBundleObjectKey(workspaceId, bundleHash.value());
        try {
            byte[] json = objectMapper.writeValueAsBytes(bundle);
            blobStore.writeNewAndDigest(objectKey, new ByteArrayInputStream(json), MAX_BUNDLE_BYTES);
            return true;
        } catch (BlobAlreadyExistsException ignored) {
            // An identical bundle was already staged by an earlier attempt, which is the one that owns it.
            return false;
        } catch (IOException e) {
            throw new ArtifactStorageException("Could not write generation input bundle " + objectKey + ".", e);
        }
    }
}
