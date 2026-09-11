package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateBindingValidatorTest {

    /**
     * MAIN_DOCUMENT: two content controls tagged "meeting.title" (a
     * deliberate duplicate, for the ambiguity case) and a TABLE node at
     * "p2". HEADER: one content control tagged "header.logo" whose own
     * nodeId ("p0/sdt0") happens to collide, as a bare string, with the
     * first MAIN_DOCUMENT control's nodeId -- proving a StructuralNode
     * binding is scoped to its declared part, not matched across parts.
     */
    private static DocxStructuralGraph graph() {
        StructuralNode mainControl1 = contentControl("p0/sdt0", "meeting.title");
        StructuralNode mainControl2 = contentControl("p1/sdt0", "meeting.title");
        StructuralNode table = new StructuralNode("p2", StructuralNodeKind.TABLE, null, null, null, null, List.of());
        StructuralNode mainBody =
                new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(mainControl1, mainControl2, table));

        StructuralNode headerControl = contentControl("p0/sdt0", "header.logo");
        StructuralNode headerBody = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(headerControl));

        return new DocxStructuralGraph(
                "test-v1",
                List.of(
                        new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, mainBody),
                        new DocumentPart("word/header1.xml", DocumentPartKind.HEADER, headerBody)));
    }

    private static StructuralNode contentControl(String nodeId, String tag) {
        return new StructuralNode(nodeId, StructuralNodeKind.CONTENT_CONTROL, null, null, tag, null, List.of());
    }

    private static FieldDefinition field(String fieldId, FieldBindingTarget binding) {
        return new FieldDefinition(fieldId, FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED, binding);
    }

    @Test
    void uniqueContentControlTagIsSupported() {
        var problems = TemplateBindingValidator.validate(
                graph(), List.of(field("logo", new FieldBindingTarget.ContentControlTag("header.logo"))));
        assertTrue(problems.isEmpty());
    }

    @Test
    void missingContentControlTagIsNotFound() {
        var problems = TemplateBindingValidator.validate(
                graph(), List.of(field("missing", new FieldBindingTarget.ContentControlTag("no.such.tag"))));
        assertEquals(List.of(new UnsupportedBinding("missing", UnsupportedBindingReason.NOT_FOUND)), problems);
    }

    @Test
    void duplicateContentControlTagIsAmbiguous() {
        var problems = TemplateBindingValidator.validate(
                graph(), List.of(field("title", new FieldBindingTarget.ContentControlTag("meeting.title"))));
        assertEquals(List.of(new UnsupportedBinding("title", UnsupportedBindingReason.AMBIGUOUS)), problems);
    }

    @Test
    void structuralNodeBindingIsScopedToItsOwnPartNotMatchedAcrossParts() {
        // "p0/sdt0" exists in both MAIN_DOCUMENT and HEADER, as a bare string, but binding to HEADER
        // must count only the HEADER match -- proving cross-part collision does not falsely register as ambiguous.
        var problems = TemplateBindingValidator.validate(
                graph(),
                List.of(field("logo", new FieldBindingTarget.StructuralNode(DocumentPartKind.HEADER, "p0/sdt0"))));
        assertTrue(problems.isEmpty());
    }

    @Test
    void structuralNodeBindingToTheWrongPartIsNotFound() {
        var problems = TemplateBindingValidator.validate(
                graph(),
                List.of(field("agenda", new FieldBindingTarget.StructuralNode(DocumentPartKind.FOOTER, "p2"))));
        assertEquals(List.of(new UnsupportedBinding("agenda", UnsupportedBindingReason.NOT_FOUND)), problems);
    }

    @Test
    void repeatedFieldIdInTheSameRequestIsFlaggedWithoutAlsoCheckingItsBinding() {
        var problems = TemplateBindingValidator.validate(
                graph(),
                List.of(
                        field("title", new FieldBindingTarget.ContentControlTag("header.logo")),
                        field("title", new FieldBindingTarget.ContentControlTag("no.such.tag"))));
        assertEquals(List.of(new UnsupportedBinding("title", UnsupportedBindingReason.DUPLICATE_FIELD_ID)), problems);
    }

    @Test
    void multipleIndependentProblemsAreAllReportedTogether() {
        var problems = TemplateBindingValidator.validate(
                graph(),
                List.of(
                        field("missing", new FieldBindingTarget.ContentControlTag("no.such.tag")),
                        field("ambiguous", new FieldBindingTarget.ContentControlTag("meeting.title")),
                        field("valid", new FieldBindingTarget.ContentControlTag("header.logo"))));
        assertEquals(
                List.of(
                        new UnsupportedBinding("missing", UnsupportedBindingReason.NOT_FOUND),
                        new UnsupportedBinding("ambiguous", UnsupportedBindingReason.AMBIGUOUS)),
                problems);
    }
}
