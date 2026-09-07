package io.github.vihuynh72.brownie.spike.docxbinding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;

class RepeatingRegionSpikeTest {

  @Test
  void tableLedClonesOneRowPerActionItemWithDistinctTags() throws IOException {
    byte[] template = SpikeRunner.buildQualifiedTemplateBytes(LayoutKind.TABLE_LED);
    byte[] filled = SpikeRunner.fillQualified(template, LayoutKind.TABLE_LED, MeetingMinutesData.sample());

    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(filled))) {
      XWPFTable table = doc.getTables().get(0);
      assertEquals(3, table.getNumberOfRows(), "header row + 2 action-item rows");

      String text = TextExtraction.extract(doc);
      assertTrue(text.contains("Reserve the van for the regional competition"), text);
      assertTrue(text.contains("Confirm sponsor logo placement on the robot"), text);
      assertNoPrototypeTagLeftOver(doc);
      assertDistinctActionTags(doc, 2);
    }
  }

  @Test
  void flowingClonesOneNumberedParagraphPerActionItemSharingOneList() throws IOException {
    MeetingMinutesData data = MeetingMinutesData.sampleWithActionItemCount(5);
    byte[] template = SpikeRunner.buildQualifiedTemplateBytes(LayoutKind.FLOWING);
    byte[] filled = SpikeRunner.fillQualified(template, LayoutKind.FLOWING, data);

    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(filled))) {
      List<BigInteger> numIds =
          doc.getParagraphs().stream().map(XWPFParagraph::getNumID).filter(java.util.Objects::nonNull).toList();
      assertEquals(5, numIds.size(), "one numbered paragraph per action item");
      assertEquals(1, numIds.stream().distinct().count(), "all clones share the same numbering list: " + numIds);

      String text = TextExtraction.extract(doc);
      for (int i = 1; i <= 5; i++) {
        assertTrue(text.contains("Synthetic task " + i), "missing item " + i + ":\n" + text);
      }
      assertNoPrototypeTagLeftOver(doc);
      assertDistinctActionTags(doc, 5);
    }
  }

  @Test
  void zeroActionItemsLeavesALabelledExplanationAndNoStrayContentControls() throws IOException {
    for (LayoutKind layout : LayoutKind.values()) {
      byte[] template = SpikeRunner.buildQualifiedTemplateBytes(layout);
      byte[] filled = SpikeRunner.fillQualified(template, layout, MeetingMinutesData.sampleWithNoActionItems());

      try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(filled))) {
        String text = TextExtraction.extract(doc);
        assertTrue(text.contains(RepeatingRegion.NO_ACTION_ITEMS_TEXT), layout + " missing explanation:\n" + text);
        assertNoPrototypeTagLeftOver(doc);
        assertDistinctActionTags(doc, 0);
      }
    }
  }

  @Test
  void headerFooterAndLogoSurviveTheFillPassUnchanged() throws IOException {
    byte[] template = SpikeRunner.buildQualifiedTemplateBytes(LayoutKind.FLOWING);
    byte[] filled = SpikeRunner.fillQualified(template, LayoutKind.FLOWING, MeetingMinutesData.sample());

    try (XWPFDocument templateDoc = new XWPFDocument(new ByteArrayInputStream(template));
        XWPFDocument filledDoc = new XWPFDocument(new ByteArrayInputStream(filled))) {
      assertEquals(
          templateDoc.getHeaderList().get(0).getText(),
          filledDoc.getHeaderList().get(0).getText(),
          "the static header must not change when scalar fields and action items are filled");
      assertEquals(
          "Page 1", // "Page " label run + the field's cached "1" result run, concatenated
          filledDoc.getFooterList().get(0).getText().replace("\n", ""),
          "the footer's PAGE field cached result must survive untouched");

      List<XWPFPictureData> beforePictures = templateDoc.getAllPictures();
      List<XWPFPictureData> afterPictures = filledDoc.getAllPictures();
      assertEquals(1, afterPictures.size(), "exactly one inline logo image");
      assertArrayEquals(
          beforePictures.get(0).getData(),
          afterPictures.get(0).getData(),
          "the logo's image bytes must be byte-for-byte unchanged by the fill pass");
    }
  }

  private static void assertNoPrototypeTagLeftOver(XWPFDocument doc) {
    boolean leftover =
        FieldBinder.allParagraphs(doc).stream()
            .flatMap(p -> List.of(p.getCTP().getSdtArray()).stream())
            .map(CTSdtRun::getSdtPr)
            .anyMatch(pr -> pr.getTag().getVal().contains(".item."));
    assertFalse(leftover, "no content control should still carry the unrewritten .item. placeholder tag");
  }

  private static void assertDistinctActionTags(XWPFDocument doc, int expectedCount) {
    List<String> actionTags =
        FieldBinder.allParagraphs(doc).stream()
            .flatMap(p -> List.of(p.getCTP().getSdtArray()).stream())
            .map(sdt -> sdt.getSdtPr().getTag().getVal())
            .filter(tag -> tag.startsWith("action."))
            .distinct()
            .toList();
    assertEquals(expectedCount * 3, actionTags.size(), "task/owner/due tags for " + expectedCount + " item(s): " + actionTags);
  }
}
