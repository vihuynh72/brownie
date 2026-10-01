package io.github.vihuynh72.brownie.api.document.docx.prepare;

import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.xmlbeans.XmlObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Leaves comments out of the copy: the comments themselves, the parts
 * newer Word versions keep beside them (their threads and ids, and the
 * people who wrote them), the relationships to all of those, and in the
 * text the marks where each comment starts and ends and the reference to
 * it. A run that held nothing but a reference goes with it.
 */
final class CommentRemover {

    private static final List<String> COMMENT_PARTS = List.of(
            "comments", "commentsExtended", "commentsIds", "commentsExtensible", "people");

    private static final Set<String> MARKS = Set.of("commentRangeStart", "commentRangeEnd", "commentReference");

    private CommentRemover() {
    }

    /** Returns how many comments were left out. */
    static int remove(WordPackage word) {
        int comments = 0;
        for (PackagePart part : word.relatedParts(word.main(), "comments")) {
            comments += WordXml.elements(word.xml(part), name -> WordXml.isW(name, "comment")).size();
        }
        for (String type : COMMENT_PARTS) {
            for (PackagePart part : word.relatedParts(word.main(), type)) {
                word.removePart(part, List.of());
            }
        }
        Set<String> referenced = new HashSet<>();
        for (PackagePart part : word.storyParts()) {
            removeMarks(word.xml(part), referenced);
        }
        return Math.max(comments, referenced.size());
    }

    static void removeMarks(XmlObject root, Set<String> referenced) {
        List<XmlObject> marks = WordXml.elements(root, name -> WordXml.W.equals(name.getNamespaceURI()) && MARKS.contains(name.getLocalPart()));
        for (int i = marks.size() - 1; i >= 0; i--) {
            XmlObject mark = marks.get(i);
            String id = WordXml.attribute(mark, WordXml.w("id"));
            if (id != null) {
                referenced.add(id);
            }
            XmlObject run = WordXml.parent(mark);
            WordXml.remove(mark);
            if (run != null && WordXml.isW(WordXml.nameOf(run), "r") && WordXml.holdsNothingBut(run, name -> WordXml.isW(name, "rPr"))) {
                WordXml.remove(run);
            }
        }
    }
}
