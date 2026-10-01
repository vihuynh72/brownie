package io.github.vihuynh72.brownie.core.compile;

import io.github.vihuynh72.brownie.core.document.FilledPdf;
import io.github.vihuynh72.brownie.core.document.PdfFillFinding;
import io.github.vihuynh72.brownie.core.document.PdfFillFindingCode;
import io.github.vihuynh72.brownie.core.document.PdfFillItem;
import io.github.vihuynh72.brownie.core.document.PdfFillRequest;
import io.github.vihuynh72.brownie.core.document.PdfFillTarget;
import io.github.vihuynh72.brownie.core.document.PdfFillVerifier;
import io.github.vihuynh72.brownie.core.document.PdfFormFiller;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import io.github.vihuynh72.brownie.core.document.PdfFontFamily;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a PDF template's fields and one revision's values become one fill, and how its findings are read, against a fake engine. */
class PdfTemplateFillTest {

    private static final PdfTextStyle SERIF_12 = new PdfTextStyle(PdfFontFamily.SERIF, false, 12);

    @Test
    void eachFieldBecomesOneItemWithItsTargetAndOverflowAndADateIsWrittenOut() {
        RecordingEngine engine = new RecordingEngine(List.of(), List.of());
        List<FieldDefinition> fields = List.of(
                field("name", FieldType.TEXT, FieldCardinality.SCALAR, new FieldBindingTarget.AcroFormField("applicant.name")),
                field("born", FieldType.DATE, FieldCardinality.SCALAR,
                        new FieldBindingTarget.PageBox(2, 72, 90, 200, 14, SERIF_12, true, PdfOverflowPolicy.BLOCK)),
                field("left.empty", FieldType.TEXT, FieldCardinality.SCALAR, new FieldBindingTarget.AcroFormField("other")));

        new PdfTemplateFill(engine, engine).fill(new byte[] {1}, fields, content(Map.of(
                "name", new FieldValue.TextValue("Nguy\u1ec5n V\u0103n A"),
                "born", new FieldValue.DateValue(LocalDate.of(2026, 9, 28)))));

        assertEquals(List.of(
                new PdfFillItem("name", new PdfFillTarget.Widget("applicant.name"), "Nguy\u1ec5n V\u0103n A", PdfOverflowPolicy.SHRINK_TO_FIT),
                new PdfFillItem("born", new PdfFillTarget.Box(2, new PdfRect(72, 90, 200, 14), SERIF_12, true), "September 28, 2026",
                        PdfOverflowPolicy.BLOCK),
                new PdfFillItem("left.empty", new PdfFillTarget.Widget("other"), "", PdfOverflowPolicy.SHRINK_TO_FIT)),
                engine.requests.get(0).items());
        assertEquals(1, engine.verified);
    }

    @Test
    void aListOfValuesIsRefusedForAPdfBeforeAnythingIsFilled() {
        RecordingEngine engine = new RecordingEngine(List.of(), List.of());
        List<FieldDefinition> fields = List.of(
                field("items", FieldType.TEXT, FieldCardinality.REPEATED, new FieldBindingTarget.AcroFormField("items")));

        TemplateFillException refused = assertThrows(TemplateFillException.class,
                () -> new PdfTemplateFill(engine, engine).fill(new byte[] {1}, fields, content(Map.of())));

        assertEquals(TemplateFillProblemReason.REPEATED_FIELD_IN_PDF, refused.reason());
        assertTrue(engine.requests.isEmpty());
    }

    @Test
    void aWordBindingInAPdfTemplateIsRefused() {
        RecordingEngine engine = new RecordingEngine(List.of(), List.of());
        List<FieldDefinition> fields = List.of(
                field("title", FieldType.TEXT, FieldCardinality.SCALAR, new FieldBindingTarget.ContentControlTag("title")));

        TemplateFillException refused = assertThrows(TemplateFillException.class,
                () -> new PdfTemplateFill(engine, engine).fill(new byte[] {1}, fields, content(Map.of())));

        assertEquals(TemplateFillProblemReason.BINDING_NOT_FOUND, refused.reason());
    }

    @Test
    void findingsFromBothStepsNameTheFieldsThatFailedAndReadAsIntegrityFindings() {
        RecordingEngine engine = new RecordingEngine(
                List.of(new PdfFillFinding("note", PdfFillFindingCode.FIXED_FIELD_OVERFLOW, null),
                        new PdfFillFinding("name", PdfFillFindingCode.FIELD_TEXT_SHRUNK, "9")),
                List.of(new PdfFillFinding("email", PdfFillFindingCode.FIELD_VALUE_NOT_STORED, null)));
        List<FieldDefinition> fields = List.of(
                field("name", FieldType.TEXT, FieldCardinality.SCALAR, new FieldBindingTarget.AcroFormField("name")),
                field("email", FieldType.TEXT, FieldCardinality.SCALAR, new FieldBindingTarget.AcroFormField("email")),
                field("note", FieldType.TEXT, FieldCardinality.SCALAR,
                        new FieldBindingTarget.PageBox(1, 72, 90, 50, 14, SERIF_12, false, PdfOverflowPolicy.SHRINK_TO_FIT)),
                field("blank", FieldType.TEXT, FieldCardinality.SCALAR, new FieldBindingTarget.AcroFormField("blank")));

        PdfTemplateFill.Result result = new PdfTemplateFill(engine, engine).fill(new byte[] {1}, fields, content(Map.of(
                "name", new FieldValue.TextValue("Ana"),
                "email", new FieldValue.TextValue("ana@example.org"),
                "note", new FieldValue.TextValue("A very long note"))));

        assertEquals(List.of("note", "email"), result.failedFieldIds());
        assertEquals(3, result.findings().size());
        List<IntegrityFinding> integrity = result.integrityFindings();
        assertEquals(List.of("name", "email", "note"), integrity.stream().map(IntegrityFinding::fieldId).toList());
        assertTrue(integrity.get(0).passed());
        assertFalse(integrity.get(1).foundInDocx(), "the email's value was not stored in its field");
        assertTrue(integrity.get(1).foundInPdf());
        assertFalse(integrity.get(2).foundInDocx() || integrity.get(2).foundInPdf(), "the note was never written");
    }

    private static FieldDefinition field(String fieldId, FieldType type, FieldCardinality cardinality, FieldBindingTarget binding) {
        return new FieldDefinition(fieldId, type, cardinality, FieldRequiredness.OPTIONAL, binding);
    }

    private static DocumentContent content(Map<String, FieldValue> values) {
        return new DocumentContent(new LinkedHashMap<>(values));
    }

    /** Fills nothing: records each request and answers with the findings it was given. */
    private static final class RecordingEngine implements PdfFormFiller, PdfFillVerifier {
        private final List<PdfFillFinding> fillFindings;
        private final List<PdfFillFinding> checkFindings;
        final List<PdfFillRequest> requests = new ArrayList<>();
        int verified;

        RecordingEngine(List<PdfFillFinding> fillFindings, List<PdfFillFinding> checkFindings) {
            this.fillFindings = fillFindings;
            this.checkFindings = checkFindings;
        }

        @Override
        public String fillerVersion() {
            return "fake-filler";
        }

        @Override
        public FilledPdf fill(byte[] sourcePdf, PdfFillRequest request) {
            requests.add(request);
            return new FilledPdf(sourcePdf, fillFindings);
        }

        @Override
        public List<PdfFillFinding> verify(byte[] sourcePdf, FilledPdf filled, PdfFillRequest request) {
            verified++;
            return checkFindings;
        }
    }
}
