package io.github.vihuynh72.brownie.api.document.docx.prepare;

import io.github.vihuynh72.brownie.api.document.docx.EmbeddedObjects;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps an embedded object only when {@link EmbeddedObjects} allows it,
 * by its program's name and by the file it embeds. Any other -- a packaged
 * file, an old equation, an ActiveX control, a link, a file that is not
 * what its object says it is -- is turned into the picture Word shows for
 * it: the object's preview shape stays as an ordinary picture, and the
 * object itself, its embedded file and its relationships go. An object
 * with no preview is removed. A control left outside any object goes too.
 */
final class EmbeddedObjectReducer {

    private static final QName OLE_MARK = new QName(WordXml.OFFICE, "ole");

    private EmbeddedObjectReducer() {
    }

    /** Returns how many objects were turned into pictures or removed. */
    static int reduce(WordPackage word) {
        int reduced = 0;
        for (PackagePart part : word.storyParts()) {
            PackagePart current = word.part(part.getPartName());
            XmlObject root = word.xml(current);
            List<String> droppedRelationships = new ArrayList<>();
            List<XmlObject> objects = WordXml.elements(root, name -> WordXml.isW(name, "object") || WordXml.isW(name, "control"));
            for (int i = objects.size() - 1; i >= 0; i--) {
                XmlObject object = objects.get(i);
                if (WordXml.isW(WordXml.nameOf(object), "control")) {
                    if (WordXml.ancestor(object, "object") == null) {
                        String id = WordXml.attribute(object, new QName(WordXml.R, "id"));
                        if (id != null) {
                            droppedRelationships.add(id);
                        }
                        WordXml.remove(object);
                    }
                    continue;
                }
                EmbeddedObjects.Description description;
                try (XmlCursor cursor = object.newCursor()) {
                    description = EmbeddedObjects.describe(cursor);
                }
                if (EmbeddedObjects.allowedIn(current, description)) {
                    continue;
                }
                droppedRelationships.addAll(description.relationshipIds());
                toPicture(object);
                reduced++;
            }
            dropRelationships(word, current, droppedRelationships);
        }
        return reduced;
    }

    /**
     * Keeps the object's shapes (its preview) and nothing else, as a
     * picture ({@code w:pict}) in the same run; with no picture in its
     * shapes, the object is removed.
     */
    private static void toPicture(XmlObject object) {
        boolean hasPreview = !WordXml.elements(object, name -> WordXml.VML.equals(name.getNamespaceURI())
                && "imagedata".equals(name.getLocalPart())).isEmpty();
        if (!hasPreview) {
            WordXml.remove(object);
            return;
        }
        for (XmlObject child : WordXml.children(object)) {
            if (!WordXml.VML.equals(WordXml.nameOf(child).getNamespaceURI())) {
                WordXml.remove(child);
            }
        }
        for (XmlObject shape : WordXml.elements(object, name -> WordXml.VML.equals(name.getNamespaceURI()))) {
            try (XmlCursor cursor = shape.newCursor()) {
                cursor.removeAttribute(OLE_MARK);
            }
        }
        try (XmlCursor cursor = object.newCursor()) {
            List<QName> attributes = new ArrayList<>();
            if (cursor.toFirstAttribute()) {
                do {
                    attributes.add(cursor.getName());
                } while (cursor.toNextAttribute());
                cursor.toParent();
            }
            for (QName attribute : attributes) {
                cursor.removeAttribute(attribute);
            }
            cursor.setName(WordXml.w("pict"));
        }
    }

    /** Removes the relationships the dropped objects used, and the embedded files and control parts behind them. */
    private static void dropRelationships(WordPackage word, PackagePart source, List<String> ids) {
        for (String id : ids) {
            PackagePart current = word.part(source.getPartName());
            PackageRelationship relationship = current == null ? null : current.getRelationship(id);
            if (relationship == null) {
                continue;
            }
            if (relationship.getTargetMode() != TargetMode.EXTERNAL) {
                PackagePart target = word.related(current, relationship);
                if (target != null && !target.getPartName().equals(source.getPartName())) {
                    word.removePart(target, List.of("activeXControlBinary"));
                    continue;
                }
            }
            current.removeRelationship(id);
        }
    }
}
