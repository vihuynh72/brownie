package io.github.vihuynh72.brownie.api.document.pdf;

import io.github.vihuynh72.brownie.core.document.PdfExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.PdfPage;
import io.github.vihuynh72.brownie.core.document.PdfParseException;
import io.github.vihuynh72.brownie.core.document.PdfStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.PdfStructuralGraph;
import io.github.vihuynh72.brownie.core.document.PdfTextLine;
import io.github.vihuynh72.brownie.core.document.UnsupportedPdfReason;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a PDF's page geometry and text using Apache PDFBox, building line
 * groupings and bounding boxes directly from raw {@link TextPosition} data
 * (through {@link PdfLineGrouper}, which the form reader shares) rather
 * than {@code PDFTextStripper}'s own default line/word assembly.
 * That default assembly was tried first and empirically confirmed (via a
 * throwaway exploration script, not assumed) to garble ordinary,
 * unrotated-looking text on a page whose {@code /Rotate} entry is set --
 * splitting single words mid-character across separate "lines" -- because
 * it tries to account for the page's viewing rotation when deciding line
 * breaks and gets it wrong for this common case. Raw {@code TextPosition}
 * coordinates, in contrast, are themselves completely unaffected by a
 * page's {@code /Rotate} value (confirmed the same way: identical
 * positions reported for the same content stream regardless of the
 * page's declared rotation), so building line/word grouping directly from
 * them side-steps the bug entirely.
 */
public final class PdfBoxStructuralExtractor implements PdfStructuralExtractor {

    /**
     * Names both this extractor's own graph shape and the PDFBox version
     * it depends on, the same reasoning {@code PoiDocxStructuralExtractor}
     * already uses for its own parser version.
     */
    static final String PARSER_VERSION = "brownie-pdf-graph-v1+pdfbox-3.0.8";

    /**
     * What one uploaded PDF may cost to read. An upload is at most ten
     * mebibytes, but a PDF's contents are compressed and its page tree is
     * only a list of references, so its size on disk bounds nothing: a few
     * kilobytes can declare a million pages or expand to gigabytes. Minutes
     * and forms run to tens of pages and tens of thousands of characters.
     */
    static final int MAX_PAGES = 200;
    static final long MAX_CHARACTERS = PdfReadingBudget.MAX_CHARACTERS;
    static final long MAX_EXPANDED_BYTES = PdfReadingBudget.MAX_EXPANDED_BYTES;
    /** The library's own words when the bound on expanded content is reached; it has no exception type for it. */
    private static final String EXPANSION_LIMIT_MESSAGE = "Maximum allowed scratch file memory exceeded";

    private final int maxPages;
    private final long maxCharacters;
    private final long maxExpandedBytes;

    public PdfBoxStructuralExtractor() {
        this(MAX_PAGES, MAX_CHARACTERS, MAX_EXPANDED_BYTES);
    }

    /** For tests, which prove each limit with a small file and a small limit instead of a huge file and the real one. */
    PdfBoxStructuralExtractor(int maxPages, long maxCharacters, long maxExpandedBytes) {
        this.maxPages = maxPages;
        this.maxCharacters = maxCharacters;
        this.maxExpandedBytes = maxExpandedBytes;
    }

    @Override
    public String parserVersion() {
        return PARSER_VERSION;
    }

    @Override
    public PdfExtractionOutcome extract(InputStream content) throws IOException {
        byte[] bytes = content.readAllBytes();
        PDDocument document;
        try {
            // Everything the library expands is held in a store with a ceiling, so expanding too much is an error
            // it reports rather than memory this process runs out of.
            document = Loader.loadPDF(bytes, "", null, null, MemoryUsageSetting.setupMainMemoryOnly(maxExpandedBytes).streamCache);
        } catch (InvalidPasswordException e) {
            return new PdfExtractionOutcome.Unsupported(
                    UnsupportedPdfReason.ENCRYPTED, "The PDF is password-protected; no password was supplied.");
        } catch (IOException e) {
            if (isExpansionLimit(e)) {
                return expandsTooFar();
            }
            throw new PdfParseException("Could not parse the package as a PDF document.", e);
        }

        try (document) {
            // The pages that are really there, counted by walking them: the number a file declares is only a claim,
            // and everything below reads the pages the walk finds.
            List<PDPage> found = new ArrayList<>();
            for (PDPage page : document.getPages()) {
                if (found.size() == maxPages) {
                    int declared = document.getNumberOfPages();
                    String howMany = declared > maxPages ? declared + " pages" : "more than " + maxPages + " pages";
                    return new PdfExtractionOutcome.Unsupported(
                            UnsupportedPdfReason.TOO_MANY_PAGES,
                            "This document has " + howMany + "; at most " + maxPages + " are read.");
                }
                found.add(page);
            }
            PdfReadingBudget budget = new PdfReadingBudget(
                    maxExpandedBytes, maxCharacters, PdfReadingBudget.MAX_CHARACTERS_ON_ONE_PAGE);
            List<PdfPage> pages = new ArrayList<>();
            boolean anyPageHasText = false;
            for (int index = 0; index < found.size(); index++) {
                PdfPage page;
                try {
                    page = extractPage(document, found.get(index), index, budget);
                } catch (IOException e) {
                    if (isExpansionLimit(e)) {
                        return expandsTooFar();
                    }
                    throw e;
                }
                pages.add(page);
                anyPageHasText = anyPageHasText || page.hasExtractableText();
            }
            if (!anyPageHasText) {
                return new PdfExtractionOutcome.Unsupported(
                        UnsupportedPdfReason.NO_EXTRACTABLE_TEXT,
                        "No page in this " + pages.size() + "-page document produced any extractable text.");
            }
            return new PdfExtractionOutcome.Supported(new PdfStructuralGraph(PARSER_VERSION, List.copyOf(pages)));
        } catch (RuntimeException e) {
            if (isExpansionLimit(e)) {
                return expandsTooFar();
            }
            throw new PdfParseException("Could not read this PDF's content.", e);
        }
    }

    /** Whether reading stopped because the budget or the library's own memory ceiling was reached; the form reader and checker ask the same. */
    static boolean isExpansionLimit(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof PdfReadingBudget.Exceeded
                    || (cause.getMessage() != null && cause.getMessage().contains(EXPANSION_LIMIT_MESSAGE))) {
                return true;
            }
        }
        return false;
    }

    private static PdfExtractionOutcome expandsTooFar() {
        return new PdfExtractionOutcome.Unsupported(
                UnsupportedPdfReason.TOO_LARGE_WHEN_EXPANDED,
                "This document's contents expand far beyond what a document of its size holds, so it was not read.");
    }

    private PdfPage extractPage(PDDocument document, PDPage page, int zeroBasedIndex, PdfReadingBudget budget)
            throws IOException {
        PDRectangle cropBox = page.getCropBox();
        int rotation = page.getRotation();

        List<TextPosition> characters = PdfPageText.characters(document, zeroBasedIndex, budget);
        List<PdfTextLine> lines = new ArrayList<>();
        for (PdfLineGrouper.GroupedLine line : PdfLineGrouper.group(characters)) {
            lines.add(new PdfTextLine(
                    lines.size(), line.text(), line.x(), line.y(), line.width(), line.height(), line.ambiguousReadingOrder()));
        }

        return new PdfPage(zeroBasedIndex + 1, cropBox.getWidth(), cropBox.getHeight(), rotation, !lines.isEmpty(), lines);
    }
}
