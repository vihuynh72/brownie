package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfPoint;
import io.github.vihuynh72.brownie.core.document.PdfRect;

/**
 * Converts a box between the three ways a PDF page can be measured, so
 * that the server, which draws into the file, and the browser, which shows
 * the page turned the way it is meant to be read, agree to the point:
 *
 * <ul>
 *   <li><b>Box space</b> (how Brownie stores boxes; {@link PdfRect}):
 *   points, the page as stored, origin at the crop box's top-left, Y
 *   down.</li>
 *   <li><b>User space</b>: the PDF's own coordinates, origin wherever the
 *   file puts it (the crop box need not start at zero), Y up. Drawing into
 *   the file happens here.</li>
 *   <li><b>Displayed space</b>: the page as a viewer shows it after turning
 *   it clockwise by its {@code /Rotate}; origin at the top-left of what is
 *   shown, Y down. Width and height swap at 90 and 270 degrees. Pointer
 *   positions arrive here, and text must read upright here.</li>
 * </ul>
 *
 * <p>The same conversions exist in the web app, and both are tested
 * against one file of worked examples ({@code
 * fixtures/public/pdf-geometry-vectors.json}), so neither can drift from
 * the other unnoticed.
 */
public final class PdfBoxGeometry {

    private PdfBoxGeometry() {
    }

    /** A rectangle in user space: lower-left and upper-right corners, Y up. */
    public record UserSpaceRect(double llx, double lly, double urx, double ury) {
    }

    /**
     * Where upright text for a box is drawn: the transform {@code [a b c d
     * e f]} (as a PDF {@code cm} operator takes it) from a frame whose
     * origin is the box's bottom-left corner as displayed, with X to the
     * displayed right and Y displayed up, into user space; and that frame's
     * {@code width} and {@code height}, which are the box's displayed size.
     */
    public record UprightFrame(double a, double b, double c, double d, double e, double f, double width, double height) {
    }

    public static UserSpaceRect toUserSpace(PdfRect box, PdfFormGraph.CropBox crop) {
        double llx = crop.llx() + box.x();
        double ury = crop.lly() + crop.height() - box.y();
        return new UserSpaceRect(llx, ury - box.height(), llx + box.width(), ury);
    }

    /** The corners may come in either order; the result is the rectangle they span. */
    public static PdfRect fromUserSpace(UserSpaceRect rect, PdfFormGraph.CropBox crop) {
        double left = Math.min(rect.llx(), rect.urx());
        double right = Math.max(rect.llx(), rect.urx());
        double bottom = Math.min(rect.lly(), rect.ury());
        double top = Math.max(rect.lly(), rect.ury());
        return new PdfRect(left - crop.llx(), crop.lly() + crop.height() - top, right - left, top - bottom);
    }

    public static PdfPoint pointFromUserSpace(double userX, double userY, PdfFormGraph.CropBox crop) {
        return new PdfPoint(userX - crop.llx(), crop.lly() + crop.height() - userY);
    }

    public static double displayedWidth(PdfFormGraph.CropBox crop, int rotation) {
        return quarterTurns(rotation) % 2 == 0 ? crop.width() : crop.height();
    }

    public static double displayedHeight(PdfFormGraph.CropBox crop, int rotation) {
        return quarterTurns(rotation) % 2 == 0 ? crop.height() : crop.width();
    }

    public static PdfPoint toDisplayed(PdfPoint point, PdfFormGraph.CropBox crop, int rotation) {
        double width = crop.width();
        double height = crop.height();
        return switch (quarterTurns(rotation)) {
            case 0 -> point;
            case 1 -> new PdfPoint(height - point.y(), point.x());
            case 2 -> new PdfPoint(width - point.x(), height - point.y());
            default -> new PdfPoint(point.y(), width - point.x());
        };
    }

    public static PdfPoint fromDisplayed(PdfPoint point, PdfFormGraph.CropBox crop, int rotation) {
        double width = crop.width();
        double height = crop.height();
        return switch (quarterTurns(rotation)) {
            case 0 -> point;
            case 1 -> new PdfPoint(point.y(), height - point.x());
            case 2 -> new PdfPoint(width - point.x(), height - point.y());
            default -> new PdfPoint(width - point.y(), point.x());
        };
    }

    public static PdfRect toDisplayed(PdfRect box, PdfFormGraph.CropBox crop, int rotation) {
        return spanning(
                toDisplayed(new PdfPoint(box.x(), box.y()), crop, rotation),
                toDisplayed(new PdfPoint(box.right(), box.bottom()), crop, rotation));
    }

    public static PdfRect fromDisplayed(PdfRect box, PdfFormGraph.CropBox crop, int rotation) {
        return spanning(
                fromDisplayed(new PdfPoint(box.x(), box.y()), crop, rotation),
                fromDisplayed(new PdfPoint(box.right(), box.bottom()), crop, rotation));
    }

    /**
     * The frame to draw a box's text in so that it reads upright once the
     * viewer turns the page. A viewer turns a page clockwise by its
     * rotation, so at 90 degrees what is displayed as "right" is the page's
     * own "up" (user-space +Y) and what is displayed as "up" is the page's
     * own "left" (user-space -X); the other turns follow the same way.
     */
    public static UprightFrame uprightFrame(PdfRect box, PdfFormGraph.CropBox crop, int rotation) {
        UserSpaceRect user = toUserSpace(box, crop);
        return switch (quarterTurns(rotation)) {
            case 0 -> new UprightFrame(1, 0, 0, 1, user.llx(), user.lly(), box.width(), box.height());
            case 1 -> new UprightFrame(0, 1, -1, 0, user.urx(), user.lly(), box.height(), box.width());
            case 2 -> new UprightFrame(-1, 0, 0, -1, user.urx(), user.ury(), box.width(), box.height());
            default -> new UprightFrame(0, -1, 1, 0, user.llx(), user.ury(), box.height(), box.width());
        };
    }

    /** A page's rotation as a number of clockwise quarter turns (0 to 3). A PDF only allows multiples of 90 degrees. */
    public static int quarterTurns(int rotation) {
        if (rotation % 90 != 0) {
            throw new IllegalArgumentException("A page rotation must be a multiple of 90 degrees, was " + rotation + ".");
        }
        return Math.floorMod(rotation / 90, 4);
    }

    private static PdfRect spanning(PdfPoint first, PdfPoint second) {
        double left = Math.min(first.x(), second.x());
        double top = Math.min(first.y(), second.y());
        return new PdfRect(left, top, Math.abs(second.x() - first.x()), Math.abs(second.y() - first.y()));
    }
}
