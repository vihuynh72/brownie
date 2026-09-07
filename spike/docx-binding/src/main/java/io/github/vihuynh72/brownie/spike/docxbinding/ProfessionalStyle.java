package io.github.vihuynh72.brownie.spike.docxbinding;

import org.apache.poi.xwpf.usermodel.LineSpacingRule;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

final class ProfessionalStyle {

  /**
   * Liberation Sans, not Arial: Arial is a Monotype font licensed to Microsoft/Apple for OS
   * bundling only, so it cannot be redistributed into a render container -- the same restriction
   * that ruled out Calibri. Liberation Sans (SIL Open Font License, Red Hat's Liberation Fonts
   * project) was built specifically as a metric-compatible substitute for Arial, so this is a
   * direct house-font choice for Brownie's built-in templates, not a silent runtime substitution.
   */
  static final String FONT_FAMILY = "Liberation Sans";
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
