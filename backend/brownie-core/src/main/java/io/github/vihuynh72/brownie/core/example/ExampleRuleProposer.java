package io.github.vihuynh72.brownie.core.example;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.rule.DateFormatStyle;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleProposalEvidence;
import io.github.vihuynh72.brownie.core.rule.RuleScope;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Proposes reusable rules by comparing what a template's own {@code
 * ALIGNED} examples actually contain at each scalar field's own bound
 * location -- never by reading anything about the field's business
 * meaning. Every proposed {@link RulePayload} is a derived count or a
 * closed enum value, never an example's own literal text: a {@code
 * MaxTextLength} bound and a {@code DateDisplayFormat} style both describe
 * a pattern across examples, and neither can be used to reconstruct which
 * example said what, closing the "mark example-specific values and avoid
 * retaining them in rule text" requirement by construction rather than by a
 * separate redaction step.
 *
 * <p>Deliberately narrow: only {@link FieldCardinality#SCALAR} fields bound
 * by a {@link FieldBindingTarget.ContentControlTag} are considered (the one
 * binding kind whose own text is actually addressable the same way across
 * every example -- see {@link ExampleAligner}'s own javadoc for why a
 * {@code StructuralNode} binding cannot be compared this way at all), and
 * only two rule kinds are proposed: {@code MaxTextLength} for a {@code
 * TEXT} field and {@code DateDisplayFormat} for a {@code DATE} field whose
 * examples' own raw text recognizably matches one of the three closed
 * {@link DateFormatStyle} presentations. Every other rule kind in this
 * product's own constrained vocabulary would require guessing the field's
 * business meaning (what counts as a "section," what a missing value
 * should default to) rather than observing a fact already present in the
 * examples, so none of them are proposed here.
 */
public final class ExampleRuleProposer {

    private ExampleRuleProposer() {
    }

    public record ProposedRule(RuleScope scope, RulePayload payload, RuleProposalEvidence evidence) {
    }

    /**
     * @param exampleGraphs one entry per {@code ALIGNED} example actually
     *     offered as evidence -- a caller filters to {@code ALIGNED} before
     *     calling this, the same way {@link ExampleAligner} itself never
     *     mixes a mismatched example's own content into anything else.
     */
    public static List<ProposedRule> propose(List<FieldDefinition> fieldDefinitions, Map<TemplateExample, DocxStructuralGraph> exampleGraphs) {
        List<ProposedRule> proposals = new ArrayList<>();
        for (FieldDefinition field : fieldDefinitions) {
            if (field.cardinality() != FieldCardinality.SCALAR
                    || !(field.binding() instanceof FieldBindingTarget.ContentControlTag(String tag))) {
                continue;
            }
            Map<Long, String> textByExampleId = new LinkedHashMap<>();
            for (Map.Entry<TemplateExample, DocxStructuralGraph> entry : exampleGraphs.entrySet()) {
                textUnderTag(entry.getValue(), tag).ifPresent(text -> textByExampleId.put(entry.getKey().id(), text));
            }
            if (textByExampleId.isEmpty()) {
                continue;
            }
            switch (field.type()) {
                case TEXT -> proposeMaxTextLength(field.fieldId(), textByExampleId).ifPresent(proposals::add);
                case DATE -> proposeDateDisplayFormat(field.fieldId(), textByExampleId).ifPresent(proposals::add);
            }
        }
        return proposals;
    }

    /**
     * The proposed bound is exactly the longest text actually observed, so
     * every example supports it by construction -- {@code MaxTextLength}
     * has no real notion of "contradiction" the way a categorical choice
     * like {@code DateDisplayFormat} does, a fact about this rule kind's
     * own nature rather than a limitation of this method.
     */
    private static Optional<ProposedRule> proposeMaxTextLength(String fieldId, Map<Long, String> textByExampleId) {
        int longestObserved = textByExampleId.values().stream().mapToInt(String::length).max().orElse(0);
        if (longestObserved <= 0) {
            return Optional.empty();
        }
        RuleProposalEvidence evidence = new RuleProposalEvidence(List.copyOf(textByExampleId.keySet()), List.of());
        return Optional.of(new ProposedRule(
                new RuleScope.SingleField(fieldId), new RulePayload.MaxTextLength(fieldId, longestObserved), evidence));
    }

    /**
     * Groups examples by which of the three closed {@link DateFormatStyle}
     * presentations their own raw text matches, and proposes the majority
     * style -- every other recognized style becomes a real, named
     * contradiction. An example whose text matches none of the three known
     * patterns casts no vote either way, rather than being force-fit into
     * the closest guess. A genuine tie between two styles proposes nothing
     * at all: "three examples with one contradiction are not unanimous
     * evidence," and a tie is an even weaker case than that.
     */
    private static Optional<ProposedRule> proposeDateDisplayFormat(String fieldId, Map<Long, String> textByExampleId) {
        Map<DateFormatStyle, List<Long>> exampleIdsByStyle = new EnumMap<>(DateFormatStyle.class);
        for (Map.Entry<Long, String> entry : textByExampleId.entrySet()) {
            classifyDate(entry.getValue())
                    .ifPresent(style -> exampleIdsByStyle.computeIfAbsent(style, ignored -> new ArrayList<>()).add(entry.getKey()));
        }
        if (exampleIdsByStyle.isEmpty()) {
            return Optional.empty();
        }
        Map.Entry<DateFormatStyle, List<Long>> majority =
                exampleIdsByStyle.entrySet().stream().max(Comparator.comparingInt(entry -> entry.getValue().size())).orElseThrow();
        long tieCount = exampleIdsByStyle.values().stream().filter(ids -> ids.size() == majority.getValue().size()).count();
        if (tieCount > 1) {
            return Optional.empty();
        }
        List<Long> contradicting = exampleIdsByStyle.entrySet().stream()
                .filter(entry -> entry.getKey() != majority.getKey())
                .flatMap(entry -> entry.getValue().stream())
                .toList();
        RuleProposalEvidence evidence = new RuleProposalEvidence(majority.getValue(), contradicting);
        return Optional.of(new ProposedRule(
                new RuleScope.SingleField(fieldId), new RulePayload.DateDisplayFormat(fieldId, majority.getKey()), evidence));
    }

    private static Optional<DateFormatStyle> classifyDate(String text) {
        String trimmed = text.strip();
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return Optional.of(DateFormatStyle.ISO);
        }
        if (trimmed.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) {
            return Optional.of(DateFormatStyle.SHORT);
        }
        if (trimmed.matches("[A-Za-z]+ \\d{1,2}, \\d{4}")) {
            return Optional.of(DateFormatStyle.LONG);
        }
        return Optional.empty();
    }

    /** The first content control matching {@code tag}'s own concatenated run text, across every part -- mirrors how {@code TemplateBindingValidator} walks the same graph, but collects text instead of counting matches. */
    private static Optional<String> textUnderTag(DocxStructuralGraph graph, String tag) {
        for (DocumentPart part : graph.parts()) {
            Optional<String> found = findTaggedControlText(part.root(), tag);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> findTaggedControlText(StructuralNode node, String tag) {
        if (node.kind() == StructuralNodeKind.CONTENT_CONTROL && tag.equals(node.contentControlTag())) {
            StringBuilder text = new StringBuilder();
            collectRunText(node, text);
            return Optional.of(text.toString());
        }
        for (StructuralNode child : node.children()) {
            Optional<String> found = findTaggedControlText(child, tag);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static void collectRunText(StructuralNode node, StringBuilder text) {
        if (node.kind() == StructuralNodeKind.RUN && node.text() != null) {
            text.append(node.text());
        }
        for (StructuralNode child : node.children()) {
            collectRunText(child, text);
        }
    }
}
