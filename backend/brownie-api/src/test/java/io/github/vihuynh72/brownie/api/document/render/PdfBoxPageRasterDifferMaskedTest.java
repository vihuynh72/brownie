package io.github.vihuynh72.brownie.api.document.render;

import io.github.vihuynh72.brownie.api.document.pdf.PdfBoxFormFiller;
import io.github.vihuynh72.brownie.api.document.pdf.PdfFormFixtures;
import io.github.vihuynh72.brownie.core.document.FilledPdf;
import io.github.vihuynh72.brownie.core.document.PdfFillItem;
import io.github.vihuynh72.brownie.core.document.PdfFillRequest;
import io.github.vihuynh72.brownie.core.document.PdfFillTarget;
import io.github.vihuynh72.brownie.core.document.PdfFontFamily;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import io.github.vihuynh72.brownie.core.template.PdfBoxGeometry;
import io.github.vihuynh72.brownie.core.validation.MaskedPageComparison;
import io.github.vihuynh72.brownie.core.validation.MaskedRasterComparison;
import io.github.vihuynh72.brownie.core.validation.RasterMask;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The masked comparison against real fills: what a fill wrote is ignored, anything else it changed is not. */
class PdfBoxPageRasterDifferMaskedTest {

    private static final PdfRect NAME_BOX = new PdfRect(140, 80, 300, 18);

    private final PdfBoxPageRasterDiffer differ = new PdfBoxPageRasterDiffer();
    private final PdfBoxFormFiller filler = new PdfBoxFormFiller();

    @Test
    void textWrittenInsideItsBoxIsNotAChange() throws IOException {
        byte[] flat = PdfFormFixtures.flatForm();
        FilledPdf filled = filler.fill(flat, fill(NAME_BOX, "Jordan Lee"));

        MaskedRasterComparison comparison = differ.compareMasked(flat, filled.bytes(), List.of(mask(1, NAME_BOX)));
        MaskedRasterComparison unmasked = differ.compareMasked(flat, filled.bytes(), List.of());

        assertFalse(comparison.changedOutsideMasks());
        assertTrue(comparison.everyPageCompared());
        assertEquals(0, comparison.pages().get(0).pixelsChangedOutsideMasks());
        assertTrue(unmasked.changedOutsideMasks(), "without its mask the written text is a change");
    }

    @Test
    void aMarkOutsideEveryBoxIsAChange() throws IOException {
        byte[] flat = PdfFormFixtures.flatForm();
        // Twelve points square, about the ink of a short stray word: 256 pixels, past the 0.02% of a Letter page (173).
        byte[] marked = withMark(filler.fill(flat, fill(NAME_BOX, "Jordan Lee")).bytes(), 400, 300, 12);

        MaskedRasterComparison comparison = differ.compareMasked(flat, marked, List.of(mask(1, NAME_BOX)));

        assertTrue(comparison.changedOutsideMasks());
        MaskedPageComparison page = comparison.pages().get(0);
        assertTrue(page.pixelsChangedOutsideMasks() >= MaskedPageComparison.MIN_CHANGED_PIXELS, "changed " + page.pixelsChangedOutsideMasks());
    }

    @Test
    void aMarkInsideABoxIsNotAChange() throws IOException {
        byte[] flat = PdfFormFixtures.flatForm();
        // The mark at user (300, 700), 4 points square, sits inside the box 140..440 across and 80..98 down.
        byte[] marked = withMark(flat, 300, 700, 4);

        assertFalse(differ.compareMasked(flat, marked, List.of(mask(1, NAME_BOX))).changedOutsideMasks());
    }

    @Test
    void masksAreTurnedWithTheirPage() throws IOException {
        for (int rotation : new int[] {90, 180, 270}) {
            byte[] flat = PdfFormFixtures.flatFormOnTurnedPage(rotation);
            PDRectangle letter = PDRectangle.LETTER;
            PdfFormGraph.CropBox crop = new PdfFormGraph.CropBox(0, 0, letter.getWidth(), letter.getHeight());
            // A box in the middle of the page as shown, turned back into the page's own frame.
            PdfRect box = PdfBoxGeometry.fromDisplayed(new PdfRect(100, 300, 250, 18), crop, rotation);
            FilledPdf filled = filler.fill(flat, fill(box, "Jordan Lee"));

            MaskedRasterComparison comparison = differ.compareMasked(flat, filled.bytes(), List.of(mask(1, box)));

            assertEquals(List.of(), filled.findings(), "rotation " + rotation);
            assertFalse(comparison.changedOutsideMasks(), "rotation " + rotation + ": " + comparison.pages());
        }
    }

