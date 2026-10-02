package io.github.vihuynh72.brownie.api.document.docx.prepare;

import io.github.vihuynh72.brownie.api.document.docx.PoiDocxStructuralExtractor;
import io.github.vihuynh72.brownie.api.document.docx.PoiFillSpotEditor;
import io.github.vihuynh72.brownie.api.document.docx.PoiTemplateFiller;
import io.github.vihuynh72.brownie.api.document.docx.RawDocx;
import io.github.vihuynh72.brownie.core.compile.FilledDocument;
import io.github.vihuynh72.brownie.core.prepare.FillSpotCandidateFinder;
import io.github.vihuynh72.brownie.core.prepare.FillableCopyCheck;
import io.github.vihuynh72.brownie.core.prepare.FoundSpots;
import io.github.vihuynh72.brownie.core.prepare.PreparationMode;
import io.github.vihuynh72.brownie.core.prepare.RulesOnlySpotNamer;
import io.github.vihuynh72.brownie.core.prepare.RulesSpotNamer;
import io.github.vihuynh72.brownie.core.prepare.SpotNaming;
import io.github.vihuynh72.brownie.core.prepare.SpotPlan;
import io.github.vihuynh72.brownie.core.prepare.WordForms;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check a working copy passes before it is stored, and the whole path
 * a sample form takes without a render: cleaned, read, searched, named by
 * the rules, edited, checked and filled.
 */
class PoiFillableCopyVerifierTest {

    private final PoiDocxStructuralExtractor extractor = new PoiDocxStructuralExtractor();
    private final PoiFillableCopyVerifier verifier = new PoiFillableCopyVerifier(extractor, new PoiTemplateFiller());
    private final PoiWordForms forms = new PoiWordForms(extractor.parserVersion());
    private final PoiFillSpotEditor editor = new PoiFillSpotEditor();

    @Test
    void eachSampleFormBecomesACopyThatPassesItsCheckAndFillsEveryValueAndPrintsItsBlanksWhenEmpty() throws IOException {
        Map<String, Integer> expectedSpots = Map.of("membership-application", 12, "equipment-request", 16, "reference-letter", 14);
        for (Map.Entry<String, Integer> form : expectedSpots.entrySet()) {
            byte[] prepared = new PoiWorkingCopyPreparer().prepare(PoiWordFormsTest.sampleForm(form.getKey() + ".docx"),
                    PreparationMode.UPLOAD).docxBytes();
            WordForms.ReadForm read = forms.read(prepared);
            FoundSpots found = FillSpotCandidateFinder.find(read.outline());
            SpotNaming naming = new RulesOnlySpotNamer(SpotNaming.DISABLED).name(1, 1, RulesSpotNamer.namingInput(found));
            SpotPlan plan = RulesSpotNamer.plan(found, naming, Set.of());
            byte[] edited = editor.apply(read.docxBytes(), plan.edits()).docxBytes();

            FillableCopyCheck check = verifier.check(read.docxBytes(), edited, plan.fields());

            assertThat(check.ok()).as(form.getKey() + ": " + check.problems()).isTrue();
            assertThat(plan.spots()).as(form.getKey()).hasSize(form.getValue());

            Map<String, FieldValue> values = new LinkedHashMap<>();
            for (FieldDefinition field : plan.fields()) {
                values.put(field.fieldId(), field.type() == FieldType.DATE
                        ? new FieldValue.DateValue(LocalDate.of(2026, 3, 4))
                        : new FieldValue.TextValue("Value of " + field.fieldId()));
            }
            String filled = PoiFillableCopyVerifier.visibleText(
                    new PoiTemplateFiller().fill(edited, plan.fields(), new DocumentContent(values)).docxBytes());
            for (FieldDefinition field : plan.fields()) {
                if (field.type() == FieldType.TEXT) {
                    assertThat(filled).as(form.getKey()).contains("Value of " + field.fieldId());
                }
            }
            FilledDocument empty = new PoiTemplateFiller().fill(edited, plan.fields(), new DocumentContent(Map.of()));
            assertThat(PoiFillableCopyVerifier.visibleText(empty.docxBytes()).replace(" ", ""))
                    .as(form.getKey() + " prints as it did when nothing is filled")
                    .isEqualTo(PoiFillableCopyVerifier.visibleText(read.docxBytes()).replace(" ", ""));
        }
    }

