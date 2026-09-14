package io.github.vihuynh72.brownie.core.example;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.rule.DateFormatStyle;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure, hand-built graphs -- no example alignment, no persistence, mirroring {@code ExampleAlignerTest}'s own style. */
class ExampleRuleProposerTest {

    @Test
    void proposesMaxTextLengthEqualToTheLongestObservedTextAndEveryExampleSupportsIt() {
        List<FieldDefinition> fields = List.of(field("meeting.title", FieldType.TEXT));
        Map<TemplateExample, DocxStructuralGraph> graphs = new LinkedHashMap<>();
        graphs.put(example(1), graphWithText("meeting.title", "Short"));
        graphs.put(example(2), graphWithText("meeting.title", "A noticeably longer meeting title"));

        List<ExampleRuleProposer.ProposedRule> proposed = ExampleRuleProposer.propose(fields, graphs);

        assertEquals(1, proposed.size());
        assertEquals(
                new RulePayload.MaxTextLength("meeting.title", "A noticeably longer meeting title".length()), proposed.get(0).payload());
        assertEquals(2, proposed.get(0).evidence().supportingExampleCount());
        assertEquals(0, proposed.get(0).evidence().contradictingExampleCount());
    }

    @Test
    void proposesTheMajorityDateStyleAndNamesEveryDisagreementAsAContradiction() {
        List<FieldDefinition> fields = List.of(field("meeting.date", FieldType.DATE));
        Map<TemplateExample, DocxStructuralGraph> graphs = new LinkedHashMap<>();
        graphs.put(example(1), graphWithText("meeting.date", "2026-09-10"));
        graphs.put(example(2), graphWithText("meeting.date", "2026-10-01"));
        graphs.put(example(3), graphWithText("meeting.date", "9/10/2026"));

        List<ExampleRuleProposer.ProposedRule> proposed = ExampleRuleProposer.propose(fields, graphs);

        assertEquals(1, proposed.size());
        assertEquals(new RulePayload.DateDisplayFormat("meeting.date", DateFormatStyle.ISO), proposed.get(0).payload());
        assertEquals(2, proposed.get(0).evidence().supportingExampleCount());
        assertEquals(List.of(3L), proposed.get(0).evidence().contradictingExampleIds());
    }

    @Test
    void aGenuineTieBetweenTwoStylesProposesNothingRatherThanGuessing() {
        List<FieldDefinition> fields = List.of(field("meeting.date", FieldType.DATE));
        Map<TemplateExample, DocxStructuralGraph> graphs = new LinkedHashMap<>();
        graphs.put(example(1), graphWithText("meeting.date", "2026-09-10"));
        graphs.put(example(2), graphWithText("meeting.date", "9/10/2026"));

        assertTrue(ExampleRuleProposer.propose(fields, graphs).isEmpty());
    }

    @Test
    void anUnrecognizedDateTextCastsNoVoteEitherWay() {
        List<FieldDefinition> fields = List.of(field("meeting.date", FieldType.DATE));
        Map<TemplateExample, DocxStructuralGraph> graphs = new LinkedHashMap<>();
        graphs.put(example(1), graphWithText("meeting.date", "2026-09-10"));
        graphs.put(example(2), graphWithText("meeting.date", "sometime next week"));

        List<ExampleRuleProposer.ProposedRule> proposed = ExampleRuleProposer.propose(fields, graphs);

        assertEquals(1, proposed.size());
        assertEquals(1, proposed.get(0).evidence().supportingExampleCount());
        assertEquals(0, proposed.get(0).evidence().contradictingExampleCount());
    }

    @Test
    void aRepeatedFieldIsNeverProposedEvenWithRecognizableText() {
        FieldDefinition repeated = new FieldDefinition(
                "action.item.task", FieldType.TEXT, FieldCardinality.REPEATED, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.ContentControlTag("action.item.task"));
        Map<TemplateExample, DocxStructuralGraph> graphs = Map.of(example(1), graphWithText("action.item.task", "Send the invoice"));

        assertTrue(ExampleRuleProposer.propose(List.of(repeated), graphs).isEmpty());
    }

    @Test
    void aStructuralNodeBoundFieldIsNeverProposed() {
        FieldDefinition field = new FieldDefinition(
                "meeting.location", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.StructuralNode(DocumentPartKind.MAIN_DOCUMENT, "p3/r0"));
        Map<TemplateExample, DocxStructuralGraph> graphs = Map.of(example(1), graphWithText("meeting.location", "Room 204"));

        assertTrue(ExampleRuleProposer.propose(List.of(field), graphs).isEmpty());
    }

    @Test
    void noProposedPayloadEverContainsAnExamplesOwnLiteralCanaryText() {
        String canary = "XZQ-UNIQUE-CANARY-9182";
        List<FieldDefinition> fields = List.of(field("meeting.title", FieldType.TEXT), field("meeting.date", FieldType.DATE));
        Map<TemplateExample, DocxStructuralGraph> graphs = Map.of(
                example(1), graphWithTags(Map.of("meeting.title", canary, "meeting.date", "2026-09-10")));

        List<ExampleRuleProposer.ProposedRule> proposed = ExampleRuleProposer.propose(fields, graphs);

        for (ExampleRuleProposer.ProposedRule rule : proposed) {
            assertFalse(rule.payload().toString().contains(canary), "payload " + rule.payload() + " must not contain the example's own text");
        }
    }

    private static FieldDefinition field(String fieldId, FieldType type) {
        return new FieldDefinition(fieldId, type, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag(fieldId));
    }

    private static TemplateExample example(long id) {
        return new TemplateExample(id, 1L, 1L, 1L, 100L + id, 200L + id, ExampleAlignmentStatus.ALIGNED, OffsetDateTime.now());
    }

    private static DocxStructuralGraph graphWithText(String tag, String text) {
        return graphWithTags(Map.of(tag, text));
    }

    private static DocxStructuralGraph graphWithTags(Map<String, String> textByTag) {
        List<StructuralNode> controls = textByTag.entrySet().stream()
                .map(entry -> {
                    StructuralNode run =
                            new StructuralNode(entry.getKey() + "/r0", StructuralNodeKind.RUN, null, entry.getValue(), null, null, List.of());
                    return new StructuralNode(entry.getKey() + "/sdt", StructuralNodeKind.CONTENT_CONTROL, null, null, entry.getKey(), null, List.of(run));
                })
                .toList();
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, controls);
        return new DocxStructuralGraph("test-v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }
}
