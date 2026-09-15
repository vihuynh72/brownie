package io.github.vihuynh72.brownie.worker.generation;

import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.generation.CancellationSignal;
import io.github.vihuynh72.brownie.core.generation.CompositionCancelledException;
import io.github.vihuynh72.brownie.core.generation.CompositionFailedException;
import io.github.vihuynh72.brownie.core.generation.CompositionResponseParseException;
import io.github.vihuynh72.brownie.core.generation.CompositionResponseParser;
import io.github.vihuynh72.brownie.core.generation.CompositionService;
import io.github.vihuynh72.brownie.core.generation.ExtractionCancelledException;
import io.github.vihuynh72.brownie.core.generation.ExtractionFailedException;
import io.github.vihuynh72.brownie.core.generation.ExtractionInputBundle;
import io.github.vihuynh72.brownie.core.generation.ExtractionResponseParseException;
import io.github.vihuynh72.brownie.core.generation.ExtractionResponseParser;
import io.github.vihuynh72.brownie.core.generation.ExtractionResult;
import io.github.vihuynh72.brownie.core.generation.ExtractionService;
import io.github.vihuynh72.brownie.core.generation.FieldCandidate;
import io.github.vihuynh72.brownie.core.generation.GenerationJobTypes;
import io.github.vihuynh72.brownie.core.generation.LabeledExcerpt;
import io.github.vihuynh72.brownie.core.generation.RequiredFactsUnresolvedException;
import io.github.vihuynh72.brownie.core.generation.usage.BudgetExceededException;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.UsageBudget;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.job.JobCompletion;
import io.github.vihuynh72.brownie.core.job.JobFailure;
import io.github.vihuynh72.brownie.core.job.JobFailureKind;
import io.github.vihuynh72.brownie.core.job.JobLeaseRepository;
import io.github.vihuynh72.brownie.core.job.JobOutputPublication;
import io.github.vihuynh72.brownie.core.job.JobOutputPublisher;
import io.github.vihuynh72.brownie.core.job.JobRelease;
import io.github.vihuynh72.brownie.core.job.JobReleaseResult;
import io.github.vihuynh72.brownie.core.job.JobState;
import io.github.vihuynh72.brownie.core.job.LeasedJob;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelTransportException;
import io.github.vihuynh72.brownie.core.question.DetectedQuestion;
import io.github.vihuynh72.brownie.core.question.DetectedQuestionsBundle;
import io.github.vihuynh72.brownie.core.question.QuestionDetectionService;
import io.github.vihuynh72.brownie.core.question.ResolvedAnswerBundle;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Processes exactly one job type -- {@code
 * GenerationOrchestrationService.EXTRACTION_JOB_TYPE} -- with no tenant
 * database access of its own: it reads back the immutable, content-
 * addressed bundle the API already froze (identified by the job's own
 * {@code processingConfigurationHash}, never anything this process looked
 * up on its own), calls the model through the exact same pure {@link
 * ExtractionService#extractFromExcerpts} the API's own real-model
 * integration test already proves, and publishes the result as a verified
 * artifact through the existing, already-tested {@link JobOutputPublisher}.
 *
 * <p>Before publishing, it also asks {@link QuestionDetectionService}
 * whether any field still needs a person's input. If so, it cannot itself
 * create an answerable {@code Question} row (it has no tenant-database
 * access to that table at all) -- it stages the detected questions as a
 * {@link DetectedQuestionsBundle} at a deterministic, job-scoped blob key
 * instead, and leaves the lease {@code WAITING_FOR_INPUT} rather than
 * completing. On a later claim of the same job (after the API's own
 * resume request), it first reads back whatever {@link ResolvedAnswerBundle}
 * the API staged in the meantime and folds those answers into the fresh
 * extraction before asking {@code QuestionDetectionService} anything
 * again, so an already-settled field is never re-asked. Resuming always
 * re-runs the model call rather than caching the first attempt's own
 * result -- a real, deliberate simplification, since a correct cache would
 * need its own invalidation story this task's scope does not cover.
 */
@Component
class GenerationExtractionJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(GenerationExtractionJobProcessor.class);
    private static final String BUNDLE_BLOB_PREFIX = "generation-input/";
    private static final long MAX_QUESTION_BLOB_BYTES = 200_000;

    private final BlobStore blobStore;
    private final ModelGateway modelGateway;
    private final ExtractionResponseParser extractionResponseParser;
    private final CompositionResponseParser compositionResponseParser;
    private final JobLeaseRepository jobLeaseRepository;
    private final JobOutputPublisher jobOutputPublisher;
    private final ObjectMapper objectMapper;

    GenerationExtractionJobProcessor(
            BlobStore blobStore,
            ModelGateway modelGateway,
            ExtractionResponseParser extractionResponseParser,
            CompositionResponseParser compositionResponseParser,
            JobLeaseRepository jobLeaseRepository,
            JobOutputPublisher jobOutputPublisher) {
        this.blobStore = blobStore;
        this.modelGateway = modelGateway;
        this.extractionResponseParser = extractionResponseParser;
        this.compositionResponseParser = compositionResponseParser;
        this.jobLeaseRepository = jobLeaseRepository;
        this.jobOutputPublisher = jobOutputPublisher;
        this.objectMapper = new ObjectMapper();
    }

    void process(LeasedJob leasedJob) {
        ExtractionInputBundle bundle;
        try {
            bundle = readBundle(leasedJob);
        } catch (IOException e) {
            // The bundle the API staged is missing or unreadable -- almost
            // certainly a transient object-storage hiccup rather than a
            // permanently broken job, since the API only ever enqueues a
            // job after that exact write already succeeded.
            log.warn("Could not read generation input bundle for job {}.", leasedJob.job().id(), e);
            releaseAfterFailure(leasedJob, new JobFailure(
                    JobFailureKind.TRANSIENT_SERVER, "GENERATION_BUNDLE_UNREADABLE", "The extraction input could not be read.", null));
            return;
        }

        ResolvedAnswerBundle resolvedAnswers;
        try {
            resolvedAnswers = readResolvedAnswers(leasedJob.job().id());
        } catch (IOException e) {
            log.warn("Could not read resolved answers for job {}.", leasedJob.job().id(), e);
            releaseAfterFailure(leasedJob, new JobFailure(
                    JobFailureKind.TRANSIENT_SERVER, "GENERATION_ANSWERS_UNREADABLE", "Previously resolved answers could not be read.", null));
            return;
        }

        var fields = bundle.fields().stream().map(ExtractionInputBundle.BundledField::toFieldDefinitionForPromptingOnly).toList();
        var excerpts = bundle.excerpts().stream()
                .map(excerpt -> new LabeledExcerpt(excerpt.spanId(), excerpt.text()))
                .toList();
        // A plain, local instance -- see OpenAiModelGatewayTest's own
        // identical reasoning: extractFromExcerpts needs no tenant access
        // at all, so the two unused constructor arguments
        // (DocumentExtractionService, SourceService) are safely null, the
        // same pattern ExtractionServiceTest already establishes.
        ExtractionService extractionService = new ExtractionService(null, null, modelGateway, extractionResponseParser);

        ExtractionResult result;
        try {
            result = extractionService.extractFromExcerpts(fields, excerpts, freshBudget(), CancellationSignal.never());
        } catch (ModelTransportException e) {
            releaseAfterFailure(leasedJob, new JobFailure(
                    e.retryable() ? JobFailureKind.TRANSIENT_PROVIDER : JobFailureKind.DETERMINISTIC,
                    e.retryable() ? "MODEL_TRANSPORT_TRANSIENT" : "MODEL_TRANSPORT_REJECTED",
                    safeMessage(e.getMessage()), null));
            return;
        } catch (ExtractionFailedException | ExtractionResponseParseException | BudgetExceededException e) {
            releaseAfterFailure(leasedJob, new JobFailure(
                    JobFailureKind.DETERMINISTIC, "GENERATION_EXTRACTION_UNUSABLE", safeMessage(e.getMessage()), null));
            return;
        } catch (ExtractionCancelledException e) {
            // Unreachable while this processor passes CancellationSignal.never()
            // (see this class's own javadoc on that deliberate simplification),
            // but extractFromExcerpts declares it, so it must be handled.
            log.warn("Extraction reported cancellation for job {} despite no cancellation signal being wired yet.", leasedJob.job().id());
            return;
        }

        Set<String> answeredFieldIds = resolvedAnswers.answers().stream()
                .map(ResolvedAnswerBundle.ResolvedAnswer::fieldId)
                .collect(Collectors.toSet());
        ExtractionResult reconciled = applyResolvedAnswers(result, resolvedAnswers);
        List<FieldDefinition> fieldsStillNeedingAnAnswer = fields.stream()
                .filter(field -> !answeredFieldIds.contains(field.fieldId()))
                .toList();
        List<DetectedQuestion> detected =
                QuestionDetectionService.detect(reconciled, fieldsStillNeedingAnAnswer, existingContentOf(bundle));

        if (detected.isEmpty()) {
            ExtractionResult finalResult = composeIfNeeded(leasedJob, bundle, fields, reconciled);
            if (finalResult != null) {
                publishResult(leasedJob, finalResult);
            }
            return;
        }
        if (!writePendingQuestions(leasedJob, detected)) {
            return;
        }
        JobReleaseResult released = jobLeaseRepository.release(
                leasedJob.leaseToken(),
                new JobRelease(
                        JobState.WAITING_FOR_INPUT, null,
                        safeMessage(detected.size() + " question(s) need input before this document can finish.")));
        if (released != JobReleaseResult.RELEASED) {
            log.info("Job {} could not be left waiting for input: {}.", leasedJob.job().id(), released);
        }
    }

    private ExtractionInputBundle readBundle(LeasedJob leasedJob) throws IOException {
        String objectKey = bundleObjectKey(leasedJob);
        try (InputStream content = blobStore.openStream(objectKey)) {
            return objectMapper.readValue(content, ExtractionInputBundle.class);
        }
    }

    private ResolvedAnswerBundle readResolvedAnswers(long jobId) throws IOException {
        String objectKey = GenerationJobTypes.resolvedAnswersObjectKey(jobId);
        if (blobStore.sizeOf(objectKey).isEmpty()) {
            return new ResolvedAnswerBundle(List.of());
        }
        try (InputStream content = blobStore.openStream(objectKey)) {
            return objectMapper.readValue(content, ResolvedAnswerBundle.class);
        }
    }

    private static ExtractionResult applyResolvedAnswers(ExtractionResult result, ResolvedAnswerBundle resolvedAnswers) {
        if (resolvedAnswers.answers().isEmpty()) {
            return result;
        }
        Map<String, FieldCandidate> reconciled = new LinkedHashMap<>(result.scalarCandidates());
        for (ResolvedAnswerBundle.ResolvedAnswer answer : resolvedAnswers.answers()) {
            reconciled.put(answer.fieldId(), new FieldCandidate(answer.fieldId(), answer.answerValue(), answer.evidenceSpanIds(), false, null));
        }
        return new ExtractionResult(reconciled, result.repeatedItems());
    }

    /** Reconstructs just enough of the document's own current content for {@link QuestionDetectionService#detect} -- see {@code ExtractionInputBundle.BundledField#existingValueText}. */
    private static DocumentContent existingContentOf(ExtractionInputBundle bundle) {
        Map<String, FieldValue> fields = new LinkedHashMap<>();
        for (ExtractionInputBundle.BundledField field : bundle.fields()) {
            if (field.existingValueText() != null) {
                fields.put(field.fieldId(), new FieldValue.TextValue(field.existingValueText()));
            }
        }
        return new DocumentContent(fields);
    }

    /**
     * Re-synthesizes {@code bundle.composableFieldIds()} from {@code
     * accepted} (for example a decisions summary), trusting them more
     * than extraction's own first, more literal pass -- or, when this
     * template names no composable fields, returns {@code accepted}
     * unchanged. Returns {@code null} (having already released the lease
     * as a failure) when composition itself could not produce a usable
     * result; rule enforcement beyond a template's own {@code
     * FieldRequiredness} is not yet threaded through this async path, so
     * only an empty rule list is offered today -- a real, named
     * simplification, not an oversight.
     */
    private ExtractionResult composeIfNeeded(
            LeasedJob leasedJob, ExtractionInputBundle bundle, List<FieldDefinition> fields, ExtractionResult accepted) {
        if (bundle.composableFieldIds().isEmpty()) {
            return accepted;
        }
        CompositionService compositionService = new CompositionService(modelGateway, compositionResponseParser);
        try {
            return compositionService.compose(
                    fields, bundle.composableFieldIds(), List.of(), accepted, freshBudget(), CancellationSignal.never());
        } catch (ModelTransportException e) {
            releaseAfterFailure(leasedJob, new JobFailure(
                    e.retryable() ? JobFailureKind.TRANSIENT_PROVIDER : JobFailureKind.DETERMINISTIC,
                    e.retryable() ? "MODEL_TRANSPORT_TRANSIENT" : "MODEL_TRANSPORT_REJECTED",
                    safeMessage(e.getMessage()), null));
            return null;
        } catch (CompositionFailedException | CompositionResponseParseException | BudgetExceededException
                | RequiredFactsUnresolvedException e) {
            releaseAfterFailure(leasedJob, new JobFailure(
                    JobFailureKind.DETERMINISTIC, "GENERATION_COMPOSITION_UNUSABLE", safeMessage(e.getMessage()), null));
            return null;
        } catch (CompositionCancelledException e) {
            // Unreachable while this processor passes CancellationSignal.never(),
            // the identical reasoning this class's own extraction call above states.
            log.warn("Composition reported cancellation for job {} despite no cancellation signal being wired yet.", leasedJob.job().id());
            return null;
        }
    }

    /** Returns {@code false} (and has already released the lease as a failure) when staging could not complete. */
    private boolean writePendingQuestions(LeasedJob leasedJob, List<DetectedQuestion> detected) {
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(new DetectedQuestionsBundle(detected));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize this run's own detected questions.", e);
        }
        try {
            blobStore.writeAndDigest(
                    GenerationJobTypes.pendingQuestionsObjectKey(leasedJob.job().id()), new ByteArrayInputStream(json), MAX_QUESTION_BLOB_BYTES);
            return true;
        } catch (IOException e) {
            log.warn("Could not stage detected questions for job {}.", leasedJob.job().id(), e);
            releaseAfterFailure(leasedJob, new JobFailure(
                    JobFailureKind.TRANSIENT_SERVER, "GENERATION_QUESTIONS_UNWRITABLE", "Detected questions could not be staged.", null));
            return false;
        }
    }

    private void publishResult(LeasedJob leasedJob, ExtractionResult result) {
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // This process's own, already-validated ExtractionResult failing
            // to serialize would mean this plain record type itself became
            // unserializable -- a programming error, not a runtime outcome
            // any caller could recover from.
            throw new IllegalStateException("Failed to serialize this run's own extraction result.", e);
        }
        JobOutputPublication publication =
                jobOutputPublisher.stageAndPublish(leasedJob, GenerationJobTypes.EXTRACTION_RESULT_OUTPUT_KIND, new ByteArrayInputStream(json));
        if (!publication.hasPublishedArtifact()) {
            // The lease was lost, cancelled, or the target moved on --
            // every case release/complete would also refuse; nothing more
            // for this attempt to do.
            log.info("Job {} extraction result was not published: {}.", leasedJob.job().id(), publication.result());
            return;
        }
        jobLeaseRepository.complete(
                leasedJob.leaseToken(),
                new JobCompletion(leasedJob.job().target(), JobState.SUCCEEDED, "Extraction complete."));
    }

    private void releaseAfterFailure(LeasedJob leasedJob, JobFailure failure) {
        jobLeaseRepository.releaseAfterFailure(leasedJob.leaseToken(), failure);
    }

    private static UsageBudget freshBudget() {
        return new UsageBudget(UsageLimits.defaultRunLimits(), ModelPricing.gpt5Mini());
    }

    /** The identical derivation {@code GenerationOrchestrationService.bundleObjectKey} uses, reproduced from the job's own frozen hash rather than imported (this module must never depend on brownie-api). */
    private static String bundleObjectKey(LeasedJob leasedJob) {
        return BUNDLE_BLOB_PREFIX + leasedJob.job().processingConfigurationHash().value() + ".json";
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "The extraction attempt failed.";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