    @Test
    void aScanWhosePictureCannotBeDecodedHereIsComparedWithoutItAndSaysSo() throws IOException {
        byte[] scan = PdfFormFixtures.scannedPage(true);
        PdfRect box = new PdfRect(72, 100, 200, 18);
        FilledPdf filled = filler.fill(scan, fill(box, "Jordan Lee"));

        MaskedRasterComparison comparison = differ.compareMasked(scan, filled.bytes(), List.of(mask(1, box)));

        MaskedPageComparison page = comparison.pages().get(0);
        assertEquals(MaskedPageComparison.Outcome.COMPARED, page.outcome());
        assertTrue(page.pictureNotCompared());
        assertTrue(comparison.anyPictureNotCompared());
        assertFalse(comparison.changedOutsideMasks());
    }

    @Test
    void aPictureOfMorePixelsThanAllowedIsNotDrawn() throws IOException {
        byte[] scan = PdfFormFixtures.scannedPage(false);
        PdfBoxPageRasterDiffer tinyPictures = new PdfBoxPageRasterDiffer(20_000, 100);

        MaskedPageComparison page = tinyPictures.compareMasked(scan, scan, List.of()).pages().get(0);

        assertTrue(page.pictureNotCompared());
        assertFalse(page.changed());
    }

    @Test
    void aPageTooLargeToDrawIsNotComparedRatherThanPassedOrFailed() throws IOException {
        byte[] huge = onePage(new PDRectangle(14_400, 14_400));

        MaskedRasterComparison comparison = differ.compareMasked(huge, huge, List.of());

        assertEquals(MaskedPageComparison.Outcome.NOT_DRAWN_TOO_LARGE, comparison.pages().get(0).outcome());
        assertFalse(comparison.everyPageCompared());
        assertFalse(comparison.changedOutsideMasks());
    }

    @Test
    void drawingPastTheTimeAllowedIsNotComparedRatherThanPassedOrFailed() throws IOException {
        byte[] flat = PdfFormFixtures.flatForm();
        PdfBoxPageRasterDiffer noTime = new PdfBoxPageRasterDiffer(0, PdfBoxPageRasterDiffer.MAX_PIXELS_PER_PICTURE);

        MaskedRasterComparison comparison = noTime.compareMasked(flat, flat, List.of());

        assertEquals(MaskedPageComparison.Outcome.NOT_DRAWN_TOO_SLOW, comparison.pages().get(0).outcome());
        assertFalse(comparison.everyPageCompared());
    }

    @Test
    void aPageAddedOrLostIsAChange() throws IOException {
        byte[] one = onePage(PDRectangle.LETTER);
        byte[] two;
        try (PDDocument document = Loader.loadPDF(one)) {
            document.addPage(new PDPage(PDRectangle.LETTER));
            two = write(document);
        }

        assertTrue(differ.compareMasked(one, two, List.of()).changedOutsideMasks());
    }

    @Test
    void theThresholdIsAShareOfThePageButNeverFewerThanFivePixels() {
        assertEquals(5, MaskedPageComparison.threshold(1_000));
        assertEquals(165, MaskedPageComparison.threshold(816L * 1056 - 40_000));
    }

    private static PdfFillRequest fill(PdfRect box, String value) {
        return new PdfFillRequest(List.of(new PdfFillItem("name",
                new PdfFillTarget.Box(1, box, new PdfTextStyle(PdfFontFamily.SANS, false, 11), false), value, PdfOverflowPolicy.SHRINK_TO_FIT)));
    }

    private static RasterMask mask(int page, PdfRect box) {
        return new RasterMask(page, box.x(), box.y(), box.width(), box.height());
    }

    /** The same file with a small black square added at user ({@code x}, {@code y}). */
    private static byte[] withMark(byte[] pdf, float x, float y, float size) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            try (PDPageContentStream cs = new PDPageContentStream(document, document.getPage(0), PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.setNonStrokingColor(0f);
                cs.addRect(x, y, size, size);
                cs.fill();
            }
            return write(document);
        }
    }

    private static byte[] onePage(PDRectangle size) throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(size));
            return write(document);
        }
    }

    private static byte[] write(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }
}
