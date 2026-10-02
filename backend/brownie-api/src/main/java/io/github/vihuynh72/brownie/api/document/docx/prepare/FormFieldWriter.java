package io.github.vihuynh72.brownie.api.document.docx.prepare;

import io.github.vihuynh72.brownie.api.document.docx.DocxNodeWalker;
import io.github.vihuynh72.brownie.core.document.FieldInstructionPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;

import javax.xml.namespace.QName;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes each of a form's own fields for a blank -- a form text box
 * ({@code FORMTEXT}), a drop-down ({@code FORMDROPDOWN}), a merge field, a
 * prompt ({@code FILLIN}, {@code ASK}) or a macro button -- out as the text
 * it shows, so that text becomes ordinary runs of its paragraph, part of
 * the anchor text a spot can be placed over. The field's own marks and
 * code go; the runs it showed stay exactly where they were, with their
 * formatting. A field that showed nothing leaves one empty run where it
 * was, so its place is still known; a macro button, which shows its own
 * code's words, leaves them as a run.
 *
 * <p>Only a field whose start, code, and end all sit directly in one
 * paragraph of the main body or a top-level table cell is written out:
 * the filler writes nowhere else, and a field that spans lines or sits
 * inside another is left as it is. The runs that now show each field's
 * text are returned, so its place can be found in the outline.
 */
final class FormFieldWriter {

    private static final QName FLD_CHAR_TYPE = WordXml.w("fldCharType");
    private static final QName INSTRUCTION = WordXml.w("instr");
    private static final QName VAL = WordXml.w("val");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern DEFAULT_FORM_NAME = Pattern.compile("(?i)(text|dropdown)\\d*");

    private FormFieldWriter() {
    }

    /** One field written out: which one (they are numbered in document order), its kind, and its own words for its blank. */
    record WrittenField(int number, String keyword, String words) {
    }

    /** Writes every such field out and returns, for each run now showing a field's text, the field it showed. */
    static Map<CTR, WrittenField> writeOut(XWPFDocument document) {
        Map<CTR, WrittenField> written = new IdentityHashMap<>();
        DocxNodeWalker.Part main = DocxNodeWalker.walk(document).getFirst();
        List<CTP> paragraphs = new ArrayList<>();
        for (DocxNodeWalker.Block block : main.blocks()) {
            switch (block) {
                case DocxNodeWalker.Paragraph paragraph -> paragraphs.add(paragraph.paragraph().getCTP());
                case DocxNodeWalker.Table table -> table.rows().forEach(row -> row.cells().forEach(cell ->
                        cell.paragraphs().forEach(paragraph -> paragraphs.add(paragraph.paragraph().getCTP()))));
                case DocxNodeWalker.OtherBlock ignored -> {
                    // No ids, and nothing the filler writes into.
                }
            }
        }
        for (CTP paragraph : paragraphs) {
            writeOutRunFields(paragraph, written);
            writeOutSimpleFields(paragraph, written);
        }
        return written;
    }

    // ---------------------------------------------------------------- fields written out in runs

    private static final class Open {
        final XmlObject begin;
        final List<XmlObject> marks = new ArrayList<>();
        final List<CTR> markRuns = new ArrayList<>();
        final List<CTR> result = new ArrayList<>();
        final StringBuilder instruction = new StringBuilder();
        boolean separated;
        boolean holdsAnother;

        Open(XmlObject begin, CTR run) {
            this.begin = begin;
            marks.add(begin);
            markRuns.add(run);
        }
    }

