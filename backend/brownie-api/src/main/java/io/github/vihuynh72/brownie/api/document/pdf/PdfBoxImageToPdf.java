package io.github.vihuynh72.brownie.api.document.pdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * A photographed or scanned paper form becomes the one-page PDF the rest of the
 * pipeline reads: the picture drawn full-page at 150 DPI so a phone photo is not
 * a four-metre page, upright the way EXIF says it was taken. A picture past forty
 * megapixels is refused -- the model could not read it anyway.
 */
public final class PdfBoxImageToPdf implements io.github.vihuynh72.brownie.core.prepare.ImageToPdf {

    private static final long MAX_PIXELS = 40L * 1_000_000;
    private static final float POINTS_PER_INCH = 72f;
    private static final float DPI = 150f;

    @Override

    public byte[] pdfOf(byte[] imageBytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) {
            throw new IOException("The file is not a picture this server reads.");
        }
        long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels > MAX_PIXELS) {
            throw new IOException("This picture is too large to read; try a smaller photo.");
        }
        image = applyExifOrientation(imageBytes, image);
        try (PDDocument document = new PDDocument()) {
            PDImageXObject picture = LosslessFactory.createFromImage(document, image);
            float width = image.getWidth() * POINTS_PER_INCH / DPI;
            float height = image.getHeight() * POINTS_PER_INCH / DPI;
            PDPage page = new PDPage(new PDRectangle(width, height));
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(picture, 0, 0, width, height);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private BufferedImage applyExifOrientation(byte[] imageBytes, BufferedImage image) throws IOException {
        int orientation = exifOrientation(imageBytes);
        if (orientation <= 1) {
            return image;
        }
        BufferedImage rotated;
        Graphics2D g;
        switch (orientation) {
            case 6 -> { // 90 CW
                rotated = new BufferedImage(image.getHeight(), image.getWidth(), image.getType());
                g = rotated.createGraphics();
                g.transform(AffineTransform.getRotateInstance(Math.PI / 2, image.getHeight() / 2.0, image.getHeight() / 2.0));
                g.drawImage(image, (image.getHeight() - image.getWidth()) / 2, (image.getHeight() - image.getWidth()) / 2, null);
            }
            case 8 -> { // 270 CW
                rotated = new BufferedImage(image.getHeight(), image.getWidth(), image.getType());
                g = rotated.createGraphics();
                g.transform(AffineTransform.getRotateInstance(-Math.PI / 2, image.getWidth() / 2.0, image.getWidth() / 2.0));
                g.drawImage(image, (image.getWidth() - image.getHeight()) / 2, (image.getWidth() - image.getHeight()) / 2, null);
            }
            case 3 -> { // 180
                rotated = new BufferedImage(image.getWidth(), image.getHeight(), image.getType());
                g = rotated.createGraphics();
                g.transform(AffineTransform.getRotateInstance(Math.PI, image.getWidth() / 2.0, image.getHeight() / 2.0));
                g.drawImage(image, 0, 0, null);
            }
            default -> { return image; }
        }
        g.dispose();
        return rotated;
    }

    /** The TIFF orientation tag inside a JPEG's EXIF block, or 1 when there is none. */
    /** The TIFF orientation tag inside a JPEG's APP1/EXIF block, or 1 when there is none. */
    private int exifOrientation(byte[] imageBytes) {
        int end = Math.min(imageBytes.length, 64 * 1024);
        for (int i = 2; i + 14 < end; i++) {
            if ((imageBytes[i] & 0xFF) == 0xFF && (imageBytes[i + 1] & 0xFF) == 0xE1
                    && "Exif\u0000\u0000".equals(new String(imageBytes, i + 4, 6, java.nio.charset.StandardCharsets.US_ASCII))) {
                return tiffOrientation(imageBytes, i + 4 + 6);
            }
            if ((imageBytes[i] & 0xFF) == 0xFF && (imageBytes[i + 1] & 0xFF) == 0xDB) {
                break; // SOS/past the headers
            }
        }
        return 1;
    }

    private int tiffOrientation(byte[] bytes, int tiff) {
        boolean little = bytes[tiff] == 0x49 && bytes[tiff + 1] == 0x49;
        boolean big = bytes[tiff] == 0x4D && bytes[tiff + 1] == 0x4D;
        if (!little && !big) {
            return 1;
        }
        int ifd = tiff + read32(bytes, tiff + 4, little);
        int entries = read16(bytes, ifd, little);
        for (int e = 0; e < entries; e++) {
            int tag = read16(bytes, ifd + 2 + e * 12, little);
            if (tag == 0x0112) {
                return read16(bytes, ifd + 2 + e * 12 + 8, little);
            }
        }
        return 1;
    }

    private int read16(byte[] bytes, int at, boolean little) {
        if (at + 1 >= bytes.length) {
            return 0;
        }
        return little
                ? (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8
                : (bytes[at] & 0xFF) << 8 | (bytes[at + 1] & 0xFF);
    }

    private int read32(byte[] bytes, int at, boolean little) {
        if (at + 3 >= bytes.length) {
            return 0;
        }
        return little
                ? (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8 | (bytes[at + 2] & 0xFF) << 16 | (bytes[at + 3] & 0xFF) << 24
                : (bytes[at] & 0xFF) << 24 | (bytes[at + 1] & 0xFF) << 16 | (bytes[at + 2] & 0xFF) << 8 | (bytes[at + 3] & 0xFF);
    }
}