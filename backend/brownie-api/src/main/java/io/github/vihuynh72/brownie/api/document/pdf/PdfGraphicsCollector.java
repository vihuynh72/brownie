package io.github.vihuynh72.brownie.api.document.pdf;

import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfPoint;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects what one page draws besides text: straight horizontal and
 * vertical lines, rectangles, and where pictures go and which codecs they
 * declare. It runs only the operators that build and paint paths, move the
 * coordinate system, and draw forms and pictures; everything else (text,
 * colours, graphics states) is skipped, because loading a font, a colour
 * profile or a pattern would expand streams nothing here needs.
 *
 * <p>Pictures are never decoded. The library decodes some pictures just to
 * describe them (an inline picture when it is read, a JPEG 2000 picture
 * when it is looked up), so both are handled here from their dictionaries,
 * before the library gets to them.
 *
 * <p>It charges a {@link PdfReadingBudget} the same way {@link
 * BoundedPdfTextStripper} does: a page's content when the page begins, a
 * form each time it is drawn, the values waiting for each operator, each
 * operator, and each save of the drawing state. Past {@value #MAX_PATH_SEGMENTS_PER_PAGE} path segments on one
 * page (a drawing, not a form), it stops recording lines and rectangles for
 * that page, and stops keeping the points of paths still being built too,
 * since a path is only recorded when it is painted and a file need never
 * paint it. Past {@value #MAX_IMAGES_PER_PAGE} pictures on one page it
 * records no more of them.
 */
final class PdfGraphicsCollector extends PDFGraphicsStreamEngine {

    static final int MAX_PATH_SEGMENTS_PER_PAGE = 5_000;
    /** A scan is one picture a page; a form with a logo and a few stamps is a handful. */
    static final int MAX_IMAGES_PER_PAGE = 2_000;

    /** Anything thinner than this is a line, however it was drawn. */
    private static final double THIN = 2;
    private static final double AXIS_TOLERANCE = 0.5;
    private static final double SHORTEST_KEPT = 1;

    private static final Set<String> PATH_AND_STATE_OPERATORS = Set.of(
            "m", "l", "c", "v", "y", "h", "re",
            "S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "n", "W", "W*",
            "q", "Q", "cm");

    private static final Map<String, String> ABBREVIATED_FILTERS = Map.of(
            "AHx", "ASCIIHexDecode", "A85", "ASCII85Decode", "LZW", "LZWDecode", "Fl", "FlateDecode",
            "RL", "RunLengthDecode", "CCF", "CCITTFaxDecode", "DCT", "DCTDecode");

    private final PdfReadingBudget budget;
    private final PDRectangle crop;
    private final List<PdfFormGraph.Rule> rules = new ArrayList<>();
    private final List<PdfRect> rects = new ArrayList<>();
    private final List<PdfFormGraph.Image> images = new ArrayList<>();

    private final List<List<Point2D>> subpaths = new ArrayList<>();
    private final List<Boolean> closed = new ArrayList<>();
    private final List<Boolean> curved = new ArrayList<>();
    private final List<Point2D[]> pathRectangles = new ArrayList<>();
    private List<Point2D> subpath;
    private Point2D current;
    private int segments;

    PdfGraphicsCollector(PDPage page, PdfReadingBudget budget) {
        super(page);
        this.budget = budget;
        this.crop = page.getCropBox();
    }

    void collect() throws IOException {
        processPage(getPage());
    }

    List<PdfFormGraph.Rule> rules() {
        return List.copyOf(rules);
    }

    List<PdfRect> rects() {
        return List.copyOf(rects);
    }

    List<PdfFormGraph.Image> images() {
        return List.copyOf(images);
    }

    /** How many points of paths not yet painted are held; for tests of the limit. */
    int pendingPoints() {
        int points = 4 * pathRectangles.size();
        for (List<Point2D> subpathPoints : subpaths) {
            points += subpathPoints.size();
        }
        return points;
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        budget.startPage();
        Iterator<PDStream> contents = page.getContentStreams();
        while (contents.hasNext()) {
            budget.charge(contents.next().getCOSObject());
        }
        budget.countValuesBeforeOperators(page, page.getCOSObject());
        super.processPage(page);
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
        budget.charge(form.getCOSObject());
        budget.countValuesBeforeOperators(form, form.getCOSObject());
        super.showForm(form);
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
        budget.charge(form.getCOSObject());
        budget.countValuesBeforeOperators(form, form.getCOSObject());
        super.showTransparencyGroup(form);
    }

    @Override
    public void saveGraphicsState() {
        budget.requireRoomToSaveState(getGraphicsStackSize());
        super.saveGraphicsState();
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        budget.countOperator();
        String name = operator.getName();
        if ("Do".equals(name)) {
            if (!recordedXObjectImage(operands)) {
                super.processOperator(operator, operands);
            }
        } else if ("BI".equals(name)) {
            COSDictionary parameters = operator.getImageParameters();
            recordImage(parameters == null ? null : firstPresent(parameters, COSName.FILTER, COSName.F));
        } else if (PATH_AND_STATE_OPERATORS.contains(name)) {
            super.processOperator(operator, operands);
        }
    }

    /** A picture drawn by name is recorded from its dictionary; a form is left to the library, which draws it through {@link #showForm}. */
    private boolean recordedXObjectImage(List<COSBase> operands) {
        if (operands.isEmpty() || !(operands.get(0) instanceof COSName xobjectName)) {
            return true;
        }
        PDResources resources = getResources();
        COSDictionary xobjects = resources == null ? null : resources.getCOSObject().getCOSDictionary(COSName.XOBJECT);
        COSBase xobject = xobjects == null ? null : xobjects.getDictionaryObject(xobjectName);
        if (!(xobject instanceof COSStream stream)) {
            return true;
        }
        if (COSName.IMAGE.equals(stream.getCOSName(COSName.SUBTYPE))) {
            recordImage(stream.getFilters());
            return true;
        }
        return !COSName.FORM.equals(stream.getCOSName(COSName.SUBTYPE));
    }

    private void recordImage(COSBase declaredFilters) {
        if (images.size() == MAX_IMAGES_PER_PAGE) {
            return;
        }
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
        double[][] corners = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] corner : corners) {
            Point2D.Float point = ctm.transformPoint((float) corner[0], (float) corner[1]);
            minX = Math.min(minX, point.x);
            maxX = Math.max(maxX, point.x);
            minY = Math.min(minY, point.y);
            maxY = Math.max(maxY, point.y);
        }
        images.add(new PdfFormGraph.Image(boxFromUserSpace(minX, minY, maxX, maxY), filterNames(declaredFilters)));
    }

    private static List<String> filterNames(COSBase declared) {
        List<String> names = new ArrayList<>();
        COSBase value = declared instanceof COSObject reference ? reference.getObject() : declared;
        if (value instanceof COSName name) {
            names.add(ABBREVIATED_FILTERS.getOrDefault(name.getName(), name.getName()));
        } else if (value instanceof COSArray array) {
            for (COSBase element : array) {
                COSBase item = element instanceof COSObject reference ? reference.getObject() : element;
                if (item instanceof COSName name) {
                    names.add(ABBREVIATED_FILTERS.getOrDefault(name.getName(), name.getName()));
                }
            }
        }
        return names;
    }

    private static COSBase firstPresent(COSDictionary dictionary, COSName first, COSName second) {
        COSBase value = dictionary.getDictionaryObject(first);
        return value != null ? value : dictionary.getDictionaryObject(second);
    }

    // ---- path construction: every point arrives already in the page's user space ----

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        segments += 4;
        if (keepingPoints()) {
            pathRectangles.add(new Point2D[] {p0, p1, p2, p3});
        }
        subpath = null;
        current = p0;
    }

    @Override
    public void moveTo(float x, float y) {
        segments++;
        Point2D start = new Point2D.Float(x, y);
        if (keepingPoints()) {
            startSubpath(start);
        }
        current = start;
    }

    @Override
    public void lineTo(float x, float y) {
        segments++;
        Point2D end = new Point2D.Float(x, y);
        if (keepingPoints()) {
            if (subpath == null) {
                startSubpath(current == null ? end : current);
            }
            subpath.add(end);
        }
        current = end;
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        segments++;
        Point2D end = new Point2D.Float(x3, y3);
        if (keepingPoints()) {
            if (subpath == null) {
                startSubpath(current == null ? end : current);
            }
            curved.set(curved.size() - 1, true);
            subpath.add(end);
        }
        current = end;
    }

    @Override
    public Point2D getCurrentPoint() {
        return current;
    }

    @Override
    public void closePath() {
        if (subpath != null && !subpath.isEmpty()) {
            closed.set(closed.size() - 1, true);
            current = subpath.get(0);
            subpath = null;
        }
    }

    @Override
    public void endPath() {
        clearPath();
    }

    @Override
    public void strokePath() {
        commitPath();
    }

    @Override
    public void fillPath(int windingRule) {
        commitPath();
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        commitPath();
    }

    @Override
    public void clip(int windingRule) {
        // The clipping path is still the current path; the painting operator or "n" that follows decides what it is.
    }

    @Override
    public void drawImage(PDImage pdImage) {
        // Pictures are recorded from their dictionaries in processOperator and never reach the library's drawing.
    }

    @Override
    public void shadingFill(COSName shadingName) {
        // A shading paints colour, not a line or a box to write in.
    }

    /**
     * Whether the page is still under the segment limit. Past it nothing on
     * the page is recorded, so what is pending is dropped and nothing more is
     * kept; the current point is still followed, since the library asks for it.
     */
    private boolean keepingPoints() {
        if (segments <= MAX_PATH_SEGMENTS_PER_PAGE) {
            return true;
        }
        if (!subpaths.isEmpty() || !pathRectangles.isEmpty()) {
            subpaths.clear();
            closed.clear();
            curved.clear();
            pathRectangles.clear();
        }
        subpath = null;
        return false;
    }

    private void startSubpath(Point2D start) {
        subpath = new ArrayList<>();
        subpath.add(start);
        subpaths.add(subpath);
        closed.add(false);
        curved.add(false);
        current = start;
    }

    private void commitPath() {
        if (segments <= MAX_PATH_SEGMENTS_PER_PAGE) {
            for (Point2D[] rectangle : pathRectangles) {
                recordClosedShape(List.of(rectangle));
            }
            for (int index = 0; index < subpaths.size(); index++) {
                List<Point2D> points = subpaths.get(index);
                if (closed.get(index) && !curved.get(index) && recordClosedShape(points)) {
                    continue;
                }
                for (int point = 1; point < points.size(); point++) {
                    recordLine(points.get(point - 1), points.get(point));
                }
                if (closed.get(index) && points.size() > 2) {
                    recordLine(points.get(points.size() - 1), points.get(0));
                }
            }
        }
        clearPath();
    }

    private void clearPath() {
        subpaths.clear();
        closed.clear();
        curved.clear();
        pathRectangles.clear();
        subpath = null;
        current = null;
    }

    /** Four corners (a fifth repeating the first is allowed) of a rectangle square to the page: recorded as a box, or as a line if thin. */
    private boolean recordClosedShape(List<Point2D> points) {
        List<Point2D> corners = new ArrayList<>(points);
        if (corners.size() == 5 && corners.get(0).distance(corners.get(4)) <= AXIS_TOLERANCE) {
            corners.remove(4);
        }
        if (corners.size() != 4) {
            return false;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Point2D corner : corners) {
            minX = Math.min(minX, corner.getX());
            maxX = Math.max(maxX, corner.getX());
            minY = Math.min(minY, corner.getY());
            maxY = Math.max(maxY, corner.getY());
        }
        for (Point2D corner : corners) {
            boolean onVerticalEdge = near(corner.getX(), minX) || near(corner.getX(), maxX);
            boolean onHorizontalEdge = near(corner.getY(), minY) || near(corner.getY(), maxY);
            if (!onVerticalEdge || !onHorizontalEdge) {
                return false;
            }
        }
        double width = maxX - minX;
        double height = maxY - minY;
        if (height < THIN && width >= SHORTEST_KEPT) {
            double middle = (minY + maxY) / 2;
            addRule(minX, middle, maxX, middle);
        } else if (width < THIN && height >= SHORTEST_KEPT) {
            double middle = (minX + maxX) / 2;
            addRule(middle, minY, middle, maxY);
        } else if (width >= THIN && height >= THIN) {
            rects.add(boxFromUserSpace(minX, minY, maxX, maxY));
        }
        return true;
    }

    private void recordLine(Point2D from, Point2D to) {
        boolean horizontal = Math.abs(to.getY() - from.getY()) <= AXIS_TOLERANCE;
        boolean vertical = Math.abs(to.getX() - from.getX()) <= AXIS_TOLERANCE;
        if ((horizontal || vertical) && from.distance(to) >= SHORTEST_KEPT) {
            addRule(from.getX(), from.getY(), to.getX(), to.getY());
        }
    }

    private void addRule(double x0, double y0, double x1, double y1) {
        rules.add(new PdfFormGraph.Rule(pointFromUserSpace(x0, y0), pointFromUserSpace(x1, y1)));
    }

    private static boolean near(double value, double edge) {
        return Math.abs(value - edge) <= AXIS_TOLERANCE;
    }

    private PdfPoint pointFromUserSpace(double x, double y) {
        return new PdfPoint(x - crop.getLowerLeftX(), crop.getUpperRightY() - y);
    }

    private PdfRect boxFromUserSpace(double minX, double minY, double maxX, double maxY) {
        return new PdfRect(minX - crop.getLowerLeftX(), crop.getUpperRightY() - maxY, maxX - minX, maxY - minY);
    }
}
