package io.github.vihuynh72.brownie.api.document.docx;

import io.github.vihuynh72.brownie.core.compile.FilledDocument;
import io.github.vihuynh72.brownie.core.compile.TemplateFillException;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.BuiltInMinutesTemplate;
import io.github.vihuynh72.brownie.core.template.BuiltInMinutesTemplateRegistry;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtContentRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoiTemplateFillerTest {

    private final PoiTemplateFiller filler = new PoiTemplateFiller();

    @Test
    void fillsEveryBuiltInBlankFixtureAndTheReopenedTextContainsEveryValue() throws IOException {
        for (BuiltInMinutesTemplate template : BuiltInMinutesTemplateRegistry.all()) {
            byte[] blank = Files.readAllBytes(fixturePath(template.templateFixturePath()));
            DocumentContent content = sampleContentWithTwoActionItems();

            FilledDocument filled = filler.fill(blank, template.fields(), content);

            assertTrue(filled.reopenedBodyText().contains("Spring Budget Planning"), template.id());
            assertTrue(filled.reopenedBodyText().contains("Riverside Robotics Club"), template.id());
            assertTrue(filled.reopenedBodyText().contains("March 5, 2026"), template.id());
            assertTrue(filled.reopenedBodyText().contains("Reserve the van"), template.id());
            assertTrue(filled.reopenedBodyText().contains("Alex Chen"), template.id());
            assertTrue(filled.reopenedBodyText().contains("Confirm sponsor logo"), template.id());
            assertTrue(filled.reopenedBodyText().contains("Priya Rao"), template.id());
            assertTrue(!filled.reopenedBodyText().contains("[meeting title]"), template.id() + " left a placeholder behind");

            // Filling again from the same blank bytes must not depend on any mutated static state.
            FilledDocument second = filler.fill(blank, template.fields(), content);
            assertEquals(filled.reopenedBodyText(), second.reopenedBodyText());
        }
    }

    @Test
    void emptyActionItemsRenderTheExplanatoryLineInsteadOfAnEmptyRegion() throws IOException {
        for (BuiltInMinutesTemplate template : BuiltInMinutesTemplateRegistry.all()) {
            byte[] blank = Files.readAllBytes(fixturePath(template.templateFixturePath()));
            DocumentContent content = new DocumentContent(Map.of(
                    "meeting.title", new FieldValue.TextValue("Officer Check-in"),
                    "meeting.date", new FieldValue.DateValue(LocalDate.of(2026, 3, 5))));

            FilledDocument filled = filler.fill(blank, template.fields(), content);

            assertTrue(filled.reopenedBodyText().contains("No action items recorded."), template.id());
            assertTrue(!filled.reopenedBodyText().contains("[task]"), template.id());
        }
    }

    @Test
    void mismatchedRepeatedFieldLengthsAreRejectedBeforeWritingAnything() throws IOException {
        BuiltInMinutesTemplate flowing = BuiltInMinutesTemplateRegistry.find("flowing-meeting-minutes").orElseThrow();
        byte[] blank = Files.readAllBytes(fixturePath(flowing.templateFixturePath()));
        DocumentContent content = new DocumentContent(Map.of(
                "meeting.title", new FieldValue.TextValue("Minutes"),
                "meeting.date", new FieldValue.DateValue(LocalDate.of(2026, 3, 5)),
                "action.item.task", new FieldValue.RepeatedTextValue(List.of("Task one", "Task two")),
                "action.item.owner", new FieldValue.RepeatedTextValue(List.of("Only one owner"))));

        assertThrows(TemplateFillException.class, () -> filler.fill(blank, flowing.fields(), content));
    }

    /**
     * {@code document.createParagraph()} always appends at the document's
     * own absolute end, regardless of where the prototype paragraph it is
     * cloning actually lives -- proven directly against this exact POI
     * version with a synthetic document whose repeated-group prototype
     * sits between two static paragraphs, a layout the built-in fixtures
     * never happen to exercise (their own prototype is always the last
     * paragraph in the body).
     */
    @Test
    void clonedRepeatedGroupParagraphsLandExactlyWherePrototypeWasEvenWithTrailingContent() throws IOException {
        byte[] blank = paragraphRepeatedGroupDocxWithTrailingContent();
        List<FieldDefinition> fields = List.of(
                repeatedField("action.item.task", FieldType.TEXT),
                repeatedField("action.item.owner", FieldType.TEXT));
        DocumentContent content = new DocumentContent(Map.of(
                "action.item.task", new FieldValue.RepeatedTextValue(List.of("First task", "Second task")),
                "action.item.owner", new FieldValue.RepeatedTextValue(List.of("Owner one", "Owner two"))));

        FilledDocument filled = filler.fill(blank, fields, content);

        String text = filled.reopenedBodyText();
        int headerIndex = text.indexOf("HEADER_BEFORE");
        int firstItemIndex = text.indexOf("First task");
        int secondItemIndex = text.indexOf("Second task");
        int footerIndex = text.indexOf("FOOTER_SIGNATURE_BLOCK");
        assertTrue(headerIndex >= 0 && firstItemIndex >= 0 && secondItemIndex >= 0 && footerIndex >= 0, text);
        assertTrue(headerIndex < firstItemIndex, "header must come before the first cloned item: " + text);
        assertTrue(firstItemIndex < secondItemIndex, "clones must stay in order: " + text);
        assertTrue(
                secondItemIndex < footerIndex,
                "cloned items must land where the prototype was, before the trailing content, not after it: " + text);
    }

    /**
     * Word and LibreOffice are both permitted to trim untagged boundary
     * whitespace on a {@code <w:t>} run. A typed value with a trailing
     * space -- routine for dictated or copy-pasted free text -- must be
     * written with {@code xml:space="preserve"} or that space can be
     * silently lost by the very renderer this compiler depends on.
     */
    @Test
    void trailingWhitespaceInAFieldValueIsMarkedPreserveButAnOrdinaryValueIsNot() throws IOException {
        byte[] blank = scalarContentControlDocx("meeting.title", "meeting.organization");
        List<FieldDefinition> fields = List.of(
                scalarField("meeting.title", FieldType.TEXT),
                scalarField("meeting.organization", FieldType.TEXT));
        DocumentContent content = new DocumentContent(Map.of(
                "meeting.title", new FieldValue.TextValue("Trailing space value "),
                "meeting.organization", new FieldValue.TextValue("NoBoundaryWhitespaceHere")));

        FilledDocument filled = filler.fill(blank, fields, content);
        String documentXml = extractDocumentXml(filled.docxBytes());

        assertTrue(
                documentXml.contains("xml:space=\"preserve\">Trailing space value <"),
                "value with trailing whitespace must be marked preserve: " + documentXml);
        assertTrue(
                !documentXml.contains("xml:space=\"preserve\">NoBoundaryWhitespaceHere<"),
                "an ordinary value must not be tagged preserve: " + documentXml);
    }

    private static byte[] paragraphRepeatedGroupDocxWithTrailingContent() throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("HEADER_BEFORE");

            XWPFParagraph prototype = doc.createParagraph();
            addContentControl(prototype, "action.item.task");
            addContentControl(prototype, "action.item.owner");

            doc.createParagraph().createRun().setText("FOOTER_SIGNATURE_BLOCK");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] scalarContentControlDocx(String... tags) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            for (String tag : tags) {
                addContentControl(doc.createParagraph(), tag);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static void addContentControl(XWPFParagraph paragraph, String tag) {
        CTP ctp = paragraph.getCTP();
        CTSdtRun sdt = ctp.addNewSdt();
        CTSdtPr sdtPr = sdt.addNewSdtPr();
        sdtPr.addNewTag().setVal(tag);
        sdtPr.addNewAlias().setVal(tag);
        CTSdtContentRun sdtContent = sdt.addNewSdtContent();
        CTR run = sdtContent.addNewR();
        run.addNewT().setStringValue("[" + tag + "]");
    }

    private static String extractDocumentXml(byte[] docxBytes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(docxBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("word/document.xml")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalStateException("word/document.xml not found in produced DOCX");
    }

    private static FieldDefinition scalarField(String fieldId, FieldType type) {
        return new FieldDefinition(
                fieldId, type, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.ContentControlTag(fieldId));
    }

    private static FieldDefinition repeatedField(String fieldId, FieldType type) {
        return new FieldDefinition(
                fieldId, type, FieldCardinality.REPEATED, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.ContentControlTag(fieldId));
    }

    private static DocumentContent sampleContentWithTwoActionItems() {
        Map<String, FieldValue> fields = new LinkedHashMap<>();
        fields.put("meeting.title", new FieldValue.TextValue("Spring Budget Planning"));
        fields.put("meeting.organization", new FieldValue.TextValue("Riverside Robotics Club"));
        fields.put("meeting.date", new FieldValue.DateValue(LocalDate.of(2026, 3, 5)));
        fields.put("meeting.attendees", new FieldValue.TextValue("Alex Chen, Priya Rao"));
        fields.put("action.item.task", new FieldValue.RepeatedTextValue(
                List.of("Reserve the van for the regional competition", "Confirm sponsor logo placement on the robot")));
        fields.put("action.item.owner", new FieldValue.RepeatedTextValue(List.of("Alex Chen", "Priya Rao")));
        fields.put("action.item.due", new FieldValue.RepeatedDateValue(
                List.of(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 15))));
        return new DocumentContent(fields);
    }

    private static Path fixturePath(String repositoryRelativePath) {
        Path path = repositoryRoot().resolve(repositoryRelativePath);
        assertTrue(Files.isRegularFile(path), () -> "missing fixture: " + path);
        return path;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("fixtures/public/templates"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("could not locate the repository root from the test working directory");
    }
}
