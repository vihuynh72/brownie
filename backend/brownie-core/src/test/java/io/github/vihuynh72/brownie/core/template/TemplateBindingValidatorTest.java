package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
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

    // ---- PDF bindings ----

    @Test
    void aPdfBindingOnAWordTemplateIsTheWrongFormat() {
        var problems = TemplateBindingValidator.validate(graph(), List.of(
                field("name", new FieldBindingTarget.AcroFormField("fullName")),
                field("box", box(1, 72, 72, 100, 14))));
        assertEquals(List.of(
                new UnsupportedBinding("name", UnsupportedBindingReason.WRONG_FORMAT),
                new UnsupportedBinding("box", UnsupportedBindingReason.WRONG_FORMAT)), problems);
    }

    @Test
    void aWordBindingOnAPdfTemplateIsTheWrongFormat() {
        var problems = TemplateBindingValidator.validate(pdf(), List.of(
                field("tag", new FieldBindingTarget.ContentControlTag("header.logo")),
                field("node", new FieldBindingTarget.StructuralNode(DocumentPartKind.MAIN_DOCUMENT, "p2"))));
        assertEquals(List.of(
                new UnsupportedBinding("tag", UnsupportedBindingReason.WRONG_FORMAT),
                new UnsupportedBinding("node", UnsupportedBindingReason.WRONG_FORMAT)), problems);
    }

    @Test
    void aFormFieldMustExistAndTakeText() {
        var problems = TemplateBindingValidator.validate(pdf(), List.of(
                field("name", new FieldBindingTarget.AcroFormField("fullName")),
                field("missing", new FieldBindingTarget.AcroFormField("nothing")),
                field("readOnly", new FieldBindingTarget.AcroFormField("reference")),
                field("checkbox", new FieldBindingTarget.AcroFormField("news")),
                field("hidden", new FieldBindingTarget.AcroFormField("hidden"))));
        assertEquals(List.of(
                new UnsupportedBinding("missing", UnsupportedBindingReason.NOT_FOUND),
                new UnsupportedBinding("readOnly", UnsupportedBindingReason.NOT_FILLABLE),
                new UnsupportedBinding("checkbox", UnsupportedBindingReason.NOT_FILLABLE),
                new UnsupportedBinding("hidden", UnsupportedBindingReason.NOT_FILLABLE)), problems);
    }

    @Test
    void aBoxMustBeWhollyOnAPageTheFormHas() {
        var problems = TemplateBindingValidator.validate(pdf(), List.of(
                field("fits", box(1, 0, 0, 612, 792)),
                field("noPage", box(3, 72, 72, 100, 14)),
                field("pastRightEdge", box(1, 560, 72, 100, 14)),
                field("aboveTop", box(1, 72, -10, 100, 14)),
                field("empty", box(1, 72, 72, 0, 14))));
        assertEquals(List.of(
                new UnsupportedBinding("fits", UnsupportedBindingReason.OVERLAPS),
                new UnsupportedBinding("noPage", UnsupportedBindingReason.OFF_PAGE),
                new UnsupportedBinding("pastRightEdge", UnsupportedBindingReason.OFF_PAGE),
                new UnsupportedBinding("aboveTop", UnsupportedBindingReason.OFF_PAGE),
                new UnsupportedBinding("empty", UnsupportedBindingReason.OFF_PAGE)), problems);
    }

    @Test
    void aBoxSmallerThanEightBySixPointsIsTooSmall() {
        var problems = TemplateBindingValidator.validate(pdf(), List.of(
                field("smallest", box(1, 72, 400, 8, 6)),
                field("narrow", box(1, 72, 420, 7.9, 14)),
                field("short", box(1, 72, 440, 100, 5.9))));
        assertEquals(List.of(
                new UnsupportedBinding("narrow", UnsupportedBindingReason.TOO_SMALL),
                new UnsupportedBinding("short", UnsupportedBindingReason.TOO_SMALL)), problems);
    }

    @Test
    void aBoxOnAPageWithItsOwnUnitCannotBeWrittenOn() {
        var problems = TemplateBindingValidator.validate(pdf(), List.of(field("scaled", box(2, 72, 72, 100, 14))));
        assertEquals(List.of(new UnsupportedBinding("scaled", UnsupportedBindingReason.NOT_FILLABLE)), problems);
    }

    @Test
    void aPlaceCoveringMoreThanAFifthOfAnotherOverlaps() {
        var problems = TemplateBindingValidator.validate(pdf(), List.of(
                field("first", box(1, 72, 500, 100, 20)),
                field("touching", box(1, 172, 500, 100, 20)),
                field("aFifth", box(1, 72, 516, 100, 20)),
                field("more", box(1, 72, 510, 100, 20)),
                field("onTheNameField", box(1, 150, 82, 100, 20)),
                field("name", new FieldBindingTarget.AcroFormField("fullName")),
                field("nameAgain", new FieldBindingTarget.AcroFormField("fullName"))));
        assertEquals(List.of(
                new UnsupportedBinding("more", UnsupportedBindingReason.OVERLAPS),
                new UnsupportedBinding("onTheNameField", UnsupportedBindingReason.OVERLAPS),
                new UnsupportedBinding("nameAgain", UnsupportedBindingReason.OVERLAPS)), problems);
    }

    @Test
    void aProtectedRegionTargetOnAPdfNeverResolves() {
        assertEquals(0, TemplateBindingValidator.matchCount(graph(), new FieldBindingTarget.AcroFormField("fullName")));
        assertEquals(0, TemplateBindingValidator.matchCount(null, new FieldBindingTarget.ContentControlTag("header.logo")));
    }

    private static FieldBindingTarget.PageBox box(int page, double x, double y, double width, double height) {
        return new FieldBindingTarget.PageBox(page, x, y, width, height, PdfTextStyle.DEFAULT, false, PdfOverflowPolicy.SHRINK_TO_FIT);
    }

    /**
     * Page 1, Letter: a name field, a read-only reference field, a check box
     * and a text field shown on no page. Page 2 declares its own unit.
     */
    private static PdfFormGraph pdf() {
        PdfFormGraph.Page first = new PdfFormGraph.Page(
                1, new PdfFormGraph.CropBox(0, 0, 612, 792), 0, 1, true, List.of(), List.of(), List.of(), List.of());
        PdfFormGraph.Page second = new PdfFormGraph.Page(
                2, new PdfFormGraph.CropBox(0, 0, 612, 792), 0, 2, true, List.of(), List.of(), List.of(), List.of());
        return new PdfFormGraph("test-form-v1", List.of(first, second), new PdfFormGraph.AcroForm(true, PdfFormGraph.XfaKind.NONE, false,
                List.of(
                        formField("fullName", PdfFormGraph.FieldKind.TEXT, false, new PdfRect(150, 82, 300, 20)),
                        formField("reference", PdfFormGraph.FieldKind.TEXT, true, new PdfRect(150, 252, 200, 20)),
                        formField("news", PdfFormGraph.FieldKind.CHECKBOX, false, new PdfRect(150, 418, 14, 14)),
                        formField("hidden", PdfFormGraph.FieldKind.TEXT, false, null)),
                0), new PdfFormGraph.Risks(false, false, false));
    }

    private static PdfFormGraph.Field formField(String name, PdfFormGraph.FieldKind kind, boolean readOnly, PdfRect widget) {
        return new PdfFormGraph.Field(name, kind, readOnly, false, false, false, null, null, null,
                widget == null ? List.of() : List.of(new PdfFormGraph.Widget(1, widget)));
    }
}
