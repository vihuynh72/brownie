package io.github.vihuynh72.brownie.api.document.docx;

import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.List;

/**
 * A run's children as pieces of its shown text, counted in code points
 * exactly as {@link RunText} counts them, and the split of one run into
 * two at a point in that text. A split keeps every child in document
 * order: text, tabs and breaks before the point stay in the run, the rest
 * go to a copy of it placed right after, and a text element the point
 * falls inside is divided between them. Both halves keep the run's own
 * formatting, and a half whose text now starts or ends with a space keeps
 * that space ({@code xml:space="preserve"}).
 */
final class RunPieces {

    private static final QName FONT = new QName(RunText.W, "font");
    private static final QName CHAR = new QName(RunText.W, "char");
    private static final QName SPACE = new QName("http://www.w3.org/XML/1998/namespace", "space");

    private RunPieces() {
    }

    /** One child of a run and where its shown text sits in the run's text; properties are not pieces. */
    record Piece(XmlObject element, String localName, int start, int end) {

        int width() {
            return end - start;
        }
    }

    static List<Piece> of(CTR run) {
        List<Piece> pieces = new ArrayList<>();
        int offset = 0;
        try (XmlCursor cursor = run.newCursor()) {
            if (!cursor.toFirstChild()) {
                return pieces;
            }
            do {
                QName name = cursor.getName();
                if (RunText.W.equals(name.getNamespaceURI()) && "rPr".equals(name.getLocalPart())) {
                    continue;
                }
                int width = width(cursor, name);
                pieces.add(new Piece(cursor.getObject(), RunText.W.equals(name.getNamespaceURI()) ? name.getLocalPart() : "",
                        offset, offset + width));
                offset += width;
            } while (cursor.toNextSibling());
        }
        return pieces;
    }

    /** How many code points of shown text the child under the cursor adds, as {@link RunText#of} reads it. */
    private static int width(XmlCursor cursor, QName name) {
        if (!RunText.W.equals(name.getNamespaceURI())) {
            return 0;
        }
        return switch (name.getLocalPart()) {
            case "t" -> {
                String text = cursor.getTextValue();
                yield text.codePointCount(0, text.length());
            }
            case "tab", "ptab", "br", "cr", "noBreakHyphen" -> 1;
            case "sym" -> {
                String symbol = RunText.symbol(cursor.getAttributeText(FONT), cursor.getAttributeText(CHAR));
                yield symbol.codePointCount(0, symbol.length());
            }
            default -> 0;
        };
    }

    /**
     * Splits {@code run} at {@code point} code points into its text, which
     * must fall strictly inside it, and returns the new run holding the
     * text from there on, placed right after {@code run}.
     */
    static CTR split(CTR run, int point) {
        CTR right;
        try (XmlCursor source = run.newCursor(); XmlCursor destination = run.newCursor()) {
            destination.toEndToken();
            destination.toNextToken();
            source.copyXml(destination);
            destination.toPrevSibling();
            right = (CTR) destination.getObject();
        }
        keep(run, point, true);
        keep(right, point, false);
        return right;
    }

    /** Leaves in {@code run} only the pieces before ({@code left}) or from ({@code !left}) the point. */
    private static void keep(CTR run, int point, boolean left) {
        for (Piece piece : of(run)) {
            boolean before = piece.width() == 0 ? piece.start() <= point : piece.end() <= point;
            boolean after = piece.width() == 0 ? piece.start() > point : piece.start() >= point;
            if (before || after) {
                if (left ? after : before) {
                    remove(piece.element());
                }
                continue;
            }
            // Only a text element is wider than one character, so only it can hold the point.
            try (XmlCursor cursor = piece.element().newCursor()) {
                String text = cursor.getTextValue();
                int cut = text.offsetByCodePoints(0, point - piece.start());
                setText(piece.element(), left ? text.substring(0, cut) : text.substring(cut));
            }
        }
    }

    /** Sets a text element's text, keeping its spaces at either end. */
    static void setText(XmlObject textElement, String text) {
        try (XmlCursor cursor = textElement.newCursor()) {
            cursor.setTextValue(text);
        }
        if (!text.isEmpty() && (Character.isWhitespace(text.charAt(0)) || Character.isWhitespace(text.charAt(text.length() - 1)))) {
            preserveSpace(textElement);
        }
    }

    static void preserveSpace(XmlObject textElement) {
        try (XmlCursor cursor = textElement.newCursor()) {
            if (!"preserve".equals(cursor.getAttributeText(SPACE))) {
                cursor.setAttributeText(SPACE, "preserve");
            }
        }
    }

    static void remove(XmlObject element) {
        try (XmlCursor cursor = element.newCursor()) {
            cursor.removeXml();
        }
    }
}
