package io.github.vihuynh72.brownie.core.generation;

import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.evidence.EvidenceLocator;
import io.github.vihuynh72.brownie.core.evidence.SourceSpan;
import io.github.vihuynh72.brownie.core.generation.usage.BudgetExceededException;
import io.github.vihuynh72.brownie.core.generation.usage.UsageBudget;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelRequest;
import io.github.vihuynh72.brownie.core.model.ModelTransportException;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import io.github.vihuynh72.brownie.core.source.SourceService;
import io.github.vihuynh72.brownie.core.source.SourceSnapshot;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts candidate field values for one template's fields from one
 * source, grounded in real, individually citable evidence, and
 * bounded by a caller-supplied {@link UsageBudget} that this class must
 * never spend past. Depends only on the interfaces above, so it has no
 * framework, model-SDK, or JSON-parsing dependency of its own -- real
 * implementations are supplied by whichever module wires this up, the
 * same shape {@code CompilationService} already establishes.
 *
 * <p>There is no silent provider
 * fallback anywhere in this class or {@code ModelGateway}: a transport
 * failure or an unusable reply is reported as exactly what it was, never
 * retried against a different provider or model.
 */
public class ExtractionService {

    /** The most one extraction request may produce; also the least a run's first request holds in the usage ledger. */
    /** The largest output a single extraction call asks for. */
    public static final int MAX_OUTPUT_TOKENS = 8000;
    private static final int MAX_ATTEMPTS = 2;
    /** The reply's floor cost, plus what each scalar candidate object costs it. */
    private static final int OUTPUT_BASE = 600;
    private static final int OUTPUT_PER_FIELD = 50;
    /** Past this many reply objects' worth of fields the fields go out in several calls, 140 scalars each. */
    private static final int REPLY_FITS_ONE_CALL = 148;
    private static final int SCALARS_PER_CALL = 140;

    private final DocumentExtractionService documentExtractionService;
    private final SourceService sourceService;
    private final ModelGateway modelGateway;
    private final ExtractionResponseParser responseParser;
    private final TransportRetryPolicy transportRetryPolicy;

    /** Reports a failure in transit at once, without trying again. */
    public ExtractionService(
            DocumentExtractionService documentExtractionService,
            SourceService sourceService,
            ModelGateway modelGateway,
            ExtractionResponseParser responseParser) {
        this(documentExtractionService, sourceService, modelGateway, responseParser, TransportRetryPolicy.none());
    }

    public ExtractionService(
            DocumentExtractionService documentExtractionService,
            SourceService sourceService,
            ModelGateway modelGateway,
            ExtractionResponseParser responseParser,
            TransportRetryPolicy transportRetryPolicy) {
        this.documentExtractionService = documentExtractionService;
        this.sourceService = sourceService;
        this.modelGateway = modelGateway;
        this.responseParser = responseParser;
        this.transportRetryPolicy = java.util.Objects.requireNonNull(transportRetryPolicy, "transportRetryPolicy");
    }

    public ExtractionResult extract(
            long workspaceId, long userId, SourceSnapshot snapshot, TemplateVersion templateVersion, UsageBudget budget,
            CancellationSignal cancellationSignal)
            throws ModelTransportException, ExtractionFailedException, ExtractionResponseParseException, BudgetExceededException,
                    ExtractionCancelledException {
        List<LabeledExcerpt> excerpts = segmentAndCiteSource(workspaceId, userId, snapshot);
        return extractFromExcerpts(templateVersion.fieldDefinitions(), excerpts, budget, cancellationSignal);
    }

