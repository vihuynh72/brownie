package io.github.vihuynh72.brownie.api.document.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.core.document.FilledPdf;
import io.github.vihuynh72.brownie.core.document.PdfFillRequest;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfPoint;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.template.PdfBoxGeometry;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.vihuynh72.brownie.api.document.pdf.PdfBoxFormFillerTest.box;
import static io.github.vihuynh72.brownie.api.document.pdf.PdfBoxFormFillerTest.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The box conversions against the worked examples the web app is tested
 * against too, and then against the library itself: a rectangle filled at
 * the example's user-space position shows up, once the page is drawn the
 * way a viewer shows it, exactly where the example's displayed box says,
 * and text written in a box at the example's displayed place reads the
 * right way up inside it.
 */
class PdfBoxGeometryVectorsTest {

    private static final double EXACT = 1e-6;

    @Test
    void everyWorkedExampleConvertsBothWays() throws IOException {
        List<JsonNode> cases = cases();
        assertEquals(16, cases.size());
        for (JsonNode example : cases) {
            String name = example.get("name").asText();
            PdfFormGraph.CropBox crop = cropOf(example);
            int rotation = example.get("rotation").asInt();
            PdfRect box = rectOf(example.get("box"));

            PdfBoxGeometry.UserSpaceRect user = PdfBoxGeometry.toUserSpace(box, crop);
            JsonNode expectedUser = example.get("userSpace");
            assertEquals(expectedUser.get("llx").asDouble(), user.llx(), EXACT, name);
            assertEquals(expectedUser.get("lly").asDouble(), user.lly(), EXACT, name);
            assertEquals(expectedUser.get("urx").asDouble(), user.urx(), EXACT, name);
            assertEquals(expectedUser.get("ury").asDouble(), user.ury(), EXACT, name);
            assertSame(box, PdfBoxGeometry.fromUserSpace(user, crop), name);

            PdfRect displayed = rectOf(example.get("displayed"));
            assertSame(displayed, PdfBoxGeometry.toDisplayed(box, crop, rotation), name);
            assertSame(box, PdfBoxGeometry.fromDisplayed(displayed, crop, rotation), name);

            PdfPoint point = new PdfPoint(example.get("point").get("x").asDouble(), example.get("point").get("y").asDouble());
            PdfPoint shownAt = PdfBoxGeometry.toDisplayed(point, crop, rotation);
            assertEquals(example.get("displayedPoint").get("x").asDouble(), shownAt.x(), EXACT, name);
            assertEquals(example.get("displayedPoint").get("y").asDouble(), shownAt.y(), EXACT, name);
            PdfPoint back = PdfBoxGeometry.fromDisplayed(shownAt, crop, rotation);
            assertEquals(point.x(), back.x(), EXACT, name);
            assertEquals(point.y(), back.y(), EXACT, name);

            PdfBoxGeometry.UprightFrame frame = PdfBoxGeometry.uprightFrame(box, crop, rotation);
            JsonNode expectedFrame = example.get("uprightFrame");
            double[] actual = {frame.a(), frame.b(), frame.c(), frame.d(), frame.e(), frame.f(), frame.width(), frame.height()};
            String[] keys = {"a", "b", "c", "d", "e", "f", "width", "height"};
            for (int index = 0; index < keys.length; index++) {
                assertEquals(expectedFrame.get(keys[index]).asDouble(), actual[index], EXACT, name + " " + keys[index]);
            }
        }
    }

    @Test
    void theLibraryDrawsEachExamplesBoxWhereTheDisplayedBoxSays() throws IOException {
        for (JsonNode example : cases()) {
            String name = example.get("name").asText();
            PdfFormGraph.CropBox crop = cropOf(example);
            int rotation = example.get("rotation").asInt();
            PdfBoxGeometry.UserSpaceRect user = PdfBoxGeometry.toUserSpace(rectOf(example.get("box")), crop);
            PdfRect displayed = rectOf(example.get("displayed"));

            BufferedImage image;
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                page.setCropBox(new PDRectangle((float) crop.llx(), (float) crop.lly(), (float) crop.width(), (float) crop.height()));
                page.setRotation(rotation);
                document.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    cs.setNonStrokingColor(0f);
                    cs.addRect((float) user.llx(), (float) user.lly(), (float) (user.urx() - user.llx()), (float) (user.ury() - user.lly()));
                    cs.fill();
                }
                // At 72 dots per inch one pixel is one point.
                image = new PDFRenderer(document).renderImageWithDPI(0, 72);
            }

