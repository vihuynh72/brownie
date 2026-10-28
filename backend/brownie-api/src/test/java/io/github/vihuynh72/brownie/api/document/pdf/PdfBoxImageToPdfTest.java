package io.github.vihuynh72.brownie.api.document.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A photographed form becomes the one-page PDF the pipeline reads. */
class PdfBoxImageToPdfTest {

    @Test
    void aPngBecomesOnePageAtOneHundredFiftyDpi() throws Exception {
        byte[] png = image("PNG", 3000, 2000);

        byte[] pdf = new PdfBoxImageToPdf().pdfOf(png);

        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());
            PDRectangle box = document.getPage(0).getMediaBox();
            assertEquals(3000f * 72 / 150, box.getWidth(), 0.5);
            assertEquals(2000f * 72 / 150, box.getHeight(), 0.5);
        }
    }

    @Test
    void aJpegSidewaysInExifComesOutUpright() throws Exception {
        byte[] jpeg = exifJpeg(2000, 1000, 6);

        byte[] pdf = new PdfBoxImageToPdf().pdfOf(jpeg);

        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            PDRectangle box = document.getPage(0).getMediaBox();
            assertEquals(1000f * 72 / 150, box.getWidth(), 0.5);
            assertEquals(2000f * 72 / 150, box.getHeight(), 0.5);
        }
    }

    @Test
    void aPictureTooBigIsToldToComeSmaller() throws Exception {
        byte[] png = image("PNG", 7000, 6000);

        java.io.IOException refused = assertThrows(java.io.IOException.class, () -> new PdfBoxImageToPdf().pdfOf(png));
        assertTrue(refused.getMessage().contains("too large"), refused.getMessage());
    }

    private static byte[] image(String format, int width, int height) throws java.io.IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static byte[] exifJpeg(int width, int height, int orientation) throws java.io.IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ImageIO.write(image, "JPEG", raw);
        byte[] jpeg = raw.toByteArray();
        return injectOrientation(jpeg, orientation);
    }

    /**
     * An APP1/EXIF block with the TIFF orientation tag, spliced after the JPEG's SOI
     * and its first APP0, the way a camera writes it.
     */
    private static byte[] injectOrientation(byte[] jpeg, int orientation) throws java.io.IOException {
        byte[] exifHeader = "Exif\0\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ByteArrayOutputStream exif = new ByteArrayOutputStream();
        exif.write(exifHeader);
        exif.write(new byte[]{0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00}); // II + 42 + IFD offset 8
        exif.write(new byte[]{0x01, 0x00});                                      // 1 entry
        exif.write(new byte[]{0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00}); // tag 0x0112, SHORT, count 1
        exif.write(new byte[]{(byte) orientation, 0x00, 0x00, 0x00});           // value
        exif.write(new byte[]{0x00, 0x00, 0x00, 0x00});                          // next IFD = 0
        byte[] block = exif.toByteArray();
        int length = block.length + 2;
        // The APP0 the writer wrote stays first; the EXIF APP1 goes right after it.
        int app0End = 2 + 2 + ((jpeg[2 + 2] & 0xFF) << 8 | (jpeg[2 + 3] & 0xFF));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, app0End);
        out.write(0xFF); out.write(0xE1);
        out.write((length >> 8) & 0xFF); out.write(length & 0xFF);
        out.write(block);
        out.write(jpeg, app0End, jpeg.length - app0End);
        return out.toByteArray();
    }
}
