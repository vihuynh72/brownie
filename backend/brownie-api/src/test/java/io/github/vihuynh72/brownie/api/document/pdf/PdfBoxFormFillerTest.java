package io.github.vihuynh72.brownie.api.document.pdf;

import io.github.vihuynh72.brownie.core.document.FilledPdf;
import io.github.vihuynh72.brownie.core.document.PdfFillFinding;
import io.github.vihuynh72.brownie.core.document.PdfFillFindingCode;
import io.github.vihuynh72.brownie.core.document.PdfFillItem;
import io.github.vihuynh72.brownie.core.document.PdfFillRequest;
import io.github.vihuynh72.brownie.core.document.PdfFillTarget;
import io.github.vihuynh72.brownie.core.document.PdfFontFamily;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfFormNotFillableException;
import io.github.vihuynh72.brownie.core.document.PdfFormReading;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import io.github.vihuynh72.brownie.core.document.UnsupportedPdfFormReason;
import io.github.vihuynh72.brownie.core.template.PdfBoxGeometry;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfBoxFormFillerTest {

    /** "Huynh Nguyen Duc" written in Vietnamese, with its letters composed. */
    private static final String VIETNAMESE_NAME = "Hu\u1EF3nh Nguy\u1EC5n \u0110\u1EE9c";
    private static final String CHINESE = "\u4E2D\u6587";
    private static final String ARABIC = "\u0645\u0631\u062D\u0628\u0627";

    private final PdfBoxFormFiller filler = new PdfBoxFormFiller();
    private final PdfBoxFillVerifier verifier = new PdfBoxFillVerifier();

    @Test
    void vietnameseGivenComposedOrDecomposedIsWrittenWithTheFontsOwnLetters() throws IOException {
        String decomposed = Normalizer.normalize(VIETNAMESE_NAME, Normalizer.Form.NFD);
        assertNotEquals(VIETNAMESE_NAME, decomposed);
        byte[] form = PdfFormFixtures.fillableForm();
        byte[] flat = PdfFormFixtures.flatForm();

        for (String given : List.of(VIETNAMESE_NAME, decomposed)) {
            PdfFillRequest intoField = request(field("name", "fullName", given));
            FilledPdf filledField = filler.fill(form, intoField);
            assertEquals(List.of(), filledField.findings());
            assertEquals(VIETNAMESE_NAME, valueOf(filledField.bytes(), "fullName"));
            assertEquals(List.of(), verifier.verify(form, filledField, intoField));

            PdfFillRequest intoBox = request(box("name", 1, new PdfRect(140, 80, 300, 18), 11, given));
            FilledPdf filledBox = filler.fill(flat, intoBox);
            assertEquals(List.of(), filledBox.findings());
            assertEquals(List.of(), verifier.verify(flat, filledBox, intoBox));
        }
    }

    @Test
    void aCharacterTheFontHasNoLetterForIsAFindingAndTheFieldIsLeftAlone() throws IOException {
        FilledPdf filled = filler.fill(PdfFormFixtures.fillableForm(), request(field("name", "fullName", "Li " + CHINESE)));

        assertEquals(List.of(
                new PdfFillFinding("name", PdfFillFindingCode.UNSUPPORTED_CHARACTER, "\u4E2D"),
                new PdfFillFinding("name", PdfFillFindingCode.UNSUPPORTED_CHARACTER, "\u6587")), filled.findings());
        assertFalse(filled.wrote("name"));
        assertNull(valueOf(filled.bytes(), "fullName"));
    }

    @Test
    void aScriptWhoseLettersJoinOrRunRightToLeftIsRefusedByName() throws IOException {
        FilledPdf filled = filler.fill(PdfFormFixtures.flatForm(),
                request(box("greeting", 1, new PdfRect(140, 80, 300, 18), 11, ARABIC)));

        assertEquals(List.of(new PdfFillFinding("greeting", PdfFillFindingCode.SCRIPT_NOT_SUPPORTED, "\u0645")), filled.findings());
    }

    @Test
    void aCombFieldPutsOneCharacterInTheMiddleOfEachCell() throws IOException {
        byte[] form = PdfFormFixtures.fillableForm();
        PdfFillRequest request = request(field("postcode", "zip", "90210"));

        FilledPdf filled = filler.fill(form, request);

        assertEquals(List.of(), filled.findings());
        assertEquals(List.of(), verifier.verify(form, filled, request));
        List<TextPosition> characters = flattenedCharacters(filled.bytes());
        float[] widget = PdfFormFixtures.ZIP_WIDGET;
        double cell = widget[2] / 5;
        String digits = "90210";
        for (int index = 0; index < 5; index++) {
            TextPosition character = characterInside(characters, widget, index, digits.charAt(index));
            double middle = character.getXDirAdj() + character.getWidthDirAdj() / 2;
            assertEquals(widget[0] + (index + 0.5) * cell, middle, 0.5, "cell " + index);
        }
    }

    @Test
    void textLongerThanTheFieldAllowsIsNeverCutShort() throws IOException {
        FilledPdf filled = filler.fill(PdfFormFixtures.fillableForm(), request(field("postcode", "zip", "902101")));

        assertEquals(List.of(new PdfFillFinding("postcode", PdfFillFindingCode.MAX_LENGTH_EXCEEDED, "5")), filled.findings());
        assertNull(valueOf(filled.bytes(), "zip"));
    }

    @Test
    void aMultiLineFieldWrapsItsTextOntoSeveralLines() throws IOException {
        byte[] form = PdfFormFixtures.fillableForm();
        String address = "Apartment 12, 345 Long Street Name That Goes On, Springfield, Some Province";
        PdfFillRequest request = request(field("address", "address", address));

        FilledPdf filled = filler.fill(form, request);

        assertEquals(List.of(), filled.findings());
        assertEquals(List.of(), verifier.verify(form, filled, request));
        long baselines = flattenedCharacters(filled.bytes()).stream()
                .filter(character -> insideWidget(character, PdfFormFixtures.ADDRESS_WIDGET))
                .map(character -> Math.round(character.getYDirAdj()))
                .distinct().count();
        assertTrue(baselines >= 2, "expected the address over several lines, got " + baselines);
    }

    @Test
    void aReadOnlyOrMissingFieldIsNotFillableAndIsLeftAsItWas() throws IOException {
        FilledPdf filled = filler.fill(PdfFormFixtures.fillableForm(),
                request(field("reference", "reference", "REF-999"), field("ghost", "noSuchField", "x")));

        assertEquals(List.of(PdfFillFindingCode.FIELD_NOT_FILLABLE, PdfFillFindingCode.FIELD_NOT_FILLABLE),
                filled.findings().stream().map(PdfFillFinding::code).toList());
        assertEquals("REF-001", valueOf(filled.bytes(), "reference"));
    }

    @Test
    void aFilledFormStaysFillableWithItsFontWholeAndAsksReadersNotToRedraw() throws IOException {
        byte[] form = PdfFormFixtures.fillableForm();
        FilledPdf filled = filler.fill(form, request(field("name", "fullName", "Jordan Lee"), field("email", "email", "j@example.org")));

        try (PDDocument document = Loader.loadPDF(filled.bytes())) {
            PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm(null);
            assertFalse(acroForm.getNeedAppearances());
            assertEquals(11, PdfFormFixtures.terminalFields(acroForm).size());
            PDTextField name = (PDTextField) acroForm.getField("fullName");
            assertEquals("Jordan Lee", name.getValueAsString());
            assertEquals("/BrSans 11 Tf 0 g", name.getDefaultAppearance());
            // "Automatic" size stays automatic for the next edit.
            assertEquals("/BrSans 0 Tf 0 g", ((PDTextField) acroForm.getField("email")).getDefaultAppearance());
            PDResources defaults = acroForm.getDefaultResources();
            PDFont font = defaults.getFont(COSName.getPDFName("BrSans"));
            PDType0Font whole = assertInstanceOf(PDType0Font.class, font);
            assertFalse(whole.getBaseFont().contains("+"), "a field's font is embedded whole, not as a subset");
            COSStream program = (COSStream) whole.getDescendantFont().getFontDescriptor().getFontFile2().getCOSObject();
            assertTrue(program.getLength() > 100_000 || program.createInputStream().readAllBytes().length > 300_000,
                    "the whole font program is embedded");
            assertTrue(name.getWidgets().get(0).getAppearance().getNormalAppearance().isStream());
        }
    }

    @Test
    void aSizeTheFormDeclaresThatNoFieldCouldUseIsReadAsAutomatic() throws IOException {
        for (String declared : List.of("1e300", "Infinity", "20000", "-4")) {
            byte[] form = withNameAppearance("/Helv " + declared + " Tf 0 g");
            PdfFillRequest request = request(field("name", "fullName", "Jordan Lee"));

            FilledPdf filled = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> filler.fill(form, request), declared);

            assertEquals(List.of(), filled.findings(), declared);
            assertEquals("Jordan Lee", valueOf(filled.bytes(), "fullName"));
            try (PDDocument document = Loader.loadPDF(filled.bytes())) {
                PDTextField name = (PDTextField) document.getDocumentCatalog().getAcroForm(null).getField("fullName");
                assertEquals("/BrSans 0 Tf 0 g", name.getDefaultAppearance(), declared);
            }
            assertEquals(List.of(), verifier.verify(form, filled, request), declared);
        }
    }

    @Test
    void aBoxsFontIsEmbeddedWithOnlyTheLettersUsed() throws IOException {
        FilledPdf filled = filler.fill(PdfFormFixtures.flatForm(),
                request(box("email", 1, new PdfRect(110, 140, 300, 18), 11, "jordan@example.org")));

        try (PDDocument document = Loader.loadPDF(filled.bytes())) {
            PDResources resources = document.getPage(0).getResources();
            boolean subset = false;
            for (COSName name : resources.getFontNames()) {
                PDFont font = resources.getFont(name);
                subset |= font.getName().matches("[A-Z]{6}\\+LiberationSans");
            }
            assertTrue(subset, "expected a subset of Liberation Sans on the page");
        }
    }

    @Test
    void textThatDoesNotFitShrinksHalfAPointAtATimeToSixPointsThenOverflows() throws IOException {
        byte[] flat = PdfFormFixtures.flatForm();
        PdfRect narrow = new PdfRect(140, 80, 150, 16);
        String longer = "Jordan Alexander Lee-Nguyen";

        FilledPdf shrunk = filler.fill(flat, request(box("name", 1, narrow, 12, longer)));
        FilledPdf tooLong = filler.fill(flat, request(box("name", 1, narrow, 12, longer.repeat(3))));
        FilledPdf blocked = filler.fill(flat, new PdfFillRequest(List.of(new PdfFillItem("name",
                new PdfFillTarget.Box(1, narrow, new PdfTextStyle(PdfFontFamily.SANS, false, 12), false), longer, PdfOverflowPolicy.BLOCK))));

        PdfFillFinding finding = shrunk.findings().get(0);
        assertEquals(PdfFillFindingCode.FIELD_TEXT_SHRUNK, finding.code());
        double size = Double.parseDouble(finding.detail());
        assertTrue(size < 12 && size >= 6 && size * 2 == Math.rint(size * 2), "shrunk to " + size);
        assertTrue(shrunk.wrote("name"));
        assertEquals(List.of(new PdfFillFinding("name", PdfFillFindingCode.FIXED_FIELD_OVERFLOW, null)), tooLong.findings());
        assertEquals(List.of(new PdfFillFinding("name", PdfFillFindingCode.FIXED_FIELD_OVERFLOW, null)), blocked.findings());
        assertFalse(blocked.wrote("name"));
        assertEquals(List.of(), verifier.verify(flat, shrunk, request(box("name", 1, narrow, 12, longer))));
    }

    @Test
    void textInABoxOnATurnedPageReadsUprightAndStaysInTheBox() throws IOException {
        for (int rotation : new int[] {0, 90, 180, 270}) {
            byte[] flat = PdfFormFixtures.flatFormOnTurnedPage(rotation);
            PdfFormGraph.Page page = graphOf(flat).pages().get(0);
            // A box just below the label as the page is shown, turned back into the page's own frame.
            PdfRect label = PdfBoxGeometry.toDisplayed(page.lines().get(0).box(), page.cropBox(), rotation);
            PdfRect box = PdfBoxGeometry.fromDisplayed(
                    new PdfRect(label.x(), label.bottom() + 12, 300, 18), page.cropBox(), rotation);
            PdfFillRequest request = request(box("name", 1, box, 11, "Jordan Lee"));

            FilledPdf filled = filler.fill(flat, request);

            assertEquals(List.of(), filled.findings(), "rotation " + rotation);
            assertEquals(List.of(), verifier.verify(flat, filled, request), "rotation " + rotation);
            PdfFormGraph.Word written = graphOf(filled.bytes()).pages().get(0).lines().stream()
                    .flatMap(line -> line.words().stream()).filter(word -> word.text().equals("Jordan")).findFirst().orElseThrow();
            assertEquals(rotation, written.textDirection(), "rotation " + rotation);
            assertTrue(box.encloses(written.box(), 1), "rotation " + rotation + ": " + written.box() + " in " + box);
        }
    }

    @Test
    void aFieldTurnedWithItsPageIsFilledUpright() throws IOException {
        for (int rotation : new int[] {90, 180, 270}) {
            byte[] form = PdfFormFixtures.fillableFormOnTurnedPage(rotation);
            PdfFillRequest request = request(field("name", "fullName", "Jordan Lee"));

            FilledPdf filled = filler.fill(form, request);

            assertEquals(List.of(), filled.findings(), "rotation " + rotation);
            assertEquals(List.of(), verifier.verify(form, filled, request), "rotation " + rotation);
        }
    }

    @Test
    void aBoxOffItsPageOrOnAPageMeasuredInLargerUnitsIsNotFillable() throws IOException {
        FilledPdf offPage = filler.fill(PdfFormFixtures.flatForm(), request(
                box("far", 1, new PdfRect(500, 80, 300, 18), 11, "x"), box("missing", 2, new PdfRect(10, 10, 50, 18), 11, "x")));
        FilledPdf largeUnits = filler.fill(PdfFormFixtures.userUnitDocument(), request(box("x", 1, new PdfRect(10, 10, 50, 18), 11, "x")));

        assertEquals(List.of(PdfFillFindingCode.FIELD_NOT_FILLABLE, PdfFillFindingCode.FIELD_NOT_FILLABLE),
                offPage.findings().stream().map(PdfFillFinding::code).toList());
        assertEquals(PdfFillFindingCode.FIELD_NOT_FILLABLE, largeUnits.findings().get(0).code());
    }

    @Test
    void lockedSignedXfaAndUnreadableFilesAreRefusedWhole() throws Exception {
        PdfFillRequest request = request(field("name", "fullName", "Jordan Lee"));

        assertEquals(UnsupportedPdfFormReason.ENCRYPTED, refusal(PdfFormFixtures.ownerPasswordOnlyForm(), request));
        assertEquals(UnsupportedPdfFormReason.ENCRYPTED, refusal(PdfFixtures.encryptedDocument(), request));
        assertEquals(UnsupportedPdfFormReason.SIGNED, refusal(PdfFormFixtures.signedForm(), request));
        assertEquals(UnsupportedPdfFormReason.XFA, refusal(PdfFormFixtures.xfaForm(false), request));
        assertEquals(UnsupportedPdfFormReason.DAMAGED, refusal(PdfFixtures.corruptPdf(), request));
    }

    @Test
    void usageRightsThatAnyChangeWouldBreakAreDropped() throws IOException {
        byte[] withRights;
        try (PDDocument document = Loader.loadPDF(PdfFormFixtures.fillableForm())) {
            COSDictionary permissions = new COSDictionary();
            permissions.setItem(COSName.getPDFName("UR3"), new COSDictionary());
            document.getDocumentCatalog().getCOSObject().setItem(COSName.PERMS, permissions);
            withRights = PdfFormFixtures.write(document);
        }

        FilledPdf filled = filler.fill(withRights, request(field("name", "fullName", "Jordan Lee")));

        try (PDDocument document = Loader.loadPDF(filled.bytes())) {
            assertNull(document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.PERMS));
        }
    }

    // ---- helpers ----

    /** The fillable form with its name field's default appearance replaced. */
    private static byte[] withNameAppearance(String defaultAppearance) throws IOException {
        try (PDDocument document = Loader.loadPDF(PdfFormFixtures.fillableForm())) {
            ((PDTextField) document.getDocumentCatalog().getAcroForm(null).getField("fullName")).setDefaultAppearance(defaultAppearance);
            return PdfFormFixtures.write(document);
        }
    }

    static PdfFillRequest request(PdfFillItem... items) {
        return new PdfFillRequest(List.of(items));
    }

    static PdfFillItem field(String fieldId, String fullName, String value) {
        return new PdfFillItem(fieldId, new PdfFillTarget.Widget(fullName), value, PdfOverflowPolicy.SHRINK_TO_FIT);
    }

    static PdfFillItem box(String fieldId, int page, PdfRect box, double size, String value) {
        return new PdfFillItem(fieldId, new PdfFillTarget.Box(page, box, new PdfTextStyle(PdfFontFamily.SANS, false, size), false),
                value, PdfOverflowPolicy.SHRINK_TO_FIT);
    }

    private UnsupportedPdfFormReason refusal(byte[] pdf, PdfFillRequest request) {
        return assertThrows(PdfFormNotFillableException.class, () -> filler.fill(pdf, request)).reason();
    }

    /** A field's stored value, or null when it has none. */
    static String valueOf(byte[] pdf, String fullName) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDTextField field = (PDTextField) document.getDocumentCatalog().getAcroForm(null).getField(fullName);
            return field.getCOSObject().getDictionaryObject(COSName.V) == null ? null : field.getValueAsString();
        }
    }

    static PdfFormGraph graphOf(byte[] pdf) {
        PdfFormReading reading = new PdfBoxFormReader().read(pdf);
        return assertInstanceOf(PdfFormReading.Supported.class, reading, () -> "expected supported, got " + reading).graph();
    }

    /** The characters of the first page once every field's appearance is drawn onto it. */
    static List<TextPosition> flattenedCharacters(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDAcroForm form = document.getDocumentCatalog().getAcroForm(null);
            List<PDField> fields = new ArrayList<>();
            form.getFieldTree().forEach(fields::add);
            form.flatten(fields, false);
            return PdfPageText.characters(document, 0, PdfReadingBudget.standard());
        }
    }

    private static boolean insideWidget(TextPosition character, float[] widget) {
        double x = character.getXDirAdj();
        double baseline = 792 - character.getYDirAdj();
        return x >= widget[0] - 1 && x <= widget[0] + widget[2] + 1 && baseline >= widget[1] - 1 && baseline <= widget[1] + widget[3] + 1;
    }

    private static TextPosition characterInside(List<TextPosition> characters, float[] widget, int index, char expected) {
        List<TextPosition> inside = characters.stream().filter(character -> insideWidget(character, widget))
                .sorted((a, b) -> Float.compare(a.getXDirAdj(), b.getXDirAdj())).toList();
        TextPosition character = inside.get(index);
        assertEquals(String.valueOf(expected), character.getUnicode());
        return character;
    }
}
