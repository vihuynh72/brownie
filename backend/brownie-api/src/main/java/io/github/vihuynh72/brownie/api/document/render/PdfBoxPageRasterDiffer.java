package io.github.vihuynh72.brownie.api.document.render;

import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.template.PdfBoxGeometry;
import io.github.vihuynh72.brownie.core.validation.MaskedPageComparison;
import io.github.vihuynh72.brownie.core.validation.MaskedRasterComparison;
import io.github.vihuynh72.brownie.core.validation.PageRasterComparison;
import io.github.vihuynh72.brownie.core.validation.PageRasterDiffer;
import io.github.vihuynh72.brownie.core.validation.RasterMask;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Rasterizes both PDFs at a fixed, deliberately low DPI -- enough to catch
 * a real layout shift, not so much that comparing every pixel of a
 * multi-page document becomes slow -- and reports the fraction of sampled
 * pixels whose combined RGB distance exceeds a small per-pixel tolerance
 * (anti-aliasing and font hinting always produce some nonzero difference
 * even between two renders of identical content). A page present in only
 * one document is not compared; {@link PageRasterComparison}'s own page
 * counts already say the counts differ.
 *
 * <p>{@link #compareMasked} compares a PDF form as uploaded with its filled
 * copy everywhere except where the fill wrote. Both files may have come
 * from a person, so the drawing there is bounded (see {@link
 * BoundedPageRenderer}): at most {@value #MASKED_COMPARISONS_AT_ONCE}
 * comparisons run at once in this process, each within {@link
 * #MASKED_COMPARISON_TIME_ALLOWED_MILLIS} milliseconds for all its pages, and
 * a picture of more than {@link #MAX_PIXELS_PER_PICTURE} pixels is not
 * drawn. A page that could not be drawn is reported as not compared, never
 * as changed or unchanged.
 */
public final class PdfBoxPageRasterDiffer implements PageRasterDiffer {

    private static final float DPI = 96f;
    private static final int PER_CHANNEL_TOLERANCE = 24;
    private static final long MAX_EXPANDED_BYTES = 256L * 1024 * 1024;
    /** A0, the largest sheet anyone prints on, is about fourteen million pixels at this resolution. */
    private static final double MAX_PIXELS_PER_PAGE = 20_000_000;

    static final int MASKED_COMPARISONS_AT_ONCE = 2;
    static final long MASKED_COMPARISON_TIME_ALLOWED_MILLIS = 20_000;
    /** A letter-size page scanned at 600 dots per inch is about 34 million pixels; this is beyond any page a person scans. */
    static final long MAX_PIXELS_PER_PICTURE = 50_000_000;
    /** How far around each place a fill wrote is left out of the comparison: anti-aliasing at the edges of its letters. */
    static final double MASK_MARGIN_POINTS = 2;
    private static final Semaphore MASKED_COMPARISONS = new Semaphore(MASKED_COMPARISONS_AT_ONCE, true);

    private final long maskedTimeAllowedMillis;
    private final long maxPixelsPerPicture;

    public PdfBoxPageRasterDiffer() {
        this(MASKED_COMPARISON_TIME_ALLOWED_MILLIS, MAX_PIXELS_PER_PICTURE);
    }

    /** For tests, which prove each limit with a small one. */
    PdfBoxPageRasterDiffer(long maskedTimeAllowedMillis, long maxPixelsPerPicture) {
        this.maskedTimeAllowedMillis = maskedTimeAllowedMillis;
        this.maxPixelsPerPicture = maxPixelsPerPicture;
    }

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

    @Override
    public MaskedRasterComparison compareMasked(byte[] referencePdfBytes, byte[] filledPdfBytes, List<RasterMask> masks) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maskedTimeAllowedMillis);
        boolean admitted = false;
        try {
            admitted = MASKED_COMPARISONS.tryAcquire(maskedTimeAllowedMillis, TimeUnit.MILLISECONDS);
            try (PDDocument reference = load(referencePdfBytes);
                    PDDocument filled = load(filledPdfBytes)) {
                int comparablePages = Math.min(reference.getNumberOfPages(), filled.getNumberOfPages());
                List<MaskedPageComparison> pages = new ArrayList<>(comparablePages);
                for (int pageIndex = 0; pageIndex < comparablePages; pageIndex++) {
                    pages.add(admitted
                            ? comparePage(reference, filled, pageIndex, masks, deadline)
                            : notDrawn(pageIndex, MaskedPageComparison.Outcome.NOT_DRAWN_TOO_SLOW));
                }
                return new MaskedRasterComparison(reference.getNumberOfPages(), filled.getNumberOfPages(), pages);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to compare pages.", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read a PDF for a masked page comparison.", e);
        } finally {
            if (admitted) {
                MASKED_COMPARISONS.release();
            }
        }
    }

    private MaskedPageComparison comparePage(PDDocument reference, PDDocument filled, int pageIndex, List<RasterMask> masks,
            long deadline) {
        PDPage page = filled.getPage(pageIndex);
        if (isTooLargeToDraw(reference.getPage(pageIndex)) || isTooLargeToDraw(page)) {
            return notDrawn(pageIndex, MaskedPageComparison.Outcome.NOT_DRAWN_TOO_LARGE);
        }
        BoundedPageRenderer referenceRenderer = new BoundedPageRenderer(reference, deadline, maxPixelsPerPicture);
        BoundedPageRenderer filledRenderer = new BoundedPageRenderer(filled, deadline, maxPixelsPerPicture);
        BufferedImage referenceImage;
        BufferedImage filledImage;
        try {
            referenceImage = referenceRenderer.renderImageWithDPI(pageIndex, DPI);
            filledImage = filledRenderer.renderImageWithDPI(pageIndex, DPI);
        } catch (BoundedPageRenderer.DeadlinePassed e) {
            return notDrawn(pageIndex, MaskedPageComparison.Outcome.NOT_DRAWN_TOO_SLOW);
        } catch (IOException | RuntimeException e) {
            return notDrawn(pageIndex, MaskedPageComparison.Outcome.NOT_DRAWN_FAILED);
        }
        boolean pictureSkipped = referenceRenderer.pictureSkipped() || filledRenderer.pictureSkipped();
        if (referenceImage.getWidth() != filledImage.getWidth() || referenceImage.getHeight() != filledImage.getHeight()) {
            long all = (long) filledImage.getWidth() * filledImage.getHeight();
            return new MaskedPageComparison(pageIndex + 1, MaskedPageComparison.Outcome.COMPARED, all, all, pictureSkipped);
        }
        boolean[] masked = maskOf(page, pageIndex + 1, masks, filledImage.getWidth(), filledImage.getHeight());
        long compared = 0;
        long changed = 0;
        for (int y = 0; y < filledImage.getHeight(); y++) {
            for (int x = 0; x < filledImage.getWidth(); x++) {
                if (masked[y * filledImage.getWidth() + x]) {
                    continue;
                }
                compared++;
                if (pixelsDiffer(referenceImage.getRGB(x, y), filledImage.getRGB(x, y))) {
                    changed++;
                }
            }
        }
        return new MaskedPageComparison(pageIndex + 1, MaskedPageComparison.Outcome.COMPARED, compared, changed, pictureSkipped);
    }

    /**
     * Which pixels of the drawn page the masks cover. A mask is measured on
     * the page as stored; the drawing is of the page as shown, turned by its
     * rotation, so each mask is turned the same way first.
     */
    private static boolean[] maskOf(PDPage page, int pageNumber, List<RasterMask> masks, int width, int height) {
        boolean[] masked = new boolean[width * height];
        PDRectangle crop = page.getCropBox();
        PdfFormGraph.CropBox cropBox = new PdfFormGraph.CropBox(crop.getLowerLeftX(), crop.getLowerLeftY(), crop.getWidth(), crop.getHeight());
        double scale = DPI / 72.0;
        for (RasterMask mask : masks) {
            if (mask.pageNumber() != pageNumber) {
                continue;
            }
            PdfRect shown = PdfBoxGeometry.toDisplayed(
                    new PdfRect(mask.x(), mask.y(), mask.width(), mask.height()).grownBy(MASK_MARGIN_POINTS), cropBox, page.getRotation());
            int left = Math.max(0, (int) Math.floor(shown.x() * scale));
            int top = Math.max(0, (int) Math.floor(shown.y() * scale));
            int right = Math.min(width, (int) Math.ceil(shown.right() * scale));
            int bottom = Math.min(height, (int) Math.ceil(shown.bottom() * scale));
            for (int y = top; y < bottom; y++) {
                for (int x = left; x < right; x++) {
                    masked[y * width + x] = true;
                }
            }
        }
        return masked;
    }

    private static MaskedPageComparison notDrawn(int pageIndex, MaskedPageComparison.Outcome outcome) {
        return new MaskedPageComparison(pageIndex + 1, outcome, 0, 0, false);
    }

    private static boolean pixelsDiffer(int argbA, int argbB) {
        int redDiff = Math.abs(((argbA >> 16) & 0xFF) - ((argbB >> 16) & 0xFF));
        int greenDiff = Math.abs(((argbA >> 8) & 0xFF) - ((argbB >> 8) & 0xFF));
        int blueDiff = Math.abs((argbA & 0xFF) - (argbB & 0xFF));
        return redDiff > PER_CHANNEL_TOLERANCE || greenDiff > PER_CHANNEL_TOLERANCE || blueDiff > PER_CHANNEL_TOLERANCE;
    }
}
