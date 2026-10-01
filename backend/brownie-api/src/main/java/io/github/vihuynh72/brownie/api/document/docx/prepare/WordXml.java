package io.github.vihuynh72.brownie.api.document.docx.prepare;

import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The few XML moves the cleaning steps are made of, done with {@link
 * XmlCursor} and names only: the schema classes the library ships leave
 * out the types of several elements this code handles (a paragraph's
 * formatting change, a section's, a table's, and every Word 2010 and later
 * element), so nothing here asks for a typed object.
 *
 * <p>Steps collect the elements they change in document order and change
 * them from the last to the first. An element met later is never an
 * ancestor of one met earlier, so changing it cannot remove or move an
 * element still waiting its turn.
 */
final class WordXml {

    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String VML = "urn:schemas-microsoft-com:vml";
    static final String OFFICE = "urn:schemas-microsoft-com:office:office";

    private WordXml() {
    }

    static QName w(String localName) {
        return new QName(W, localName);
    }

    static boolean isW(QName name, String localName) {
        return W.equals(name.getNamespaceURI()) && localName.equals(name.getLocalPart());
    }

    /** Every element under {@code root} (itself included) whose name matches, in document order. */
    static List<XmlObject> elements(XmlObject root, Predicate<QName> matches) {
        List<XmlObject> found = new ArrayList<>();
        try (XmlCursor cursor = root.newCursor()) {
            int depth = 0;
            XmlCursor.TokenType token = cursor.currentTokenType();
            while (true) {
                if (token.isStart()) {
                    depth++;
                    if (matches.test(cursor.getName())) {
                        found.add(cursor.getObject());
                    }
                } else if (token.isEnd()) {
                    depth--;
                    if (depth <= 0) {
                        break;
                    }
                }
                token = cursor.toNextToken();
                if (token == XmlCursor.TokenType.NONE || token == XmlCursor.TokenType.ENDDOC) {
                    break;
                }
            }
        }
        return found;
    }

    /** The name of the element's parent, or null for a root. */
    static QName parentName(XmlObject element) {
        try (XmlCursor cursor = element.newCursor()) {
            return cursor.toParent() && cursor.currentTokenType().isStart() ? cursor.getName() : null;
        }
    }

    static XmlObject parent(XmlObject element) {
        try (XmlCursor cursor = element.newCursor()) {
            return cursor.toParent() && cursor.currentTokenType().isStart() ? cursor.getObject() : null;
        }
    }

    /** The nearest ancestor with this Word name, or null. */
    static XmlObject ancestor(XmlObject element, String localName) {
        try (XmlCursor cursor = element.newCursor()) {
            while (cursor.toParent() && cursor.currentTokenType().isStart()) {
                if (isW(cursor.getName(), localName)) {
                    return cursor.getObject();
                }
            }
            return null;
        }
    }

    /** The element's element children, in order. */
    static List<XmlObject> children(XmlObject element) {
        List<XmlObject> children = new ArrayList<>();
        try (XmlCursor cursor = element.newCursor()) {
            if (cursor.toFirstChild()) {
                do {
                    children.add(cursor.getObject());
                } while (cursor.toNextSibling());
            }
        }
        return children;
    }

    static QName nameOf(XmlObject element) {
        try (XmlCursor cursor = element.newCursor()) {
            return cursor.getName();
        }
    }

    static XmlObject firstChild(XmlObject element, String localName) {
        for (XmlObject child : children(element)) {
            if (isW(nameOf(child), localName)) {
                return child;
            }
        }
        return null;
    }

    static String attribute(XmlObject element, QName name) {
        try (XmlCursor cursor = element.newCursor()) {
            return cursor.getAttributeText(name);
        }
    }

    static void remove(XmlObject element) {
        try (XmlCursor cursor = element.newCursor()) {
            cursor.removeXml();
        }
    }

    /** Moves {@code element} to just before {@code before}. */
    static void moveBefore(XmlObject element, XmlObject before) {
        try (XmlCursor source = element.newCursor(); XmlCursor destination = before.newCursor()) {
            source.moveXml(destination);
        }
    }

    /**
     * Replaces the element by its children, in order, leaving out the
     * children {@code dropped} names (a wrapper's own properties).
     */
    static void unwrap(XmlObject element, Predicate<QName> dropped) {
        for (XmlObject child : children(element)) {
            if (dropped.test(nameOf(child))) {
                remove(child);
            } else {
                moveBefore(child, element);
            }
        }
        remove(element);
    }

    /** True when the element has no element child besides the ones {@code ignored} names. */
    static boolean holdsNothingBut(XmlObject element, Predicate<QName> ignored) {
        return children(element).stream().allMatch(child -> ignored.test(nameOf(child)));
    }
}
