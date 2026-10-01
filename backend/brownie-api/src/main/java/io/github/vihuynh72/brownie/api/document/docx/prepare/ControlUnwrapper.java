package io.github.vihuynh72.brownie.api.document.docx.prepare;

import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;

import javax.xml.namespace.QName;
import java.util.List;
import java.util.Set;

/**
 * Takes away the wrappers that hide text from the reader and the filler:
 * smart tags and custom XML, at any level, and content controls around
 * whole paragraphs, tables, rows or cells. What they wrap stays where it
 * was. The only fill spots the filler writes into are content controls
 * inside a paragraph, so those are never touched, and neither is a
 * control that holds a building block (a table of contents, a cover page,
 * a page number).
 *
 * <p>A control around one paragraph that shows only its placeholder is a
 * blank the form asks to be filled: it becomes a control inside that
 * paragraph, with the same properties, so it stays a fill spot.
 */
final class ControlUnwrapper {

    private static final Set<String> BLOCK_CONTAINERS = Set.of(
            "body", "tc", "hdr", "ftr", "txbxContent", "footnote", "endnote", "comment", "docPartBody");

    private enum Level {
        RUN, BLOCK, ROW, CELL
    }

    private ControlUnwrapper() {
    }

    /** Returns how many wrappers were taken away or moved inside their paragraph. */
    static int unwrap(WordPackage word) {
        int unwrapped = 0;
        for (PackagePart part : word.storyParts()) {
            unwrapped += unwrap(word.xml(part));
        }
        return unwrapped;
    }

    static int unwrap(XmlObject root) {
        List<XmlObject> wrappers = WordXml.elements(root,
                name -> WordXml.isW(name, "sdt") || WordXml.isW(name, "smartTag") || WordXml.isW(name, "customXml"));
        int unwrapped = 0;
        for (int i = wrappers.size() - 1; i >= 0; i--) {
            XmlObject wrapper = wrappers.get(i);
            QName name = WordXml.nameOf(wrapper);
            if (WordXml.isW(name, "smartTag")) {
                WordXml.unwrap(wrapper, child -> WordXml.isW(child, "smartTagPr"));
                unwrapped++;
            } else if (WordXml.isW(name, "customXml")) {
                WordXml.unwrap(wrapper, child -> WordXml.isW(child, "customXmlPr"));
                unwrapped++;
            } else if (unwrapControl(wrapper)) {
                unwrapped++;
            }
        }
        return unwrapped;
    }

    private static boolean unwrapControl(XmlObject sdt) {
        XmlObject properties = WordXml.firstChild(sdt, "sdtPr");
        XmlObject content = WordXml.firstChild(sdt, "sdtContent");
        Level level = levelOf(sdt, content);
        if (level == Level.RUN || (properties != null && WordXml.firstChild(properties, "docPartObj") != null)) {
            return false;
        }
        if (level == Level.BLOCK && properties != null && WordXml.firstChild(properties, "showingPlcHdr") != null && content != null) {
            List<XmlObject> blocks = WordXml.children(content);
            if (blocks.size() == 1 && WordXml.isW(WordXml.nameOf(blocks.getFirst()), "p")) {
                moveIntoParagraph(sdt, properties, blocks.getFirst());
                return true;
            }
        }
        if (content != null) {
            WordXml.unwrap(content, name -> false);
        }
        WordXml.unwrap(sdt, name -> WordXml.isW(name, "sdtPr") || WordXml.isW(name, "sdtEndPr"));
        return true;
    }

    /** By what the control holds; an empty control by where it sits. */
    private static Level levelOf(XmlObject sdt, XmlObject content) {
        if (content != null) {
            for (XmlObject child : WordXml.children(content)) {
                QName name = WordXml.nameOf(child);
                if (WordXml.isW(name, "p") || WordXml.isW(name, "tbl")) {
                    return Level.BLOCK;
                }
                if (WordXml.isW(name, "tr")) {
                    return Level.ROW;
                }
                if (WordXml.isW(name, "tc")) {
                    return Level.CELL;
                }
            }
        }
        QName parent = WordXml.parentName(sdt);
        if (parent == null || !WordXml.W.equals(parent.getNamespaceURI())) {
            return Level.RUN;
        }
        if ("tbl".equals(parent.getLocalPart())) {
            return Level.ROW;
        }
        if ("tr".equals(parent.getLocalPart())) {
            return Level.CELL;
        }
        return BLOCK_CONTAINERS.contains(parent.getLocalPart()) ? Level.BLOCK : Level.RUN;
    }

    /**
     * Puts a new control inside the paragraph, after its properties, with
     * the block control's own properties and the paragraph's content as its
     * content; then the paragraph takes the block control's place.
     */
    private static void moveIntoParagraph(XmlObject blockSdt, XmlObject properties, XmlObject paragraph) {
        XmlObject endProperties = WordXml.firstChild(blockSdt, "sdtEndPr");
        XmlObject firstContent = null;
        for (XmlObject child : WordXml.children(paragraph)) {
            if (!WordXml.isW(WordXml.nameOf(child), "pPr")) {
                firstContent = child;
                break;
            }
        }
        try (XmlCursor inside = firstContent != null ? firstContent.newCursor() : paragraph.newCursor()) {
            if (firstContent == null) {
                inside.toEndToken();
            }
            inside.beginElement(WordXml.w("sdt"));
            try (XmlCursor move = properties.newCursor()) {
                move.moveXml(inside);
            }
            if (endProperties != null) {
                try (XmlCursor move = endProperties.newCursor()) {
                    move.moveXml(inside);
                }
            }
            inside.beginElement(WordXml.w("sdtContent"));
            try (XmlCursor newSdt = inside.newCursor()) {
                newSdt.toParent();
                newSdt.toParent();
                while (true) {
                    try (XmlCursor following = newSdt.newCursor()) {
                        if (!following.toNextSibling()) {
                            break;
                        }
                        following.moveXml(inside);
                    }
                }
            }
        }
        WordXml.moveBefore(paragraph, blockSdt);
        WordXml.remove(blockSdt);
    }
}