    @Test
    void aCopyWhoseTextChangedFailsAsAWhole() {
        byte[] before = docx("<w:p><w:r><w:t>Name: </w:t></w:r><w:sdt><w:sdtPr><w:tag w:val=\"name\"/></w:sdtPr>"
                + "<w:sdtContent><w:r><w:t>____</w:t></w:r></w:sdtContent></w:sdt></w:p>");
        byte[] after = docx("<w:p><w:r><w:t>Nome: </w:t></w:r><w:sdt><w:sdtPr><w:tag w:val=\"name\"/></w:sdtPr>"
                + "<w:sdtContent><w:r><w:t>____</w:t></w:r></w:sdtContent></w:sdt></w:p>");

        FillableCopyCheck check = verifier.check(before, after, List.of(field("name", FieldCardinality.SCALAR)));

        assertThat(check.copyFailed()).isTrue();
    }

    @Test
    void aFieldWhoseTagIsMissingTwiceOverOrOutsideTheBodyFailsOnItsOwn() {
        byte[] copy = docx("<w:p>" + control("twice") + control("twice") + control("once") + "</w:p>");

        FillableCopyCheck check = verifier.check(copy, copy, List.of(
                field("once", FieldCardinality.SCALAR), field("twice", FieldCardinality.SCALAR), field("nowhere", FieldCardinality.SCALAR)));

        assertThat(check.copyFailed()).isFalse();
        assertThat(check.failedFieldIds()).containsExactlyInAnyOrder("twice", "nowhere");
    }

    @Test
    void aRepeatingRowTheFillerCannotRepeatIsReportedSoItCanBeFilledOnce() {
        String table = "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol/></w:tblGrid><w:tr><w:tc><w:tcPr/><w:p><w:r><w:t>Item</w:t></w:r></w:p></w:tc></w:tr>"
                + "<w:tr><w:tc><w:tcPr/><w:p>%s</w:p></w:tc></w:tr></w:tbl><w:p/>";
        byte[] copy = docx(String.format(table, "<w:r><w:t>First table</w:t></w:r>") + String.format(table, control("item")));

        FillableCopyCheck check = verifier.check(copy, copy, List.of(field("item", FieldCardinality.REPEATED)));

        assertThat(check.repeatedGroupFailed()).as(check.problems().toString()).isTrue();
        assertThat(check.ok()).isFalse();
        assertThat(verifier.check(copy, copy, List.of(field("item", FieldCardinality.SCALAR))).ok()).isTrue();
    }

    @Test
    void aCopyStillHoldingAMacroProjectFails() throws IOException {
        byte[] copy = RawDocx.builder()
                .document("<w:p>" + control("name") + "</w:p>")
                .part("word/vbaProject.bin", "application/vnd.ms-office.vbaProject", new byte[] {1, 2, 3})
                .build();

        assertThat(verifier.check(copy, copy, List.of(field("name", FieldCardinality.SCALAR))).copyFailed()).isTrue();
    }

    private static String control(String tag) {
        return "<w:sdt><w:sdtPr><w:tag w:val=\"" + tag + "\"/><w:text/></w:sdtPr><w:sdtContent><w:r><w:t>x</w:t></w:r></w:sdtContent></w:sdt>";
    }

    private static FieldDefinition field(String tag, FieldCardinality cardinality) {
        return new FieldDefinition(tag, FieldType.TEXT, cardinality, FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag(tag));
    }

    private static byte[] docx(String body) {
        try {
            return RawDocx.builder().document(body).build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
