package io.github.vihuynh72.brownie.core.validation;

import java.util.List;

/**
 * Two PDFs drawn page by page and compared everywhere except where a fill
 * was meant to write: the check that filling a form changed nothing else.
 * Only pages present in both are compared; different page counts are a
 * change of their own.
 */
public record MaskedRasterComparison(int referencePageCount, int filledPageCount, List<MaskedPageComparison> pages) {

    public MaskedRasterComparison {
        pages = List.copyOf(pages);
    }

    public boolean changedOutsideMasks() {
        return referencePageCount != filledPageCount || pages.stream().anyMatch(MaskedPageComparison::changed);
    }

    /** Whether every shared page was drawn and compared; a page that was not is a gap in the check, not a change. */
    public boolean everyPageCompared() {
        return pages.stream().allMatch(page -> page.outcome() == MaskedPageComparison.Outcome.COMPARED);
    }

    public boolean anyPictureNotCompared() {
        return pages.stream().anyMatch(MaskedPageComparison::pictureNotCompared);
    }
}
