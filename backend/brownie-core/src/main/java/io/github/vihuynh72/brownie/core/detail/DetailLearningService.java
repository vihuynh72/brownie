package io.github.vihuynh72.brownie.core.detail;

import io.github.vihuynh72.brownie.core.generation.usage.BudgetExceededException;
import io.github.vihuynh72.brownie.core.generation.usage.MemberUsageLedger;
import io.github.vihuynh72.brownie.core.generation.usage.MemberUsageRepository;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits;
import io.github.vihuynh72.brownie.core.generation.usage.UsageBudget;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelMessage;
import io.github.vihuynh72.brownie.core.model.ModelMessageRole;
import io.github.vihuynh72.brownie.core.model.ModelRequest;
import io.github.vihuynh72.brownie.core.model.ModelTransportException;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import io.github.vihuynh72.brownie.core.model.JsonSchema;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentRepository;
import io.github.vihuynh72.brownie.core.revision.DocumentRevision;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.revision.ReviewState;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Learns the approved answers on a form as saved details -- "the person
 * fills this in once, Brownie remembers for the next one". A batch fill
 * never teaches anything, a sensitive value is never saved automatically,
 * and a form that reads as someone else's is left alone. A model failure
 * is UNSURE, not an error: a person's approval always goes through.
 */
public class DetailLearningService {

    private static final Logger log = LoggerFactory.getLogger(DetailLearningService.class);

