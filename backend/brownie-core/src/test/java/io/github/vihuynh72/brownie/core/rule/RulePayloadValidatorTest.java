package io.github.vihuynh72.brownie.core.rule;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.source.SourceKind;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulePayloadValidatorTest {

    private static final List<FieldDefinition> FIELDS = List.of(
            new FieldDefinition(
                    "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                    new FieldBindingTarget.ContentControlTag("meeting.title")),
            new FieldDefinition(
                    "action.items", FieldType.TEXT, FieldCardinality.REPEATED, FieldRequiredness.OPTIONAL,
                    new FieldBindingTarget.StructuralNode(DocumentPartKind.MAIN_DOCUMENT, "p2")));

    /** Main document: the "meeting.title" content control, a "duplicate.tag" control appearing twice (for ambiguity), and a table at "p2" (the action-items field's own binding, reused as a protected-region target). */
    private static DocxStructuralGraph graph() {
        StructuralNode titleControl = contentControl("p0/sdt0", "meeting.title");
        StructuralNode dup1 = contentControl("p1/sdt0", "duplicate.tag");
        StructuralNode dup2 = contentControl("p1/sdt1", "duplicate.tag");
        StructuralNode table = new StructuralNode("p2", StructuralNodeKind.TABLE, null, null, null, null, List.of());
        StructuralNode body = new StructuralNode(
                "body", StructuralNodeKind.BODY, null, null, null, null, List.of(titleControl, dup1, dup2, table));
        return new DocxStructuralGraph("test-v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }

    private static StructuralNode contentControl(String nodeId, String tag) {
        return new StructuralNode(nodeId, StructuralNodeKind.CONTENT_CONTROL, null, null, tag, null, List.of());
    }

    @Test
    void requiredFieldsIsValidWhenEveryFieldExists() {
        var problems = RulePayloadValidator.validate(
                FIELDS, graph(), new RuleScope.WholeTemplate(), new RulePayload.RequiredFields(List.of("meeting.title")));
        assertTrue(problems.isEmpty());
    }

    @Test
    void requiredFieldsRejectsAnEmptyList() {
        var problems =
                RulePayloadValidator.validate(FIELDS, graph(), new RuleScope.WholeTemplate(), new RulePayload.RequiredFields(List.of()));
        assertEquals(List.of(new RuleProblem(RuleProblemReason.MALFORMED, "fieldIds must not be empty")), problems);
    }

    @Test
    void requiredFieldsRejectsAnUnknownField() {
        var problems = RulePayloadValidator.validate(
                FIELDS, graph(), new RuleScope.WholeTemplate(), new RulePayload.RequiredFields(List.of("no.such.field")));
        assertEquals(RuleProblemReason.UNKNOWN_FIELD, problems.get(0).reason());
    }

    @Test
    void requiredFieldsRejectsADuplicateEntry() {
        var problems = RulePayloadValidator.validate(
                FIELDS, graph(), new RuleScope.WholeTemplate(), new RulePayload.RequiredFields(List.of("meeting.title", "meeting.title")));
        assertEquals(RuleProblemReason.MALFORMED, problems.get(0).reason());
    }

    @Test
    void maxTextLengthRejectsANonPositiveBound() {
        var problems = RulePayloadValidator.validate(
                FIELDS, graph(), new RuleScope.SingleField("meeting.title"), new RulePayload.MaxTextLength("meeting.title", 0));
        assertEquals(List.of(new RuleProblem(RuleProblemReason.MALFORMED, "maxCharacters must be positive")), problems);
    }

    @Test
    void maxTextLengthRejectsAnUnknownField() {
        var problems = RulePayloadValidator.validate(
                FIELDS, graph(), new RuleScope.SingleField("no.such.field"), new RulePayload.MaxTextLength("no.such.field", 100));
        // Both the scope's own fieldId and the payload's own fieldId are unknown -- two problems, not one.
        assertEquals(2, problems.size());
        assertTrue(problems.stream().allMatch(p -> p.reason() == RuleProblemReason.UNKNOWN_FIELD));
    }

    @Test
    void maxItemCountOnAScalarFieldIsWrongCardinality() {
        var problems = RulePayloadValidator.validate(
                FIELDS, graph(), new RuleScope.SingleField("meeting.title"), new RulePayload.MaxItemCount("meeting.title", 5));
        assertEquals(RuleProblemReason.WRONG_CARDINALITY, problems.get(0).reason());
    }

    @Test
    void maxItemCountOnARepeatedFieldIsValid() {
        var problems = RulePayloadValidator.validate(
                FIELDS, graph(), new RuleScope.SingleField("action.items"), new RulePayload.MaxItemCount("action.items", 5));
        assertTrue(problems.isEmpty());
    }

    @Test
    void allowedSectionOrderChecksStructureNotRealFieldIdentity() {
        // "agenda"/"decisions" are not real field IDs, and that is fine -- no
        // section concept exists yet to validate them against; only shape is checked.
        var problems = RulePayloadValidator.validate(
                FIELDS, graph(), new RuleScope.WholeTemplate(), new RulePayload.AllowedSectionOrder(List.of("agenda", "decisions")));
        assertTrue(problems.isEmpty());
    }

    @Test
    void allowedSectionOrderRejectsADuplicateSection() {
        var problems = RulePayloadValidator.validate(
                FIELDS, graph(), new RuleScope.WholeTemplate(), new RulePayload.AllowedSectionOrder(List.of("agenda", "agenda")));
        assertEquals(RuleProblemReason.MALFORMED, problems.get(0).reason());
    }

    @Test
    void dateDisplayFormatIsValidForAKnownField() {
        var problems = RulePayloadValidator.validate(
                FIELDS, graph(), new RuleScope.SingleField("meeting.title"),
                new RulePayload.DateDisplayFormat("meeting.title", DateFormatStyle.LONG));
        assertTrue(problems.isEmpty());
    }

    @Test
    void allowedSourceKindsRejectsAnEmptyList() {
        var problems = RulePayloadValidator.validate(
                FIELDS, graph(), new RuleScope.SingleField("meeting.title"),
                new RulePayload.AllowedSourceKinds("meeting.title", List.of()));
        assertTrue(problems.stream().anyMatch(p -> p.reason() == RuleProblemReason.MALFORMED));
    }

    @Test
    void allowedSourceKindsIsValidWithARealSourceKind() {
        var problems = RulePayloadValidator.validate(
                FIELDS, graph(), new RuleScope.SingleField("meeting.title"),
                new RulePayload.AllowedSourceKinds("meeting.title", List.of(SourceKind.ARTIFACT)));
        assertTrue(problems.isEmpty());
    }

    @Test
    void repeatableRegionEmptyBehaviorOnAScalarFieldIsWrongCardinality() {
        var problems = RulePayloadValidator.validate(
                FIELDS, graph(), new RuleScope.SingleField("meeting.title"),
                new RulePayload.RepeatableRegionEmptyBehavior("meeting.title", EmptyValueResolution.OMIT));
        assertEquals(RuleProblemReason.WRONG_CARDINALITY, problems.get(0).reason());
    }

    @Test
    void protectedRegionIsValidWhenTheTargetResolvesExactlyOnce() {
        var problems = RulePayloadValidator.validate(
                FIELDS, graph(), new RuleScope.WholeTemplate(),
                new RulePayload.ProtectedRegion(new FieldBindingTarget.ContentControlTag("meeting.title")));
        assertTrue(problems.isEmpty());
    }

    @Test
    void protectedRegionIsUnsupportedWhenTheTargetIsMissing() {
        var problems = RulePayloadValidator.validate(
                FIELDS, graph(), new RuleScope.WholeTemplate(),
                new RulePayload.ProtectedRegion(new FieldBindingTarget.ContentControlTag("no.such.tag")));
        assertEquals(RuleProblemReason.UNSUPPORTED_TARGET, problems.get(0).reason());
    }

    @Test
    void protectedRegionIsUnsupportedWhenTheTargetIsAmbiguous() {
        var problems = RulePayloadValidator.validate(
                FIELDS, graph(), new RuleScope.WholeTemplate(),
                new RulePayload.ProtectedRegion(new FieldBindingTarget.ContentControlTag("duplicate.tag")));
        assertEquals(RuleProblemReason.UNSUPPORTED_TARGET, problems.get(0).reason());
    }
}