            assertEquals(PdfBoxGeometry.displayedWidth(crop, rotation), image.getWidth(), 1, name);
            assertEquals(PdfBoxGeometry.displayedHeight(crop, rotation), image.getHeight(), 1, name);
            assertTrue(dark(image, displayed.centerX(), displayed.centerY()), name + ": the middle of the box is drawn");
            assertTrue(dark(image, displayed.x() + 2, displayed.y() + 2), name + ": inside its top-left corner");
            assertTrue(dark(image, displayed.right() - 2, displayed.bottom() - 2), name + ": inside its bottom-right corner");
            assertTrue(!dark(image, displayed.x() - 3, displayed.centerY()), name + ": left of the box is blank");
            assertTrue(!dark(image, displayed.right() + 3, displayed.centerY()), name + ": right of the box is blank");
            assertTrue(!dark(image, displayed.centerX(), displayed.y() - 3), name + ": above the box is blank");
            assertTrue(!dark(image, displayed.centerX(), displayed.bottom() + 3), name + ": below the box is blank");
        }
    }

    @Test
    void textWrittenInEachExamplesBoxReadsUprightInsideIt() throws IOException {
        for (JsonNode example : cases()) {
            String name = example.get("name").asText();
            PdfFormGraph.CropBox crop = cropOf(example);
            int rotation = example.get("rotation").asInt();
            // The example's box as displayed, made wider than tall so that one word fits on one line, turned back.
            PdfRect displayed = rectOf(example.get("displayed"));
            double width = Math.max(displayed.width(), displayed.height());
            double x = Math.min(displayed.x(), PdfBoxGeometry.displayedWidth(crop, rotation) - width);
            PdfRect wide = new PdfRect(x, displayed.y(), width, Math.min(displayed.width(), displayed.height()));
            PdfRect box = PdfBoxGeometry.fromDisplayed(wide, crop, rotation);
            byte[] blank;
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                page.setCropBox(new PDRectangle((float) crop.llx(), (float) crop.lly(), (float) crop.width(), (float) crop.height()));
                page.setRotation(rotation);
                document.addPage(page);
                blank = PdfFormFixtures.write(document);
            }
            PdfFillRequest fill = request(box("value", 1, box, 10, "Upright"));

            FilledPdf filled = new PdfBoxFormFiller().fill(blank, fill);

            assertEquals(List.of(), filled.findings(), name);
            assertEquals(List.of(), new PdfBoxFillVerifier().verify(blank, filled, fill), name);
            PdfFormGraph.Word word = PdfBoxFormFillerTest.graphOf(filled.bytes()).pages().get(0).lines().get(0).words().get(0);
            assertEquals("Upright", word.text(), name);
            assertEquals(rotation, word.textDirection(), name);
            assertTrue(box.encloses(word.box(), 1), name + ": " + word.box() + " in " + box);
        }
    }

    private static boolean dark(BufferedImage image, double x, double y) {
        int px = (int) Math.floor(x);
        int py = (int) Math.floor(y);
        if (px < 0 || py < 0 || px >= image.getWidth() || py >= image.getHeight()) {
            return false;
        }
        return (image.getRGB(px, py) & 0xFF) < 128;
    }

    private static void assertSame(PdfRect expected, PdfRect actual, String name) {
        PdfBoxFormReaderTest.assertRect(expected, actual, EXACT);
    }

    private static PdfFormGraph.CropBox cropOf(JsonNode example) {
        JsonNode crop = example.get("cropBox");
        return new PdfFormGraph.CropBox(crop.get("llx").asDouble(), crop.get("lly").asDouble(), crop.get("width").asDouble(), crop.get("height").asDouble());
    }

    private static PdfRect rectOf(JsonNode rect) {
        return new PdfRect(rect.get("x").asDouble(), rect.get("y").asDouble(), rect.get("width").asDouble(), rect.get("height").asDouble());
    }

    private static List<JsonNode> cases() throws IOException {
        JsonNode vectors = new ObjectMapper().readTree(Files.readString(repositoryRoot().resolve("fixtures/public/pdf-geometry-vectors.json")));
        List<JsonNode> cases = new java.util.ArrayList<>();
        vectors.get("cases").forEach(cases::add);
        return cases;
    }

    static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("fixtures/public/templates"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("could not locate the repository root from the test working directory");
    }
}