    private static final String PROMPT_VERSION = "detail-learn-v1";
    private static final int MAX_ATTEMPTS = 2;

    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["subject", "pairs"],
              "additionalProperties": false,
              "properties": {
                "subject": {"type": "string", "enum": ["SELF", "OTHER", "UNSURE"]},
                "pairs": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["label", "key"],
                    "additionalProperties": false,
                    "properties": {
                      "label": {"type": "string"},
                      "key": {"type": ["string", "null"]}
                    }
                  }
                }
              }
            }
            """;

    private final DocumentRepository documentRepository;
    private final TemplateService templateService;
    private final SavedDetailRepository savedDetailRepository;
    private final DetailLearningRepository detailLearningRepository;
    private final io.github.vihuynh72.brownie.core.batch.BatchRepository batchRepository;
    private final ModelGateway modelGateway;
    private final MemberUsageRepository usageRepository;
    private final ModelPricing modelPricing;
    private final UsageLimits directRequestLimits;
    private final MonthlyUsageLimits monthlyUsageLimits;
    private final String modelName;
    private final LearningReplyParser replyParser;

    public DetailLearningService(
            DocumentRepository documentRepository,
            TemplateService templateService,
            SavedDetailRepository savedDetailRepository,
            DetailLearningRepository detailLearningRepository,
            io.github.vihuynh72.brownie.core.batch.BatchRepository batchRepository,
            ModelGateway modelGateway,
            MemberUsageRepository usageRepository,
            ModelPricing modelPricing,
            UsageLimits directRequestLimits,
            MonthlyUsageLimits monthlyUsageLimits,
            String modelName,
            LearningReplyParser replyParser) {
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.templateService = Objects.requireNonNull(templateService);
        this.savedDetailRepository = Objects.requireNonNull(savedDetailRepository);
        this.detailLearningRepository = Objects.requireNonNull(detailLearningRepository);
        this.batchRepository = Objects.requireNonNull(batchRepository);
        this.modelGateway = Objects.requireNonNull(modelGateway);
        this.usageRepository = Objects.requireNonNull(usageRepository);
        this.modelPricing = Objects.requireNonNull(modelPricing);
        this.directRequestLimits = Objects.requireNonNull(directRequestLimits);
        this.monthlyUsageLimits = Objects.requireNonNull(monthlyUsageLimits);
        this.modelName = Objects.requireNonNull(modelName);
        this.replyParser = Objects.requireNonNull(replyParser);
    }

    public record AcceptedField(String label, String value) {
    }


    public record LearnedKey(String key, String label) {
    }

    public enum Outcome {
        SELF, OTHER, UNSURE, SKIPPED
    }

    public record LearningResult(
            Long learningId, Outcome subject, List<LearnedKey> saved, List<LearnedKey> replaced, String reason) {

        static LearningResult skipped() {
            return new LearningResult(null, Outcome.SKIPPED, List.of(), List.of(), "bulk");
        }

        static LearningResult other(String name) {
            return new LearningResult(null, Outcome.OTHER, List.of(), List.of(),
                    "This form looks like it's for " + name + ", so nothing was saved to Your details.");
        }

        static LearningResult unsure() {
            return new LearningResult(null, Outcome.UNSURE, List.of(), List.of(),
                    "Brownie couldn't tell whether this form is about you, so nothing was saved.");
        }
    }

    /**
     * Reads the revision's accepted values with their field labels, decides
     * who the form is about, and on SELF saves the non-sensitive mapped
     * ones, recording what it changed so {@link #undo} can put it back.
     */
    public LearningResult learn(long workspaceId, long userId, long documentId, long revisionId, String displayName) {
        Document document = documentRepository.find(workspaceId, userId, documentId)
                .orElseThrow(() -> new DetailDocumentNotFoundException(documentId));
        if (batchRepository.documentIsInBatch(workspaceId, userId, documentId)) {
            return LearningResult.skipped();
        }
        DocumentRevision revision = documentRepository.findRevision(workspaceId, userId, documentId, revisionId)
                .orElseThrow(() -> new DetailDocumentNotFoundException(documentId));

        Map<String, String> labels = fieldIdToLabel(workspaceId, userId, document, revision);
        List<AcceptedField> accepted = new ArrayList<>();
        revision.fieldStates().forEach((ref, state) -> {
            if (state.review() != ReviewState.ACCEPTED) {
                return;
            }
            FieldValue value = revision.content().fields().get(ref.fieldId());
            String text = value == null ? null : value.asPlainText();
            if (text == null || text.isBlank()) {
                return;
            }
            accepted.add(new AcceptedField(labels.getOrDefault(ref.fieldId(), ref.fieldId()), text));
        });
        if (accepted.isEmpty()) {
            return LearningResult.unsure();
        }

        // The name rule comes before the model: a clearly different name is OTHER without a call.
        String savedFullName = savedDetailRepository.list(userId).stream()
                .filter(each -> each.key().equals(DetailKey.PERSON_FULL_NAME.key()))
                .map(SavedDetail::valueText).findFirst().orElse(null);
        for (AcceptedField field : accepted) {
            if (isFullNameLabel(field.label()) && clearlyDifferent(field.value(), savedFullName != null ? savedFullName : displayName)) {
                return LearningResult.other(field.value());
            }
        }

        Optional<LearningReply> reply = askModel(workspaceId, userId, accepted, displayName, savedFullName);
        if (reply.isEmpty()) {
            return LearningResult.unsure();
        }
        Map<String, String> labelToKey = new LinkedHashMap<>();
        for (LearningReply.Pair pair : reply.get().pairs()) {
            if (pair.key() != null) {
                DetailKey.find(pair.key()).ifPresent(each -> labelToKey.put(pair.label(), each.key()));
            }
        }

        if (reply.get().subject() != LearningReply.Subject.SELF) {
            return reply.get().subject() == LearningReply.Subject.OTHER
                    ? LearningResult.other(displayNameOrDefault(accepted, displayName))
                    : LearningResult.unsure();
        }

        List<SavedDetail> existing = savedDetailRepository.list(userId);
        Map<String, SavedDetail> byKey = new LinkedHashMap<>();
        existing.forEach(each -> byKey.put(each.key(), each));
        List<DetailLearning.WrittenKey> writes = new ArrayList<>();
        List<LearnedKey> saved = new ArrayList<>();
        List<LearnedKey> replaced = new ArrayList<>();
        for (AcceptedField field : accepted) {
            String slug = labelToKey.get(field.label());
            if (slug == null) {
                continue;
            }
            DetailKey detailKey = DetailKey.find(slug).orElse(null);
            if (detailKey == null || detailKey.sensitive() || field.value().length() > 2000) {
                continue;
            }
            SavedDetail previous = byKey.get(slug);
            writes.add(new DetailLearning.WrittenKey(slug, previous == null ? null : previous.valueText(), field.value()));
            savedDetailRepository.upsert(
                    userId, document.workspaceId(), slug, detailKey.label(), field.value(), null, null, false,
                    DetailOrigin.AUTO, documentId, field.label(), document.title());
            if (previous == null) {
                saved.add(new LearnedKey(slug, detailKey.label()));
            } else {
                replaced.add(new LearnedKey(slug, detailKey.label()));
            }
        }
        if (writes.isEmpty()) {
            return LearningResult.unsure();
        }
        long learningId = detailLearningRepository.record(userId, documentId, writes);
        return new LearningResult(learningId, Outcome.SELF, saved, replaced, null);
    }

    /**
     * Puts back exactly what one learning wrote -- only the rows whose
     * current value is still the one it wrote are touched, so a value the
     * person changed since is left alone. Idempotent.
     */
    public void undo(long workspaceId, long userId, long learningId) {
        DetailLearning learning = detailLearningRepository.find(userId, learningId)
                .orElseThrow(() -> new DetailLearningNotFoundException(learningId));
        Map<String, SavedDetail> current = new LinkedHashMap<>();
        savedDetailRepository.list(userId).forEach(each -> current.put(each.key(), each));
        for (DetailLearning.WrittenKey write : learning.writes()) {
            SavedDetail row = current.get(write.key());
            if (row == null || !java.util.Objects.equals(row.valueText(), write.writtenValue())) {
                // Nothing there anymore, or the person changed it since: leave it alone.
                continue;
            }
            if (write.previousValue() == null) {
                savedDetailRepository.delete(userId, write.key());
            } else {
                savedDetailRepository.upsert(
                        userId, row.workspaceId(), write.key(), row.label(), write.previousValue(), null, null, false,
                        row.origin(), row.sourceDocumentId(), row.sourceLabel(), row.sourceTitle());
            }
        }
    }

    private Map<String, String> fieldIdToLabel(long workspaceId, long userId, Document document, DocumentRevision revision) {
        Map<String, String> labels = new LinkedHashMap<>();
        templateService.findVersion(workspaceId, userId, document.templateId(), revision.templateVersionId())
                .ifPresent(version -> version.fieldDefinitions()
                        .forEach(field -> labels.put(field.fieldId(), field.label() == null ? field.fieldId() : field.label())));
        return labels;
    }

    /** Labels that mean the PRIMARY subject's own name, and no one else's. */
    private static final java.util.Set<String> OTHER_PERSON_WORDS = java.util.Set.of(
            "spouse", "partner", "emergency", "contact", "parent", "guardian", "child", "dependent",
            "employer", "reference", "representative", "preparer", "requester");

    /** "Name", "Full name", "Legal name", "Applicant name", "Patient name", "Name of entity", "Name of individual":
        the primary subject's own name, and not a spouse's or an emergency contact's. */
    private static boolean isFullNameLabel(String label) {
        String normalized = normalize(label);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String word : OTHER_PERSON_WORDS) {
            if (java.util.Arrays.asList(normalized.split(" ")).contains(word)) {
                return false;
            }
        }
        return normalized.equals("name") || normalized.equals("your name")
                || normalized.equals("full name") || normalized.equals("complete name")
                || normalized.equals("legal name") || normalized.equals("applicant name")
                || normalized.equals("patient name") || normalized.equals("name of entity")
                || normalized.equals("name of individual") || normalized.endsWith(" full name");
    }

    /**
     * Whether {@code value} plainly is not {@code against}: case and
     * whitespace aside, and ignoring a middle initial, so "Daniel Ortiz"
     * reads as "daniel ortiz" and "D. Ortiz" reads as "Ortiz" for that
     * check. No name to compare against means it is not clearly different.
     */
    private static boolean clearlyDifferent(String value, String against) {
        if (against == null || against.isBlank()) {
            return false;
        }
        return !normalize(value).equals(normalize(against));
    }

    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String collapsed = name.trim().toLowerCase().replace('_', ' ').replaceAll("\\s+", " ");
        // Drop a middle initial: "daniel r ortiz" -> "daniel ortiz".
        return collapsed.replaceAll("(\\s)[a-z]\\s", "$1");
    }

    private static String displayNameOrDefault(List<AcceptedField> accepted, String displayName) {
        return accepted.stream().filter(each -> isFullNameLabel(each.label())).map(AcceptedField::value)
                .findFirst().orElse(displayName == null ? "someone else" : displayName);
    }

    /**
     * One bounded ask: the pairs, the canonical keys it may answer with,
     * and the name context. Up to {@code MAX_ATTEMPTS} physical calls on a
     * transport or malformed reply; anything else is UNSURE by returning
     * empty -- never an exception at the caller's expense.
     */
    private Optional<LearningReply> askModel(
            long workspaceId, long userId, List<AcceptedField> accepted, String displayName, String savedFullName) {
        UsageBudget budget = new UsageBudget(
                directRequestLimits, modelPricing,
                new MemberUsageLedger(usageRepository, workspaceId, userId, modelName, modelPricing,
                        directRequestLimits, monthlyUsageLimits));
        ModelRequest request = new ModelRequest(PROMPT_VERSION, List.of(
                new ModelMessage(ModelMessageRole.SYSTEM,
                        "You decide whether a filled form is about the account's owner and which canonical"
                                + " detail keys its approved field labels map to. Answer with the JSON"
                                + " shape asked for and nothing else: a pair's key is null when no"
                                + " canonical key fits. Never invent a key outside the list."),
                new ModelMessage(ModelMessageRole.USER, prompt(accepted, displayName, savedFullName))),
                new JsonSchema(SCHEMA), 1024);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                budget.reserveForCall(UsageBudget.estimateInputTokens(request), request.maxOutputTokens(), request.promptVersion());
            } catch (BudgetExceededException | RuntimeException e) {
                log.info("Learning a form's details fell back to UNSURE before it was sent: {}", e.getMessage());
                return Optional.empty();
            }
            ModelCompletion completion;
            try {
                completion = modelGateway.complete(request);
            } catch (ModelTransportException | RuntimeException e) {
                budget.retainReservationAfterLostResponse();
                if (attempt == MAX_ATTEMPTS) {
                    log.info("Learning a form's details fell back to UNSURE: the model could not be reached.", e);
                    return Optional.empty();
                }
                continue;
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
                if (attempt == MAX_ATTEMPTS) {
                    return Optional.empty();
                }
                continue;
            }
            try {
                return Optional.of(replyParser.parse(content));
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.info("Learning a form's details fell back to UNSURE: the reply was not the asked-for JSON.");
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static String prompt(List<AcceptedField> accepted, String displayName, String savedFullName) {
        StringBuilder pairs = new StringBuilder();
        for (AcceptedField field : accepted) {
            pairs.append("- \"").append(field.label()).append("\": \"").append(field.value().replace("\"", "'")).append("\"\n");
        }
        StringBuilder keys = new StringBuilder();
        for (DetailKey key : DetailKey.values()) {
            keys.append("- ").append(key.key()).append(" (").append(key.label()).append(")\n");
        }
        return "Approved field labels and the values a person confirmed:\n" + pairs
                + "\nCanonical detail keys you may map a label to (answer null when none fits):\n" + keys
                + "\nThe account's display name is \"" + (displayName == null ? "" : displayName) + "\"."
                + (savedFullName != null ? " The name already saved is \"" + savedFullName + "\"." : "")
                + "\nIs this form about the account's owner? Answer subject SELF, OTHER or UNSURE,"
                + " and for each approved label the canonical key it maps to.";
    }
}
