package io.github.vihuynh72.brownie.spike.docxbinding;

import java.math.BigInteger;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;

public final class LayoutBuilder {

  private LayoutBuilder() {}

  public static XWPFDocument build(LayoutKind layout, BindingMethod method) {
    XWPFDocument doc = new XWPFDocument();
    switch (layout) {
      case FLOWING -> buildFlowing(doc, method);
      case TABLE_LED -> buildTableLed(doc, method);
    }
    return doc;
  }

  private static void buildFlowing(XWPFDocument doc, BindingMethod method) {
    titleParagraph(doc, "Meeting Minutes");
    header(doc, method);

    sectionHeading(doc, "Decisions");
    labeledField(doc, method, "", "meeting.decisions", "[decisions]");

    sectionHeading(doc, "Action Items");
    actionParagraph(doc, method, 1);
    actionParagraph(doc, method, 2);
  }

  private static void buildTableLed(XWPFDocument doc, BindingMethod method) {
    titleParagraph(doc, "Meeting Minutes");
    header(doc, method);

    sectionHeading(doc, "Decisions");
    labeledField(doc, method, "", "meeting.decisions", "[decisions]");

    sectionHeading(doc, "Action Items");
    XWPFTable table = doc.createTable(3, 3);
    styleTable(table);
    headerCell(table.getRow(0).getCell(0), "Task");
    headerCell(table.getRow(0).getCell(1), "Owner");
    headerCell(table.getRow(0).getCell(2), "Due date");

    actionRow(table.getRow(1), method, 1);
    actionRow(table.getRow(2), method, 2);
  }

  private static void header(XWPFDocument doc, BindingMethod method) {
    labeledField(doc, method, "Title: ", "meeting.title", "[meeting title]");
    labeledField(doc, method, "Organization: ", "meeting.organization", "[organization]");
    labeledField(doc, method, "Date: ", "meeting.date", "[date]");
    labeledField(doc, method, "Location: ", "meeting.location", "[location]");
    labeledField(doc, method, "Attendees: ", "meeting.attendees", "[attendees]");
  }

  private static void titleParagraph(XWPFDocument doc, String text) {
    XWPFParagraph paragraph = doc.createParagraph();
    ProfessionalStyle.heading(paragraph);
    XWPFRun run = paragraph.createRun();
    ProfessionalStyle.title(run);
    run.setText(text);
  }

  private static void sectionHeading(XWPFDocument doc, String text) {
    XWPFParagraph paragraph = doc.createParagraph();
    ProfessionalStyle.heading(paragraph);
    XWPFRun run = paragraph.createRun();
    ProfessionalStyle.label(run);
    run.setFontSize(13);
    run.setText(text);
  }

  private static void labeledField(
      XWPFDocument doc, BindingMethod method, String label, String tag, String placeholder) {
    XWPFParagraph paragraph = doc.createParagraph();
    ProfessionalStyle.spacedParagraph(paragraph);
    if (!label.isEmpty()) {
      XWPFRun labelRun = paragraph.createRun();
      ProfessionalStyle.label(labelRun);
      labelRun.setText(label);
    }
    FieldBinder.appendField(paragraph, method, tag, placeholder);
  }

  private static void actionParagraph(XWPFDocument doc, BindingMethod method, int index) {
    XWPFParagraph paragraph = doc.createParagraph();
    ProfessionalStyle.spacedParagraph(paragraph);

    XWPFRun taskLabel = paragraph.createRun();
    ProfessionalStyle.body(taskLabel);
    taskLabel.setText("Task: ");
    FieldBinder.appendField(paragraph, method, "action." + index + ".task", "[task]");

    XWPFRun ownerLabel = paragraph.createRun();
    ProfessionalStyle.body(ownerLabel);
    ownerLabel.setText("   Owner: ");
    FieldBinder.appendField(paragraph, method, "action." + index + ".owner", "[owner]");

    XWPFRun dueLabel = paragraph.createRun();
    ProfessionalStyle.body(dueLabel);
    dueLabel.setText("   Due: ");
    FieldBinder.appendField(paragraph, method, "action." + index + ".due", "[due date]");
  }

  private static void actionRow(XWPFTableRow row, BindingMethod method, int index) {
    fillCell(row.getCell(0), method, "action." + index + ".task", "[task]");
    fillCell(row.getCell(1), method, "action." + index + ".owner", "[owner]");
    fillCell(row.getCell(2), method, "action." + index + ".due", "[due date]");
  }

  private static void fillCell(XWPFTableCell cell, BindingMethod method, String tag, String placeholder) {
    XWPFParagraph paragraph = cell.getParagraphs().get(0);
    ProfessionalStyle.spacedParagraph(paragraph);
    FieldBinder.appendField(paragraph, method, tag, placeholder);
  }

  private static void headerCell(XWPFTableCell cell, String text) {
    XWPFParagraph paragraph = cell.getParagraphs().get(0);
    ProfessionalStyle.spacedParagraph(paragraph);
    XWPFRun run = paragraph.createRun();
    ProfessionalStyle.label(run);
    run.setText(text);
  }

  private static void styleTable(XWPFTable table) {
    table.setWidth("100%");
    CTTblPr tblPr = table.getCTTbl().getTblPr();
    if (tblPr == null) {
      tblPr = table.getCTTbl().addNewTblPr();
    }
    if (tblPr.isSetTblBorders()) {
      tblPr.unsetTblBorders();
    }
    CTTblBorders borders = tblPr.addNewTblBorders();
    thinBorder(borders.addNewTop());
    thinBorder(borders.addNewBottom());
    thinBorder(borders.addNewLeft());
    thinBorder(borders.addNewRight());
    thinBorder(borders.addNewInsideH());
    thinBorder(borders.addNewInsideV());
  }

  private static void thinBorder(CTBorder border) {
    border.setVal(STBorder.SINGLE);
    border.setSz(BigInteger.valueOf(4));
    border.setSpace(BigInteger.ZERO);
    border.setColor(ProfessionalStyle.INK_COLOR);
  }
}
