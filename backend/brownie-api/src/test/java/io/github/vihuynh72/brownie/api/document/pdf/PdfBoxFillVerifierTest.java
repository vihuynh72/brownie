package io.github.vihuynh72.brownie.api.document.pdf;

import io.github.vihuynh72.brownie.core.document.FilledPdf;
import io.github.vihuynh72.brownie.core.document.PdfFillFinding;
import io.github.vihuynh72.brownie.core.document.PdfFillFindingCode;
import io.github.vihuynh72.brownie.core.document.PdfFillRequest;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import static io.github.vihuynh72.brownie.api.document.pdf.PdfBoxFormFillerTest.box;
import static io.github.vihuynh72.brownie.api.document.pdf.PdfBoxFormFillerTest.field;
import static io.github.vihuynh72.brownie.api.document.pdf.PdfBoxFormFillerTest.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfBoxFillVerifierTest {

    private static final PdfRect NAME_BOX = new PdfRect(140, 80, 300, 18);

    private final PdfBoxFormFiller filler = new PdfBoxFormFiller();
    private final PdfBoxFillVerifier verifier = new PdfBoxFillVerifier();

    @Test
    void anHonestFillOfFieldsAndBoxesHasNothingToReport() throws IOException {
        byte[] form = PdfFormFixtures.fillableForm();
        PdfFillRequest fields = request(field("name", "fullName", "Jordan Lee"), field("email", "email", "jordan@example.org"),
                field("address", "address", "12 Long Street\nSpringfield"), field("phone", "applicant.phone", "555 0100"));
        byte[] flat = PdfFormFixtures.flatForm();
        PdfFillRequest boxes = request(box("name", 1, NAME_BOX, 11, "Jordan Lee"),
                box("email", 1, new PdfRect(110, 140, 300, 18), 11, "jordan@example.org"));

        assertEquals(List.of(), verifier.verify(form, filler.fill(form, fields), fields));
        assertEquals(List.of(), verifier.verify(flat, filler.fill(flat, boxes), boxes));
    }

    @Test
    void aValueOtherThanTheOneIntendedIsCaughtBothStoredAndDrawn() throws IOException {
        byte[] form = PdfFormFixtures.fillableForm();
        FilledPdf filled = filler.fill(form, request(field("name", "fullName", "Jordan Lee")));

        List<PdfFillFinding> findings = verifier.verify(form, filled, request(field("name", "fullName", "Priya Nair")));

        assertEquals(List.of(
                new PdfFillFinding("name", PdfFillFindingCode.FIELD_VALUE_NOT_STORED, "Jordan Lee"),
                new PdfFillFinding("name", PdfFillFindingCode.FIELD_NOT_VISIBLE_IN_OUTPUT, "Jordan Lee")), findings);
    }

    @Test
    void aStoredValueThatDisagreesWithWhatIsDrawnIsCaught() throws IOException {
        byte[] form = PdfFormFixtures.fillableForm();
        PdfFillRequest request = request(field("name", "fullName", "Jordan Lee"));
        FilledPdf filled = changed(filler.fill(form, request),
                document -> document.getDocumentCatalog().getAcroForm(null).getField("fullName").getCOSObject().setString(COSName.V, "Priya Nair"));

        assertEquals(List.of(new PdfFillFinding("name", PdfFillFindingCode.FIELD_VALUE_NOT_STORED, "Priya Nair")),
                verifier.verify(form, filled, request));
    }

    @Test
    void aFieldWithoutADrawnAppearanceIsCaught() throws IOException {
        byte[] form = PdfFormFixtures.fillableForm();
        PdfFillRequest request = request(field("name", "fullName", "Jordan Lee"));
        FilledPdf filled = changed(filler.fill(form, request), document -> ((PDTerminalField)
                document.getDocumentCatalog().getAcroForm(null).getField("fullName")).getWidgets().get(0).getCOSObject().removeItem(COSName.AP));

        List<PdfFillFindingCode> codes = verifier.verify(form, filled, request).stream().map(PdfFillFinding::code).toList();

        assertTrue(codes.contains(PdfFillFindingCode.FIELD_APPEARANCE_MISSING), codes.toString());
        assertTrue(codes.contains(PdfFillFindingCode.FIELD_NOT_VISIBLE_IN_OUTPUT), codes.toString());
    }

    @Test
    void textAddedOutsideEveryBoxIsCaught() throws IOException {
        byte[] flat = PdfFormFixtures.flatForm();
        PdfFillRequest request = request(box("name", 1, NAME_BOX, 11, "Jordan Lee"));
        FilledPdf filled = changed(filler.fill(flat, request), document -> {
            try (PDPageContentStream cs = new PDPageContentStream(document, document.getPage(0), PDPageContentStream.AppendMode.APPEND, true, true)) {
                PdfFormFixtures.writeLine(cs, PdfFormFixtures.helvetica(), 11, 400, 300, "Extra");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        assertEquals(List.of(new PdfFillFinding(null, PdfFillFindingCode.TEXT_OUTSIDE_FILL_SPOT, "Extra")),
                verifier.verify(flat, filled, request));
    }

    @Test
    void textDrawnSomewhereOtherThanItsBoxIsNotVisibleThereAndIsOutsideEveryBox() throws IOException {
        byte[] flat = PdfFormFixtures.flatForm();
        FilledPdf filled = filler.fill(flat, request(box("name", 1, NAME_BOX, 11, "Jordan Lee")));

        List<PdfFillFinding> findings = verifier.verify(flat, filled, request(box("name", 1, new PdfRect(140, 300, 300, 18), 11, "Jordan Lee")));

        assertEquals(List.of(
                new PdfFillFinding("name", PdfFillFindingCode.FIELD_NOT_VISIBLE_IN_OUTPUT, ""),
                new PdfFillFinding(null, PdfFillFindingCode.TEXT_OUTSIDE_FILL_SPOT, "JordanLee")), findings);
    }

    @Test
    void aLetterThatReachesPastItsBoxIsCaught() throws IOException {
        byte[] flat = PdfFormFixtures.flatForm();
        FilledPdf filled = filler.fill(flat, request(box("name", 1, NAME_BOX, 11, "Jordan Lee")));

        // The same text, checked against a box that ends before its last letter does.
        List<PdfFillFindingCode> codes = verifier.verify(flat, filled, request(box("name", 1, new PdfRect(140, 80, 50, 18), 11, "Jordan Lee")))
                .stream().map(PdfFillFinding::code).toList();

        assertTrue(codes.contains(PdfFillFindingCode.FIELD_NOT_VISIBLE_IN_OUTPUT), codes.toString());
        assertTrue(codes.contains(PdfFillFindingCode.TEXT_OUTSIDE_FILL_SPOT), codes.toString());
    }

    @Test
    void aFieldTheFillDidNotNameMustNotChange() throws IOException {
        byte[] form = PdfFormFixtures.fillableForm();
        PdfFillRequest request = request(field("name", "fullName", "Jordan Lee"));
        FilledPdf filled = changed(filler.fill(form, request), document -> {
            PDField email = document.getDocumentCatalog().getAcroForm(null).getField("email");
            email.getCOSObject().setString(COSName.V, "someone@example.org");
        });

        assertEquals(List.of(new PdfFillFinding(null, PdfFillFindingCode.OTHER_FIELD_CHANGED, "email")),
                verifier.verify(form, filled, request));
    }

    @Test
    void aFieldTheFillAddedOrRemovedIsCaught() throws IOException {
        byte[] form = PdfFormFixtures.fillableForm();
        PdfFillRequest request = request(field("name", "fullName", "Jordan Lee"));
        FilledPdf filled = changed(filler.fill(form, request), document -> {
            PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm(null);
            COSArray fields = acroForm.getCOSObject().getCOSArray(COSName.FIELDS);
            assertTrue(fields.removeObject(acroForm.getField("news").getCOSObject()));
        });

        List<PdfFillFindingCode> codes = verifier.verify(form, filled, request).stream().map(PdfFillFinding::code).toList();

        assertTrue(codes.contains(PdfFillFindingCode.FIELD_COUNT_CHANGED), codes.toString());
    }

    @Test
    void aValueTheFillerLeftOutIsNotReportedAgain() throws IOException {
        byte[] form = PdfFormFixtures.fillableForm();
        PdfFillRequest request = request(field("name", "fullName", "Jordan Lee"), field("postcode", "zip", "902101"));
        FilledPdf filled = filler.fill(form, request);

        assertEquals(List.of(PdfFillFindingCode.MAX_LENGTH_EXCEEDED), filled.findings().stream().map(PdfFillFinding::code).toList());
        assertEquals(List.of(), verifier.verify(form, filled, request));
    }

    @Test
    void theUnderscoresAFieldSitsOverAreThePagesNotPartOfTheValue() throws IOException {
        byte[] form = PdfFormFixtures.fieldOverPrintedBlank();
        PdfFillRequest request = request(field("name", "fullName", "Priya Rao"));

        FilledPdf filled = filler.fill(form, request);

        assertEquals(List.of(), filled.findings());
        assertEquals(List.of(), verifier.verify(form, filled, request));
    }

    @Test
    void aFieldShownInSeveralPlacesIsCheckedInEachOnItsOwn() throws IOException {
        for (boolean onTwoPages : new boolean[] {false, true}) {
            byte[] form = PdfFormFixtures.fieldShownTwice(onTwoPages);
            PdfFillRequest request = request(field("name", "fullName", "Priya Rao"));

            FilledPdf filled = filler.fill(form, request);

            assertEquals(List.of(), filled.findings());
            assertEquals(List.of(), verifier.verify(form, filled, request), onTwoPages ? "two pages" : "one page");
            // One place showing something else is still caught, once for the value.
            FilledPdf oneWrong = changed(filled, document -> ((PDTerminalField) document.getDocumentCatalog().getAcroForm(null)
                    .getField("fullName")).getWidgets().get(1).getCOSObject().removeItem(COSName.AP));
            assertEquals(List.of(PdfFillFindingCode.FIELD_APPEARANCE_MISSING, PdfFillFindingCode.FIELD_NOT_VISIBLE_IN_OUTPUT),
                    verifier.verify(form, oneWrong, request).stream().map(PdfFillFinding::code).toList());
        }
    }

    /** Two widgets of one field on the same rectangle draw the value twice at one spot, which reads back once: one place, not a missing one. */
    @Test
    void aFieldShownTwiceAtTheVerySamePlaceIsCheckedAsOnePlace() throws IOException {
        byte[] form;
        try (PDDocument document = Loader.loadPDF(PdfFormFixtures.fieldShownTwice(false))) {
            List<org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget> widgets =
                    ((PDTerminalField) document.getDocumentCatalog().getAcroForm(null).getField("fullName")).getWidgets();
            widgets.get(1).setRectangle(widgets.get(0).getRectangle());
            form = PdfFormFixtures.write(document);
        }
        PdfFillRequest request = request(field("name", "fullName", "Priya Rao"));

        FilledPdf filled = filler.fill(form, request);

        assertEquals(List.of(), filled.findings());
        assertEquals(List.of(), verifier.verify(form, filled, request));
    }

    @Test
    void aValueTheFormAlreadyShowedInTheFieldIsNotMistakenForThePages() throws IOException {
        byte[] form = PdfFormFixtures.fillableForm();
        byte[] alreadyFilled = filler.fill(form, request(field("name", "fullName", "Jordan"))).bytes();
        PdfFillRequest request = request(field("name", "fullName", "Jordan Lee"));

        assertEquals(List.of(), verifier.verify(alreadyFilled, filler.fill(alreadyFilled, request), request));
    }

    @Test
    void aWidgetNoReaderDrawsIsNeitherFilledNorChecked() throws IOException {
        byte[] form = PdfFormFixtures.fieldsNobodySees();
        PdfFillRequest request = request(field("name", "fullName", "Priya Rao"), field("explain", "explain", "Because"),
                field("nowhere", "nowhere", "Here"));

        FilledPdf filled = filler.fill(form, request);

        assertEquals(List.of(
                new PdfFillFinding("explain", PdfFillFindingCode.FIELD_NOT_FILLABLE, "The form does not show the field explain on any page."),
                new PdfFillFinding("nowhere", PdfFillFindingCode.FIELD_NOT_FILLABLE, "The form does not show the field nowhere on any page.")),
                filled.findings());
        assertEquals(List.of(), verifier.verify(form, filled, request));
    }

    @Test
    void aValueTheFormLeavesReadersToDrawKeepsTheFormAskingThemTo() throws IOException {
        byte[] form;
        try (PDDocument document = Loader.loadPDF(PdfFormFixtures.fillableForm())) {
            PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm(null);
            acroForm.setNeedAppearances(true);
            ((PDTerminalField) acroForm.getField("reference")).getWidgets().get(0).getCOSObject().removeItem(COSName.AP);
            form = PdfFormFixtures.write(document);
        }
        PdfFillRequest request = request(field("name", "fullName", "Jordan Lee"));

        FilledPdf filled = filler.fill(form, request);

        try (PDDocument document = Loader.loadPDF(filled.bytes())) {
            assertTrue(document.getDocumentCatalog().getAcroForm(null).getNeedAppearances(), "REF-001 shows only while readers draw it");
        }
        assertEquals(List.of(), verifier.verify(form, filled, request));
        FilledPdf stoppedAsking = changed(filled, document -> document.getDocumentCatalog().getAcroForm(null).setNeedAppearances(false));
        assertEquals(List.of(new PdfFillFinding(null, PdfFillFindingCode.OTHER_FIELD_CHANGED, "reference")),
                verifier.verify(form, stoppedAsking, request));
    }

    private static FilledPdf changed(FilledPdf filled, Consumer<PDDocument> change) throws IOException {
        try (PDDocument document = Loader.loadPDF(filled.bytes())) {
            change.accept(document);
            return new FilledPdf(PdfFormFixtures.write(document), filled.findings());
        }
    }
}
