package io.github.vihuynh72.brownie.core.document;

import java.util.List;

/**
 * One page's extracted geometry and text. {@code pageNumber} is one-based
 * and user-visible -- never the library's own zero-based page index.
 * {@code width}/{@code height} are the page's effective (crop box) extent
 * in PDF user-space points, in the page's own native, unrotated
 * orientation -- the same orientation {@code lines}' bounding boxes are
 * expressed in. {@code rotationDegrees} (0/90/180/270) is the page's own
 * {@code /Rotate} viewing rotation, stored as its own fact rather than
 * baked into the geometry above: PDF's page rotation is a display-time
 * transform a viewer applies, not a change to the content stream's own
 * coordinate space, so a consumer that needs a "visual" position must
 * apply this rotation itself. {@code hasExtractableText} is false when
 * this page produced no text at all -- most often a scanned or image-only
 * page -- in which case {@code lines} is empty; that is a real, reportable
 * fact about the page, not a failure of this extractor.
 */
public record PdfPage(int pageNumber, double width, double height, int rotationDegrees, boolean hasExtractableText, List<PdfTextLine> lines) {
}
