package io.github.vihuynh72.brownie.core.validation;

/**
 * A place on one page where a fill was meant to change the page, so a
 * masked comparison ignores it: a box text was drawn in, or a field's
 * widget. Measured like every PDF box: points, the page as stored, origin
 * at the crop box's top-left, Y down. The comparison adds its own small
 * margin around it.
 */
public record RasterMask(int pageNumber, double x, double y, double width, double height) {
}