    private static void writeOutRunFields(CTP paragraph, Map<CTR, WrittenField> written) {
        Deque<Open> open = new ArrayDeque<>();
        List<Open> complete = new ArrayList<>();
        for (XmlObject child : WordXml.children(paragraph)) {
            if (!(child instanceof CTR run)) {
                if (!open.isEmpty() && !WordXml.isW(WordXml.nameOf(child), "bookmarkStart")
                        && !WordXml.isW(WordXml.nameOf(child), "bookmarkEnd") && !WordXml.isW(WordXml.nameOf(child), "proofErr")) {
                    open.forEach(field -> field.holdsAnother = true);
                }
                continue;
            }
            for (XmlObject part : WordXml.children(run)) {
                QName name = WordXml.nameOf(part);
                if (WordXml.isW(name, "fldChar")) {
                    String type = WordXml.attribute(part, FLD_CHAR_TYPE);
                    if ("begin".equals(type)) {
                        open.forEach(field -> field.holdsAnother = true);
                        open.push(new Open(part, run));
                    } else if ("separate".equals(type) && !open.isEmpty()) {
                        open.peek().separated = true;
                        open.peek().marks.add(part);
                        open.peek().markRuns.add(run);
                    } else if ("end".equals(type) && !open.isEmpty()) {
                        Open field = open.pop();
                        field.marks.add(part);
                        field.markRuns.add(run);
                        complete.add(field);
                    }
                } else if (WordXml.isW(name, "instrText")) {
                    if (!open.isEmpty() && !open.peek().separated) {
                        open.peek().instruction.append(textOf(part));
                        open.peek().marks.add(part);
                        open.peek().markRuns.add(run);
                    }
                } else if (!WordXml.isW(name, "rPr") && !open.isEmpty() && open.peek().separated) {
                    Open field = open.peek();
                    if (field.result.isEmpty() || field.result.getLast() != run) {
                        field.result.add(run);
                    }
                }
            }
        }
        for (Open field : complete) {
            String instruction = field.instruction.toString();
            if (field.holdsAnother || FieldInstructionPolicy.classify(instruction) != FieldInstructionPolicy.Treatment.TO_SPOT) {
                continue;
            }
            String keyword = FieldInstructionPolicy.keyword(instruction);
            WrittenField writtenField = new WrittenField(written.size() + 1, keyword, wordsOf(keyword, instruction, field.begin));
            List<CTR> shown = new ArrayList<>(field.result);
            shown.removeIf(field.markRuns::contains);
            if (shown.isEmpty()) {
                String display = "MACROBUTTON".equals(keyword) ? macroButtonText(instruction) : "";
                shown.add(runBefore(field.markRuns.getFirst(), display));
            }
            for (XmlObject mark : field.marks) {
                WordXml.remove(mark);
            }
            for (CTR run : field.markRuns) {
                if (!shown.contains(run) && WordXml.holdsNothingBut(run, name -> WordXml.isW(name, "rPr"))) {
                    WordXml.remove(run);
                }
            }
            shown.forEach(run -> written.put(run, writtenField));
        }
    }

    // ---------------------------------------------------------------- simple fields

    /**
     * A simple field is replaced by the runs it holds. Where they end up is
     * found by position: they take the field's own place among the
     * paragraph's children, in order.
     */
    private static void writeOutSimpleFields(CTP paragraph, Map<CTR, WrittenField> written) {
        List<XmlObject> fields = new ArrayList<>();
        for (XmlObject child : WordXml.children(paragraph)) {
            if (WordXml.isW(WordXml.nameOf(child), "fldSimple")
                    && FieldInstructionPolicy.classify(WordXml.attribute(child, INSTRUCTION)) == FieldInstructionPolicy.Treatment.TO_SPOT) {
                fields.add(child);
            }
        }
        for (XmlObject field : fields) {
            String instruction = WordXml.attribute(field, INSTRUCTION);
            String keyword = FieldInstructionPolicy.keyword(instruction);
            WrittenField writtenField = new WrittenField(written.size() + 1, keyword, wordsOf(keyword, instruction, null));
            boolean showsText = WordXml.children(field).stream()
                    .anyMatch(inner -> inner instanceof CTR run && !WordXml.holdsNothingBut(run, name -> WordXml.isW(name, "rPr")));
            if (!showsText) {
                String display = "MACROBUTTON".equals(keyword) ? macroButtonText(instruction) : "";
                CTR placeholder = newRun(display);
                try (XmlCursor to = field.newCursor(); XmlCursor from = placeholder.newCursor()) {
                    to.toEndToken();
                    from.moveXml(to);
                }
            }
            List<XmlObject> before = WordXml.children(paragraph);
            int index = indexOf(before, field);
            int kept = (int) WordXml.children(field).stream().filter(inner -> !WordXml.isW(WordXml.nameOf(inner), "fldData")).count();
            WordXml.unwrap(field, name -> WordXml.isW(name, "fldData"));
            List<XmlObject> after = WordXml.children(paragraph);
            for (XmlObject moved : after.subList(index, index + kept)) {
                if (moved instanceof CTR run) {
                    written.put(run, writtenField);
                }
            }
        }
    }

