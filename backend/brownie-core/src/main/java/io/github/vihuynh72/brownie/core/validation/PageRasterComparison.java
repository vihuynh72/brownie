package io.github.vihuynh72.brownie.core.validation;

import java.util.List;

/**
 * The coarse, whole-page pixel-difference result of rasterizing two PDFs at
 * the same fixed resolution and comparing corresponding pages. {@code
 * perPageDifferenceFraction} covers only the pages present in both
 * documents, in order -- {@code 0.0} means identical pixels, {@code 1.0}
 * means every sampled pixel differed. This is a coarse signal only:
 * anti-aliasing, font hinting, and expected reflow all produce nonzero
 * differences on an otherwise-correct page, so this alone never proves a
 * defect (see {@code LayoutComparator}'s own javadoc for the structural
 * comparison that can).
 */
public record PageRasterComparison(int baselinePageCount, int filledPageCount, List<Double> perPageDifferenceFraction) {

    public PageRasterComparison {
        perPageDifferenceFraction = List.copyOf(perPageDifferenceFraction);
    }
}
