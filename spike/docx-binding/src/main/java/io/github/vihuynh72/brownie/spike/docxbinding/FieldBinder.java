package io.github.vihuynh72.brownie.spike.docxbinding;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHpsMeasure;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtContentRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;

final class FieldBinder {

  private FieldBinder() {}

  static void appendField(XWPFParagraph paragraph, BindingMethod method, String tag, String placeholder) {
    if (method == BindingMethod.CONTENT_CONTROL) {
      appendContentControl(paragraph, tag, placeholder);
    } else {
      appendMergeField(paragraph, tag, placeholder);
    }
  }

  // ---- Content control (w:sdt) ----

  private static void appendContentControl(XWPFParagraph paragraph, String tag, String text) {
    CTP ctp = paragraph.getCTP();
    CTSdtRun sdt = ctp.addNewSdt();
    CTSdtPr sdtPr = sdt.addNewSdtPr();
    sdtPr.addNewTag().setVal(tag);
    sdtPr.addNewAlias().setVal(tag);
    CTSdtContentRun content = sdt.addNewSdtContent();
    CTR run = content.addNewR();
    styleRawRun(run);
    run.addNewT().setStringValue(text);
  }

  static void fillContentControls(XWPFDocument doc, Map<String, String> values) {
    for (CTSdtRun sdt : collectSdt(doc)) {
      String tag = sdt.getSdtPr().getTag().getVal();
      String value = values.get(tag);
      if (value == null) {
        continue;
      }
      CTSdtContentRun content = sdt.getSdtContent();
      if (content.sizeOfRArray() == 0) {
        CTR run = content.addNewR();
        styleRawRun(run);
        run.addNewT().setStringValue(value);
        continue;
      }
      CTR first = content.getRArray(0);
      while (first.sizeOfTArray() > 0) {
        first.removeT(0);
      }
      first.addNewT().setStringValue(value);
      for (int i = content.sizeOfRArray() - 1; i >= 1; i--) {
        content.removeR(i);
      }
    }
  }

  static List<CTSdtRun> collectSdt(XWPFDocument doc) {
    List<CTSdtRun> result = new ArrayList<>();
    for (XWPFParagraph paragraph : allParagraphs(doc)) {
      result.addAll(List.of(paragraph.getCTP().getSdtArray()));
    }
    return result;
  }

  private static void styleRawRun(CTR run) {
    CTRPr rPr = run.addNewRPr();
    rPr.addNewRFonts().setAscii(ProfessionalStyle.FONT_FAMILY);
    CTHpsMeasure sz = rPr.addNewSz();
    sz.setVal(BigInteger.valueOf(ProfessionalStyle.BODY_POINTS * 2L));
    rPr.addNewColor().setVal(ProfessionalStyle.INK_COLOR);
  }

  // ---- Merge field (w:fldChar / w:instrText MERGEFIELD) ----

  private static void appendMergeField(XWPFParagraph paragraph, String tag, String text) {
    XWPFRun begin = paragraph.createRun();
    begin.getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);

    XWPFRun instruction = paragraph.createRun();
    CTText instrText = instruction.getCTR().addNewInstrText();
    instrText.setStringValue(" MERGEFIELD " + tag + " \\* MERGEFORMAT ");
    preserveSpace(instrText);

    XWPFRun separate = paragraph.createRun();
    separate.getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);

    XWPFRun result = paragraph.createRun();
    ProfessionalStyle.body(result);
    result.setText(text);

    XWPFRun end = paragraph.createRun();
    end.getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
  }

  static void fillMergeFields(XWPFDocument doc, Map<String, String> values) {
    for (XWPFParagraph paragraph : allParagraphs(doc)) {
      List<XWPFRun> runs = paragraph.getRuns();
      for (int i = 0; i < runs.size(); i++) {
        CTR ctr = runs.get(i).getCTR();
        if (ctr.sizeOfInstrTextArray() == 0) {
          continue;
        }
        String tag = extractMergeFieldTag(ctr.getInstrTextArray(0).getStringValue());
        if (tag == null || !values.containsKey(tag)) {
          continue;
        }
        int separateIndex = findFldChar(runs, i, STFldCharType.SEPARATE);
        int endIndex = findFldChar(runs, separateIndex, STFldCharType.END);
        if (separateIndex < 0 || endIndex < 0 || separateIndex + 1 >= endIndex) {
          continue;
        }
        int resultIndex = separateIndex + 1;
        runs.get(resultIndex).setText(values.get(tag), 0);
        for (int k = endIndex - 1; k > resultIndex; k--) {
          paragraph.removeRun(k);
        }
      }
    }
  }

  private static int findFldChar(List<XWPFRun> runs, int fromInclusive, STFldCharType.Enum type) {
    if (fromInclusive < 0) {
      return -1;
    }
    for (int i = fromInclusive; i < runs.size(); i++) {
      CTR ctr = runs.get(i).getCTR();
      if (ctr.sizeOfFldCharArray() > 0 && ctr.getFldCharArray(0).getFldCharType() == type) {
        return i;
      }
    }
    return -1;
  }

  private static void preserveSpace(CTText text) {
    try (XmlCursor cursor = text.newCursor()) {
      cursor.toNextToken();
      cursor.insertAttributeWithValue(new QName("http://www.w3.org/XML/1998/namespace", "space"), "preserve");
    }
  }

  private static String extractMergeFieldTag(String instructionText) {
    String trimmed = instructionText.trim();
    String prefix = "MERGEFIELD ";
    if (!trimmed.startsWith(prefix)) {
      return null;
    }
    String rest = trimmed.substring(prefix.length()).trim();
    int spaceIndex = rest.indexOf(' ');
    return spaceIndex < 0 ? rest : rest.substring(0, spaceIndex);
  }

  // ---- Shared traversal ----

  static List<XWPFParagraph> allParagraphs(XWPFDocument doc) {
    List<XWPFParagraph> result = new ArrayList<>(doc.getParagraphs());
    for (XWPFTable table : doc.getTables()) {
      for (XWPFTableRow row : table.getRows()) {
        for (XWPFTableCell cell : row.getTableCells()) {
          result.addAll(cell.getParagraphs());
        }
      }
    }
    return result;
  }
}
