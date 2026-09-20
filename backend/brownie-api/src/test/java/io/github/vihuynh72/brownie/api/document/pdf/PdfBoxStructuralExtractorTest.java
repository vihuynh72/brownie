package io.github.vihuynh72.brownie.api.document.pdf;

import io.github.vihuynh72.brownie.core.document.PdfExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.PdfPage;
import io.github.vihuynh72.brownie.core.document.PdfParseException;
import io.github.vihuynh72.brownie.core.document.PdfStructuralGraph;
import io.github.vihuynh72.brownie.core.document.PdfTextLine;
import io.github.vihuynh72.brownie.core.document.UnsupportedPdfReason;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfBoxStructuralExtractorTest {

    private final PdfBoxStructuralExtractor extractor = new PdfBoxStructuralExtractor();

    @Test
    void parserVersionIsStableAndNonBlank() {
        assertEquals(PdfBoxStructuralExtractor.PARSER_VERSION, extractor.parserVersion());
        assertFalse(extractor.parserVersion().isBlank());
    }

    @Test
    void twoLineDocumentExtractsInTopToBottomReadingOrder() throws IOException {
        PdfStructuralGraph graph = supported(PdfFixtures.twoLineDocument());
        PdfPage page = graph.pages().get(0);

        assertEquals(1, page.pageNumber());
        assertEquals(0, page.rotationDegrees());
        assertTrue(page.hasExtractableText());
        assertEquals(2, page.lines().size());
        // The line placed higher on the page (y=720) must be read first,
        // even though it was NOT the first content drawn if drawing order
        // differed -- proving this is a real geometric sort, not just
        // content-stream order.
        assertEquals("Meeting called to order at 6pm.", page.lines().get(0).text());
        assertEquals("Attendees: Jordan Lee, Priya Nair.", page.lines().get(1).text());
        assertFalse(page.lines().get(0).ambiguousReadingOrder());
    }

    @Test
    void pageGeometryMatchesLetterSize() throws IOException {
        PdfStructuralGraph graph = supported(PdfFixtures.twoLineDocument());
        PdfPage page = graph.pages().get(0);
        assertEquals(612.0, page.width(), 0.01);
        assertEquals(792.0, page.height(), 0.01);
    }

    @Test
    void lineBoundingBoxIsTopLeftOriginAndYDown() throws IOException {
        PdfStructuralGraph graph = supported(PdfFixtures.twoLineDocument());
        PdfTextLine topLine = graph.pages().get(0).lines().get(0);
        PdfTextLine bottomLine = graph.pages().get(0).lines().get(1);

        // The text placed at content-stream y=720 (near the physical top of a
        // 792-tall page) must report a SMALLER y (closer to 0, the top) than
        // the text at y=700 -- proving top-left origin, Y increasing downward.
        assertTrue(topLine.y() < bottomLine.y(), "expected the visually-higher line to have the smaller (top-origin) y");
        assertTrue(topLine.x() > 0);
        assertTrue(topLine.width() > 0);
        assertTrue(topLine.height() > 0);
    }

    @Test
    void rotatedPageReportsRotationButUnaffectedGeometryAndCorrectReadingOrder() throws IOException {
        for (int rotation : new int[] {90, 180, 270}) {
            PdfStructuralGraph graph = supported(PdfFixtures.rotatedTwoLineDocument(rotation));
            PdfPage page = graph.pages().get(0);

            assertEquals(rotation, page.rotationDegrees(), "rotation " + rotation);
            assertEquals(2, page.lines().size(), "rotation " + rotation + " line count");
            // The critical regression case: PDFTextStripper's own default line
            // assembly was empirically found to garble this exact scenario
            // (ordinary unrotated text on a page marked rotated) into nonsense
            // like "B\nottom\nLeft\n...". This extractor must not reproduce that.
            assertEquals(
                    "Meeting called to order at 6pm.", page.lines().get(0).text(), "rotation " + rotation + " first line");
            assertEquals(
                    "Attendees: Jordan Lee, Priya Nair.", page.lines().get(1).text(), "rotation " + rotation + " second line");
        }
    }

    @Test
    void multiPageDocumentUsesOneBasedPageNumbersInOrder() throws IOException {
        PdfStructuralGraph graph = supported(PdfFixtures.multiPageDocument(3));
        assertEquals(3, graph.pages().size());
        for (int i = 0; i < 3; i++) {
            PdfPage page = graph.pages().get(i);
            assertEquals(i + 1, page.pageNumber());
            assertEquals("This is page " + (i + 1) + ".", page.lines().get(0).text());
        }
    }

    @Test
    void noTextPageIsMarkedWithoutExtractableTextButDocumentStillFails() throws IOException {
        // A single page with zero text anywhere means the WHOLE document has
        // no extractable text, which is UNSUPPORTED, not a per-page detail.
        PdfExtractionOutcome outcome = extract(PdfFixtures.noTextPageDocument());
        assertTrue(outcome instanceof PdfExtractionOutcome.Unsupported, "expected unsupported, got " + outcome);
        var unsupported = (PdfExtractionOutcome.Unsupported) outcome;
        assertEquals(UnsupportedPdfReason.NO_EXTRACTABLE_TEXT, unsupported.reason());
    }

    @Test
    void aTextlessPageAmongTextPagesIsFlaggedButDocumentStillSucceeds() throws IOException {
        PdfStructuralGraph graph = supported(PdfFixtures.mixedTextAndNoTextPagesDocument());
        assertEquals(2, graph.pages().size());
        assertTrue(graph.pages().get(0).hasExtractableText());
        assertFalse(graph.pages().get(1).hasExtractableText());
        assertTrue(graph.pages().get(1).lines().isEmpty());
    }

    @Test
    void aWideGapWithNoLiteralSpaceCharacterIsReconstructedAsAWordSpace() throws IOException {
        PdfStructuralGraph graph = supported(PdfFixtures.gapSpacedWordsDocument());
        PdfTextLine line = graph.pages().get(0).lines().get(0);
        assertEquals("Quorum met", line.text());
        assertFalse(line.ambiguousReadingOrder(), "an ordinary word gap should not itself be flagged ambiguous");
    }

    @Test
    void sideBySideTextIsFlaggedAmbiguous() throws IOException {
        PdfStructuralGraph graph = supported(PdfFixtures.sideBySideColumnsDocument());
        PdfTextLine line = graph.pages().get(0).lines().get(0);
        assertTrue(line.ambiguousReadingOrder(), "two widely separated text regions on one geometric line should be flagged");
        assertTrue(line.text().contains("Left column text."));
        assertTrue(line.text().contains("Right column text."));
    }

    @Test
    void ambiguousReadingOrderIsScopedPerLineNotAcrossTheWholePage() throws IOException {
        PdfStructuralGraph graph = supported(PdfFixtures.twoRowGridDocument());
        PdfPage page = graph.pages().get(0);
        assertEquals(2, page.lines().size());

        PdfTextLine firstRow = page.lines().get(0);
        PdfTextLine secondRow = page.lines().get(1);
        assertTrue(firstRow.ambiguousReadingOrder(), "first row should be independently flagged ambiguous");
        assertTrue(secondRow.ambiguousReadingOrder(), "second row should be independently flagged ambiguous");
        assertTrue(firstRow.text().contains("Task"));
        assertTrue(firstRow.text().contains("Owner"));
        assertTrue(secondRow.text().contains("Draft agenda"));
        assertTrue(secondRow.text().contains("Jordan Lee"));
    }

    @Test
    void encryptedDocumentIsUnsupported() throws Exception {
        PdfExtractionOutcome outcome = extract(PdfFixtures.encryptedDocument());
        assertTrue(outcome instanceof PdfExtractionOutcome.Unsupported, "expected unsupported, got " + outcome);
        assertEquals(UnsupportedPdfReason.ENCRYPTED, ((PdfExtractionOutcome.Unsupported) outcome).reason());
    }

    @Test
    void aDocumentWithMorePagesThanAreReadIsRefusedWholeNotReadInPart() throws IOException {
        PdfBoxStructuralExtractor threePagesAtMost = new PdfBoxStructuralExtractor(3, 1_000_000, 64L * 1024 * 1024);

        PdfExtractionOutcome four = threePagesAtMost.extract(new ByteArrayInputStream(PdfFixtures.multiPageDocument(4)));
        PdfExtractionOutcome three = threePagesAtMost.extract(new ByteArrayInputStream(PdfFixtures.multiPageDocument(3)));

        PdfExtractionOutcome.Unsupported refused = assertInstanceOf(PdfExtractionOutcome.Unsupported.class, four);
        assertEquals(UnsupportedPdfReason.TOO_MANY_PAGES, refused.reason());
        assertTrue(refused.detail().contains("4 pages"));
        assertInstanceOf(PdfExtractionOutcome.Supported.class, three);
    }

    @Test
    void aSmallFileThatExpandsFarBeyondItsSizeIsRefusedInsteadOfBeingHeldInMemory() throws IOException {
        byte[] bomb = PdfFixtures.documentWhoseContentExpandsTo(8 * 1024 * 1024);
        assertTrue(bomb.length < 256 * 1024, "the fixture is only a bomb if it is small on disk, was " + bomb.length);
        PdfBoxStructuralExtractor oneMebibyteAtMost = new PdfBoxStructuralExtractor(200, 1_000_000, 1024 * 1024);

        PdfExtractionOutcome outcome = oneMebibyteAtMost.extract(new ByteArrayInputStream(bomb));

        PdfExtractionOutcome.Unsupported refused = assertInstanceOf(PdfExtractionOutcome.Unsupported.class, outcome);
        assertEquals(UnsupportedPdfReason.TOO_LARGE_WHEN_EXPANDED, refused.reason());
    }

    @Test
    void aFormThePageFindsOnlyThroughItsParentIsChargedLikeAnyOther() throws IOException {
        byte[] bomb = PdfFixtures.documentDrawingOneForm(8 * 1024 * 1024, 1, true);
        assertTrue(bomb.length < 256 * 1024, "the fixture is only a bomb if it is small on disk, was " + bomb.length);
        PdfBoxStructuralExtractor oneMebibyteAtMost = new PdfBoxStructuralExtractor(200, 1_000_000, 1024 * 1024);

        PdfExtractionOutcome outcome = oneMebibyteAtMost.extract(new ByteArrayInputStream(bomb));

        assertEquals(
                UnsupportedPdfReason.TOO_LARGE_WHEN_EXPANDED,
                assertInstanceOf(PdfExtractionOutcome.Unsupported.class, outcome).reason());
    }

    @Test
    void whatAStreamCallsItselfDoesNotDecideWhetherItIsCharged() throws IOException {
        byte[] bomb = PdfFixtures.documentWhoseContentCallsItselfAPicture(8 * 1024 * 1024);
        assertTrue(bomb.length < 256 * 1024, "the fixture is only a bomb if it is small on disk, was " + bomb.length);
        PdfBoxStructuralExtractor oneMebibyteAtMost = new PdfBoxStructuralExtractor(200, 1_000_000, 1024 * 1024);

        PdfExtractionOutcome outcome = oneMebibyteAtMost.extract(new ByteArrayInputStream(bomb));

        assertEquals(
                UnsupportedPdfReason.TOO_LARGE_WHEN_EXPANDED,
                assertInstanceOf(PdfExtractionOutcome.Unsupported.class, outcome).reason());
    }

    @Test
    void aModestFormIsChargedEveryTimeItIsDrawnNotOnce() throws IOException {
        PdfBoxStructuralExtractor oneMebibyteAtMost = new PdfBoxStructuralExtractor(200, 1_000_000, 1024 * 1024);
        byte[] drawnAHundredTimes = PdfFixtures.documentDrawingOneForm(64 * 1024, 100, false);
        byte[] drawnFiveTimes = PdfFixtures.documentDrawingOneForm(64 * 1024, 5, false);

        PdfExtractionOutcome hundred = oneMebibyteAtMost.extract(new ByteArrayInputStream(drawnAHundredTimes));
        PdfExtractionOutcome five = oneMebibyteAtMost.extract(new ByteArrayInputStream(drawnFiveTimes));

        assertEquals(
                UnsupportedPdfReason.TOO_LARGE_WHEN_EXPANDED,
                assertInstanceOf(PdfExtractionOutcome.Unsupported.class, hundred).reason());
        // Read to the end and found to hold no text, which is a different answer from being refused for its size.
        assertEquals(
                UnsupportedPdfReason.NO_EXTRACTABLE_TEXT,
                assertInstanceOf(PdfExtractionOutcome.Unsupported.class, five).reason());
    }

    @Test
    void aFontWhoseGlyphsTheFileDrawsItselfIsChargedForThemBeforeItIsLoaded() throws IOException {
        byte[] bomb = PdfFixtures.documentWhoseDrawnFontExpandsTo(8 * 1024 * 1024);
        assertTrue(bomb.length < 256 * 1024, "the fixture is only a bomb if it is small on disk, was " + bomb.length);
        PdfBoxStructuralExtractor oneMebibyteAtMost = new PdfBoxStructuralExtractor(200, 1_000_000, 1024 * 1024);

        PdfExtractionOutcome outcome = oneMebibyteAtMost.extract(new ByteArrayInputStream(bomb));

        assertEquals(
                UnsupportedPdfReason.TOO_LARGE_WHEN_EXPANDED,
                assertInstanceOf(PdfExtractionOutcome.Unsupported.class, outcome).reason());
    }

    @Test
    void theNumberOfPagesAFileDeclaresIsNotTakenOnTrust() throws IOException {
        PdfBoxStructuralExtractor threePagesAtMost = new PdfBoxStructuralExtractor(3, 1_000_000, 64L * 1024 * 1024);

        PdfExtractionOutcome claimsOne = threePagesAtMost.extract(
                new ByteArrayInputStream(PdfFixtures.documentDeclaringAPageCountItDoesNotHave(4, 1)));
        PdfExtractionOutcome claimsNine = threePagesAtMost.extract(
                new ByteArrayInputStream(PdfFixtures.documentDeclaringAPageCountItDoesNotHave(2, 9)));

        assertEquals(
                UnsupportedPdfReason.TOO_MANY_PAGES,
                assertInstanceOf(PdfExtractionOutcome.Unsupported.class, claimsOne).reason());
        PdfExtractionOutcome.Supported read = assertInstanceOf(PdfExtractionOutcome.Supported.class, claimsNine);
        assertEquals(2, read.graph().pages().size());
    }

    @Test
    void moreTextThanAnyDocumentOfItsKindHoldsIsRefused() throws IOException {
        PdfBoxStructuralExtractor twentyCharactersAtMost = new PdfBoxStructuralExtractor(200, 20, 64L * 1024 * 1024);

        PdfExtractionOutcome outcome = twentyCharactersAtMost.extract(new ByteArrayInputStream(PdfFixtures.twoLineDocument()));

        assertEquals(
                UnsupportedPdfReason.TOO_LARGE_WHEN_EXPANDED,
                assertInstanceOf(PdfExtractionOutcome.Unsupported.class, outcome).reason());
    }

    @Test
    void corruptPdfThrowsPdfParseException() {
        assertThrows(PdfParseException.class, () -> extractor.extract(new ByteArrayInputStream(PdfFixtures.corruptPdf())));
    }

    private PdfStructuralGraph supported(byte[] bytes) throws IOException {
        PdfExtractionOutcome outcome = extract(bytes);
        assertTrue(outcome instanceof PdfExtractionOutcome.Supported, "expected supported, got " + outcome);
        return ((PdfExtractionOutcome.Supported) outcome).graph();
    }

    private PdfExtractionOutcome extract(byte[] bytes) throws IOException {
        return extractor.extract(new ByteArrayInputStream(bytes));
    }
}
