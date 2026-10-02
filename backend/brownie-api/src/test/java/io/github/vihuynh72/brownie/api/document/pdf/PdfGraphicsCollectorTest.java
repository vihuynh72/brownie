package io.github.vihuynh72.brownie.api.document.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What one page's drawing may make the collector hold. The expanded-bytes
 * budget bounds how much content is read, not what each operator leaves
 * behind, so these limits are on what is kept.
 */
class PdfGraphicsCollectorTest {

    @Test
    void rectanglesThatAreNeverPaintedAreNotHeldPastTheSegmentLimit() throws IOException {
        byte[] pdf = PdfFormFixtures.pageDrawnWith("0 0 1 1 re\n", 6_000);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PdfGraphicsCollector collector = new PdfGraphicsCollector(document.getPage(0), PdfReadingBudget.standard());
            collector.collect();

            assertEquals(0, collector.pendingPoints(), "past the limit nothing is recorded, so nothing is kept");
            assertEquals(0, collector.rects().size());
        }
        byte[] fewer = PdfFormFixtures.pageDrawnWith("0 0 1 1 re\n", 100);
        try (PDDocument document = Loader.loadPDF(fewer)) {
            PdfGraphicsCollector collector = new PdfGraphicsCollector(document.getPage(0), PdfReadingBudget.standard());
            collector.collect();

            assertEquals(400, collector.pendingPoints(), "under the limit a path waits for its painting operator");
        }
    }

    @Test
    void pastTwoThousandPicturesOnAPageNoMoreAreRecorded() throws IOException {
        try (PDDocument document = Loader.loadPDF(PdfFormFixtures.manyPicturesDocument(2_100))) {
            PdfGraphicsCollector collector = new PdfGraphicsCollector(document.getPage(0), PdfReadingBudget.standard());
            collector.collect();

            assertEquals(PdfGraphicsCollector.MAX_IMAGES_PER_PAGE, collector.images().size());
        }
    }

    @Test
    void aPageWithMoreOperatorsThanAreReadStopsTheReading() throws IOException {
        byte[] pdf = PdfFormFixtures.pageDrawnWith("n\n", 1_001);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PdfReadingBudget budget = new PdfReadingBudget(1024 * 1024, 1_000, 1_000, 1_000);

            assertThrows(PdfReadingBudget.Exceeded.class, () -> new PdfGraphicsCollector(document.getPage(0), budget).collect());
            assertThrows(PdfReadingBudget.Exceeded.class, () -> PdfPageText.characters(document, 0, budget));
        }
        try (PDDocument document = Loader.loadPDF(PdfFormFixtures.pageDrawnWith("n\n", 1_000))) {
            PdfReadingBudget budget = new PdfReadingBudget(1024 * 1024, 1_000, 1_000, 1_000);

            PdfPageText.characters(document, 0, budget);
            new PdfGraphicsCollector(document.getPage(0), budget).collect();
        }
    }

    /**
     * The library holds every value until its operator comes, and builds an
     * array or a dictionary whole: values piled up before one operator stop
     * the reading before the library reads them, wherever they are written --
     * on their own, inside one array or dictionary, in an inline picture's
     * description, across the streams a page's content is split into, or in
     * a form the page draws.
     */
    @Test
    void valuesPiledUpBeforeOneOperatorStopTheReadingPastTheLimit() throws IOException {
        int limit = PdfReadingBudget.MAX_VALUES_BEFORE_AN_OPERATOR;
        List<byte[]> tooMany = List.of(
                PdfFormFixtures.pageDrawnWith("[] ", limit + 1),
                PdfFormFixtures.pageDrawnWith("[" + "1 ".repeat(limit) + "] TJ\n", 1),
                PdfFormFixtures.pageDrawnWith("/Span <<" + "/A 1 ".repeat(limit) + ">> BDC EMC\n", 1),
                PdfFormFixtures.pageDrawnWith("BI " + "/W 1 ".repeat(limit) + "ID x EI\n", 1),
                PdfFormFixtures.pageDrawnWithStreams("[] ".repeat(limit / 2), "[] ".repeat(limit / 2 + 1) + "n\n"),
                PdfFormFixtures.pageDrawingAFormOf("(text) ".repeat(limit + 1) + "Tj\n"));
        for (byte[] pdf : tooMany) {
            try (PDDocument document = Loader.loadPDF(pdf)) {
                assertThrows(PdfReadingBudget.Exceeded.class,
                        () -> new PdfGraphicsCollector(document.getPage(0), PdfReadingBudget.standard()).collect());
                assertThrows(PdfReadingBudget.Exceeded.class, () -> PdfPageText.characters(document, 0, PdfReadingBudget.standard()));
            }
        }
        List<byte[]> withinTheLimit = List.of(
                PdfFormFixtures.pageDrawnWith("1 0 0 1 0 0 cm\n", 20_000),
                PdfFormFixtures.pageDrawnWith("[" + "1 ".repeat(limit - 10) + "] 0 d\n", 1),
                PdfFormFixtures.pageDrawingAFormOf("(text) ".repeat(limit - 10) + "n\n"));
        for (byte[] pdf : withinTheLimit) {
            try (PDDocument document = Loader.loadPDF(pdf)) {
                new PdfGraphicsCollector(document.getPage(0), PdfReadingBudget.standard()).collect();
                PdfPageText.characters(document, 0, PdfReadingBudget.standard());
            }
        }
    }

    @Test
    void savesThatAreNeverRestoredStopTheReadingPastTheLimit() throws IOException {
        byte[] tooDeep = PdfFormFixtures.pageDrawnWith("q\n", PdfReadingBudget.MAX_SAVED_STATES + 1);
        try (PDDocument document = Loader.loadPDF(tooDeep)) {
            assertThrows(PdfReadingBudget.Exceeded.class,
                    () -> new PdfGraphicsCollector(document.getPage(0), PdfReadingBudget.standard()).collect());
            assertThrows(PdfReadingBudget.Exceeded.class, () -> PdfPageText.characters(document, 0, PdfReadingBudget.standard()));
        }
        byte[] deepEnough = PdfFormFixtures.pageDrawnWith("q\n", PdfReadingBudget.MAX_SAVED_STATES);
        byte[] manyRestored = PdfFormFixtures.pageDrawnWith("q 1 0 0 1 0 0 cm Q\n", 10_000);
        for (byte[] pdf : new byte[][] {deepEnough, manyRestored}) {
            try (PDDocument document = Loader.loadPDF(pdf)) {
                new PdfGraphicsCollector(document.getPage(0), PdfReadingBudget.standard()).collect();
                PdfPageText.characters(document, 0, PdfReadingBudget.standard());
            }
        }
    }
}
