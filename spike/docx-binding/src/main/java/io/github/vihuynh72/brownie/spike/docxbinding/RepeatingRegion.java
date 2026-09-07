package io.github.vihuynh72.brownie.spike.docxbinding;

import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;

/**
 * Turns one prototype row or paragraph -- tagged with the placeholder segment ".item." -- into
 * however many concrete rows or paragraphs a document actually needs: one per action item, or a
 * single explanatory line when there are none.
 *
 * <p>A naive XML copy is not enough here, for two separate reasons this class has to handle
 * together, in one pass, before a cloned row is ever attached to the table:
 *
 * <ul>
 *   <li>Every clone of the prototype starts out carrying the identical content-control tag, so a
 *       scalar fill-by-tag pass could only ever address one of them. Each clone's tag is rewritten
 *       from "action.item.task" to "action.&lt;index&gt;.task" so it is addressable on its own.
 *   <li>{@code XWPFTable.addRow} copies a row's XML into the table's own tree rather than adopting
 *       the row object by reference, so any Java-level edit made to a row's content controls
 *       *after* {@code addRow} is silently lost -- it lands on an orphaned copy, not the live
 *       document. Table rows therefore get their values written before they are added, not after.
 *       (Cloned paragraphs do not have this problem: {@code XWPFDocument.createParagraph()} yields
 *       an already-attached paragraph, and copying the prototype's XML into it in place keeps it
 *       live, so paragraphs are filled the more obvious way, after creation.)
 * </ul>
 */
final class RepeatingRegion {

  private static final String PLACEHOLDER_SEGMENT = ".item.";
  static final String NO_ACTION_ITEMS_TEXT = "No action items recorded.";

  private RepeatingRegion() {}

  /** Binds the action-item region of a {@link LayoutBuilder#buildQualified} document. */
  static void bind(XWPFDocument doc, LayoutKind layout, List<ActionItemData> items) {
    switch (layout) {
      case FLOWING -> bindParagraphs(doc, findPrototypeParagraph(doc), items);
      case TABLE_LED -> bindTableRows(doc.getTables().get(0), items);
    }
  }

  private static void bindTableRows(XWPFTable table, List<ActionItemData> items) {
    int prototypeIndex = table.getNumberOfRows() - 1;
    XWPFTableRow prototype = table.getRow(prototypeIndex);

    if (items.isEmpty()) {
      explainEmptyRow(prototype);
      return;
    }

    CTRow prototypeXml = prototype.getCtRow();
    for (int i = 0; i < items.size(); i++) {
      XWPFTableRow clonedRow = new XWPFTableRow((CTRow) prototypeXml.copy(), table);
      for (XWPFTableCell cell : clonedRow.getTableCells()) {
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
          bindControls(paragraph, i + 1, items.get(i));
        }
      }
      table.addRow(clonedRow);
    }
    table.removeRow(prototypeIndex);
  }

  private static void bindParagraphs(XWPFDocument doc, XWPFParagraph prototype, List<ActionItemData> items) {
    if (items.isEmpty()) {
      explainEmptyParagraph(prototype);
      return;
    }

    CTP prototypeXml = prototype.getCTP();
    for (int i = 0; i < items.size(); i++) {
      XWPFParagraph cloned = doc.createParagraph();
      cloned.getCTP().set(prototypeXml.copy());
      bindControls(cloned, i + 1, items.get(i));
    }
    doc.removeBodyElement(doc.getPosOfParagraph(prototype));
  }

  /** Rewrites tags "action.item.X" to "action.&lt;index&gt;.X" and fills each with its value. */
  private static void bindControls(XWPFParagraph paragraph, int index, ActionItemData item) {
    for (CTSdtRun sdt : paragraph.getCTP().getSdtArray()) {
      String rewritten = sdt.getSdtPr().getTag().getVal().replace(PLACEHOLDER_SEGMENT, "." + index + ".");
      sdt.getSdtPr().getTag().setVal(rewritten);
      sdt.getSdtPr().getAlias().setVal(rewritten);
      FieldBinder.setContentControlText(sdt, fieldValue(rewritten, item));
    }
  }

  private static String fieldValue(String rewrittenTag, ActionItemData item) {
    if (rewrittenTag.endsWith(".task")) {
      return item.task();
    }
    if (rewrittenTag.endsWith(".owner")) {
      return item.owner();
    }
    if (rewrittenTag.endsWith(".due")) {
      return item.due();
    }
    throw new IllegalStateException("unrecognized action-item tag: " + rewrittenTag);
  }

  private static void explainEmptyRow(XWPFTableRow prototype) {
    List<XWPFTableCell> cells = prototype.getTableCells();
    for (XWPFTableCell cell : cells) {
      removeSdtControls(cell.getParagraphs().get(0).getCTP());
    }
    XWPFRun run = cells.get(0).getParagraphs().get(0).createRun();
    ProfessionalStyle.body(run);
    run.setText(NO_ACTION_ITEMS_TEXT);
  }

  private static void explainEmptyParagraph(XWPFParagraph prototype) {
    while (!prototype.getRuns().isEmpty()) {
      prototype.removeRun(0);
    }
    removeSdtControls(prototype.getCTP());
    if (prototype.getCTP().isSetPPr() && prototype.getCTP().getPPr().isSetNumPr()) {
      prototype.getCTP().getPPr().unsetNumPr();
    }
    XWPFRun run = prototype.createRun();
    ProfessionalStyle.body(run);
    run.setText(NO_ACTION_ITEMS_TEXT);
  }

  private static void removeSdtControls(CTP ctp) {
    for (int i = ctp.sizeOfSdtArray() - 1; i >= 0; i--) {
      ctp.removeSdt(i);
    }
  }

  private static XWPFParagraph findPrototypeParagraph(XWPFDocument doc) {
    for (XWPFParagraph paragraph : doc.getParagraphs()) {
      for (CTSdtRun sdt : paragraph.getCTP().getSdtArray()) {
        if (sdt.getSdtPr().getTag().getVal().contains(PLACEHOLDER_SEGMENT)) {
          return paragraph;
        }
      }
    }
    throw new IllegalStateException("no action-item prototype paragraph found");
  }
}
