package io.github.vihuynh72.brownie.api.document.render;

import io.github.vihuynh72.brownie.core.prepare.ScanPageRasterizer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** PDFBox's half of {@link ScanPageRasterizer}: the page drawn at about 110 DPI, the long side at most 1,600 px. */
public final class PdfBoxPageRasterizer implements ScanPageRasterizer {

    private static final int MAX_LONG_SIDE = 1600;
    private static final float QUALITY = 0.8f;

    @Override
    public PageJpeg jpegOf(byte[] pdf, int pageIndex) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, 110, ImageType.GRAY);
            if (Math.max(image.getWidth(), image.getHeight()) > MAX_LONG_SIDE) {
                double scale = MAX_LONG_SIDE / (double) Math.max(image.getWidth(), image.getHeight());
                int width = (int) (image.getWidth() * scale);
                int height = (int) (image.getHeight() * scale);
                BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
                java.awt.Graphics2D g = small.createGraphics();
                g.drawImage(image, 0, 0, width, height, null);
                g.dispose();
                image = small;
            }
            byte[] pixels = new byte[image.getWidth() * image.getHeight()];
            int[] row = new int[image.getWidth()];
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
                for (int x = 0; x < image.getWidth(); x++) {
                    pixels[y * image.getWidth() + x] = (byte) (row[x] & 0xFF);
                }
            }
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            try (ImageOutputStream output = ImageIO.createImageOutputStream(jpeg)) {
                ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(QUALITY);
                writer.setOutput(output);
                writer.write(null, new IIOImage(image, null, null), param);
                writer.dispose();
            }
            return new PageJpeg(jpeg.toByteArray(), image.getWidth(), image.getHeight(), pixels);
        }
    }
}
