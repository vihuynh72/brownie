package io.github.vihuynh72.brownie.spike.docxbinding;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.util.Units;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;

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

  // ---- Qualified content-control template: header, footer, logo, numbering, one prototype ----

  /**
   * Builds the real candidate template for the chosen binding convention (content controls):
   * a static header and footer, a decimal numbering list for repeatable action items, an inline
   * logo, the same five scalar meeting fields as the comparison layouts, and exactly one
   * action-item prototype tagged "action.item.*" for {@link RepeatingRegion} to clone.
   */
  public static XWPFDocument buildQualified(LayoutKind layout) throws IOException {
    XWPFDocument doc = new XWPFDocument();
    addHeaderFooter(doc);
    BigInteger numId = createDecimalNumbering(doc);
    addLogo(doc);

    titleParagraph(doc, "Meeting Minutes");
    header(doc, BindingMethod.CONTENT_CONTROL);

    sectionHeading(doc, "Decisions");
    labeledField(doc, BindingMethod.CONTENT_CONTROL, "", "meeting.decisions", "[decisions]");

    sectionHeading(doc, "Action Items");
    switch (layout) {
      case FLOWING -> actionItemPrototypeParagraph(doc, numId);
      case TABLE_LED -> actionItemPrototypeTable(doc);
    }
    return doc;
  }

  private static void actionItemPrototypeTable(XWPFDocument doc) {
    XWPFTable table = doc.createTable(2, 3);
    styleTable(table);
    headerCell(table.getRow(0).getCell(0), "Task");
    headerCell(table.getRow(0).getCell(1), "Owner");
    headerCell(table.getRow(0).getCell(2), "Due date");
    fillCell(table.getRow(1).getCell(0), BindingMethod.CONTENT_CONTROL, "action.item.task", "[task]");
    fillCell(table.getRow(1).getCell(1), BindingMethod.CONTENT_CONTROL, "action.item.owner", "[owner]");
    fillCell(table.getRow(1).getCell(2), BindingMethod.CONTENT_CONTROL, "action.item.due", "[due date]");
  }

  private static void actionItemPrototypeParagraph(XWPFDocument doc, BigInteger numId) {
    XWPFParagraph paragraph = doc.createParagraph();
    ProfessionalStyle.spacedParagraph(paragraph);
    paragraph.setNumID(numId);

    XWPFRun taskLabel = paragraph.createRun();
    ProfessionalStyle.body(taskLabel);
    taskLabel.setText("Task: ");
    FieldBinder.appendField(paragraph, BindingMethod.CONTENT_CONTROL, "action.item.task", "[task]");

    XWPFRun ownerLabel = paragraph.createRun();
    ProfessionalStyle.body(ownerLabel);
    ownerLabel.setText("   Owner: ");
    FieldBinder.appendField(paragraph, BindingMethod.CONTENT_CONTROL, "action.item.owner", "[owner]");

    XWPFRun dueLabel = paragraph.createRun();
    ProfessionalStyle.body(dueLabel);
    dueLabel.setText("   Due: ");
    FieldBinder.appendField(paragraph, BindingMethod.CONTENT_CONTROL, "action.item.due", "[due date]");
  }

  private static void addHeaderFooter(XWPFDocument doc) {
    XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
    XWPFParagraph headerParagraph = header.createParagraph();
    ProfessionalStyle.spacedParagraph(headerParagraph);
    XWPFRun headerRun = headerParagraph.createRun();
    ProfessionalStyle.label(headerRun);
    headerRun.setText("Brownie Meeting Minutes Template");

    XWPFFooter footer = doc.createFooter(HeaderFooterType.DEFAULT);
    XWPFParagraph footerParagraph = footer.createParagraph();
    ProfessionalStyle.spacedParagraph(footerParagraph);
    XWPFRun label = footerParagraph.createRun();
    ProfessionalStyle.body(label);
    label.setText("Page ");
    appendPageNumberField(footerParagraph);
  }

  private static void appendPageNumberField(XWPFParagraph paragraph) {
    XWPFRun begin = paragraph.createRun();
    begin.getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);

    XWPFRun instruction = paragraph.createRun();
    instruction.getCTR().addNewInstrText().setStringValue(" PAGE ");

    XWPFRun separate = paragraph.createRun();
    separate.getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);

    XWPFRun cachedResult = paragraph.createRun();
    ProfessionalStyle.body(cachedResult);
    cachedResult.setText("1");

    XWPFRun end = paragraph.createRun();
    end.getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
  }

  /** One decimal numbering list ("1.", "2.", ...) shared by every cloned action-item paragraph. */
  private static BigInteger createDecimalNumbering(XWPFDocument doc) {
    XWPFNumbering numbering = doc.createNumbering();

    CTAbstractNum ctAbstractNum = CTAbstractNum.Factory.newInstance();
    ctAbstractNum.setAbstractNumId(BigInteger.ZERO);
    CTLvl level = ctAbstractNum.addNewLvl();
    level.setIlvl(BigInteger.ZERO);
    level.addNewStart().setVal(BigInteger.ONE);
    level.addNewNumFmt().setVal(STNumberFormat.DECIMAL);
    level.addNewLvlText().setVal("%1.");

    BigInteger abstractNumId = numbering.addAbstractNum(new XWPFAbstractNum(ctAbstractNum));
    return numbering.addNum(abstractNumId);
  }

  private static void addLogo(XWPFDocument doc) throws IOException {
    XWPFParagraph paragraph = doc.createParagraph();
    ProfessionalStyle.spacedParagraph(paragraph);
    XWPFRun run = paragraph.createRun();
    int emuSize = Units.pixelToEMU(SyntheticLogo.SIZE_PX);
    try (ByteArrayInputStream in = new ByteArrayInputStream(SyntheticLogo.pngBytes())) {
      run.addPicture(in, PictureType.PNG, "brownie-logo.png", emuSize, emuSize);
    } catch (InvalidFormatException e) {
      throw new IOException("synthetic logo could not be embedded", e);
    }
  }
}
