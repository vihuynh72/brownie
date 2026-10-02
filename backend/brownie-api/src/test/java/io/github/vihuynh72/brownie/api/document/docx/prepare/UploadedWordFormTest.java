package io.github.vihuynh72.brownie.api.document.docx.prepare;

import io.github.vihuynh72.brownie.api.document.docx.PoiDocxStructuralExtractor;
import io.github.vihuynh72.brownie.api.document.docx.PoiFillSpotEditor;
import io.github.vihuynh72.brownie.api.document.docx.PoiTemplateFiller;
import io.github.vihuynh72.brownie.api.document.docx.RawDocx;
import io.github.vihuynh72.brownie.core.prepare.FillSpotCandidateFinder;
import io.github.vihuynh72.brownie.core.prepare.FillableCopyCheck;
import io.github.vihuynh72.brownie.core.prepare.FoundSpots;
import io.github.vihuynh72.brownie.core.prepare.NamingSource;
import io.github.vihuynh72.brownie.core.prepare.PreparationMode;
import io.github.vihuynh72.brownie.core.prepare.RulesOnlySpotNamer;
import io.github.vihuynh72.brownie.core.prepare.RulesSpotNamer;
import io.github.vihuynh72.brownie.core.prepare.SpotNaming;
import io.github.vihuynh72.brownie.core.prepare.SpotNamingInput;
import io.github.vihuynh72.brownie.core.prepare.SpotPlan;
import io.github.vihuynh72.brownie.core.prepare.WordForms;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Word uploads taken the whole way the upload step takes them, with the
 * rules naming every place: cleaned, read, searched, named, edited, checked
 * and filled. Each case is a layout real forms use.
 */
class UploadedWordFormTest {

    private final PoiDocxStructuralExtractor extractor = new PoiDocxStructuralExtractor();
    private final PoiFillableCopyVerifier verifier = new PoiFillableCopyVerifier(extractor, new PoiTemplateFiller());
    private final PoiWordForms forms = new PoiWordForms(extractor.parserVersion());
    private final PoiFillSpotEditor editor = new PoiFillSpotEditor();

