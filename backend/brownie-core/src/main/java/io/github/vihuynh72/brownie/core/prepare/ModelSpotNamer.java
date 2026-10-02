package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.generation.usage.BudgetExceededException;
import io.github.vihuynh72.brownie.core.generation.usage.MemberUsageLedger;
import io.github.vihuynh72.brownie.core.generation.usage.MemberUsageRepository;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits;
import io.github.vihuynh72.brownie.core.generation.usage.UsageBudget;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimitKind;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelRequest;
import io.github.vihuynh72.brownie.core.model.ModelTransportException;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import io.github.vihuynh72.brownie.core.template.FieldIds;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Names the places found in a form with a few cheap model calls, one for
 * each part of the document (see {@link FillSpotChunker}), for Word and
 * PDF alike. The rules' decisions are the floor: a place the model did not
 * decide, a part it did not answer usably and every part beyond the call
 * limit keep what {@link RulesOnlySpotNamer} would have said, so a person
 * always gets named places and an upload never fails here.
 *
 * <p>Each call is a request the person made directly, charged to them in
 * the usage ledger the way an Assist request is: its own budget of one
 * request at the direct-request limits, reserved before it is sent and
 * settled from what the provider reports, under the workspace's and
 * everyone's monthly allowances. One attempt per part and no repair: a
 * refused, cut-off, unreadable or unsent reply sends that part to the
 * rules. When the allowance is used up, the provider cannot be reached or
 * it rejects the request's settings, the parts not yet sent go to the
 * rules without a call, since each would fail the same way.
 *
 * <p>Nothing in a reply is trusted as it stands, the same way {@code
 * ExtractionService} refuses evidence it did not offer: an id that was not
 * offered or comes twice is ignored; a label that is not one line of at
 * most 60 characters and {@value #MAX_LABEL_WORDS} words, with a letter or
 * digit, or that only copies a value printed in the place's table (the
 * course beside a year), is replaced by the rules' label; a row that was
 * not offered, or holds no kept place, does not repeat. {@code required}
 * only ever becomes a hint.
 *
 * <p>The model only ever decides about the rules' weaker guesses. A place
 * the rules are sure of ({@link NamingCandidate#sure()}) stays whatever
 * the reply says, unless it is for a signature; places of one group (a
 * table row, the boxes of one grid) are kept or left out together, kept
 * when any of them is. Whoever carries the decisions out counts the places
 * left out ({@link PreparationNotice#PLACES_LEFT_OUT}), so none goes
 * quietly.
 */
public final class ModelSpotNamer implements SpotNamer {

    /** The most input one part may be estimated at, well inside the direct-request limit of 40,000. */
    public static final int MAX_PART_INPUT_TOKENS = 10_000;
    /** The most places one part may ask about. */
    public static final int MAX_PART_CANDIDATES = 150;
    /** A label is a name: a reply that writes a sentence there is not naming anything. */
    static final int MAX_LABEL_WORDS = 8;

    /** The notice's detail when places beyond the call limit were named by the rules. */
    public static final String TOO_MANY_PLACES = "TOO_MANY_PLACES";
    public static final String SOME_NAMED_BY_RULES = "SOME_NAMED_BY_RULES";

    private static final Logger log = LoggerFactory.getLogger(ModelSpotNamer.class);

    private final ModelGateway modelGateway;
    private final FillSpotResponseParser responseParser;
    private final MemberUsageRepository usageRepository;
    private final UsageLimits directRequestLimits;
    private final MonthlyUsageLimits monthlyUsageLimits;
    private final ModelPricing modelPricing;
    private final String modelName;
    private final int maxModelCalls;

    public ModelSpotNamer(
            ModelGateway modelGateway,
            FillSpotResponseParser responseParser,
            MemberUsageRepository usageRepository,
            UsageLimits directRequestLimits,
            MonthlyUsageLimits monthlyUsageLimits,
            ModelPricing modelPricing,
            String modelName,
            int maxModelCalls) {
        if (maxModelCalls < 1) {
            throw new IllegalArgumentException("brownie.fill-spots.max-model-calls must be at least 1, was " + maxModelCalls + ".");
        }
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway");
        this.responseParser = Objects.requireNonNull(responseParser, "responseParser");
        this.usageRepository = Objects.requireNonNull(usageRepository, "usageRepository");
        this.directRequestLimits = Objects.requireNonNull(directRequestLimits, "directRequestLimits");
        this.monthlyUsageLimits = Objects.requireNonNull(monthlyUsageLimits, "monthlyUsageLimits");
        this.modelPricing = Objects.requireNonNull(modelPricing, "modelPricing");
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.maxModelCalls = maxModelCalls;
    }

    @Override
    public SpotNaming name(long workspaceId, long userId, SpotNamingInput input) {
        if (input.candidates().isEmpty()) {
            return new SpotNaming(List.of(), null, NamingSource.RULES, SpotNaming.NO_CANDIDATES, List.of());
        }
        FillSpotChunker.Plan plan = FillSpotChunker.plan(input, maxModelCalls, MAX_PART_INPUT_TOKENS, MAX_PART_CANDIDATES);

        Map<String, NamedSpot> decided = new HashMap<>();
        String repeatingRow = null;
        boolean modelNamedAny = false;
        String unsentReason = null;
        String firstFailure = null;
        for (FillSpotChunker.Chunk chunk : plan.chunks()) {
            PartOutcome outcome = unsentReason != null ? PartOutcome.failed(unsentReason, true) : ask(workspaceId, userId, chunk);
            Map<String, NamedSpot> part;
            String row;
            if (outcome.reply() != null) {
                modelNamedAny = true;
                part = sanitized(chunk, outcome.reply());
                row = sanitizedRow(chunk, outcome.reply().repeatingRow(), part);
            } else {
                firstFailure = firstFailure == null ? outcome.failure() : firstFailure;
                unsentReason = outcome.stopsTheRest() ? outcome.failure() : unsentReason;
                part = byRules(chunk.candidates());
                row = firstRowWithAKeptPlace(chunk.rowKeys(), chunk.candidates(), part);
            }
            decided.putAll(part);
            repeatingRow = repeatingRow == null ? row : repeatingRow;
        }
        Map<String, NamedSpot> leftOver = byRules(plan.leftOver());
        decided.putAll(leftOver);
        if (repeatingRow == null) {
            repeatingRow = firstRowWithAKeptPlace(input.offeredRowKeys(), plan.leftOver(), leftOver);
        }
        for (NamingCandidate candidate : input.candidates()) {
            decided.putIfAbsent(candidate.id(), RulesOnlySpotNamer.byRules(candidate));
        }
        keepGroupsWhole(input.candidates(), decided);

        List<NamedSpot> spots = input.candidates().stream().map(candidate -> decided.get(candidate.id())).toList();
        if (!modelNamedAny) {
            return new SpotNaming(spots, repeatingRow, NamingSource.RULES,
                    firstFailure == null ? SpotNaming.MODEL_UNAVAILABLE : firstFailure, List.of());
        }
        List<PreparationNotice> notices = new ArrayList<>();
        long namedByRules = spots.stream().filter(spot -> spot.keep() && !spot.namedByModel()).count();
        if (namedByRules > 0) {
            notices.add(new PreparationNotice(SOME_NAMED_BY_RULES, (int) namedByRules,
                    firstFailure != null ? firstFailure : plan.leftOver().isEmpty() ? null : TOO_MANY_PLACES));
        }
        return new SpotNaming(spots, repeatingRow, NamingSource.MODEL, null, notices);
    }

    /**
     * One physical call for one part, written into the usage ledger before
     * it is sent and charged to the person who uploaded the form, exactly
     * as an Assist request is. Every way it can fail is an outcome, not an
     * exception: the caller names that part by the rules instead.
     */
    private PartOutcome ask(long workspaceId, long userId, FillSpotChunker.Chunk chunk) {
        ModelRequest request = chunk.request();
        UsageBudget budget = new UsageBudget(
                directRequestLimits,
                modelPricing,
                new MemberUsageLedger(
                        usageRepository, workspaceId, userId, modelName, modelPricing, directRequestLimits, monthlyUsageLimits));
        try {
            budget.reserveForCall(UsageBudget.estimateInputTokens(request), request.maxOutputTokens(), request.promptVersion());
        } catch (BudgetExceededException e) {
            boolean allowance = e.kind() == UsageLimitKind.WORKSPACE_MONTH || e.kind() == UsageLimitKind.GLOBAL_MONTH;
            log.info("Naming part of a form fell back to the rules before it was sent: {}", e.getMessage());
            return PartOutcome.failed(
                    allowance ? SpotNaming.ALLOWANCE_USED_UP : SpotNaming.MODEL_UNAVAILABLE, e.kind() != UsageLimitKind.RUN);
        } catch (RuntimeException e) {
            // The ledger could not be written, so nothing may be sent; the form is still named, by the rules.
            log.warn("Could not reserve model usage for naming part of a form; the rules name it instead.", e);
            return PartOutcome.failed(SpotNaming.MODEL_UNAVAILABLE, true);
        }

        ModelCompletion completion;
        try {
            completion = modelGateway.complete(request);
        } catch (ModelTransportException e) {
            budget.retainReservationAfterLostResponse();
            log.info("Naming part of a form fell back to the rules: the model could not be reached ({}).", e.getMessage());
            return PartOutcome.failed(SpotNaming.MODEL_UNAVAILABLE, true);
        } catch (RuntimeException e) {
            budget.retainReservationAfterLostResponse();
            log.warn("The model call naming part of a form failed; the rules name it instead.", e);
            return PartOutcome.failed(SpotNaming.MODEL_UNAVAILABLE, true);
        }

        String content = switch (completion) {
            case ModelCompletion.Success success -> {
                budget.settleActual(success.usage());
                yield success.content();
            }
            case ModelCompletion.Refusal refusal -> {
                budget.settleActual(refusal.usage());
                yield null;
            }
            case ModelCompletion.MalformedOutput malformed -> {
                budget.settleActual(malformed.usage());
                yield null;
            }
            case ModelCompletion.IncompleteOutput incomplete -> {
                budget.settleActual(incomplete.usage());
                yield null;
            }
            case ModelCompletion.UnsupportedParameters unsupported -> {
                budget.settleActual(new ModelUsage(0, 0));
                yield null;
            }
        };
        if (content == null) {
            log.info("Naming part of a form fell back to the rules: the reply was {}.", completion.getClass().getSimpleName());
            return PartOutcome.failed(SpotNaming.MODEL_UNAVAILABLE, completion instanceof ModelCompletion.UnsupportedParameters);
        }
        try {
            return PartOutcome.answered(responseParser.parse(content));
        } catch (FillSpotResponseParseException e) {
            log.info("Naming part of a form fell back to the rules: the reply did not have the asked-for shape ({}).", e.getMessage());
            return PartOutcome.failed(SpotNaming.MODEL_UNAVAILABLE, false);
        }
    }

    /** The reply's decisions about the places it was asked about; any it left out keep the rules' decision. */
    static Map<String, NamedSpot> sanitized(FillSpotChunker.Chunk chunk, FillSpotReply reply) {
        Map<String, NamingCandidate> offered = new LinkedHashMap<>();
        chunk.candidates().forEach(candidate -> offered.put(candidate.id(), candidate));
        Map<String, NamedSpot> decided = new LinkedHashMap<>();
        for (FillSpotReply.Spot spot : reply.spots()) {
            NamingCandidate candidate = offered.get(spot.id());
            if (candidate == null || decided.containsKey(spot.id())) {
                continue;
            }
            String label = acceptableLabel(spot.label());
            if (label != null && copiesATableValue(candidate, label)) {
                label = null;
            }
            String suggestedType = FillSpotPromptBuilder.REPLY_TYPES.contains(spot.type()) ? spot.type() : candidate.rulesType().name();
            FieldType type = FieldType.DATE.name().equals(suggestedType) ? FieldType.DATE : FieldType.TEXT;
            // A place the rules are sure of is not the model's to leave out; a place to sign never is the rules' to keep.
            boolean keep = spot.keep() || candidate.sure() && !candidate.signatureLike();
            decided.put(spot.id(), new NamedSpot(
                    spot.id(), keep, label == null ? candidate.rulesLabel() : label, type, suggestedType, spot.required(),
                    label != null));
        }
        for (NamingCandidate candidate : chunk.candidates()) {
            decided.computeIfAbsent(candidate.id(), id -> RulesOnlySpotNamer.byRules(candidate));
        }
        return decided;
    }

    /** A label as it will be stored, or null when the reply's text cannot be one and the rules' label stands. */
    static String acceptableLabel(String text) {
        String label = FieldIds.normalizeLabel(text);
        if (label == null || label.split(" ").length > MAX_LABEL_WORDS) {
            return null;
        }
        return label;
    }

    /**
     * Whether a label only repeats a value printed in the place's table, so
     * that it names what was written beside the place rather than what goes
     * in it. Case, spaces and punctuation do not count. The rules' own label
     * is never a copy: the words beside a cell under a header that only says
     * an answer goes there ("Question | Answer"), or the words before a blank
     * in a cell ("Name ____"), name the place though they are printed in the
     * table.
     */
    static boolean copiesATableValue(NamingCandidate candidate, String label) {
        String wanted = loose(label);
        return !wanted.isEmpty() && !wanted.equals(loose(candidate.rulesLabel()))
                && candidate.tableValues().stream().anyMatch(value -> loose(value).equals(wanted));
    }

    private static String loose(String text) {
        StringBuilder letters = new StringBuilder(text.length());
        text.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).forEach(letters::appendCodePoint);
        return letters.toString();
    }

    /**
     * Places of one group are kept or left out together: when any of them is
     * kept, every other one is kept too, except a place to sign. A table
     * row with one cell left out, or a grid missing a row, would leave the
     * person a part of something meant to be filled in whole.
     */
    static void keepGroupsWhole(List<NamingCandidate> candidates, Map<String, NamedSpot> decided) {
        Set<String> kept = new HashSet<>();
        for (NamingCandidate candidate : candidates) {
            if (candidate.groupKey() != null && decided.get(candidate.id()).keep()) {
                kept.add(candidate.groupKey());
            }
        }
        for (NamingCandidate candidate : candidates) {
            NamedSpot spot = decided.get(candidate.id());
            if (candidate.groupKey() != null && kept.contains(candidate.groupKey()) && !spot.keep() && !candidate.signatureLike()) {
                decided.put(candidate.id(), new NamedSpot(spot.id(), true, spot.label(), spot.type(), spot.suggestedType(),
                        spot.requiredHint(), spot.namedByModel()));
            }
        }
    }

    /** The reply's repeating row, when it was offered for this part and a kept place sits in it. */
    private static String sanitizedRow(FillSpotChunker.Chunk chunk, String chosen, Map<String, NamedSpot> decided) {
        return chosen != null && chunk.rowKeys().contains(chosen)
                ? firstRowWithAKeptPlace(List.of(chosen), chunk.candidates(), decided)
                : null;
    }

    private static Map<String, NamedSpot> byRules(List<NamingCandidate> candidates) {
        Map<String, NamedSpot> decided = new LinkedHashMap<>();
        candidates.forEach(candidate -> decided.put(candidate.id(), RulesOnlySpotNamer.byRules(candidate)));
        return decided;
    }

    /** The first of {@code rowKeys} that one of {@code candidates} sits in and was kept, or null: a row with nothing to fill does not repeat. */
    private static String firstRowWithAKeptPlace(
            List<String> rowKeys, List<NamingCandidate> candidates, Map<String, NamedSpot> decided) {
        for (String rowKey : rowKeys) {
            for (NamingCandidate candidate : candidates) {
                NamedSpot spot = decided.get(candidate.id());
                if (rowKey.equals(candidate.rowKey()) && spot != null && spot.keep()) {
                    return rowKey;
                }
            }
        }
        return null;
    }

    /** What asking about one part came to: a reply to check, or why the rules name it. */
    private record PartOutcome(FillSpotReply reply, String failure, boolean stopsTheRest) {

        static PartOutcome answered(FillSpotReply reply) {
            return new PartOutcome(reply, null, false);
        }

        static PartOutcome failed(String failure, boolean stopsTheRest) {
            return new PartOutcome(null, failure, stopsTheRest);
        }
    }
}
