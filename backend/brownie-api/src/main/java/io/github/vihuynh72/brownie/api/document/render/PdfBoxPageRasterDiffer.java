package io.github.vihuynh72.brownie.api.document.render;

import io.github.vihuynh72.brownie.core.validation.PageRasterComparison;
import io.github.vihuynh72.brownie.core.validation.PageRasterDiffer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Rasterizes both PDFs at a fixed, deliberately low DPI -- enough to catch
 * a real layout shift, not so much that comparing every pixel of a
 * multi-page document becomes slow -- and reports the fraction of sampled
 * pixels whose combined RGB distance exceeds a small per-pixel tolerance
 * (anti-aliasing and font hinting always produce some nonzero difference
 * even between two renders of identical content). A page present in only
 * one document is not compared; {@link PageRasterComparison}'s own page
 * counts already say the counts differ.
 */
public final class PdfBoxPageRasterDiffer implements PageRasterDiffer {

    private static final float DPI = 96f;
    private static final int PER_CHANNEL_TOLERANCE = 24;
    private static final long MAX_EXPANDED_BYTES = 256L * 1024 * 1024;
    /** A0, the largest sheet anyone prints on, is about fourteen million pixels at this resolution. */
    private static final double MAX_PIXELS_PER_PAGE = 20_000_000;

    @Override
    public PageRasterComparison compare(byte[] baselinePdfBytes, byte[] filledPdfBytes) {
        try (PDDocument baseline = load(baselinePdfBytes);
                PDDocument filled = load(filledPdfBytes)) {
            int baselinePageCount = baseline.getNumberOfPages();
            int filledPageCount = filled.getNumberOfPages();
            int comparablePages = Math.min(baselinePageCount, filledPageCount);

            PDFRenderer baselineRenderer = new PDFRenderer(baseline);
            PDFRenderer filledRenderer = new PDFRenderer(filled);
            List<Double> perPageDifference = new ArrayList<>(comparablePages);
            for (int pageIndex = 0; pageIndex < comparablePages; pageIndex++) {
                if (isTooLargeToDraw(baseline.getPage(pageIndex)) || isTooLargeToDraw(filled.getPage(pageIndex))) {
                    // Not drawn, so not comparable, so counted as wholly different: the answer that stops an export.
                    perPageDifference.add(1.0);
                    continue;
                }
                BufferedImage baselineImage = baselineRenderer.renderImageWithDPI(pageIndex, DPI);
                BufferedImage filledImage = filledRenderer.renderImageWithDPI(pageIndex, DPI);
                perPageDifference.add(pixelDifferenceFraction(baselineImage, filledImage));
            }
            return new PageRasterComparison(baselinePageCount, filledPageCount, perPageDifference);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to rasterize a PDF for layout comparison.", e);
        }
    }

    /** Both files came out of the renderer and are read as what they are: something this process did not write. */
    private static PDDocument load(byte[] pdfBytes) throws IOException {
        return Loader.loadPDF(pdfBytes, "", null, null, MemoryUsageSetting.setupMainMemoryOnly(MAX_EXPANDED_BYTES).streamCache);
    }

    /** A page may declare itself two hundred inches square, which at this resolution is over a gigabyte of pixels. */
    private static boolean isTooLargeToDraw(PDPage page) {
        PDRectangle box = page.getCropBox();
        double pixels = (box.getWidth() / 72.0 * DPI) * (box.getHeight() / 72.0 * DPI);
        return pixels > MAX_PIXELS_PER_PAGE;
    }

    private static double pixelDifferenceFraction(BufferedImage baseline, BufferedImage filled) {
        if (baseline.getWidth() != filled.getWidth() || baseline.getHeight() != filled.getHeight()) {
            return 1.0;
        }
        int width = baseline.getWidth();
        int height = baseline.getHeight();
        long totalPixels = (long) width * height;
        if (totalPixels == 0) {
            return 0.0;
        }
        long differing = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (pixelsDiffer(baseline.getRGB(x, y), filled.getRGB(x, y))) {
                    differing++;
                }
            }
        }
        return (double) differing / (double) totalPixels;
    }

    private static boolean pixelsDiffer(int argbA, int argbB) {
        int redDiff = Math.abs(((argbA >> 16) & 0xFF) - ((argbB >> 16) & 0xFF));
        int greenDiff = Math.abs(((argbA >> 8) & 0xFF) - ((argbB >> 8) & 0xFF));
        int blueDiff = Math.abs((argbA & 0xFF) - (argbB & 0xFF));
        return redDiff > PER_CHANNEL_TOLERANCE || greenDiff > PER_CHANNEL_TOLERANCE || blueDiff > PER_CHANNEL_TOLERANCE;
    }
}
