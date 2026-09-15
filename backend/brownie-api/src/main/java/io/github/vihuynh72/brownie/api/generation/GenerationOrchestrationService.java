package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.api.job.CanonicalRequestHasher;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStorageException;
import io.github.vihuynh72.brownie.core.artifact.BlobAlreadyExistsException;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.generation.ExtractionInputBundle;
import io.github.vihuynh72.brownie.core.generation.ExtractionResult;
import io.github.vihuynh72.brownie.core.generation.ExtractionService;
import io.github.vihuynh72.brownie.core.generation.FieldCandidate;
import io.github.vihuynh72.brownie.core.generation.GenerationJobTypes;
import io.github.vihuynh72.brownie.core.generation.LabeledExcerpt;
import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.CommandReceipt;
import io.github.vihuynh72.brownie.core.job.EnqueueJobCommand;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.job.JobCommandRepository;
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
import io.github.vihuynh72.brownie.core.source.SourceService;
import io.github.vihuynh72.brownie.core.source.SourceSnapshot;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionNotFoundException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 */
@Service
public class GenerationOrchestrationService {

    /** Re-exposed for this package's own callers; the shared, cross-module value lives in {@link GenerationJobTypes} so the worker can reference it without ever depending on this module. */
    public static final String EXTRACTION_JOB_TYPE = GenerationJobTypes.EXTRACTION_JOB_TYPE;
    public static final String EXTRACTION_RESULT_OUTPUT_KIND = GenerationJobTypes.EXTRACTION_RESULT_OUTPUT_KIND;

    private static final String EXTRACTING_STAGE = "extracting";
    private static final String BUNDLE_BLOB_PREFIX = "generation-input/";
    private static final long MAX_BUNDLE_BYTES = 2_000_000;

    /**
     * Which of a template's own scalar TEXT fields get re-synthesized by
     * {@code CompositionService} rather than trusted as extraction's own
     * more literal first pass -- a real, deliberately narrow, hardcoded
     * convention rather than a general per-template composability model,
     * matching the one composable field ({@code meeting.decisions}) that
     * actually exists in the built-in templates today. A later phase that
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
    private final BlobStore blobStore;
    private final CanonicalRequestHasher canonicalRequestHasher;
    private final ObjectMapper objectMapper;

    public GenerationOrchestrationService(
            RevisionService revisionService,
            TemplateService templateService,
            SourceService sourceService,
            ExtractionService extractionService,
            QuestionService questionService,
            ArtifactService artifactService,
            JobCommandRepository jobCommandRepository,
            JobOutputArtifactRepository jobOutputArtifactRepository,
            BlobStore blobStore,
            CanonicalRequestHasher canonicalRequestHasher,
            ObjectMapper objectMapper) {
        this.revisionService = revisionService;
        this.templateService = templateService;
        this.sourceService = sourceService;
        this.extractionService = extractionService;
        this.questionService = questionService;
        this.artifactService = artifactService;
        this.jobCommandRepository = jobCommandRepository;
        this.jobOutputArtifactRepository = jobOutputArtifactRepository;
        this.blobStore = blobStore;
        this.canonicalRequestHasher = canonicalRequestHasher;
        this.objectMapper = objectMapper;
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
        TemplateVersion templateVersion = templateService
                .findVersion(workspaceId, userId, document.templateId(), document.templateVersionId())
                .orElseThrow(() -> new TemplateVersionNotFoundException(document.templateId(), document.templateVersionId()));
        DocumentContent existingContent = revisionService
                .findRevision(workspaceId, userId, documentId, document.currentRevisionId())
                .map(DocumentRevision::content)
                .orElseGet(DocumentContent::empty);
        SourceSnapshot snapshot = sourceService.attachSnapshot(workspaceId, userId, sourceArtifactId);
        List<LabeledExcerpt> excerpts = extractionService.segmentAndCiteSource(workspaceId, userId, snapshot);
        List<String> composableFieldIds = templateVersion.fieldDefinitions().stream()
                .map(FieldDefinition::fieldId)
                .filter(COMPOSABLE_FIELD_IDS::contains)
                .toList();
        ExtractionInputBundle bundle = ExtractionInputBundle.from(templateVersion.fieldDefinitions(), excerpts, existingContent, composableFieldIds);

        CanonicalRequestHash bundleHash = canonicalRequestHasher.hash(bundle);
        writeBundleIfAbsent(bundleHash, bundle);

        return jobCommandRepository.enqueue(
                workspaceId,
                userId,
                new EnqueueJobCommand(
                        idempotencyKey,
                        requestHash,
                        new JobType(EXTRACTION_JOB_TYPE),
                        new JobTarget("document", documentId, document.currentRevisionId()),
                        new JobStage(EXTRACTING_STAGE),
                        bundleHash,
                        OffsetDateTime.now()));
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
     * Idempotent by construction: once any question exists for this
     * document, this never re-reads the blob or persists again.
     */
    public List<Question> openQuestions(long workspaceId, long userId, long documentId, long jobId) {
        List<Question> alreadyPersisted = questionService.openQuestions(workspaceId, userId, documentId);
        if (!alreadyPersisted.isEmpty()) {
            return alreadyPersisted;
        }
        DetectedQuestionsBundle pending = readPendingQuestions(jobId);
        if (pending == null || pending.questions().isEmpty()) {
            return List.of();
        }
        return questionService.persistDetected(workspaceId, userId, documentId, pending.questions());
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
        List<ResolvedAnswerBundle.ResolvedAnswer> answers = questionService.allQuestions(workspaceId, userId, documentId).stream()
                .filter(question -> question.status() == QuestionStatus.ANSWERED)
                .map(ResolvedAnswerBundle.ResolvedAnswer::from)
                .toList();
        writeResolvedAnswers(jobId, new ResolvedAnswerBundle(answers));
        return jobCommandRepository.requestResume(workspaceId, userId, new ResumeJobCommand(idempotencyKey, requestHash, jobId));
    }

