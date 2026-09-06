package io.github.vihuynh72.brownie.spike.docxbinding;

import org.apache.poi.xwpf.usermodel.LineSpacingRule;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

final class ProfessionalStyle {

  static final String FONT_FAMILY = "Calibri";
  static final int BODY_POINTS = 11;
  static final int TITLE_POINTS = 16;
  static final String INK_COLOR = "000000";

  private ProfessionalStyle() {}

  static void body(XWPFRun run) {
    run.setFontFamily(FONT_FAMILY);
    run.setFontSize(BODY_POINTS);
    run.setColor(INK_COLOR);
  }

  static void label(XWPFRun run) {
    body(run);
    run.setBold(true);
  }

  static void title(XWPFRun run) {
    run.setFontFamily(FONT_FAMILY);
    run.setFontSize(TITLE_POINTS);
    run.setColor(INK_COLOR);
    run.setBold(true);
  }

  static void spacedParagraph(XWPFParagraph paragraph) {
    paragraph.setSpacingLineRule(LineSpacingRule.AUTO);
    paragraph.setSpacingBetween(1.5d);
    paragraph.setSpacingAfter(160);
  }

  static void heading(XWPFParagraph paragraph) {
    spacedParagraph(paragraph);
    paragraph.setAlignment(ParagraphAlignment.LEFT);
  }
}
