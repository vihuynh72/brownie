package io.github.vihuynh72.brownie.api.document.docx.prepare;

import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.impl.values.XmlValueDisconnectedException;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Accepts every tracked change, the way Word's "Accept All" does, in the
 * body, headers, footers, footnotes and endnotes (text boxes included, as
 * they sit inside those). Inserted and moved-in content stays and loses its
 * marking; deleted and moved-away content goes. A deleted paragraph mark
 * joins its paragraph to the next one, which keeps its own paragraph
 * formatting. A deleted row or cell goes; an inserted one stays. Recorded
 * formatting changes keep the current formatting and drop the old. Change
 * tracking is switched off, so the copy does not record the filling as
 * changes.
 */
final class TrackedChangeAcceptor {

    /** Content that stays: the wrapper goes, its content moves up. */
    private static final Set<String> KEPT_CONTENT = Set.of("ins", "moveTo");
    /** Content that goes, wrapper and all. */
    private static final Set<String> REMOVED_CONTENT = Set.of("del", "moveFrom");
    /** Markers that only record a change and go on their own. */
    private static final Set<String> MARKERS = Set.of(
            "rPrChange", "pPrChange", "sectPrChange", "tblPrChange", "tblPrExChange", "trPrChange", "tcPrChange",
            "tblGridChange", "numberingChange", "cellIns", "cellMerge",
            "moveFromRangeStart", "moveFromRangeEnd", "moveToRangeStart", "moveToRangeEnd",
            "customXmlInsRangeStart", "customXmlInsRangeEnd", "customXmlDelRangeStart", "customXmlDelRangeEnd",
            "customXmlMoveFromRangeStart", "customXmlMoveFromRangeEnd", "customXmlMoveToRangeStart", "customXmlMoveToRangeEnd");
    /** Markers of the end of a range, which are bookkeeping and not changes of their own. */
    private static final Set<String> RANGE_ENDS = Set.of(
            "moveFromRangeEnd", "moveToRangeEnd", "customXmlInsRangeEnd", "customXmlDelRangeEnd",
            "customXmlMoveFromRangeEnd", "customXmlMoveToRangeEnd");

    private TrackedChangeAcceptor() {
    }

    /** Returns how many changes were accepted. */
    static int accept(WordPackage word) {
        int accepted = 0;
        for (PackagePart part : word.storyParts()) {
            accepted += accept(word.xml(part));
        }
        for (PackagePart settings : word.relatedParts(word.main(), "settings")) {
            for (XmlObject tracking : WordXml.elements(word.xml(settings), name -> WordXml.isW(name, "trackRevisions"))) {
                WordXml.remove(tracking);
            }
        }
        return accepted;
    }

    static int accept(XmlObject root) {
        List<XmlObject> changes = WordXml.elements(root, TrackedChangeAcceptor::isChange);
        List<XmlObject> joinedParagraphs = new ArrayList<>();
        List<XmlObject> shrunk = new ArrayList<>();
        int accepted = 0;
        for (int i = changes.size() - 1; i >= 0; i--) {
            XmlObject change = changes.get(i);
            String local;
            QName parent;
            try {
                local = WordXml.nameOf(change).getLocalPart();
                parent = WordXml.parentName(change);
            } catch (XmlValueDisconnectedException gone) {
                // Inside a row or cell already removed with its own deletion.
                continue;
            }
            if (!RANGE_ENDS.contains(local)) {
                accepted++;
            }
            boolean marker = parent != null && (WordXml.isW(parent, "rPr") || WordXml.isW(parent, "trPr") || WordXml.isW(parent, "tcPr"));
            if (KEPT_CONTENT.contains(local)) {
                if (marker) {
                    WordXml.remove(change);
                } else {
                    WordXml.unwrap(change, name -> false);
                }
            } else if (REMOVED_CONTENT.contains(local)) {
                if (!marker) {
                    WordXml.remove(change);
                } else if (WordXml.isW(parent, "trPr")) {
                    removeEnclosing(change, "tr", shrunk);
                } else {
                    XmlObject paragraph = paragraphMarkOwner(change);
                    WordXml.remove(change);
                    if (paragraph != null) {
                        joinedParagraphs.add(paragraph);
                    }
                }
            } else if ("cellDel".equals(local)) {
                removeEnclosing(change, "tc", shrunk);
            } else {
                WordXml.remove(change);
            }
        }
        // Collected last to first; joined first to last, so a run of deleted marks folds into the paragraph after them.
        for (int i = joinedParagraphs.size() - 1; i >= 0; i--) {
            joinWithNext(joinedParagraphs.get(i));
        }
        removeEmptied(shrunk);
        return accepted;
    }

