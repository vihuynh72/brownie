package io.github.vihuynh72.brownie.api.document.pdf;

import io.github.vihuynh72.brownie.core.document.PdfExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfFormReading;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfStructuralGraph;
import io.github.vihuynh72.brownie.core.document.UnsupportedPdfFormReason;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfBoxFormReaderTest {

    private final PdfBoxFormReader reader = new PdfBoxFormReader();

    @Test
    void parserVersionNamesTheGraphAndTheLibrary() {
        assertEquals("brownie-pdf-form-v1+pdfbox-3.0.8", reader.parserVersion());
    }

    @Test
    void everyFieldOfAFillableFormIsReadWithItsKindFlagsAndWidgets() throws IOException {
        PdfFormGraph graph = supported(PdfFormFixtures.fillableForm());
        PdfFormGraph.AcroForm form = graph.acroForm();
        assertTrue(form.present());
        assertEquals(PdfFormGraph.XfaKind.NONE, form.xfa());
        assertEquals(0, form.signedSignatureCount());

        Map<String, PdfFormGraph.Field> fields = form.fields().stream()
                .collect(Collectors.toMap(PdfFormGraph.Field::fullName, Function.identity()));
        assertEquals(List.of("fullName", "address", "zip", "reference", "email", "birthDate", "applicant.phone", "news",
                "contactBy", "country", "signature"), form.fields().stream().map(PdfFormGraph.Field::fullName).toList());

        PdfFormGraph.Field name = fields.get("fullName");
        assertEquals(PdfFormGraph.FieldKind.TEXT, name.kind());
        assertEquals("Full name", name.tooltip());
        assertFalse(name.multiline());
        assertNull(name.maxLen());
        assertTrue(fields.get("address").multiline());
        assertTrue(fields.get("zip").comb());
        assertEquals(5, fields.get("zip").maxLen());
        assertTrue(fields.get("reference").readOnly());
        assertTrue(fields.get("email").required());
        assertEquals("mm/dd/yyyy", fields.get("birthDate").dateFormat());
        assertNull(name.dateFormat());
        assertEquals(PdfFormGraph.FieldKind.CHECKBOX, fields.get("news").kind());
        assertEquals(PdfFormGraph.FieldKind.RADIO, fields.get("contactBy").kind());
        assertEquals(2, fields.get("contactBy").widgets().size());
        assertEquals(PdfFormGraph.FieldKind.CHOICE, fields.get("country").kind());
        assertEquals(PdfFormGraph.FieldKind.SIGNATURE, fields.get("signature").kind());

        // The widget at user (150, 690) 300 by 20 on a 792-point page: its top is 792 - 710 = 82 points down.
        PdfFormGraph.Widget widget = name.widgets().get(0);
        assertEquals(1, widget.pageNumber());
        assertRect(new PdfRect(150, 82, 300, 20), widget.box(), 0.01);
    }

    @Test
    void wordsCarryTheirFontAndSizeAndTheLinesAreTheSourceExtractorsLines() throws IOException {
        byte[] pdf = PdfFormFixtures.flatForm();
        PdfFormGraph graph = supported(pdf);
        PdfStructuralGraph source = ((PdfExtractionOutcome.Supported) new PdfBoxStructuralExtractor()
                .extract(new ByteArrayInputStream(pdf))).graph();

        List<String> formLines = graph.pages().get(0).lines().stream().map(PdfFormGraph.Line::text).toList();
        List<String> sourceLines = source.pages().get(0).lines().stream().map(line -> line.text()).toList();
        assertEquals(sourceLines, formLines);

        PdfFormGraph.Line heading = lineStartingWith(graph, "Volunteer");
        PdfFormGraph.Word first = heading.words().get(0);
        assertEquals("Volunteer", first.text());
        assertEquals("Helvetica-Bold", first.fontName());
        assertEquals(14, first.fontSizePt(), 0.01);
        assertEquals(0, first.textDirection());
    }

    @Test
    void aBlankPrintedHardAgainstItsLabelIsAWordOfItsOwn() throws IOException {
        PdfFormGraph graph = supported(PdfFormFixtures.flatForm());

        List<String> name = lineStartingWith(graph, "Full").words().stream().map(PdfFormGraph.Word::text).toList();
        List<String> birth = lineStartingWith(graph, "Date").words().stream().map(PdfFormGraph.Word::text).toList();

        assertEquals(List.of("Full", "name:", "______________________________"), name);
        assertEquals(List.of("Date", "of", "birth:", ".............................."), birth);
    }

    @Test
    void aSubsetFontsNameLosesItsTag() throws IOException {
        PdfFormGraph graph = supported(PdfFormFixtures.subsetFontDocument());

        PdfFormGraph.Word word = graph.pages().get(0).lines().get(0).words().get(0);

        assertEquals("Heading:", word.text());
        assertEquals("LiberationSans-Bold", word.fontName());
    }

    @Test
    void linesRectanglesAndPicturesAreRecordedInTheSameFrameAsText() throws IOException {
        PdfFormGraph.Page page = supported(PdfFormFixtures.flatForm()).pages().get(0);

        // The line under "Address:" runs from user x 130 to 500 at user y 578: 792 - 578 = 214 points down.
        assertTrue(page.rules().stream().anyMatch(rule -> rule.horizontal()
                && Math.abs(rule.from().y() - 214) < 0.01 && Math.abs(rule.length() - 370) < 0.01), page.rules().toString());
        // Six cells of the table, each 150 by 25.
        assertEquals(6, page.rects().stream().filter(rect -> Math.abs(rect.width() - 150) < 0.01).count());
        assertTrue(page.images().isEmpty());
    }

    @Test
    void aScanIsReadAsAPageWithoutTextNotRefused() throws IOException {
        PdfFormGraph plain = supported(PdfFormFixtures.scannedPage(false));
        PdfFormGraph jbig2 = supported(PdfFormFixtures.scannedPage(true));

        PdfFormGraph.Page page = plain.pages().get(0);
        assertFalse(page.hasText());
        assertEquals(1, page.images().size());
        assertEquals(List.of("FlateDecode"), page.images().get(0).filters());
        assertRect(new PdfRect(0, 0, 612, 792), page.images().get(0).box(), 0.01);
        assertEquals(List.of("JBIG2Decode"), jbig2.pages().get(0).images().get(0).filters());
        assertFalse(plain.acroForm().present());
    }

    @Test
    void positionsAreMeasuredFromTheCropBoxNotTheFilesOrigin() throws IOException {
        PdfFormGraph.Page page = supported(PdfFormFixtures.offsetCropBoxDocument()).pages().get(0);

        assertEquals(36, page.cropBox().llx(), 0.01);
        assertEquals(50, page.cropBox().lly(), 0.01);
        // Text at user (100, 700) on a crop box whose top-left is user (36, 742): 64 across and, at its baseline, 42 down.
        PdfFormGraph.Word word = page.lines().get(0).words().get(0);
        assertEquals(64, word.box().x(), 0.01);
        assertEquals(42, word.box().bottom(), 0.01);
        // The rectangle at user (100, 600) 200 by 20: its top, user 620, is 122 down.
        assertRect(new PdfRect(64, 122, 200, 20), page.rects().get(0), 0.01);
        assertEquals(64, page.rules().get(0).from().x(), 0.01);
        assertEquals(242, page.rules().get(0).from().y(), 0.01);
    }

    @Test
    void aTurnedPageKeepsItsRotationAsAFactAndItsUprightTextRunsThatWay() throws IOException {
        for (int rotation : new int[] {0, 90, 180, 270}) {
            PdfFormGraph.Page page = supported(PdfFormFixtures.flatFormOnTurnedPage(rotation)).pages().get(0);

            assertEquals(rotation, page.rotation(), "rotation " + rotation);
            PdfFormGraph.Word label = page.lines().get(0).words().get(1);
            assertEquals("name:", label.text(), "rotation " + rotation);
            assertEquals(rotation, label.textDirection(), "rotation " + rotation);
            assertEquals(1, page.rules().size(), "rotation " + rotation);
        }
    }

    @Test
    void aPagesUserUnitIsRecorded() throws IOException {
        assertEquals(2.0, supported(PdfFormFixtures.userUnitDocument()).pages().get(0).userUnit(), 0.001);
        assertEquals(1.0, supported(PdfFixtures.twoLineDocument()).pages().get(0).userUnit(), 0.001);
    }

    @Test
    void pastFiveThousandPathSegmentsOnAPageNoMoreLinesAreRecorded() throws IOException {
        PdfFormGraph.Page page = supported(PdfFormFixtures.manyLinesDocument(6_000)).pages().get(0);

        assertTrue(page.rules().isEmpty(), "a page past the limit is a drawing: its lines are not recorded, got " + page.rules().size());
        assertEquals(100, supported(PdfFormFixtures.manyLinesDocument(100)).pages().get(0).rules().size());
    }

    @Test
    void aFewKilobytesOfSavedDrawingStatesAreTooLargeNotAnOutOfMemoryError() throws IOException {
        byte[] savesNeverRestored = PdfFormFixtures.pageDrawnWith("q\n", 2_000_000);

        assertTrue(savesNeverRestored.length < 64 * 1024, "the file itself is small: " + savesNeverRestored.length);
        assertRefused(UnsupportedPdfFormReason.TOO_LARGE, savesNeverRestored);
        assertRefused(UnsupportedPdfFormReason.TOO_LARGE, PdfFormFixtures.pageDrawnWith("0 0 1 1 re\n", 2_100_000));
    }

    @Test
    void aFewKilobytesOfValuesWaitingForOneOperatorAreTooLargeNotAnOutOfMemoryError() throws IOException {
        byte[] emptyArraysAndNoOperator = PdfFormFixtures.pageDrawnWith("[]", 1_000_000);

        assertTrue(emptyArraysAndNoOperator.length < 64 * 1024, "the file itself is small: " + emptyArraysAndNoOperator.length);
        assertRefused(UnsupportedPdfFormReason.TOO_LARGE, emptyArraysAndNoOperator);
    }

    @Test
    void moreWordsThanAnyFormHoldsIsTooLargeSinceTheWholeReadingIsStored() throws IOException {
        byte[] onePageTooMany = PdfFormFixtures.pagesOfTinyWords(1, PdfBoxFormReader.MAX_WORDS_ON_A_PAGE + 1);
        byte[] tooManyInAll = PdfFormFixtures.pagesOfTinyWords(6, 17_000);

        assertTrue(onePageTooMany.length < 16 * 1024, "the file itself is small: " + onePageTooMany.length);
        assertRefused(UnsupportedPdfFormReason.TOO_LARGE, onePageTooMany);
        assertRefused(UnsupportedPdfFormReason.TOO_LARGE, tooManyInAll);
        PdfFormGraph justUnder = supported(PdfFormFixtures.pagesOfTinyWords(1, PdfBoxFormReader.MAX_WORDS_ON_A_PAGE));
        assertEquals(PdfBoxFormReader.MAX_WORDS_ON_A_PAGE,
                justUnder.pages().get(0).lines().stream().mapToInt(line -> line.words().size()).sum());
    }

    @Test
    void moreLinesBoxesAndPicturesThanAnyFormDrawsIsTooLarge() throws IOException {
        // A page's lines are recorded up to five thousand path segments: 2,500 lines of two each, on forty-one pages.
        byte[] pdf = PdfFormFixtures.manyLinesDocument(41, 2_500);
        assertEquals(2_500, supported(PdfFormFixtures.manyLinesDocument(1, 2_500)).pages().get(0).rules().size());

        assertRefused(UnsupportedPdfFormReason.TOO_LARGE, pdf);
    }

    @Test
    void aWidgetNoReaderDrawsIsNotRead() throws IOException {
        Map<String, PdfFormGraph.Field> fields = supported(PdfFormFixtures.fieldsNobodySees()).acroForm().fields().stream()
                .collect(Collectors.toMap(PdfFormGraph.Field::fullName, Function.identity()));

        assertTrue(fields.get("explain").widgets().isEmpty(), "a hidden field is shown nowhere");
        assertTrue(fields.get("nowhere").widgets().isEmpty(), "a field with no room is shown nowhere");
        assertEquals(1, fields.get("fullName").widgets().size(), "only the place that is shown");
    }

    @Test
    void lockedSignedAndXfaFormsAreRefused() throws Exception {
        assertRefused(UnsupportedPdfFormReason.ENCRYPTED, PdfFixtures.encryptedDocument());
        assertRefused(UnsupportedPdfFormReason.ENCRYPTED, PdfFormFixtures.ownerPasswordOnlyForm());
        assertRefused(UnsupportedPdfFormReason.SIGNED, PdfFormFixtures.signedForm());
        assertRefused(UnsupportedPdfFormReason.XFA, PdfFormFixtures.xfaForm(false));
        assertRefused(UnsupportedPdfFormReason.XFA, PdfFormFixtures.xfaForm(true));
    }

    @Test
    void filesThatDoThingsOnTheirOwnAreRefused() throws IOException {
        assertRefused(UnsupportedPdfFormReason.LAUNCH_ACTION, PdfFormFixtures.launchActionDocument());
        assertRefused(UnsupportedPdfFormReason.EMBEDDED_FILES, PdfFormFixtures.embeddedFileDocument());
        assertRefused(UnsupportedPdfFormReason.DOCUMENT_JAVASCRIPT, PdfFormFixtures.documentJavaScript(false));
        assertRefused(UnsupportedPdfFormReason.DOCUMENT_JAVASCRIPT, PdfFormFixtures.documentJavaScript(true));
    }

    @Test
    void aScriptAnAnnotationRunsAsItsPageOpensOrMediaPlaysIsRefused() throws IOException {
        assertRefused(UnsupportedPdfFormReason.DOCUMENT_JAVASCRIPT,
                PdfFormFixtures.annotationScript(PdfFormFixtures.ScriptPlace.WIDGET_PAGE_OPEN));
        assertRefused(UnsupportedPdfFormReason.DOCUMENT_JAVASCRIPT,
                PdfFormFixtures.annotationScript(PdfFormFixtures.ScriptPlace.SCREEN_PAGE_OPEN));
        assertRefused(UnsupportedPdfFormReason.DOCUMENT_JAVASCRIPT,
                PdfFormFixtures.annotationScript(PdfFormFixtures.ScriptPlace.SCREEN_CLICK));
        assertFalse(supported(PdfFormFixtures.annotationScript(PdfFormFixtures.ScriptPlace.WIDGET_MOUSE_DOWN)).risks().any(),
                "a script a click on a field runs waits for the person");
    }

    /** One script reached from a click and from a page opening is a script that runs by itself, whichever is looked at first. */
    @Test
    void aPageOpenScriptAlsoReachedFromSomethingAPersonDoesIsRefused() throws IOException {
        for (PdfFormFixtures.FirstReach first : PdfFormFixtures.FirstReach.values()) {
            assertEquals(UnsupportedPdfFormReason.DOCUMENT_JAVASCRIPT,
                    reasonOf(reader.read(PdfFormFixtures.pageOpenScriptReachedFirstFrom(first))), first.name());
        }
    }

    /** What lies past the actions followed was not looked at, so a file with more of them is refused, not passed. */
    @Test
    void aPageOpenScriptBehindMoreActionsThanAreFollowedIsStillRefused() throws IOException {
        assertRefused(UnsupportedPdfFormReason.DOCUMENT_JAVASCRIPT, PdfFormFixtures.longOutlineThenPageOpenScript(4_000));
        assertRefused(UnsupportedPdfFormReason.TOO_LARGE, PdfFormFixtures.longOutlineThenPageOpenScript(6_000));
    }

    @Test
    void aFileCarriedAnywhereInTheDocumentIsRefused() throws IOException {
        assertRefused(UnsupportedPdfFormReason.EMBEDDED_FILES, PdfFormFixtures.pageCarryingAFile());
    }

    @Test
    void aFieldsOwnFormattingScriptIsNotARefusal() throws IOException {
        PdfFormGraph graph = supported(PdfFormFixtures.fillableForm());

        assertFalse(graph.risks().any());
    }

    @Test
    void whatCannotBeReadIsDamagedNotAnException() {
        assertRefused(UnsupportedPdfFormReason.DAMAGED, PdfFixtures.corruptPdf());
    }

    @Test
    void tooManyPagesFieldsOrExpandedBytesIsTooLarge() throws IOException {
        PdfBoxFormReader threePagesAtMost = new PdfBoxFormReader(3, 1_000_000, 64L * 1024 * 1024);
        PdfBoxFormReader oneMebibyteAtMost = new PdfBoxFormReader(200, 1_000_000, 1024 * 1024);

        assertEquals(UnsupportedPdfFormReason.TOO_LARGE, reasonOf(threePagesAtMost.read(PdfFixtures.multiPageDocument(4))));
        assertInstanceOf(PdfFormReading.Supported.class, threePagesAtMost.read(PdfFixtures.multiPageDocument(3)));
        assertEquals(UnsupportedPdfFormReason.TOO_LARGE,
                reasonOf(oneMebibyteAtMost.read(PdfFixtures.documentWhoseContentExpandsTo(8 * 1024 * 1024))));
        assertEquals(UnsupportedPdfFormReason.TOO_LARGE,
                reasonOf(oneMebibyteAtMost.read(PdfFixtures.documentDrawingOneForm(64 * 1024, 100, false))));
        assertEquals(UnsupportedPdfFormReason.TOO_LARGE, reasonOf(reader.read(PdfFormFixtures.formWithTextFields(1_001))));
        assertInstanceOf(PdfFormReading.Supported.class, reader.read(PdfFormFixtures.formWithTextFields(1_000)));
    }

    private PdfFormGraph supported(byte[] pdf) {
        PdfFormReading reading = reader.read(pdf);
        return assertInstanceOf(PdfFormReading.Supported.class, reading, () -> "expected supported, got " + reading).graph();
    }

    private void assertRefused(UnsupportedPdfFormReason reason, byte[] pdf) {
        assertEquals(reason, reasonOf(reader.read(pdf)));
    }

    private static UnsupportedPdfFormReason reasonOf(PdfFormReading reading) {
        return assertInstanceOf(PdfFormReading.Unsupported.class, reading, () -> "expected a refusal, got " + reading).reason();
    }

    private static PdfFormGraph.Line lineStartingWith(PdfFormGraph graph, String start) {
        return graph.pages().get(0).lines().stream().filter(line -> line.text().startsWith(start)).findFirst().orElseThrow();
    }

    static void assertRect(PdfRect expected, PdfRect actual, double tolerance) {
        String message = "expected " + expected + " but was " + actual;
        assertEquals(expected.x(), actual.x(), tolerance, message);
        assertEquals(expected.y(), actual.y(), tolerance, message);
        assertEquals(expected.width(), actual.width(), tolerance, message);
        assertEquals(expected.height(), actual.height(), tolerance, message);
    }
}
