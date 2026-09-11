package io.github.vihuynh72.brownie.core.rule;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleConflictDetectorTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long TEMPLATE_ID = 1L;
    private static final long VERSION_ID = 1L;
    private static final long USER_ID = 7L;

    private static final List<FieldDefinition> FIELDS = List.of(new FieldDefinition(
            "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
            new FieldBindingTarget.ContentControlTag("meeting.title")));

    private static DocxStructuralGraph graph() {
        StructuralNode control = new StructuralNode("p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.title", null, List.of());
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(control));
        return new DocxStructuralGraph("test-v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }

    private static RuleRevision rule(long id, RulePayload payload) {
        return new RuleRevision(
                id, WORKSPACE_ID, TEMPLATE_ID, VERSION_ID, payload.category(), new RuleScope.WholeTemplate(), payload, "test-v1",
                RuleRevisionStatus.PROPOSED, null, USER_ID, OffsetDateTime.now());
    }

    @Test
    void noConflictsAmongUnrelatedRules() {
        var conflicts = RuleConflictDetector.detectConflicts(
                FIELDS, graph(),
                List.of(
                        rule(1, new RulePayload.RequiredFields(List.of("meeting.title"))),
                        rule(2, new RulePayload.MaxTextLength("meeting.title", 100))));
        assertTrue(conflicts.isEmpty());
    }

    @Test
    void twoRulesOfTheSameKindWithTheSameValueAreRedundantNotConflicting() {
        var conflicts = RuleConflictDetector.detectConflicts(
                FIELDS, graph(),
                List.of(
                        rule(1, new RulePayload.DateDisplayFormat("meeting.title", DateFormatStyle.LONG)),
                        rule(2, new RulePayload.DateDisplayFormat("meeting.title", DateFormatStyle.LONG))));
        assertTrue(conflicts.isEmpty());
    }

    @Test
    void twoRulesOfTheSameKindWithDifferentValuesAreADirectContradiction() {
        var conflicts = RuleConflictDetector.detectConflicts(
                FIELDS, graph(),
                List.of(
                        rule(1, new RulePayload.DateDisplayFormat("meeting.title", DateFormatStyle.LONG)),
                        rule(2, new RulePayload.DateDisplayFormat("meeting.title", DateFormatStyle.ISO))));
        assertEquals(1, conflicts.size());
        assertEquals(RuleConflictReason.DIRECT_CONTRADICTION, conflicts.get(0).reason());
        assertEquals(List.of(1L, 2L), conflicts.get(0).ruleIds());
    }

    @Test
    void twoDifferentRequiredFieldsListsAreAdditiveNotConflicting() {
        // RequiredFields is excluded from the direct-contradiction check since its own list is additive.
        var conflicts = RuleConflictDetector.detectConflicts(
                FIELDS, graph(),
                List.of(
                        rule(1, new RulePayload.RequiredFields(List.of("meeting.title"))),
                        rule(2, new RulePayload.RequiredFields(List.of("meeting.title")))));
        assertTrue(conflicts.isEmpty());
    }

    @Test
    void requiredFieldWithAnOmitMissingValueRuleIsAConflict() {
        var conflicts = RuleConflictDetector.detectConflicts(
                FIELDS, graph(),
                List.of(
                        rule(1, new RulePayload.RequiredFields(List.of("meeting.title"))),
                        rule(2, new RulePayload.MissingValueBehavior("meeting.title", EmptyValueResolution.OMIT))));
        assertEquals(1, conflicts.size());
        assertEquals(RuleConflictReason.REQUIREDNESS_VS_MISSING_VALUE, conflicts.get(0).reason());
        assertEquals(List.of(1L, 2L), conflicts.get(0).ruleIds());
    }

    @Test
    void requiredFieldWithABlankMissingValueRuleIsNotAConflict() {
        var conflicts = RuleConflictDetector.detectConflicts(
                FIELDS, graph(),
                List.of(
                        rule(1, new RulePayload.RequiredFields(List.of("meeting.title"))),
                        rule(2, new RulePayload.MissingValueBehavior("meeting.title", EmptyValueResolution.BLANK))));
        assertTrue(conflicts.isEmpty());
    }

    @Test
    void protectedRegionMatchingAFieldsOwnBindingIsAConflict() {
        var conflicts = RuleConflictDetector.detectConflicts(
                FIELDS, graph(), List.of(rule(1, new RulePayload.ProtectedRegion(new FieldBindingTarget.ContentControlTag("meeting.title")))));
        assertEquals(1, conflicts.size());
        assertEquals(RuleConflictReason.PROTECTED_FIELD_BINDING, conflicts.get(0).reason());
    }

    @Test
    void protectedRegionMatchingTheSameNodeThroughADifferentBindingKindIsStillAConflict() {
        // The field is bound via ContentControlTag; the protected region names the exact same
        // physical node via its own StructuralNode path instead -- still the same real location.
        var conflicts = RuleConflictDetector.detectConflicts(
                FIELDS, graph(),
                List.of(rule(
                        1, new RulePayload.ProtectedRegion(new FieldBindingTarget.StructuralNode(DocumentPartKind.MAIN_DOCUMENT, "p0/sdt0")))));
        assertEquals(1, conflicts.size());
        assertEquals(RuleConflictReason.PROTECTED_FIELD_BINDING, conflicts.get(0).reason());
    }

    @Test
    void protectedRegionElsewhereInTheDocumentIsNotAConflict() {
        StructuralNode control = new StructuralNode("p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.title", null, List.of());
        StructuralNode other = new StructuralNode("p1/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "other.tag", null, List.of());
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(control, other));
        DocxStructuralGraph graph =
                new DocxStructuralGraph("test-v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));

        var conflicts = RuleConflictDetector.detectConflicts(
                FIELDS, graph, List.of(rule(1, new RulePayload.ProtectedRegion(new FieldBindingTarget.ContentControlTag("other.tag")))));
        assertTrue(conflicts.isEmpty());
    }
}