    private static int indexOf(List<XmlObject> children, XmlObject element) {
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) == element) {
                return i;
            }
        }
        throw new IllegalStateException("A field is not where it was just found.");
    }

    // ---------------------------------------------------------------- words and runs

    /**
     * What a field calls its blank: a merge field's name, a prompt's
     * question, a macro button's words, a form box's help or status text,
     * or its own name when Word did not make that one up ("Text1").
     */
    static String wordsOf(String keyword, String instruction, XmlObject beginMark) {
        String rest = instruction.strip();
        rest = rest.length() > keyword.length() ? rest.substring(rest.toUpperCase(Locale.ROOT).indexOf(keyword) + keyword.length()).strip() : "";
        return switch (keyword) {
            case "MERGEFIELD" -> firstToken(rest);
            case "FILLIN" -> firstQuoted(rest);
            case "ASK" -> firstQuoted(rest);
            case "MACROBUTTON" -> macroButtonText(instruction);
            case "FORMTEXT", "FORMDROPDOWN" -> formFieldWords(beginMark);
            default -> null;
        };
    }

    private static String formFieldWords(XmlObject beginMark) {
        if (beginMark == null) {
            return null;
        }
        XmlObject data = WordXml.firstChild(beginMark, "ffData");
        if (data == null) {
            return null;
        }
        for (String name : List.of("helpText", "statusText")) {
            XmlObject element = WordXml.firstChild(data, name);
            String value = element == null ? null : WordXml.attribute(element, VAL);
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        XmlObject name = WordXml.firstChild(data, "name");
        String value = name == null ? null : WordXml.attribute(name, VAL);
        return value == null || value.isBlank() || DEFAULT_FORM_NAME.matcher(value.strip()).matches() ? null : value.strip();
    }

    /** A macro button shows the words after its macro's name. */
    static String macroButtonText(String instruction) {
        String[] tokens = instruction.strip().split("\\s+", 3);
        return tokens.length < 3 ? "" : tokens[2].strip();
    }

    private static String firstToken(String rest) {
        if (rest.isEmpty()) {
            return null;
        }
        Matcher quoted = QUOTED.matcher(rest);
        if (rest.startsWith("\"") && quoted.find()) {
            return quoted.group(1);
        }
        return rest.split("\\s+", 2)[0];
    }

    private static String firstQuoted(String rest) {
        Matcher quoted = QUOTED.matcher(rest);
        if (quoted.find()) {
            return quoted.group(1);
        }
        String[] tokens = rest.split("\\s+");
        return tokens.length > 1 ? tokens[1] : null;
    }

    /** A run showing {@code text}, formatted like {@code neighbour}, placed right before it. */
    private static CTR runBefore(CTR neighbour, String text) {
        CTR run = newRun(text);
        if (neighbour.isSetRPr()) {
            run.setRPr(neighbour.getRPr());
        }
        try (XmlCursor to = neighbour.newCursor(); XmlCursor from = run.newCursor()) {
            from.moveXml(to);
            to.toPrevSibling();
            return (CTR) to.getObject();
        }
    }

    private static CTR newRun(String text) {
        CTP scratch = CTP.Factory.newInstance();
        CTR run = scratch.addNewR();
        CTText element = run.addNewT();
        element.setStringValue(text);
        if (!text.isEmpty() && (Character.isWhitespace(text.charAt(0)) || Character.isWhitespace(text.charAt(text.length() - 1)))) {
            try (XmlCursor cursor = element.newCursor()) {
                cursor.toNextToken();
                cursor.insertAttributeWithValue(new QName("http://www.w3.org/XML/1998/namespace", "space"), "preserve");
            }
        }
        return run;
    }

    private static String textOf(XmlObject element) {
        try (XmlCursor cursor = element.newCursor()) {
            return cursor.getTextValue();
        }
    }
}