    /**
     * Turns a finished job's own published result into a real, reviewable
     * {@link PatchProposal} against the document's current revision --
     * frozen against whichever revision is current right now, not whatever
     * it was when generation started, the same base-revision discipline
     * {@code RevisionService#proposePatch} already requires of every
     * proposal. Only resolved scalar fields are proposed; an unresolved
     * one (still missing after every question this run could raise) is
     * left for a person to fill in directly instead. Repeated fields
     * (action items) are not proposed -- the same deliberately out-of-
     * scope boundary {@code CompositionService}/{@code
     * QuestionDetectionService} already both hold for repeated data.
     */
    public PatchProposal applyResultAsPatchProposal(long workspaceId, long userId, long documentId, long jobId) {
        Document document = revisionService
                .findDocument(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        TemplateVersion templateVersion = templateService
                .findVersion(workspaceId, userId, document.templateId(), document.templateVersionId())
                .orElseThrow(() -> new TemplateVersionNotFoundException(document.templateId(), document.templateVersionId()));
        Map<String, FieldType> typeByFieldId = new LinkedHashMap<>();
        templateVersion.fieldDefinitions().forEach(field -> typeByFieldId.put(field.fieldId(), field.type()));

        long artifactId = jobOutputArtifactRepository
                .findArtifactId(workspaceId, userId, jobId, GenerationJobTypes.EXTRACTION_RESULT_OUTPUT_KIND)
                .orElseThrow(() -> new GenerationResultNotFoundException(jobId));
        ExtractionResult result = readResult(workspaceId, userId, artifactId);

        Map<String, FieldValue> proposedValues = new LinkedHashMap<>();
        Map<String, List<Long>> proposedEvidence = new LinkedHashMap<>();
        result.scalarCandidates().forEach((fieldId, candidate) -> {
            FieldValue value = toFieldValue(candidate, typeByFieldId.get(fieldId));
            if (value == null) {
                return;
            }
            proposedValues.put(fieldId, value);
            if (!candidate.evidenceSpanIds().isEmpty()) {
                proposedEvidence.put(fieldId, candidate.evidenceSpanIds());
            }
        });
        if (proposedValues.isEmpty()) {
            throw new GenerationResultEmptyException(jobId);
        }

        return revisionService.proposePatch(workspaceId, userId, documentId, document.currentRevisionId(), proposedValues, proposedEvidence);
    }

    private ExtractionResult readResult(long workspaceId, long userId, long artifactId) {
        try (ReadableArtifact readable = artifactService.openContent(workspaceId, userId, artifactId)) {
            return objectMapper.readValue(readable.content(), ExtractionResult.class);
        } catch (IOException e) {
            throw new ArtifactStorageException("Could not read published generation result artifact " + artifactId + ".", e);
        }
    }

    /** {@code null} for an unresolved candidate or a field this template version no longer defines. */
    private static FieldValue toFieldValue(FieldCandidate candidate, FieldType type) {
        if (candidate.unresolved() || candidate.value() == null || type == null) {
            return null;
        }
        return switch (type) {
            case TEXT -> new FieldValue.TextValue(candidate.value());
            // Already validated parseable by the extraction response parser before this candidate ever existed.
            case DATE -> new FieldValue.DateValue(LocalDate.parse(candidate.value()));
        };
    }

    private DetectedQuestionsBundle readPendingQuestions(long jobId) {
        String objectKey = GenerationJobTypes.pendingQuestionsObjectKey(jobId);
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

    private void writeResolvedAnswers(long jobId, ResolvedAnswerBundle answers) {
        String objectKey = GenerationJobTypes.resolvedAnswersObjectKey(jobId);
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
    private void writeBundleIfAbsent(CanonicalRequestHash bundleHash, ExtractionInputBundle bundle) {
        String objectKey = bundleObjectKey(bundleHash);
        try {
            byte[] json = objectMapper.writeValueAsBytes(bundle);
            blobStore.writeNewAndDigest(objectKey, new ByteArrayInputStream(json), MAX_BUNDLE_BYTES);
        } catch (BlobAlreadyExistsException ignored) {
            // An identical bundle was already staged by an earlier attempt.
        } catch (IOException e) {
            throw new ArtifactStorageException("Could not write generation input bundle " + objectKey + ".", e);
        }
    }

    /** The same deterministic key derivation the worker's own processor must reproduce -- see {@code GenerationExtractionJobProcessor}. */
    public static String bundleObjectKey(CanonicalRequestHash bundleHash) {
        return BUNDLE_BLOB_PREFIX + bundleHash.value() + ".json";
    }
}