    /**
     * The pure heart of this service: build the request, spend from the
     * budget, call the model, parse its reply (retrying once, on a
     * structurally invalid reply only: one repair, never a
     * loop), and reject fabricated evidence -- everything this class does
     * that does not itself require a real artifact/extraction chain to
     * exercise. Public for two real callers with no access to a tenant
     * database at all: a unit test using a {@code FakeModelGateway} and a
     * hand-built parser double (avoiding the need to fake {@link
     * DocumentExtractionService}'s and {@link SourceService}'s own deep
     * dependency chains just to prove this logic, the same reasoning that
     * keeps {@code CompilationServiceTest} scoped to paths that never
     * reach {@code ArtifactService}), and the trusted worker process,
     * which is given only an already-frozen bundle of field definitions
     * and cited excerpts (assembled ahead of time by a caller that does
     * have tenant access) rather than a live {@code SourceSnapshot} to
     * chase down itself.
     */
    public ExtractionResult extractFromExcerpts(
            List<FieldDefinition> fieldDefinitions, List<LabeledExcerpt> excerpts, UsageBudget budget, CancellationSignal cancellationSignal)
            throws ModelTransportException, ExtractionFailedException, ExtractionResponseParseException, BudgetExceededException,
                    ExtractionCancelledException {
        Set<Long> allowedSpanIds = new HashSet<>();
        excerpts.forEach(excerpt -> allowedSpanIds.add(excerpt.spanId()));

        Map<String, FieldCandidate> scalarCandidates = new LinkedHashMap<>();
        List<RepeatedItemCandidate> repeatedItems = new ArrayList<>();
        boolean first = true;
        for (List<FieldDefinition> batch : batches(fieldDefinitions)) {
            if (!first && cancellationSignal.isCancellationRequested()) {
                throw new ExtractionCancelledException();
            }
            ExtractionResult part = extractOneBatch(batch, excerpts, allowedSpanIds, budget, cancellationSignal);
            scalarCandidates.putAll(part.scalarCandidates());
            repeatedItems.addAll(part.repeatedItems());
            first = false;
        }
        return new ExtractionResult(scalarCandidates, repeatedItems);
    }

