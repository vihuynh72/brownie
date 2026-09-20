package io.github.vihuynh72.brownie.core.generation;

import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.PlainTextExtractionVersion;
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

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts candidate field values for one template's fields from one
 * plain-text source, grounded in real, individually citable evidence, and
 * bounded by a caller-supplied {@link UsageBudget} that this class must
 * never spend past. Depends only on the interfaces above, so it has no
 * framework, model-SDK, or JSON-parsing dependency of its own -- real
 * implementations are supplied by whichever module wires this up, the
 * same shape {@code CompilationService} already establishes.
 *
 * <p>Plain text only: a source's DOCX or PDF structural graph is not yet
 * segmented into addressable excerpts here -- a real, named boundary, not
 * an oversight, tracked the same way this codebase already tracks other
 * deliberately incomplete capabilities. There is also no silent provider
 * fallback anywhere in this class or {@code ModelGateway}: a transport
 * failure or an unusable reply is reported as exactly what it was, never
 * retried against a different provider or model.
 */
public class ExtractionService {

    /** The most one extraction request may produce; also the least a run's first request holds in the usage ledger. */
    public static final int MAX_OUTPUT_TOKENS = 2000;
    private static final int MAX_ATTEMPTS = 2;

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
        List<FieldDefinition> scalarFields =
                fieldDefinitions.stream().filter(f -> f.cardinality() == FieldCardinality.SCALAR).toList();
        List<FieldDefinition> repeatedFields =
                fieldDefinitions.stream().filter(f -> f.cardinality() == FieldCardinality.REPEATED).toList();

        Set<Long> allowedSpanIds = new HashSet<>();
        excerpts.forEach(excerpt -> allowedSpanIds.add(excerpt.spanId()));

        ModelRequest request = ExtractionPromptBuilder.build(fieldDefinitions, excerpts, MAX_OUTPUT_TOKENS);
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
     * Segments the snapshot's current plain-text extraction into
     * paragraphs and creates a real, addressable {@link SourceSpan} for
     * each one -- the exact set of citations this run is allowed to
     * receive back. Public so a caller that wants to freeze a bundle of
     * excerpts ahead of an asynchronous {@link #extractFromExcerpts} call
     * (rather than an immediate {@link #extract}) can still reuse this
     * exact citation logic instead of duplicating it.
     */
    public List<LabeledExcerpt> segmentAndCiteSource(long workspaceId, long userId, SourceSnapshot snapshot) {
        PlainTextExtractionVersion extraction = documentExtractionService.extractPlainText(workspaceId, userId, snapshot.artifactId());
        if (extraction.status() != ExtractionStatus.COMPLETE) {
            throw new SourceNotExtractableException(snapshot.id(), extraction.status());
        }
        List<PlainTextSegmenter.Segment> segments =
                PlainTextSegmenter.segmentIntoParagraphs(extraction.graph().normalizedText());
        return segments.stream()
                .map(segment -> {
                    EvidenceLocator locator = new EvidenceLocator.PlainText(segment.startCodePoint(), segment.endCodePointExclusive());
                    SourceSpan span = sourceService.createSpan(workspaceId, userId, snapshot.id(), locator);
                    return new LabeledExcerpt(span.id(), segment.text());
                })
                .toList();
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
