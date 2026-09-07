package io.github.vihuynh72.brownie.spike.docxbinding;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtContentRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;

/**
 * Reads visible text directly from the XML in document order, including text
 * inside inline content controls, instead of relying on the high-level
 * paragraph API (which is not guaranteed to walk w:sdt content).
 */
final class TextExtraction {

  private TextExtraction() {}

  static String extract(XWPFDocument doc) {
    StringBuilder result = new StringBuilder();
    for (XWPFParagraph paragraph : FieldBinder.allParagraphs(doc)) {
      result.append(extract(paragraph)).append('\n');
    }
    return result.toString();
  }

  private static String extract(XWPFParagraph paragraph) {
    StringBuilder sb = new StringBuilder();
    for (XmlObject child : paragraph.getCTP().selectPath("./*")) {
      if (child instanceof CTR run) {
        appendRunText(sb, run);
      } else if (child instanceof CTSdtRun sdt) {
        CTSdtContentRun content = sdt.getSdtContent();
        if (content != null) {
          for (CTR run : content.getRArray()) {
            appendRunText(sb, run);
          }
        }
      }
    }
    return sb.toString();
  }

  private static void appendRunText(StringBuilder sb, CTR run) {
    for (CTText text : run.getTArray()) {
      sb.append(text.getStringValue());
    }
  }
}