    /**
     * One request's worth of fields, answered by one call and its own repair
     * attempts. The call's output cap is sized to the fields it carries, not a
     * constant: each scalar candidate object costs the reply about fifty
     * tokens and a repeated group about ten times that.
     */
    private ExtractionResult extractOneBatch(
            List<FieldDefinition> batchFields, List<LabeledExcerpt> excerpts, Set<Long> allowedSpanIds,
            UsageBudget budget, CancellationSignal cancellationSignal)
            throws ModelTransportException, ExtractionFailedException, ExtractionResponseParseException, BudgetExceededException,
                    ExtractionCancelledException {
        List<FieldDefinition> scalarFields =
                batchFields.stream().filter(f -> f.cardinality() == FieldCardinality.SCALAR).toList();
        List<FieldDefinition> repeatedFields =
                batchFields.stream().filter(f -> f.cardinality() == FieldCardinality.REPEATED).toList();

        ModelRequest request = ExtractionPromptBuilder.build(batchFields, excerpts, outputTokenCap(batchFields));
        int estimatedInputTokens = UsageBudget.estimateInputTokens(request);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (cancellationSignal.isCancellationRequested()) {
                throw new ExtractionCancelledException();
            }

            // Reserving, sending, keeping the reservation when the answer is
            // lost, and the bounded retry of a failure in transit all live in
            // one shared place; a retry there is not one of this loop's two
            // attempts, which exist only to repair an unusable reply.
            ModelCompletion completion;
            try {
                completion = BoundedModelCall.complete(
                        modelGateway, request, estimatedInputTokens, budget, cancellationSignal, transportRetryPolicy);
            } catch (ModelCallCancelledException e) {
                throw new ExtractionCancelledException();
            }

            boolean isLastAttempt = attempt == MAX_ATTEMPTS;
            switch (completion) {
                case ModelCompletion.Success success -> {
                    budget.settleActual(success.usage());
                    try {
                        ExtractionResult rawResult = responseParser.parse(success.content(), scalarFields, repeatedFields);
                        return rejectFabricatedEvidence(rawResult, allowedSpanIds);
                    } catch (ExtractionResponseParseException e) {
                        if (isLastAttempt) {
                            throw e;
                        }
                    }
                }
                case ModelCompletion.MalformedOutput malformed -> {
                    budget.settleActual(malformed.usage());
                    if (isLastAttempt) {
                        throw new ExtractionFailedException("The model's reply was not usable after one repair attempt: " + malformed.reason());
                    }
                }
                case ModelCompletion.Refusal refusal -> {
                    budget.settleActual(refusal.usage());
                    throw new ExtractionFailedException("The model refused to extract: " + refusal.reason());
                }
                case ModelCompletion.IncompleteOutput incomplete -> {
                    budget.settleActual(incomplete.usage());
                    throw new ExtractionFailedException("The model's reply was cut off: " + incomplete.reason());
                }
                case ModelCompletion.UnsupportedParameters unsupported -> {
                    // The request itself was rejected before the provider
                    // did any generation work, confirmed, not merely
                    // assumed unknown -- settle at zero rather than
                    // retaining the worst-case reservation.
                    budget.settleActual(new ModelUsage(0, 0));
                    throw new ExtractionFailedException("The model provider rejected this request: " + unsupported.reason());
                }
            }
        }
        throw new IllegalStateException("Unreachable: the loop above always returns or throws by its last attempt.");
    }

    /**
     * The fields one request carries. A reply object per field costs the
     * answer roughly fifty tokens and a repeated group ten times that, so a
     * form whose fields would not fit one call's cap is split into as many
     * calls of consecutive scalar fields as it needs, each getting the same
     * excerpts and the same repair rule. Repeated fields ride with the
     * first call: their items are one object each too, and the first call
     * is the least crowded.
     */
    public static List<List<FieldDefinition>> batches(List<FieldDefinition> fieldDefinitions) {
        List<FieldDefinition> scalars =
                fieldDefinitions.stream().filter(f -> f.cardinality() == FieldCardinality.SCALAR).toList();
        List<FieldDefinition> repeated =
                fieldDefinitions.stream().filter(f -> f.cardinality() == FieldCardinality.REPEATED).toList();
        int size = scalars.size() + 10 * repeated.size();
        if (size <= REPLY_FITS_ONE_CALL) {
            return List.of(fieldDefinitions);
        }
        List<List<FieldDefinition>> batches = new ArrayList<>();
        for (int start = 0; start < scalars.size(); start += SCALARS_PER_CALL) {
            List<FieldDefinition> batch = new ArrayList<>(scalars.subList(start, Math.min(start + SCALARS_PER_CALL, scalars.size())));
            if (start == 0) {
                batch.addAll(repeated);
            }
            batches.add(batch);
        }
        return batches;
    }

    public static int outputTokenCap(List<FieldDefinition> fields) {
        int size = fields.stream()
                .mapToInt(f -> f.cardinality() == FieldCardinality.REPEATED ? 10 : 1)
                .sum();
        return Math.min(MAX_OUTPUT_TOKENS, OUTPUT_BASE + OUTPUT_PER_FIELD * size);
    }

    /** Creates addressable citations for each source excerpt before sending it to the model. */
    public List<LabeledExcerpt> segmentAndCiteSource(long workspaceId, long userId, SourceSnapshot snapshot) {
        List<LabeledExcerpt> excerpts = new ArrayList<>();
        var extraction = documentExtractionService.extract(workspaceId, userId, snapshot.artifactId());
        switch (extraction) {
            case io.github.vihuynh72.brownie.core.document.ExtractionResult.PlainText result -> {
                requireComplete(snapshot, result.version().status());
                for (var segment : PlainTextSegmenter.segmentIntoParagraphs(result.version().graph().normalizedText())) {
                    addSegments(excerpts, workspaceId, userId, snapshot, segment.text(),
                            (start, end) -> new EvidenceLocator.PlainText(segment.startCodePoint() + start, segment.startCodePoint() + end));
                }
            }
            case io.github.vihuynh72.brownie.core.document.ExtractionResult.Docx result -> {
                requireComplete(snapshot, result.version().status());
                for (var part : result.version().graph().parts()) {
                    addDocxNode(excerpts, workspaceId, userId, snapshot, part.partName(), part.root());
                }
            }
            case io.github.vihuynh72.brownie.core.document.ExtractionResult.Pdf result -> {
                if (result.version().unsupportedReason() == io.github.vihuynh72.brownie.core.document.UnsupportedPdfReason.NO_EXTRACTABLE_TEXT) {
                    throw new SourceNotExtractableException(snapshot.id(), "PDF has no extractable text. Use a text-based PDF or a text source.");
                }
                if (result.version().status() == ExtractionStatus.FAILED) {
                    throw new SourceNotExtractableException(snapshot.id(), "PDF could not be read. Check the file and try another source.");
                }
                requireComplete(snapshot, result.version().status());
                for (var page : result.version().graph().pages()) {
                    for (var line : page.lines()) {
                        addSegments(excerpts, workspaceId, userId, snapshot, line.text(),
                                (start, end) -> new EvidenceLocator.Pdf(page.pageNumber(), line.lineIndex(), start, end));
                    }
                }
                if (excerpts.isEmpty()) {
                    throw new SourceNotExtractableException(snapshot.id(), "PDF has no extractable text. Use a text-based PDF or a text source.");
                }
            }
        }
        return List.copyOf(excerpts);
    }

    private static void requireComplete(SourceSnapshot snapshot, ExtractionStatus status) {
        if (status != ExtractionStatus.COMPLETE) {
            throw new SourceNotExtractableException(snapshot.id(), status);
        }
    }

    private void addDocxNode(List<LabeledExcerpt> excerpts, long workspaceId, long userId, SourceSnapshot snapshot,
                             String partName, StructuralNode node) {
        if (node.text() != null) {
            addSegments(excerpts, workspaceId, userId, snapshot, node.text(),
                    (start, end) -> new EvidenceLocator.Docx(partName, node.nodeId(), start, end));
        }
        for (var child : node.children()) {
            addDocxNode(excerpts, workspaceId, userId, snapshot, partName, child);
        }
    }

    private void addSegments(List<LabeledExcerpt> excerpts, long workspaceId, long userId, SourceSnapshot snapshot,
                             String text, java.util.function.BiFunction<Integer, Integer, EvidenceLocator> locator) {
        int count = text.codePointCount(0, text.length());
        for (int start = 0; start < count; start += 2000) {
            int end = Math.min(start + 2000, count);
            String chunk = text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
            if (!chunk.isBlank()) {
                SourceSpan span = sourceService.createSpan(workspaceId, userId, snapshot.id(), locator.apply(start, end));
                excerpts.add(new LabeledExcerpt(span.id(), chunk));
            }
        }
    }

    /**
     * Rejects, rather than trusts, any candidate that cites an evidence
     * span outside the exact set this run actually offered -- a
     * fabricated or out-of-scope reference (see {@code ExtractionService}'s
     * own class documentation). A rejected candidate becomes explicitly unresolved, never
     * silently stripped of just the bad citation while keeping its value.
     */
    private ExtractionResult rejectFabricatedEvidence(ExtractionResult raw, Set<Long> allowedSpanIds) {
        Map<String, FieldCandidate> scalars = new LinkedHashMap<>();
        raw.scalarCandidates().forEach((fieldId, candidate) -> scalars.put(fieldId, sanitize(candidate, allowedSpanIds)));

        List<RepeatedItemCandidate> items = raw.repeatedItems().stream()
                .map(item -> {
                    Map<String, FieldCandidate> sanitizedFields = new LinkedHashMap<>();
                    item.fields().forEach((fieldId, candidate) -> sanitizedFields.put(fieldId, sanitize(candidate, allowedSpanIds)));
                    return new RepeatedItemCandidate(sanitizedFields);
                })
                .toList();

        return new ExtractionResult(scalars, items);
    }

    private static FieldCandidate sanitize(FieldCandidate candidate, Set<Long> allowedSpanIds) {
        if (candidate.unresolved() || allowedSpanIds.containsAll(candidate.evidenceSpanIds())) {
            return candidate;
        }
        return new FieldCandidate(
                candidate.fieldId(), null, List.of(), true, "Cited an evidence span that was not offered for this extraction.");
    }
}
