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
import org.apache.poi.xwpf.usermodel.XWPFTable;
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

    /**
     * A real form often holds content controls nobody tagged (a date picker, a check box) beside the
     * tagged ones; they are not bound to a field and must be left exactly as they are, not stop the
     * fill.
     */
    @Test
    void untaggedContentControlsBesideTaggedOnesAreLeftAsTheyAre() throws IOException {
        byte[] blank;
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph paragraph = doc.createParagraph();
            addUntaggedContentControl(paragraph, "Pick a date");
            addContentControl(paragraph, "meeting.title");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            blank = out.toByteArray();
        }

        FilledDocument filled = filler.fill(
                blank,
                List.of(scalarField("meeting.title", FieldType.TEXT)),
                new DocumentContent(Map.of("meeting.title", new FieldValue.TextValue("Spring Planning"))));

        String text = filled.reopenedBodyText();
        assertTrue(text.contains("Spring Planning"), text);
        assertTrue(text.contains("Pick a date"), text);
    }

    /** A repeated control Word shows without a title (no alias) is repeated all the same. */
    @Test
    void repeatedControlsWithoutATitleAreRepeated() throws IOException {
        byte[] blank;
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph prototype = doc.createParagraph();
            CTSdtRun sdt = prototype.getCTP().addNewSdt();
            sdt.addNewSdtPr().addNewTag().setVal("action.item.task");
            sdt.addNewSdtContent().addNewR().addNewT().setStringValue("[task]");
            addUntaggedContentControl(prototype, "untagged");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            blank = out.toByteArray();
        }

        FilledDocument filled = filler.fill(
                blank,
                List.of(repeatedField("action.item.task", FieldType.TEXT)),
                new DocumentContent(Map.of(
                        "action.item.task", new FieldValue.RepeatedTextValue(List.of("First task", "Second task")))));

        String text = filled.reopenedBodyText();
        assertTrue(text.contains("First task") && text.contains("Second task"), text);
    }

    /**
     * Word saves a control still showing its prompt marked as showing its
     * placeholder, with the prompt styled Placeholder Text. Once a value is
     * written, neither may stay, or Word shows the value grey and treats it
     * as the prompt; a run style the form itself chose is kept.
     */
    @Test
    void aValueWrittenIntoAWordPlaceholderIsNoLongerMarkedAsThePlaceholder() throws IOException {
        byte[] blank;
        try (XWPFDocument doc = new XWPFDocument()) {
            addWordPlaceholderContentControl(doc.createParagraph(), "meeting.title");
            addStyledContentControl(doc.createParagraph(), "meeting.organization", "Strong");
            addWordPlaceholderContentControl(doc.createParagraph(), "action.item.task");
            blank = toBytes(doc);
        }

        FilledDocument filled = filler.fill(
                blank,
                List.of(
                        scalarField("meeting.title", FieldType.TEXT),
                        scalarField("meeting.organization", FieldType.TEXT),
                        repeatedField("action.item.task", FieldType.TEXT)),
                new DocumentContent(Map.of(
                        "meeting.title", new FieldValue.TextValue("Spring Planning"),
                        "meeting.organization", new FieldValue.TextValue("Robotics Club"),
                        "action.item.task", new FieldValue.RepeatedTextValue(List.of("First task", "Second task")))));
        String documentXml = extractDocumentXml(filled.docxBytes());

        String text = filled.reopenedBodyText();
        assertTrue(text.contains("Spring Planning") && text.contains("First task") && text.contains("Second task"), text);
        assertTrue(!documentXml.contains("showingPlcHdr"), "a written control must not say it shows its placeholder: " + documentXml);
        assertTrue(!documentXml.contains("PlaceholderText"), "a written value must not keep the placeholder's style: " + documentXml);
        assertTrue(documentXml.contains("w:val=\"Strong\""), "the form's own run style must be kept: " + documentXml);
    }

    /** A tagged control saved with no content element at all is filled, not a crash. */
    @Test
    void aTaggedControlWithNoContentIsFilled() throws IOException {
        byte[] blank;
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().getCTP().addNewSdt().addNewSdtPr().addNewTag().setVal("meeting.title");
            doc.createParagraph().getCTP().addNewSdt().addNewSdtPr().addNewTag().setVal("action.item.task");
            blank = toBytes(doc);
        }

        FilledDocument filled = filler.fill(
                blank,
                List.of(scalarField("meeting.title", FieldType.TEXT), repeatedField("action.item.task", FieldType.TEXT)),
                new DocumentContent(Map.of(
                        "meeting.title", new FieldValue.TextValue("Spring Planning"),
                        "action.item.task", new FieldValue.RepeatedTextValue(List.of("First task", "Second task")))));

        String text = filled.reopenedBodyText();
        assertTrue(text.contains("Spring Planning") && text.contains("First task") && text.contains("Second task"), text);
    }

    /**
     * "No action items recorded." belongs to the built-in minutes. Any other
     * form's empty table loses its item row -- everything in it, a control
     * nobody tagged included, only ever belonged to one item -- and keeps
     * its header row.
     */
    @Test
    void anEmptyGroupInAnotherFormsTableLeavesOnlyTheHeaderRowAndNoSentence() throws IOException {
        byte[] blank;
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Expense");
            table.getRow(0).getCell(1).setText("Amount");
            XWPFParagraph firstCell = table.getRow(1).getCell(0).getParagraphs().getFirst();
            addUntaggedContentControl(firstCell, "Pick a category");
            addContentControl(firstCell, "expense.item");
            addContentControl(table.getRow(1).getCell(1).getParagraphs().getFirst(), "expense.amount");
            blank = toBytes(doc);
        }

        FilledDocument filled = filler.fill(
                blank,
                List.of(repeatedField("expense.item", FieldType.TEXT), repeatedField("expense.amount", FieldType.TEXT)),
                new DocumentContent(Map.of()));

        String text = filled.reopenedBodyText();
        assertTrue(!text.contains("No action items recorded."), text);
        assertTrue(!text.contains("[expense.item]") && !text.contains("[expense.amount]"), "a placeholder was left behind: " + text);
        assertTrue(text.contains("Expense") && text.contains("Amount"), text);
        try (XWPFDocument reopened = new XWPFDocument(new java.io.ByteArrayInputStream(filled.docxBytes()))) {
            assertEquals(1, reopened.getTables().getFirst().getNumberOfRows());
        }
    }

    /**
     * A single value a form fills into the same row, or the same paragraph, as an empty group keeps its
     * value: only the group's own controls go.
     */
    @Test
    void anEmptyGroupKeepsASingleValueFilledIntoTheSameRowOrParagraph() throws IOException {
        byte[] tableForm;
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Item");
            table.getRow(0).getCell(1).setText("Approver");
            addContentControl(table.getRow(1).getCell(0).getParagraphs().getFirst(), "item");
            addContentControl(table.getRow(1).getCell(1).getParagraphs().getFirst(), "approver");
            tableForm = toBytes(doc);
        }
        byte[] paragraphForm;
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph paragraph = doc.createParagraph();
            addContentControl(paragraph, "item");
            addContentControl(paragraph, "approver");
            paragraphForm = toBytes(doc);
        }
        List<FieldDefinition> fields = List.of(repeatedField("item", FieldType.TEXT), scalarField("approver", FieldType.TEXT));
        DocumentContent content = new DocumentContent(Map.of("approver", new FieldValue.TextValue("Dana Lee")));

        String fromTable = filler.fill(tableForm, fields, content).reopenedBodyText();
        String fromParagraph = filler.fill(paragraphForm, fields, content).reopenedBodyText();

        assertTrue(fromTable.contains("Dana Lee") && !fromTable.contains("[item]"), fromTable);
        assertTrue(fromParagraph.contains("Dana Lee") && !fromParagraph.contains("[item]"), fromParagraph);
    }

    /** A table has to keep a row, so a prototype row with nothing above it stays, without the group's controls. */
    @Test
    void anEmptyGroupThatIsATablesOnlyRowKeepsTheRowWithoutItsControls() throws IOException {
        byte[] blank;
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph cell = doc.createTable(1, 1).getRow(0).getCell(0).getParagraphs().getFirst();
            addUntaggedContentControl(cell, "Pick a category");
            addContentControl(cell, "expense.item");
            blank = toBytes(doc);
        }

        FilledDocument filled = filler.fill(blank, List.of(repeatedField("expense.item", FieldType.TEXT)), new DocumentContent(Map.of()));

        String text = filled.reopenedBodyText();
        assertTrue(!text.contains("No action items recorded.") && !text.contains("[expense.item]"), text);
        assertTrue(text.contains("Pick a category"), text);
        try (XWPFDocument reopened = new XWPFDocument(new java.io.ByteArrayInputStream(filled.docxBytes()))) {
            assertEquals(1, reopened.getTables().getFirst().getNumberOfRows());
        }
    }

    @Test
    void anEmptyGroupOfParagraphsInAnotherFormIsRemovedWithoutASentence() throws IOException {
        byte[] blank;
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("HEADER_BEFORE");
            XWPFParagraph prototype = doc.createParagraph();
            addUntaggedContentControl(prototype, "Pick a category");
            addContentControl(prototype, "expense.item");
            doc.createParagraph().createRun().setText("FOOTER_SIGNATURE_BLOCK");
            blank = toBytes(doc);
        }

        FilledDocument filled = filler.fill(blank, List.of(repeatedField("expense.item", FieldType.TEXT)), new DocumentContent(Map.of()));

        String text = filled.reopenedBodyText();
        assertTrue(!text.contains("No action items recorded."), text);
        assertTrue(!text.contains("[expense.item]") && !text.contains("Pick a category"), text);
        assertTrue(text.contains("HEADER_BEFORE") && text.contains("FOOTER_SIGNATURE_BLOCK"), text);
    }

    /** The built-in sentence takes its whole paragraph, so it never runs on from a control nobody tagged. */
    @Test
    void theBuiltInSentenceStandsAloneInItsParagraph() throws IOException {
        byte[] blank;
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph prototype = doc.createParagraph();
            addUntaggedContentControl(prototype, "Pick a date");
            addContentControl(prototype, "action.item.task");
            addContentControl(prototype, "action.item.owner");
            addContentControl(prototype, "action.item.due");
            blank = toBytes(doc);
        }
        List<FieldDefinition> builtInGroup = BuiltInMinutesTemplateRegistry.find("flowing-meeting-minutes").orElseThrow().fields().stream()
                .filter(field -> field.cardinality() == FieldCardinality.REPEATED)
                .toList();

        FilledDocument filled = filler.fill(blank, builtInGroup, new DocumentContent(Map.of()));

        try (XWPFDocument reopened = new XWPFDocument(new java.io.ByteArrayInputStream(filled.docxBytes()))) {
            assertEquals(
                    List.of("No action items recorded."),
                    reopened.getParagraphs().stream().map(XWPFParagraph::getText).filter(line -> !line.isEmpty()).toList());
        }
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

    /** A control as Word saves one still showing its prompt. */
    private static void addWordPlaceholderContentControl(XWPFParagraph paragraph, String tag) {
        CTSdtRun sdt = paragraph.getCTP().addNewSdt();
        CTSdtPr sdtPr = sdt.addNewSdtPr();
        sdtPr.addNewTag().setVal(tag);
        sdtPr.addNewShowingPlcHdr();
        CTR run = sdt.addNewSdtContent().addNewR();
        run.addNewRPr().addNewRStyle().setVal("PlaceholderText");
        run.addNewT().setStringValue("Click or tap here to enter text.");
    }

    private static void addStyledContentControl(XWPFParagraph paragraph, String tag, String runStyle) {
        CTSdtRun sdt = paragraph.getCTP().addNewSdt();
        sdt.addNewSdtPr().addNewTag().setVal(tag);
        CTR run = sdt.addNewSdtContent().addNewR();
        run.addNewRPr().addNewRStyle().setVal(runStyle);
        run.addNewT().setStringValue("[" + tag + "]");
    }

    private static void addUntaggedContentControl(XWPFParagraph paragraph, String text) {
        CTSdtRun sdt = paragraph.getCTP().addNewSdt();
        sdt.addNewSdtPr();
        sdt.addNewSdtContent().addNewR().addNewT().setStringValue(text);
    }

    private static byte[] toBytes(XWPFDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.write(out);
        return out.toByteArray();
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