    private static boolean isChange(QName name) {
        if (!WordXml.W.equals(name.getNamespaceURI())) {
            return false;
        }
        String local = name.getLocalPart();
        return KEPT_CONTENT.contains(local) || REMOVED_CONTENT.contains(local) || MARKERS.contains(local) || "cellDel".equals(local);
    }

    /** The paragraph whose mark this is, when the marker sits in the paragraph's own properties ({@code w:p/w:pPr/w:rPr}). */
    private static XmlObject paragraphMarkOwner(XmlObject marker) {
        XmlObject runProperties = WordXml.parent(marker);
        XmlObject paragraphProperties = runProperties == null ? null : WordXml.parent(runProperties);
        if (paragraphProperties == null || !WordXml.isW(WordXml.nameOf(paragraphProperties), "pPr")) {
            return null;
        }
        XmlObject paragraph = WordXml.parent(paragraphProperties);
        return paragraph != null && WordXml.isW(WordXml.nameOf(paragraph), "p") ? paragraph : null;
    }

    /** Removes the row or cell the marker belongs to, noting the table or row it came out of. */
    private static void removeEnclosing(XmlObject marker, String localName, List<XmlObject> shrunk) {
        XmlObject enclosing = WordXml.ancestor(marker, localName);
        if (enclosing == null) {
            WordXml.remove(marker);
            return;
        }
        XmlObject container = WordXml.ancestor(enclosing, "tr".equals(localName) ? "tbl" : "tr");
        if (container != null) {
            shrunk.add(container);
        }
        WordXml.remove(enclosing);
    }

    /**
     * Moves the paragraph's content to the start of the next paragraph and
     * removes it. With no paragraph right after it (a table, the end of a
     * cell), there is nothing to join, and the paragraph stays as it is.
     */
    private static void joinWithNext(XmlObject paragraph) {
        XmlObject next;
        try (XmlCursor cursor = paragraph.newCursor()) {
            if (!cursor.toNextSibling() || !WordXml.isW(cursor.getName(), "p")) {
                return;
            }
            next = cursor.getObject();
        } catch (XmlValueDisconnectedException gone) {
            return;
        }
        XmlObject anchor = null;
        for (XmlObject child : WordXml.children(next)) {
            if (!WordXml.isW(WordXml.nameOf(child), "pPr")) {
                anchor = child;
                break;
            }
        }
        for (XmlObject child : WordXml.children(paragraph)) {
            if (WordXml.isW(WordXml.nameOf(child), "pPr")) {
                continue;
            }
            if (anchor != null) {
                WordXml.moveBefore(child, anchor);
            } else {
                try (XmlCursor source = child.newCursor(); XmlCursor end = next.newCursor()) {
                    end.toEndToken();
                    source.moveXml(end);
                }
            }
        }
        WordXml.remove(paragraph);
    }

    /**
     * A table must keep a row and a row a cell. A row left with no cell by
     * a deleted cell goes, and a table left with no row by a deleted row
     * goes; rows still wrapped in a content control count as rows.
     */
    private static void removeEmptied(List<XmlObject> shrunk) {
        List<XmlObject> tables = new ArrayList<>();
        for (XmlObject container : shrunk) {
            try {
                boolean row = WordXml.isW(WordXml.nameOf(container), "tr");
                if (row && WordXml.elements(container, name -> WordXml.isW(name, "tc")).isEmpty()) {
                    XmlObject table = WordXml.ancestor(container, "tbl");
                    WordXml.remove(container);
                    if (table != null) {
                        tables.add(table);
                    }
                } else if (!row) {
                    tables.add(container);
                }
            } catch (XmlValueDisconnectedException gone) {
                // Already removed with something around it.
            }
        }
        for (XmlObject table : tables) {
            try {
                if (WordXml.elements(table, name -> WordXml.isW(name, "tr")).isEmpty()) {
                    WordXml.remove(table);
                }
            } catch (XmlValueDisconnectedException gone) {
                // Already removed.
            }
        }
    }
}
