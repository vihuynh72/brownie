package io.github.vihuynh72.brownie.spike.docxbinding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

public final class SpikeRunner {

  private SpikeRunner() {}

  public static void main(String[] args) throws IOException {
    Path outDir = Path.of("target", "spike-output");
    Files.createDirectories(outDir);

    for (LayoutKind layout : LayoutKind.values()) {
      for (BindingMethod method : BindingMethod.values()) {
        byte[] template = buildTemplateBytes(layout, method);
        byte[] filled = fill(template, method, MeetingMinutesData.sample());

        String base = layout.name().toLowerCase() + "-" + method.name().toLowerCase();
        Files.write(outDir.resolve(base + "-template.docx"), template);
        Files.write(outDir.resolve(base + "-filled.docx"), filled);
      }
    }

    for (LayoutKind layout : LayoutKind.values()) {
      writeQualified(outDir, layout, "two-items", MeetingMinutesData.sample());
      writeQualified(outDir, layout, "five-items", MeetingMinutesData.sampleWithActionItemCount(5));
      writeQualified(outDir, layout, "no-items", MeetingMinutesData.sampleWithNoActionItems());
      writeQualified(outDir, layout, "short", MeetingMinutesData.sampleShort());
      writeQualified(outDir, layout, "long", MeetingMinutesData.sampleLong());
      writeQualified(outDir, layout, "empty", MeetingMinutesData.sampleEmpty());
      writeQualified(outDir, layout, "accented-names", MeetingMinutesData.sampleAccentedNames());
      writeQualified(outDir, layout, "multi-page", MeetingMinutesData.sampleWithActionItemCount(40));
    }

    System.out.println("Wrote spike documents to " + outDir.toAbsolutePath());
  }

  private static void writeQualified(Path outDir, LayoutKind layout, String caseName, MeetingMinutesData data)
      throws IOException {
    byte[] template = buildQualifiedTemplateBytes(layout);
    byte[] filled = fillQualified(template, layout, data);

    String base = "qualified-" + layout.name().toLowerCase() + "-" + caseName;
    Files.write(outDir.resolve(base + "-template.docx"), template);
    Files.write(outDir.resolve(base + "-filled.docx"), filled);
  }

  static byte[] buildTemplateBytes(LayoutKind layout, BindingMethod method) throws IOException {
    try (XWPFDocument doc = LayoutBuilder.build(layout, method);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.write(out);
      return out.toByteArray();
    }
  }

  static byte[] fill(byte[] templateBytes, BindingMethod method, MeetingMinutesData data) throws IOException {
    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(templateBytes));
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      if (method == BindingMethod.CONTENT_CONTROL) {
        FieldBinder.fillContentControls(doc, data.asMap());
      } else {
        FieldBinder.fillMergeFields(doc, data.asMap());
      }
      doc.write(out);
      return out.toByteArray();
    }
  }

  static byte[] buildQualifiedTemplateBytes(LayoutKind layout) throws IOException {
    try (XWPFDocument doc = LayoutBuilder.buildQualified(layout);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.write(out);
      return out.toByteArray();
    }
  }

  static byte[] fillQualified(byte[] templateBytes, LayoutKind layout, MeetingMinutesData data) throws IOException {
    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(templateBytes));
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      RepeatingRegion.bind(doc, layout, data.actionItems());
      FieldBinder.fillContentControls(doc, data.asMap());
      doc.write(out);
      return out.toByteArray();
    }
  }
}
