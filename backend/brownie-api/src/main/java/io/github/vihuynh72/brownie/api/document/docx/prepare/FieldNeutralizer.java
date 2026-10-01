package io.github.vihuynh72.brownie.api.document.docx.prepare;

import io.github.vihuynh72.brownie.core.document.FieldInstructionPolicy;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Freezes every field {@link FieldInstructionPolicy} does not let stay live:
 * the field is replaced by the text it shows. For a field written out in
 * runs, that means removing its start, its code, the mark between code and
 * result, and its end, and keeping the result's runs where they are; a
 * field with no result leaves nothing. A simple field is replaced by the
 * runs it holds. Fields that stay live are left exactly as they are,
 * including the form fields a later step turns into fill spots.
 *
 * <p>A field's code can be split over runs and paragraphs and can hold
 * other fields, so fields are followed across the whole part: every run
 * child is listed in document order, fields are matched from each start to
 * its end, and the children of a frozen field's code are marked; only then
 * is anything removed, so one field's removal never hides another's parts.
 */
final class FieldNeutralizer {

    private FieldNeutralizer() {
    }

    /** Returns how many fields were frozen. */
    static int freeze(WordPackage word) {
        int frozen = 0;
        for (PackagePart part : word.storyParts()) {
            frozen += freeze(word.xml(part));
        }
        return frozen;
    }

    private enum Kind {
        BEGIN, SEPARATE, END, CODE, OTHER
    }

    private record Entry(XmlObject run, XmlObject child, Kind kind) {
    }

    private static final class OpenField {
        private final int begin;
        private int separate = -1;
        private final StringBuilder instruction = new StringBuilder();

        private OpenField(int begin) {
            this.begin = begin;
        }
    }

    static int freeze(XmlObject root) {
        List<Entry> entries = new ArrayList<>();
        for (XmlObject run : WordXml.elements(root, name -> WordXml.isW(name, "r"))) {
            for (XmlObject child : WordXml.children(run)) {
                entries.add(new Entry(run, child, kindOf(child)));
            }
        }

        Set<Integer> marked = new LinkedHashSet<>();
        Deque<OpenField> open = new ArrayDeque<>();
        int frozen = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            switch (entry.kind()) {
                case BEGIN -> open.push(new OpenField(i));
                case CODE -> {
                    OpenField field = open.peek();
                    if (field != null && field.separate < 0) {
                        field.instruction.append(textOf(entry.child()));
                    }
                }
                case SEPARATE -> {
                    OpenField field = open.peek();
                    if (field != null && field.separate < 0) {
                        field.separate = i;
                    }
                }
                case END -> {
                    OpenField field = open.poll();
                    if (field != null
                            && FieldInstructionPolicy.classify(field.instruction.toString()) == FieldInstructionPolicy.Treatment.FREEZE) {
                        int codeEnd = field.separate >= 0 ? field.separate : i;
                        for (int j = field.begin; j <= codeEnd; j++) {
                            marked.add(j);
                        }
                        marked.add(i);
                        frozen++;
                    }
                }
                case OTHER -> {
                    // Text, tabs, pictures: code when inside a code, result otherwise; only the marks decide.
                }
            }
        }

        Set<XmlObject> touchedRuns = new LinkedHashSet<>();
        for (int index : marked) {
            Entry entry = entries.get(index);
            if (!WordXml.isW(WordXml.nameOf(entry.child()), "rPr")) {
                WordXml.remove(entry.child());
                touchedRuns.add(entry.run());
            }
        }
        for (XmlObject run : touchedRuns) {
            if (WordXml.holdsNothingBut(run, name -> WordXml.isW(name, "rPr"))) {
                WordXml.remove(run);
            }
        }
        return frozen + freezeSimpleFields(root);
    }

    private static Kind kindOf(XmlObject child) {
        try (XmlCursor cursor = child.newCursor()) {
            if (WordXml.isW(cursor.getName(), "instrText")) {
                return Kind.CODE;
            }
            if (!WordXml.isW(cursor.getName(), "fldChar")) {
                return Kind.OTHER;
            }
            String type = cursor.getAttributeText(WordXml.w("fldCharType"));
            if (type == null) {
                return Kind.OTHER;
            }
            return switch (type) {
                case "begin" -> Kind.BEGIN;
                case "separate" -> Kind.SEPARATE;
                case "end" -> Kind.END;
                default -> Kind.OTHER;
            };
        }
    }

    private static String textOf(XmlObject element) {
        try (XmlCursor cursor = element.newCursor()) {
            return cursor.getTextValue();
        }
    }

    /** A simple field to freeze is replaced by the runs it holds; its stored field data goes. */
    private static int freezeSimpleFields(XmlObject root) {
        List<XmlObject> fields = WordXml.elements(root, name -> WordXml.isW(name, "fldSimple"));
        int frozen = 0;
        for (int i = fields.size() - 1; i >= 0; i--) {
            XmlObject field = fields.get(i);
            String instruction = WordXml.attribute(field, WordXml.w("instr"));
            if (FieldInstructionPolicy.classify(instruction) == FieldInstructionPolicy.Treatment.FREEZE) {
                WordXml.unwrap(field, name -> WordXml.isW(name, "fldData"));
                frozen++;
            }
        }
        return frozen;
    }
}
