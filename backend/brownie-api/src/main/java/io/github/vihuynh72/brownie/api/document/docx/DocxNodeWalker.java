package io.github.vihuynh72.brownie.api.document.docx;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHyperlink;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;

import java.util.ArrayList;
import java.util.List;

/**
 * The one walk that gives every paragraph, table, row, cell, run and
 * inline content control of a Word document its node id. Ids are stored --
 * rules and bindings name a node by its id, and a place on the page is sent
 * back as one -- so everything that turns an id back into a place in the
 * file walks the same way, through here.
 *
 * <p>The ids are index paths. A part's body elements share one counter:
 * {@code p0}, {@code tbl1}, and {@code body2} for anything else the library
 * lists there (a content control around whole paragraphs). A table's rows
 * are {@code tbl1/row0}, a row's cells {@code tbl1/row0/cell0}, a cell's
 * paragraphs {@code tbl1/row0/cell0/p0}. Inside a paragraph one counter runs
 * over its runs ({@code r0}), its inline content controls ({@code sdt1}),
 * and each run of a hyperlink, which is numbered as a run of the paragraph;
 * a control's own runs are {@code sdt1/r0}. Bookmarks, proofing marks,
 * smart tags, custom XML wrappers and simple fields are passed over and do
 * not move the counter, and neither does a row or cell wrapped in a content
 * control, or a table or control inside a cell: the library does not list
 * those, and the ids keep following what it lists.
 *
 * <p>Which elements count comes from the library's own lists (body
 * elements, rows, cells, paragraphs), exactly as the graph always has; only
 * a paragraph's own children are read from the XML, because the library's
 * list of a paragraph's runs leaves out an inline content control's runs.
 */
public final class DocxNodeWalker {

    /** What directly holds a run: the paragraph itself, a hyperlink in it, or an inline content control in it. */
    public enum RunContainer {
        PARAGRAPH,
        HYPERLINK,
        CONTROL
    }

    /** A part whose body carries node ids: the main document, a header, a footer. */
    public record Part(String partName, DocumentPartKind kind, List<Block> blocks) {
    }

    /** One element of a body or of a table cell. */
    public sealed interface Block permits Paragraph, Table, OtherBlock {
        String nodeId();
    }

    public record Paragraph(String nodeId, XWPFParagraph paragraph, List<Inline> inlines) implements Block {
    }

    public record Table(String nodeId, XWPFTable table, List<Row> rows) implements Block {
    }

    public record Row(String nodeId, XWPFTableRow row, List<Cell> cells) {
    }

    /** A cell's paragraphs are its blocks; a table or content control inside a cell is not given an id. */
    public record Cell(String nodeId, XWPFTableCell cell, List<Paragraph> paragraphs) {
    }

    /** A body element that is neither a paragraph nor a table (a content control around whole paragraphs); its content has no ids. */
    public record OtherBlock(String nodeId, IBodyElement element) implements Block {
    }

    /** One element of a paragraph that has an id. */
    public sealed interface Inline permits Run, Control {
        String nodeId();
    }

    public record Run(String nodeId, CTR run, RunContainer container) implements Inline {
    }

    /** An inline content control; its runs are the ones directly inside its content. */
    public record Control(String nodeId, CTSdtRun sdt, List<Run> runs) implements Inline {
    }

    private DocxNodeWalker() {
    }

    /** The main document, then every header, then every footer, in the order the library lists them. */
    public static List<Part> walk(XWPFDocument document) {
        List<Part> parts = new ArrayList<>();
        parts.add(new Part(partName(document.getPackagePart()), DocumentPartKind.MAIN_DOCUMENT, blocks(document.getBodyElements())));
        for (XWPFHeader header : document.getHeaderList()) {
            parts.add(new Part(partName(header.getPackagePart()), DocumentPartKind.HEADER, blocks(header.getBodyElements())));
        }
        for (XWPFFooter footer : document.getFooterList()) {
            parts.add(new Part(partName(footer.getPackagePart()), DocumentPartKind.FOOTER, blocks(footer.getBodyElements())));
        }
        return parts;
    }

    /** A part's name as the graph records it: the package part name without its leading slash. */
    public static String partName(PackagePart packagePart) {
        String name = packagePart.getPartName().getName();
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private static List<Block> blocks(List<IBodyElement> bodyElements) {
        List<Block> blocks = new ArrayList<>();
        int index = 0;
        for (IBodyElement element : bodyElements) {
            blocks.add(switch (element.getElementType()) {
                case PARAGRAPH -> paragraph((XWPFParagraph) element, "p" + index);
                case TABLE -> table((XWPFTable) element, "tbl" + index);
                default -> new OtherBlock("body" + index, element);
            });
            index++;
        }
        return List.copyOf(blocks);
    }

    private static Table table(XWPFTable table, String nodeId) {
        List<Row> rows = new ArrayList<>();
        int rowIndex = 0;
        for (XWPFTableRow row : table.getRows()) {
            String rowId = nodeId + "/row" + rowIndex;
            List<Cell> cells = new ArrayList<>();
            int cellIndex = 0;
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell(cell, rowId + "/cell" + cellIndex));
                cellIndex++;
            }
            rows.add(new Row(rowId, row, List.copyOf(cells)));
            rowIndex++;
        }
        return new Table(nodeId, table, List.copyOf(rows));
    }

    private static Cell cell(XWPFTableCell cell, String nodeId) {
        List<Paragraph> paragraphs = new ArrayList<>();
        int index = 0;
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            paragraphs.add(paragraph(paragraph, nodeId + "/p" + index));
            index++;
        }
        return new Cell(nodeId, cell, List.copyOf(paragraphs));
    }

    private static Paragraph paragraph(XWPFParagraph paragraph, String nodeId) {
        List<Inline> inlines = new ArrayList<>();
        int index = 0;
        try (XmlCursor cursor = paragraph.getCTP().newCursor()) {
            if (cursor.toFirstChild()) {
                do {
                    XmlObject child = cursor.getObject();
                    if (child instanceof CTR run) {
                        inlines.add(new Run(nodeId + "/r" + index, run, RunContainer.PARAGRAPH));
                        index++;
                    } else if (child instanceof CTSdtRun sdt) {
                        inlines.add(control(sdt, nodeId + "/sdt" + index));
                        index++;
                    } else if (child instanceof CTHyperlink hyperlink) {
                        for (CTR run : hyperlink.getRArray()) {
                            inlines.add(new Run(nodeId + "/r" + index, run, RunContainer.HYPERLINK));
                            index++;
                        }
                    }
                } while (cursor.toNextSibling());
            }
        }
        return new Paragraph(nodeId, paragraph, List.copyOf(inlines));
    }

    private static Control control(CTSdtRun sdt, String nodeId) {
        List<Run> runs = new ArrayList<>();
        if (sdt.isSetSdtContent()) {
            int index = 0;
            for (CTR run : sdt.getSdtContent().getRArray()) {
                runs.add(new Run(nodeId + "/r" + index, run, RunContainer.CONTROL));
                index++;
            }
        }
        return new Control(nodeId, sdt, List.copyOf(runs));
    }
}