    @Test
    void theTableLedMinutesKeepTheirActionItemsAsARowThatRepeats() throws IOException {
        Made made = make(publicTemplate("table-led-meeting-minutes.docx"));

        assertThat(made.check().ok()).as(made.check().problems().toString()).isTrue();
        List<FieldDefinition> actions = made.plan().fields().stream()
                .filter(field -> field.fieldId().startsWith("action.item."))
                .toList();
        assertThat(actions).isNotEmpty();
        assertThat(actions).allSatisfy(field -> assertThat(field.cardinality()).isEqualTo(FieldCardinality.REPEATED));
        assertThat(made.plan().fields()).filteredOn(field -> !field.fieldId().startsWith("action.item."))
                .allSatisfy(field -> assertThat(field.cardinality()).isEqualTo(FieldCardinality.SCALAR));

        Map<String, FieldValue> values = new LinkedHashMap<>();
        for (FieldDefinition field : actions) {
            values.put(field.fieldId(), field.type() == FieldType.DATE
                    ? new FieldValue.RepeatedDateValue(List.of(LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 5)))
                    : new FieldValue.RepeatedTextValue(List.of("First " + field.fieldId(), "Second " + field.fieldId())));
        }
        String filled = PoiFillableCopyVerifier.visibleText(
                new PoiTemplateFiller().fill(made.edited(), made.plan().fields(), new DocumentContent(values)).docxBytes());
        assertThat(filled).contains("First action.item.task", "Second action.item.task");
    }

    @Test
    void aLabelCellBesideTheCellThatHoldsTheAnswerGetsNoSpotOfItsOwn() {
        String untagged = "<w:sdt><w:sdtPr><w:showingPlcHdr/><w:text/></w:sdtPr><w:sdtContent><w:r>"
                + "<w:t>Click or tap here to enter text.</w:t></w:r></w:sdtContent></w:sdt>";
        Made made = make(docx(table(
                row(cell(text("Name:")), cell(control("name"))),
                row(cell(text("Email:")), cell(untagged)),
                row(cell(text("Phone:")), cell("")),
                row(cell(text("City:")), cell(text("____________"))))));

        assertThat(made.check().ok()).as(made.check().problems().toString()).isTrue();
        assertThat(made.plan().fields()).extracting(FieldDefinition::fieldId).containsExactly("name", "email", "phone", "city");
    }

    @Test
    void aCellThatContinuesAVerticalMergeGetsNoSpotAndTheLineBesideItIsNamedByTheMergedCell() {
        String restart = "<w:tc><w:tcPr><w:vMerge w:val=\"restart\"/></w:tcPr><w:p>" + text("Address") + "</w:p></w:tc>";
        String continued = "<w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc>";
        Made made = make(docx(table(
                row(cell(text("Name")), cell("")),
                row(restart, cell("")),
                row(continued, cell("")))));

        assertThat(made.check().ok()).as(made.check().problems().toString()).isTrue();
        assertThat(made.plan().fields()).extracting(FieldDefinition::fieldId).containsExactly("name", "address", "address.2");
        assertThat(made.found().candidates()).extracting(candidate -> candidate.anchor().paragraphNodeId())
                .noneMatch(nodeId -> nodeId.contains("row2/cell0"));
    }

    @Test
    void aValueOnATabThatDrawsALineFollowsItsLabelAndTheLineFillsWhatIsLeft() {
        Made made = make(docx("<w:p><w:pPr><w:tabs><w:tab w:val=\"left\" w:leader=\"underscore\" w:pos=\"7000\"/></w:tabs></w:pPr>"
                + text("Name:") + "<w:r><w:tab/></w:r></w:p>"));

        assertThat(made.check().ok()).as(made.check().problems().toString()).isTrue();
        assertThat(made.plan().fields()).extracting(FieldDefinition::fieldId).containsExactly("name");
        assertThat(filledText(made)).contains("Name: Value of name\t");
    }

    @Test
    void aValueWrittenOnABlankOfUnderlinedTabsTakesTheirPlace() {
        Made made = make(docx("<w:p>" + text("Phone: ") + "<w:r><w:rPr><w:u w:val=\"single\"/></w:rPr><w:tab/><w:tab/></w:r></w:p>"));

        assertThat(made.check().ok()).as(made.check().problems().toString()).isTrue();
        assertThat(made.plan().fields()).extracting(FieldDefinition::fieldId).containsExactly("phone");
        assertThat(filledText(made)).contains("Phone: Value of phone\n");
        assertThat(PoiFillableCopyVerifier.visibleText(new PoiTemplateFiller()
                .fill(made.edited(), made.plan().fields(), new DocumentContent(Map.of())).docxBytes())).contains("Phone: \t\t");
    }

    @Test
    void aBlankNamedLikeATagInTheHeaderGetsAnIdOfItsOwnAndPassesItsCheck() throws IOException {
        byte[] form = RawDocx.builder()
                .document(text("Company: ______").transform(run -> "<w:p>" + run + "</w:p>")
                        + "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdH\"/></w:sectPr>")
                .part("word/header1.xml", RawDocx.HEADER_CONTENT_TYPE, RawDocx.wordRoot("hdr", "<w:p>" + control("company") + "</w:p>"))
                .documentRelationship("rIdH", RawDocx.RELATIONSHIP_TYPE_BASE + "header", "header1.xml", false)
                .build();

        Made made = make(form);

        assertThat(made.check().ok()).as(made.check().problems().toString()).isTrue();
        assertThat(made.plan().fields()).extracting(FieldDefinition::fieldId).containsExactly("company.2");
    }

    @Test
    void anEmptyPromptFieldBetweenUnderscoresIsThePlaceAndTheUnderscoresStay() {
        String field = "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> FILLIN \"Your name\" </w:instrText></w:r>"
                + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";
        Made made = make(docx("<w:p>" + text("Name: ___") + field + text("___") + "</w:p>"));

        assertThat(made.check().ok()).as(made.check().problems().toString()).isTrue();
        assertThat(made.plan().spots()).extracting(spot -> spot.kind().name()).containsExactly("FORM_FIELD");
        assertThat(filledText(made)).contains("Name: ___Value of " + made.plan().fields().getFirst().fieldId() + "___");
    }

    // ---------------------------------------------------------------- the upload step's Word path

    private record Made(FoundSpots found, SpotPlan plan, byte[] edited, FillableCopyCheck check) {
    }

    /** What {@code FillableFormService} does with a Word upload, with the rules as the naming step. */
    private Made make(byte[] upload) {
        byte[] prepared = new PoiWorkingCopyPreparer().prepare(upload, PreparationMode.UPLOAD).docxBytes();
        WordForms.ReadForm read = forms.read(prepared);
        FoundSpots found = FillSpotCandidateFinder.find(read.outline());
        SpotNamingInput input = RulesSpotNamer.namingInput(found);
        SpotNaming naming = input.candidates().isEmpty()
                ? new SpotNaming(List.of(), null, NamingSource.RULES, SpotNaming.NO_CANDIDATES, List.of())
                : new RulesOnlySpotNamer(SpotNaming.DISABLED).name(1, 1, input);
        SpotPlan plan = RulesSpotNamer.plan(found, naming, Set.of());
        byte[] base = plan.removedRowNodeIds().isEmpty() ? read.docxBytes() : forms.withoutRows(read.docxBytes(), plan.removedRowNodeIds());
        byte[] edited = editor.apply(base, plan.edits()).docxBytes();
        return new Made(found, plan, edited, verifier.check(base, edited, plan.fields()));
    }

    // ---------------------------------------------------------------- building forms

    private static byte[] docx(String body) {
        try {
            return RawDocx.builder().document(body + "<w:p/>").build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(String text) {
        return "<w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r>";
    }

    private static String control(String tag) {
        return "<w:sdt><w:sdtPr><w:tag w:val=\"" + tag + "\"/><w:text/></w:sdtPr><w:sdtContent><w:r><w:t>x</w:t></w:r></w:sdtContent></w:sdt>";
    }

    private static String table(String... rows) {
        return "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol/><w:gridCol/></w:tblGrid>" + String.join("", rows) + "</w:tbl>";
    }

    private static String row(String... cells) {
        return "<w:tr>" + String.join("", cells) + "</w:tr>";
    }

    private static String cell(String paragraphContent) {
        return "<w:tc><w:tcPr/><w:p>" + paragraphContent + "</w:p></w:tc>";
    }

    /** The visible text of the copy with every field filled with "Value of" its id. */
    private static String filledText(Made made) {
        Map<String, FieldValue> values = new LinkedHashMap<>();
        for (FieldDefinition field : made.plan().fields()) {
            values.put(field.fieldId(), new FieldValue.TextValue("Value of " + field.fieldId()));
        }
        return PoiFillableCopyVerifier.visibleText(
                new PoiTemplateFiller().fill(made.edited(), made.plan().fields(), new DocumentContent(values)).docxBytes());
    }

    private static byte[] publicTemplate(String fileName) throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("fixtures/public/templates"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("could not locate the repository root from the test working directory");
        }
        return Files.readAllBytes(current.resolve("fixtures/public/templates").resolve(fileName));
    }
}
