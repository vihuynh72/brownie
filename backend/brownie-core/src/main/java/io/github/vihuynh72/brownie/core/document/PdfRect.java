package io.github.vihuynh72.brownie.core.document;

/**
 * A rectangle on one PDF page, in the one convention every PDF form record
 * uses: points, on the page as it is stored (before its {@code /Rotate} is
 * applied), with the origin at the crop box's top-left corner and Y
 * increasing downward. That is the convention {@link PdfTextLine} already
 * uses, so a line, a word, a widget and a drawn box can be compared
 * directly. A viewer that shows the page turned applies the rotation on
 * top; {@code io.github.vihuynh72.brownie.core.template.PdfBoxGeometry}
 * converts both ways.
 */
public record PdfRect(double x, double y, double width, double height) {

    public double right() {
        return x + width;
    }

    public double bottom() {
        return y + height;
    }

    public double centerX() {
        return x + width / 2;
    }

    public double centerY() {
        return y + height / 2;
    }

    public double area() {
        return Math.max(0, width) * Math.max(0, height);
    }

    public boolean contains(double pointX, double pointY) {
        return pointX >= x && pointX <= right() && pointY >= y && pointY <= bottom();
    }

    /** Whether {@code other} lies wholly inside this rectangle once this one is grown by {@code tolerance} on every side. */
    public boolean encloses(PdfRect other, double tolerance) {
        return other.x >= x - tolerance && other.right() <= right() + tolerance
                && other.y >= y - tolerance && other.bottom() <= bottom() + tolerance;
    }

    public PdfRect grownBy(double margin) {
        return new PdfRect(x - margin, y - margin, width + 2 * margin, height + 2 * margin);
    }

    /** The area the two rectangles share; zero when they only touch or are apart. */
    public double overlapArea(PdfRect other) {
        double overlapWidth = Math.min(right(), other.right()) - Math.max(x, other.x);
        double overlapHeight = Math.min(bottom(), other.bottom()) - Math.max(y, other.y);
        return overlapWidth <= 0 || overlapHeight <= 0 ? 0 : overlapWidth * overlapHeight;
    }

    /** The smallest rectangle holding both. */
    public PdfRect union(PdfRect other) {
        double left = Math.min(x, other.x);
        double top = Math.min(y, other.y);
        return new PdfRect(left, top, Math.max(right(), other.right()) - left, Math.max(bottom(), other.bottom()) - top);
    }
}
