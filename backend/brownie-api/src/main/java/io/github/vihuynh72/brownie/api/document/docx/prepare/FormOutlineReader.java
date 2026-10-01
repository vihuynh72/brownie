package io.github.vihuynh72.brownie.api.document.docx.prepare;

import io.github.vihuynh72.brownie.api.document.docx.DocxNodeLocator;
import io.github.vihuynh72.brownie.api.document.docx.DocxNodeWalker;
import io.github.vihuynh72.brownie.api.document.docx.RunText;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.FieldInstructionPolicy;
import io.github.vihuynh72.brownie.core.prepare.FormOutline;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTVMerge;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;

import javax.xml.namespace.QName;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a Word form into the {@link FormOutline} the finder searches: every
 * paragraph of the main body and its top-level table cells, walked by
 * {@link DocxNodeWalker} so each has its graph node id, and the paragraphs
 * of headers, footers, text boxes and tables inside tables, which the
 * filler cannot reach, so blanks there can be counted. Each run's text is
 * {@link RunText}'s, so a paragraph's anchor text here is exactly the
 * graph's.
 *
 * <p>Per run it records what the graph does not carry: whether it is
 * underlined or hidden (directly, by its character style or by its
 * paragraph's style), inside a link, or inside a field Word works out; per
 * tab, the line it draws to its stop; and where the form's own fields and
 * controls sit. The runs a form field was written out to ({@link
 * FormFieldWriter}) are named by their node ids.
 */
final class FormOutlineReader {

    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)(heading|title|subtitle)\\b.*");
    private static final QName FLD_CHAR_TYPE = WordXml.w("fldCharType");
    private static final QName VAL = WordXml.w("val");
    private static final QName LEADER = WordXml.w("leader");
    private static final QName POSITION = WordXml.w("pos");
    private static final String W14 = "http://schemas.microsoft.com/office/word/2010/wordml";
    private static final String MARKUP_COMPATIBILITY = "http://schemas.openxmlformats.org/markup-compatibility/2006";

    private final XWPFDocument document;
    private final XWPFStyles styles;
    private final Map<String, FormFieldWriter.WrittenField> writtenFields;
    private final List<FormOutline.Paragraph> paragraphs = new ArrayList<>();
    /** The fields open at this point of the part being read: a field can start in one paragraph and end in another. */
    private final Deque<OpenField> openFields = new ArrayDeque<>();

    private record OpenField(StringBuilder instruction, int beginOffset, String paragraphKey) {
    }

    private FormOutlineReader(XWPFDocument document, Map<String, FormFieldWriter.WrittenField> writtenFields) {
        this.document = document;
        this.styles = document.getStyles();
        this.writtenFields = writtenFields;
    }

    /**
     * {@code writtenFields} names, by the node id of each run in the main
     * document now showing a form field's text, the field it showed.
     */
    static FormOutline read(XWPFDocument document, Map<String, FormFieldWriter.WrittenField> writtenFields, String parserVersion) {
        FormOutlineReader reader = new FormOutlineReader(document, writtenFields);
        List<DocxNodeWalker.Part> parts = DocxNodeWalker.walk(document);
        for (DocxNodeWalker.Part part : parts) {
            reader.readPart(part);
        }
        reader.readTextBoxes();
        return new FormOutline(parserVersion, reader.paragraphs);
    }

    private void readPart(DocxNodeWalker.Part part) {
        openFields.clear();
        boolean main = part.kind() == DocumentPartKind.MAIN_DOCUMENT;
        FormOutline.Region paragraphRegion = main ? FormOutline.Region.BODY : FormOutline.Region.HEADER_FOOTER;
        int tableNumber = 0;
        for (DocxNodeWalker.Block block : part.blocks()) {
            switch (block) {
                case DocxNodeWalker.Paragraph paragraph -> addWalked(part, paragraph, paragraphRegion, null);
                case DocxNodeWalker.Table table -> {
                    tableNumber++;
                    int rowIndex = 0;
                    for (DocxNodeWalker.Row row : table.rows()) {
                        int columnIndex = 0;
                        for (DocxNodeWalker.Cell cell : row.cells()) {
                            FormOutline.Cell position = main
                                    ? new FormOutline.Cell(tableNumber, table.nodeId(), row.nodeId(), rowIndex, columnIndex, table.rows().size(),
                                            continuesMerge(cell.cell()))
                                    : null;
                            for (DocxNodeWalker.Paragraph paragraph : cell.paragraphs()) {
                                addWalked(part, paragraph, main ? FormOutline.Region.TOP_TABLE_CELL : paragraphRegion, position);
                            }
                            readNestedTables(part, cell.cell(), cell.nodeId());
                            columnIndex++;
                        }
                        rowIndex++;
                    }
                }
                case DocxNodeWalker.OtherBlock ignored -> {
                    // A block that is neither: its content has no ids, and the working copy has none of them.
                }
            }
        }
    }

    /** A {@code w:vMerge} without {@code w:val="restart"}: the cell continues the merged cell above it. */
    private static boolean continuesMerge(XWPFTableCell cell) {
        CTTcPr properties = cell.getCTTc().getTcPr();
        if (properties == null || !properties.isSetVMerge()) {
            return false;
        }
        CTVMerge merge = properties.getVMerge();
        return !merge.isSetVal() || merge.getVal() != STMerge.RESTART;
    }

    // ---------------------------------------------------------------- paragraphs with node ids

    private void addWalked(DocxNodeWalker.Part part, DocxNodeWalker.Paragraph walked, FormOutline.Region region, FormOutline.Cell cell) {
        String key = part.partName() + "#" + walked.nodeId();
        XWPFParagraph paragraph = walked.paragraph();
        List<String> leaders = tabLeaders(paragraph);
        List<FormOutline.Atom> atoms = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int offset = 0;
        int tabs = 0;
        FormOutline.FormField currentField = null;
        FormFieldWriter.WrittenField currentWritten = null;
        for (DocxNodeWalker.Inline inline : walked.inlines()) {
            switch (inline) {
                case DocxNodeWalker.Run run -> {
                    CTR ctr = run.run();
                    String shown = DocxNodeLocator.shownText(ctr);
                    int width = shown.codePointCount(0, shown.length());
                    boolean inField = followFields(ctr, offset, key, atoms);
                    if (ctr.sizeOfDrawingArray() > 0) {
                        atoms.add(new FormOutline.Drawing(offset));
                    }
                    FormFieldWriter.WrittenField written = part.kind() == DocumentPartKind.MAIN_DOCUMENT
                            ? writtenFields.get(run.nodeId())
                            : null;
                    if (written != null && written == currentWritten && currentField != null) {
                        atoms.remove(currentField);
                        currentField = new FormOutline.FormField(currentField.start(), offset + width, written.keyword(), written.words());
                        atoms.add(currentField);
                    } else if (written != null) {
                        currentField = new FormOutline.FormField(offset, offset + width, written.keyword(), written.words());
                        currentWritten = written;
                        atoms.add(currentField);
                    }
                    Look look = lookOf(ctr, paragraph);
                    if (width > 0) {
                        atoms.add(new FormOutline.Run(offset, offset + width, look.underlined(), look.hidden(),
                                run.container() == DocxNodeWalker.RunContainer.HYPERLINK, inField));
                        for (int i = 0; i < shown.length(); ) {
                            int codePoint = shown.codePointAt(i);
                            if (codePoint == '\t') {
                                atoms.add(new FormOutline.Tab(offset + shown.codePointCount(0, i), tabs < leaders.size() ? leaders.get(tabs) : null));
                                tabs++;
                            }
                            i += Character.charCount(codePoint);
                        }
                    }
                    text.append(shown);
                    offset += width;
                }
                case DocxNodeWalker.Control control -> atoms.add(controlAtom(control, offset));
            }
        }
        paragraphs.add(new FormOutline.Paragraph(key, part.kind(), region, walked.nodeId(), text.toString(), styleName(paragraph),
                isHeading(paragraph), cell, atoms));
    }

    private FormOutline.Control controlAtom(DocxNodeWalker.Control control, int offset) {
        CTSdtRun sdt = control.sdt();
        String tag = DocxNodeLocator.tagOf(sdt);
        String alias = sdt.isSetSdtPr() && sdt.getSdtPr().isSetAlias() ? sdt.getSdtPr().getAlias().getVal() : null;
        StringBuilder shown = new StringBuilder();
        for (DocxNodeWalker.Run run : control.runs()) {
            shown.append(DocxNodeLocator.shownText(run.run()));
        }
        return new FormOutline.Control(offset, control.nodeId(), tag, alias, shown.toString(), controlKind(sdt));
    }

    private static FormOutline.ControlKind controlKind(CTSdtRun sdt) {
        if (!sdt.isSetSdtPr()) {
            return FormOutline.ControlKind.TEXT;
        }
        for (XmlObject property : WordXml.children(sdt.getSdtPr())) {
            QName name = WordXml.nameOf(property);
            if (W14.equals(name.getNamespaceURI()) && "checkbox".equals(name.getLocalPart())) {
                return FormOutline.ControlKind.CHECKBOX;
            }
            if (WordXml.isW(name, "picture")) {
                return FormOutline.ControlKind.PICTURE;
            }
            if (WordXml.isW(name, "date")) {
                return FormOutline.ControlKind.DATE;
            }
        }
        return FormOutline.ControlKind.TEXT;
    }

    /**
     * Follows field starts, codes and ends through one run, and says
     * whether the run's text is shown by a field. A form checkbox field is
     * recorded where it starts once its code says what it is.
     */
    private boolean followFields(CTR run, int offset, String paragraphKey, List<FormOutline.Atom> atoms) {
        boolean shownByField = false;
        for (XmlObject child : WordXml.children(run)) {
            QName name = WordXml.nameOf(child);
            if (WordXml.isW(name, "fldChar")) {
                String type = WordXml.attribute(child, FLD_CHAR_TYPE);
                if ("begin".equals(type)) {
                    openFields.push(new OpenField(new StringBuilder(), offset, paragraphKey));
                } else if ("end".equals(type) && !openFields.isEmpty()) {
                    OpenField field = openFields.pop();
                    if (FieldInstructionPolicy.classify(field.instruction().toString()) == FieldInstructionPolicy.Treatment.CHECKBOX) {
                        atoms.add(new FormOutline.CheckboxField(field.paragraphKey().equals(paragraphKey) ? field.beginOffset() : 0));
                    }
                }
            } else if (WordXml.isW(name, "instrText")) {
                if (!openFields.isEmpty()) {
                    openFields.peek().instruction().append(textOf(child));
                }
            } else if (!WordXml.isW(name, "rPr") && !openFields.isEmpty()) {
                shownByField = true;
            }
        }
        return shownByField || !openFields.isEmpty();
    }

    // ---------------------------------------------------------------- paragraphs the filler cannot reach

    private void readNestedTables(DocxNodeWalker.Part part, XWPFTableCell cell, String cellNodeId) {
        int nested = 0;
        for (XWPFTable table : cell.getTables()) {
            String prefix = part.partName() + "#" + cellNodeId + "/nested" + nested++;
            int rowIndex = 0;
            for (XWPFTableRow row : table.getRows()) {
                int columnIndex = 0;
                for (XWPFTableCell inner : row.getTableCells()) {
                    int index = 0;
                    for (XWPFParagraph paragraph : inner.getParagraphs()) {
                        addUnwalked(prefix + "/row" + rowIndex + "/cell" + columnIndex + "/p" + index++, part.kind(),
                                FormOutline.Region.NESTED_TABLE, paragraph.getCTP(), paragraph);
                    }
                    readNestedTables(part, inner, cellNodeId + "/nested" + (nested - 1) + "/row" + rowIndex + "/cell" + columnIndex);
                    columnIndex++;
                }
                rowIndex++;
            }
        }
    }

    private void readTextBoxes() {
        readTextBoxes(DocumentPartKind.MAIN_DOCUMENT, "word/document.xml", document.getDocument());
        for (XWPFHeader header : document.getHeaderList()) {
            readTextBoxes(DocumentPartKind.HEADER, DocxNodeWalker.partName(header.getPackagePart()), header._getHdrFtr());
        }
        for (XWPFFooter footer : document.getFooterList()) {
            readTextBoxes(DocumentPartKind.FOOTER, DocxNodeWalker.partName(footer.getPackagePart()), footer._getHdrFtr());
        }
    }

    private void readTextBoxes(DocumentPartKind part, String partName, XmlObject root) {
        int box = 0;
        for (XmlObject content : WordXml.elements(root, name -> WordXml.isW(name, "txbxContent"))) {
            if (isFallback(content)) {
                continue;
            }
            int index = 0;
            for (XmlObject paragraph : WordXml.elements(content, name -> WordXml.isW(name, "p"))) {
                if (paragraph instanceof CTP ctp) {
                    addUnwalked(partName + "#textbox" + box + "/p" + index++, part, FormOutline.Region.TEXT_BOX, ctp, null);
                }
            }
            box++;
        }
    }

    /** A text box's older copy, kept for programs that cannot read the newer one: the same text a second time. */
    private static boolean isFallback(XmlObject element) {
        try (var cursor = element.newCursor()) {
            while (cursor.toParent() && cursor.currentTokenType().isStart()) {
                QName name = cursor.getName();
                if (MARKUP_COMPATIBILITY.equals(name.getNamespaceURI()) && "Fallback".equals(name.getLocalPart())) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A paragraph the walk gives no id: its runs' text, read the same way, with none of it a place to fill. */
    private void addUnwalked(String key, DocumentPartKind part, FormOutline.Region region, CTP paragraph, XWPFParagraph xwpf) {
        List<FormOutline.Atom> atoms = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int offset = 0;
        for (XmlObject child : WordXml.children(paragraph)) {
            List<CTR> runs = new ArrayList<>();
            if (child instanceof CTR run) {
                runs.add(run);
            } else if (WordXml.isW(WordXml.nameOf(child), "hyperlink")) {
                for (XmlObject inner : WordXml.children(child)) {
                    if (inner instanceof CTR run) {
                        runs.add(run);
                    }
                }
            }
            for (CTR run : runs) {
                String shown = DocxNodeLocator.shownText(run);
                int width = shown.codePointCount(0, shown.length());
                if (width > 0) {
                    Look look = xwpf == null ? directLook(run) : lookOf(run, xwpf);
                    atoms.add(new FormOutline.Run(offset, offset + width, look.underlined(), look.hidden(), false, false));
                }
                text.append(shown);
                offset += width;
            }
        }
        paragraphs.add(new FormOutline.Paragraph(key, part, region, null, text.toString(), null, false, null, atoms));
    }

    // ---------------------------------------------------------------- how a run looks

    private record Look(boolean underlined, boolean hidden) {
    }

    /** A run's underline and hidden state: its own properties first, then its character style, then its paragraph's style. */
    private Look lookOf(CTR run, XWPFParagraph paragraph) {
        List<XmlObject> chain = new ArrayList<>();
        XmlObject direct = run.isSetRPr() ? run.getRPr() : null;
        if (direct != null) {
            chain.add(direct);
            XmlObject characterStyle = WordXml.firstChild(direct, "rStyle");
            if (characterStyle != null) {
                addStyleRunProperties(WordXml.attribute(characterStyle, VAL), chain);
            }
        }
        addStyleRunProperties(paragraph.getStyleID(), chain);
        Boolean underlined = null;
        Boolean hidden = null;
        for (XmlObject properties : chain) {
            if (underlined == null) {
                XmlObject underline = WordXml.firstChild(properties, "u");
                if (underline != null) {
                    String value = WordXml.attribute(underline, VAL);
                    underlined = value != null && !"none".equals(value);
                }
            }
            if (hidden == null) {
                XmlObject vanish = WordXml.firstChild(properties, "vanish");
                if (vanish != null) {
                    hidden = isOn(vanish);
                }
            }
        }
        return new Look(Boolean.TRUE.equals(underlined), Boolean.TRUE.equals(hidden));
    }

    private static Look directLook(CTR run) {
        if (!run.isSetRPr()) {
            return new Look(false, false);
        }
        XmlObject underline = WordXml.firstChild(run.getRPr(), "u");
        XmlObject vanish = WordXml.firstChild(run.getRPr(), "vanish");
        String value = underline == null ? null : WordXml.attribute(underline, VAL);
        return new Look(value != null && !"none".equals(value), vanish != null && isOn(vanish));
    }

    private static boolean isOn(XmlObject toggle) {
        String value = WordXml.attribute(toggle, VAL);
        return value == null || value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on");
    }

    private void addStyleRunProperties(String styleId, List<XmlObject> chain) {
        Set<String> visited = new HashSet<>();
        while (styleId != null && visited.add(styleId) && styles != null) {
            XWPFStyle style = styles.getStyle(styleId);
            if (style == null) {
                return;
            }
            if (style.getCTStyle().isSetRPr()) {
                chain.add(style.getCTStyle().getRPr());
            }
            styleId = style.getBasisStyleID();
        }
    }

    // ---------------------------------------------------------------- paragraph properties

    /**
     * The line each tab of the paragraph draws, in order: the paragraph's
     * own tab stops, or else its style's, sorted by position; a tab past
     * the last stop is a default stop, which draws nothing.
     */
    private List<String> tabLeaders(XWPFParagraph paragraph) {
        List<XmlObject> stops = tabStops(paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : null);
        Set<String> visited = new HashSet<>();
        String styleId = paragraph.getStyleID();
        while (stops.isEmpty() && styleId != null && visited.add(styleId) && styles != null) {
            XWPFStyle style = styles.getStyle(styleId);
            if (style == null) {
                break;
            }
            stops = tabStops(style.getCTStyle().isSetPPr() ? style.getCTStyle().getPPr() : null);
            styleId = style.getBasisStyleID();
        }
        List<XmlObject> sorted = new ArrayList<>(stops);
        sorted.sort(Comparator.comparingLong(FormOutlineReader::positionOf));
        List<String> leaders = new ArrayList<>();
        for (XmlObject stop : sorted) {
            String leader = WordXml.attribute(stop, LEADER);
            leaders.add(leader == null || "none".equals(leader) ? null : leader);
        }
        return leaders;
    }

    private static List<XmlObject> tabStops(XmlObject paragraphProperties) {
        List<XmlObject> stops = new ArrayList<>();
        XmlObject tabs = paragraphProperties == null ? null : WordXml.firstChild(paragraphProperties, "tabs");
        if (tabs == null) {
            return stops;
        }
        for (XmlObject stop : WordXml.children(tabs)) {
            if (WordXml.isW(WordXml.nameOf(stop), "tab") && !"clear".equals(WordXml.attribute(stop, VAL))) {
                stops.add(stop);
            }
        }
        return stops;
    }

    private static long positionOf(XmlObject stop) {
        try {
            return Long.parseLong(WordXml.attribute(stop, POSITION).strip());
        } catch (NumberFormatException | NullPointerException e) {
            return 0;
        }
    }

    private String styleName(XWPFParagraph paragraph) {
        String styleId = paragraph.getStyleID();
        if (styleId == null || styles == null) {
            return null;
        }
        XWPFStyle style = styles.getStyle(styleId);
        return style == null || style.getName() == null ? styleId : style.getName();
    }

    private boolean isHeading(XWPFParagraph paragraph) {
        String name = styleName(paragraph);
        if (name != null && HEADING_STYLE.matcher(name.strip()).matches()) {
            return true;
        }
        return paragraph.getCTP().isSetPPr() && WordXml.firstChild(paragraph.getCTP().getPPr(), "outlineLvl") != null;
    }

    private static String textOf(XmlObject element) {
        try (var cursor = element.newCursor()) {
            return cursor.getTextValue();
        }
    }
}
