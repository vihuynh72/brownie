package io.github.vihuynh72.brownie.spike.docxbinding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;

class DocxBindingSpikeTest {

  @Test
  void contentControlFlowingPreservesTagAndReplacesPlaceholderText() throws IOException {
    byte[] template = SpikeRunner.buildTemplateBytes(LayoutKind.FLOWING, BindingMethod.CONTENT_CONTROL);
    byte[] filled = SpikeRunner.fill(template, BindingMethod.CONTENT_CONTROL, MeetingMinutesData.sample());

    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(filled))) {
      String text = TextExtraction.extract(doc);
      assertTrue(text.contains("José Núñez"), "accented attendee name must survive fill:\n" + text);
      assertTrue(text.contains("Spring Budget Planning"), "filled title must appear:\n" + text);
      assertFalse(text.contains("[organization]"), "placeholder text must be replaced:\n" + text);

      boolean titleTagStillAddressable =
          FieldBinder.collectSdt(doc).stream()
              .map(CTSdtRun::getSdtPr)
              .anyMatch(pr -> "meeting.title".equals(pr.getTag().getVal()));
      assertTrue(titleTagStillAddressable, "the meeting.title content control tag must remain after fill");
    }
  }

  @Test
  void mergeFieldFlowingKeepsFieldCodeAndUpdatesCachedResult() throws IOException {
    byte[] template = SpikeRunner.buildTemplateBytes(LayoutKind.FLOWING, BindingMethod.MERGE_FIELD);
    byte[] filled = SpikeRunner.fill(template, BindingMethod.MERGE_FIELD, MeetingMinutesData.sample());

    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(filled))) {
      String text = TextExtraction.extract(doc);
      assertTrue(text.contains("José Núñez"), "accented attendee name must survive fill:\n" + text);
      assertTrue(text.contains("Spring Budget Planning"), "filled title must appear:\n" + text);

      boolean fieldCodeRetained =
          FieldBinder.allParagraphs(doc).stream()
              .flatMap(p -> p.getRuns().stream())
              .anyMatch(
                  run ->
                      run.getCTR().sizeOfInstrTextArray() > 0
                          && run.getCTR()
                              .getInstrTextArray(0)
                              .getStringValue()
                              .contains("MERGEFIELD meeting.title"));
      assertTrue(fieldCodeRetained, "the MERGEFIELD instruction must remain next to the cached result");
    }
  }

  @Test
  void tableLedActionItemsBindUnderBothMethods() throws IOException {
    for (BindingMethod method : BindingMethod.values()) {
      byte[] template = SpikeRunner.buildTemplateBytes(LayoutKind.TABLE_LED, method);
      byte[] filled = SpikeRunner.fill(template, method, MeetingMinutesData.sample());

      try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(filled))) {
        String text = TextExtraction.extract(doc);
        assertTrue(
            text.contains("Reserve the van for the regional competition"),
            method + " row 1 task missing:\n" + text);
        assertTrue(
            text.contains("Confirm sponsor logo placement on the robot"),
            method + " row 2 task missing:\n" + text);
      }
    }
  }
}
