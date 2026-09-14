package io.github.vihuynh72.brownie.api.document.render;

import io.github.vihuynh72.brownie.core.validation.PageRasterComparison;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the rasterized comparison against real, small, well-formed PDFs -- the same "build real bytes through PDDocument#save" discipline {@code PdfFixtures} already uses for extraction tests. */
class PdfBoxPageRasterDifferTest {

    private final PdfBoxPageRasterDiffer differ = new PdfBoxPageRasterDiffer();

    @Test
    void twoRendersOfTheIdenticalPageHaveZeroDifference() throws IOException {
        byte[] pdf = onePageWithText("Meeting called to order at 6pm.");

        PageRasterComparison comparison = differ.compare(pdf, pdf);

        assertEquals(1, comparison.baselinePageCount());
        assertEquals(1, comparison.filledPageCount());
        assertEquals(1, comparison.perPageDifferenceFraction().size());
        assertEquals(0.0, comparison.perPageDifferenceFraction().get(0), 0.0001);
    }

    @Test
    void visiblyDifferentContentProducesANonZeroDifference() throws IOException {
        byte[] baseline = onePageWithText("Meeting called to order at 6pm.");
        byte[] filled = onePageWithFilledRectangle();

        PageRasterComparison comparison = differ.compare(baseline, filled);

        assertTrue(comparison.perPageDifferenceFraction().get(0) > 0.05);
    }

    @Test
    void differingPageCountsAreReportedAndOnlyTheSharedPagesAreCompared() throws IOException {
        byte[] baseline = onePageWithText("Page one only.");
        byte[] filled = twoPagesWithText("Page one only.", "An extra second page.");

        PageRasterComparison comparison = differ.compare(baseline, filled);

        assertEquals(1, comparison.baselinePageCount());
        assertEquals(2, comparison.filledPageCount());
        assertEquals(1, comparison.perPageDifferenceFraction().size());
        assertEquals(0.0, comparison.perPageDifferenceFraction().get(0), 0.0001);
    }

    private static byte[] onePageWithText(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, 72, 720, text);
            }
            return write(doc);
        }
    }

    private static byte[] onePageWithFilledRectangle() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(0.1f, 0.1f, 0.1f);
                cs.addRect(50, 500, 500, 250);
                cs.fill();
            }
            return write(doc);
        }
    }

    private static byte[] twoPagesWithText(String firstPageText, String secondPageText) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage first = new PDPage(PDRectangle.LETTER);
            doc.addPage(first);
            try (PDPageContentStream cs = new PDPageContentStream(doc, first)) {
                writeLine(cs, 72, 720, firstPageText);
            }
            PDPage second = new PDPage(PDRectangle.LETTER);
            doc.addPage(second);
            try (PDPageContentStream cs = new PDPageContentStream(doc, second)) {
                writeLine(cs, 72, 720, secondPageText);
            }
            return write(doc);
        }
    }

    private static void writeLine(PDPageContentStream cs, float x, float y, String text) throws IOException {
        PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        cs.beginText();
        cs.setFont(font, 12);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private static byte[] write(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }
}
