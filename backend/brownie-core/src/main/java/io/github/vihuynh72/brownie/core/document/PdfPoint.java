package io.github.vihuynh72.brownie.core.document;

/** A point on one PDF page, in the same convention as {@link PdfRect}: points, the page as stored, origin at the crop box's top-left, Y down. */
public record PdfPoint(double x, double y) {
}
