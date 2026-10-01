package io.github.vihuynh72.brownie.api.document.docx;

import io.github.vihuynh72.brownie.core.compile.FilledDocument;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.prepare.AnchorPlacement;
import io.github.vihuynh72.brownie.core.prepare.DocxAnchor;
import io.github.vihuynh72.brownie.core.prepare.FillSpotPlacementException;
import io.github.vihuynh72.brownie.core.prepare.SpotEdit;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.CandidateBindingReport;
import io.github.vihuynh72.brownie.core.template.FieldBindingCandidateProposer;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.ParagraphAnchorText;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The editor against small Word files written as XML: where each kind of
 * place puts its control, what goes in it, what formatting it takes, what
 * it refuses, and that what it writes reads back as a supported document
 * the filler fills.
 */
class PoiFillSpotEditorTest {

    private static final PoiDocxStructuralExtractor EXTRACTOR = new PoiDocxStructuralExtractor();
    private static final String ARIAL_14_RED = "<w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\"/><w:b/><w:i/><w:caps/>"
            + "<w:color w:val=\"C00000\"/><w:sz w:val=\"28\"/><w:szCs w:val=\"28\"/><w:u w:val=\"single\"/><w:lang w:val=\"en-GB\"/></w:rPr>";

    private final PoiFillSpotEditor editor = new PoiFillSpotEditor();

    // ---------------------------------------------------------------- AT

    @Test
    void aSpotAtTheStartOfARunTakesThatRunsFontSizeColourAndLanguageButNotItsEmphasis() {
        byte[] docx = docx(paragraph(run(ARIAL_14_RED, "Name")));

        byte[] edited = apply(docx, insert(at(docx, "p0", 0), "full.name", "Full name", null));

        CTP p = paragraphs(edited).getFirst();
        assertThat(p.sizeOfSdtArray()).isEqualTo(1);
        assertThat(childNames(p)).containsExactly("sdt", "r");
        CTR spotRun = p.getSdtArray(0).getSdtContent().getRArray(0);
        String properties = spotRun.getRPr().xmlText();
        assertThat(properties).contains("Arial").contains("C00000").contains("28").contains("en-GB");
        assertThat(properties).doesNotContain("<w:b").doesNotContain("<w:i ").doesNotContain("<w:i/>")
                .doesNotContain("caps").doesNotContain("<w:u");
        assertControl(p.getSdtArray(0), "full.name", "Full name", false);
        assertThat(anchorText(edited, "p0")).isEqualTo("Name");
    }

    @Test
    void aSpotInsideARunSplitsItKeepingBothHalvesFormattingAndSpaces() {
        byte[] docx = docx(paragraph(run(ARIAL_14_RED, "Name:  here")));

        byte[] edited = apply(docx, insert(at(docx, "p0", 6), "name", "Name", null));

        CTP p = paragraphs(edited).getFirst();
        assertThat(childNames(p)).containsExactly("r", "sdt", "r");
        assertThat(RunText.of(p.getRArray(0))).isEqualTo("Name: ");
        assertThat(RunText.of(p.getRArray(1))).isEqualTo(" here");
        assertThat(p.getRArray(0).getTArray(0).xmlText()).contains("xml:space=\"preserve\"");
        assertThat(p.getRArray(1).getTArray(0).xmlText()).contains("xml:space=\"preserve\"");
        assertThat(p.getRArray(0).getRPr().xmlText()).contains("<w:b");
        assertThat(p.getRArray(1).getRPr().xmlText()).contains("<w:b");
        assertThat(anchorText(edited, "p0")).isEqualTo("Name:  here");
    }

    @Test
    void aSpotAtTheEndAfterAColonGetsOneSpaceBeforeItOutsideTheControl() {
        byte[] docx = docx(paragraph(run("Name:")));

        byte[] edited = apply(docx, insert(at(docx, "p0", 5), "name", "Name", null));

        CTP p = paragraphs(edited).getFirst();
        assertThat(childNames(p)).containsExactly("r", "r", "sdt");
        assertThat(RunText.of(p.getRArray(1))).isEqualTo(" ");
        assertThat(anchorText(edited, "p0")).isEqualTo("Name: ");
    }

    @Test
    void aSpotJustBeforeATabGetsOneSpaceBeforeItUnlessOneIsThere() {
        byte[] docx = docx(paragraph(run("Name:"), "<w:r><w:tab/></w:r>"));

        byte[] edited = apply(docx, insert(at(docx, "p0", 5), "name", "Name", null));

        CTP p = paragraphs(edited).getFirst();
        assertThat(childNames(p)).containsExactly("r", "r", "sdt", "r");
        assertThat(anchorText(edited, "p0")).isEqualTo("Name: \t");

        byte[] spaced = docx(paragraph(run("Name: "), "<w:r><w:tab/></w:r>"));
        assertThat(anchorText(apply(spaced, insert(at(spaced, "p0", 6), "name", "Name", null)), "p0")).isEqualTo("Name: \t");
    }

    @Test
    void aSpotBetweenTwoRunsGoesBetweenThemAndTakesTheFormattingOfTheOneBefore() {
        byte[] docx = docx(paragraph(run("<w:rPr><w:sz w:val=\"20\"/></w:rPr>", "Date: "), run("<w:rPr><w:sz w:val=\"40\"/></w:rPr>", "(today)")));

        byte[] edited = apply(docx, insert(at(docx, "p0", 6), "date", "Date", null));

        CTP p = paragraphs(edited).getFirst();
        assertThat(childNames(p)).containsExactly("r", "sdt", "r");
        assertThat(p.getSdtArray(0).getSdtContent().getRArray(0).getRPr().xmlText()).contains("20").doesNotContain("40");
    }

    @Test
    void aSpotInAnEmptyParagraphTakesTheParagraphMarksFormatting() {
        byte[] docx = docx("<w:p><w:pPr><w:rPr><w:rFonts w:ascii=\"Georgia\"/><w:b/></w:rPr></w:pPr></w:p>");

        byte[] edited = apply(docx, insert(at(docx, "p0", 0), "notes", "Notes", null));

        CTP p = paragraphs(edited).getFirst();
        assertThat(childNames(p)).containsExactly("pPr", "sdt");
        String properties = p.getSdtArray(0).getSdtContent().getRArray(0).getRPr().xmlText();
        assertThat(properties).contains("Georgia").doesNotContain("<w:b");
    }

    @Test
    void aSpotInATableCellIsARunLevelControlInsideTheCellsParagraph() {
        byte[] docx = docx(table(row(cell(paragraph(run("Name"))), cell("<w:p/>"))));

        byte[] edited = apply(docx, insert(at(docx, "tbl0/row0/cell1/p0", 0), "name", "Name", null));

        try (XWPFDocument document = open(edited)) {
            CTP cellParagraph = document.getTables().getFirst().getRow(0).getCell(1).getParagraphs().getFirst().getCTP();
            assertThat(cellParagraph.sizeOfSdtArray()).isEqualTo(1);
            assertThat(document.getTables().getFirst().getRow(0).getCell(1).getCTTc().xmlText()).doesNotContain("<w:sdtContent><w:p");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertFillsWithNeighbourStyle(edited, "name", FieldCardinality.SCALAR);
    }

    // ---------------------------------------------------------------- REPLACE

    @Test
    void replacingAcrossThreeDifferentlyFormattedRunsMovesThemIntoTheControlKeepingTheFirstRunsFormatting() {
        byte[] docx = docx(paragraph(
                run("Name: "),
                run("<w:rPr><w:u w:val=\"single\"/></w:rPr>", "____"),
                run("<w:rPr><w:b/></w:rPr>", "____"),
                run("<w:rPr><w:i/></w:rPr>", "____"),
                run(" end")));

        byte[] edited = apply(docx, insert(replace(docx, "p0", 6, 18), "name", "Name", "____________"));

        CTP p = paragraphs(edited).getFirst();
        assertThat(childNames(p)).containsExactly("r", "sdt", "r");
        CTSdtRun sdt = p.getSdtArray(0);
        assertThat(sdt.getSdtContent().sizeOfRArray()).isEqualTo(3);
        assertThat(sdt.getSdtContent().getRArray(0).getRPr().xmlText()).contains("<w:u");
        assertThat(sdt.getSdtPr().isSetShowingPlcHdr()).isFalse();
        assertThat(anchorText(edited, "p0")).isEqualTo("Name:  end");

        FilledDocument filled = new PoiTemplateFiller().fill(edited, List.of(field("name", FieldCardinality.SCALAR)),
                new DocumentContent(Map.of("name", new FieldValue.TextValue("Ada Lovelace"))));
        CTR written = paragraphs(filled.docxBytes()).getFirst().getSdtArray(0).getSdtContent().getRArray(0);
        assertThat(RunText.of(written)).isEqualTo("Ada Lovelace");
        assertThat(written.getRPr().xmlText()).contains("<w:u");
    }

    @Test
    void replacingPartOfARunSplitsItAtBothEnds() {
        byte[] docx = docx(paragraph(run("Company: [Company name] Ltd")));

        byte[] edited = apply(docx, insert(replace(docx, "p0", 9, 23), "company", "Company", "[Company name]"));

        CTP p = paragraphs(edited).getFirst();
        assertThat(childNames(p)).containsExactly("r", "sdt", "r");
        assertThat(RunText.of(p.getRArray(0))).isEqualTo("Company: ");
        assertThat(RunText.of(p.getSdtArray(0).getSdtContent().getRArray(0))).isEqualTo("[Company name]");
        assertThat(RunText.of(p.getRArray(1))).isEqualTo(" Ltd");
    }

    @Test
    void aBracketedPromptBecomesAControlShowingItsPlaceholder() {
        byte[] docx = docx(paragraph(run("[Your name]")));

        byte[] edited = apply(docx, insert(replace(docx, "p0", 0, 11), "your.name", "Your name", "[Your name]"));

        assertControl(paragraphs(edited).getFirst().getSdtArray(0), "your.name", "Your name", true);
    }

    @Test
    void aWholeLineOfUnderscoresIsReplacedAndALineWithWordsGetsTheSpotAtItsEnd() {
        byte[] docx = docx(paragraph(run("  ______  ")) + paragraph(run("Signed by")));

        byte[] edited = apply(docx,
                insert(whole(docx, "p0"), "line", "Line", null),
                insert(whole(docx, "p1"), "signed.by", "Signed by", null));

        List<CTP> paragraphs = paragraphs(edited);
        assertThat(childNames(paragraphs.get(0))).containsExactly("sdt");
        assertThat(RunText.of(paragraphs.get(0).getSdtArray(0).getSdtContent().getRArray(0))).isEqualTo("  ______  ");
        assertThat(childNames(paragraphs.get(1))).containsExactly("r", "sdt");
    }

    @Test
    void aWholeLineOfDashesIsReplacedAsTheVersionItIsAddedToRecordsIt() {
        byte[] docx = docx(paragraph(run("----------")));

        byte[] edited = apply(docx, insert(whole(docx, "p0"), "comments", "Comments", "----------"));

        CTP p = paragraphs(edited).getFirst();
        assertThat(childNames(p)).containsExactly("sdt");
        assertThat(RunText.of(p.getSdtArray(0).getSdtContent().getRArray(0))).isEqualTo("----------");
    }

    @Test
    void severalPlacesInOneLineAreAllFoundInTheFileAsItWasGiven() {
        byte[] docx = docx(paragraph(run("From ____ to ____ on [day]")));

        byte[] edited = apply(docx,
                insert(replace(docx, "p0", 5, 9), "from", "From", "____"),
                insert(replace(docx, "p0", 13, 17), "to", "To", "____"),
                insert(replace(docx, "p0", 21, 26), "day", "Day", "[day]"));

        CTP p = paragraphs(edited).getFirst();
        assertThat(p.sizeOfSdtArray()).isEqualTo(3);
        assertThat(anchorText(edited, "p0")).isEqualTo("From  to  on ");
        assertThat(extracted(edited)).isInstanceOf(DocxExtractionOutcome.Supported.class);
    }

    // ---------------------------------------------------------------- controls

    @Test
    void anExistingControlIsTaggedAndTitledAndOtherwiseLeftAsItWas() {
        byte[] docx = docx(paragraph(run("Name: ") + "<w:sdt><w:sdtPr><w:id w:val=\"5\"/><w:text/></w:sdtPr>"
                + "<w:sdtContent><w:r><w:t>Click here</w:t></w:r></w:sdtContent></w:sdt>"));
        DocxAnchor anchor = new DocxAnchor(DocumentPartKind.MAIN_DOCUMENT, "p0", AnchorPlacement.EXISTING_CONTROL, 6, 6,
                DocxAnchor.hashOf("Name: "), PoiDocxStructuralExtractor.PARSER_VERSION, "p0/sdt1");

        byte[] edited = apply(docx, insert(anchor, "name", "Name", null));

        CTSdtRun sdt = paragraphs(edited).getFirst().getSdtArray(0);
        assertControl(sdt, "name", "Name", false);
        assertThat(sdt.getSdtPr().getId().getVal().intValue()).isEqualTo(5);
        assertThat(RunText.of(sdt.getSdtContent().getRArray(0))).isEqualTo("Click here");

        byte[] retagged = apply(edited, new SpotEdit.Retag(DocumentPartKind.MAIN_DOCUMENT, "p0/sdt1", "full.name", "Full name"));
        assertControl(paragraphs(retagged).getFirst().getSdtArray(0), "full.name", "Full name", false);
    }

    @Test
    void unwrappingASpotPutsTheLineBackAsItReadBefore() {
        byte[] docx = docx(paragraph(run("Name: "), run("<w:rPr><w:u w:val=\"single\"/></w:rPr>", "______"), run(".")));
        byte[] inserted = apply(docx,
                insert(replace(docx, "p0", 6, 12), "name", "Name", "______"),
                insert(at(docx, "p0", 13), "after", "After", null));

        byte[] unwrapped = apply(inserted, new SpotEdit.Unwrap("name"), new SpotEdit.Unwrap("after"));

        CTP p = paragraphs(unwrapped).getFirst();
        assertThat(p.sizeOfSdtArray()).isZero();
        assertThat(anchorText(unwrapped, "p0")).isEqualTo("Name: ______.");
        assertThat(p.getRArray(1).getRPr().xmlText()).contains("<w:u");
        assertThat(extracted(unwrapped)).isInstanceOf(DocxExtractionOutcome.Supported.class);
    }

    @Test
    void untaggingASpotLeavesItsControlWithoutATagOrTitle() {
        byte[] docx = docx(paragraph(run("[Company]")));
        byte[] inserted = apply(docx, insert(replace(docx, "p0", 0, 9), "company", "Company", "[Company]"));

        byte[] untagged = apply(inserted, new SpotEdit.Untag("company"));

        CTSdtRun sdt = paragraphs(untagged).getFirst().getSdtArray(0);
        assertThat(sdt.getSdtPr().isSetTag()).isFalse();
        assertThat(sdt.getSdtPr().isSetAlias()).isFalse();
        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graph(untagged));
        assertThat(report.candidates()).isEmpty();
        assertThat(report.untaggedContentControlCount()).isEqualTo(1);
    }

    @Test
    void everyNewControlHasItsOwnIdThatNoControlAlreadyHas() {
        byte[] docx = docx(paragraph(run("a ____ b ____ c ____"), "<w:sdt><w:sdtPr><w:id w:val=\"77\"/></w:sdtPr>"
                + "<w:sdtContent><w:r><w:t>x</w:t></w:r></w:sdtContent></w:sdt>"));

        byte[] edited = apply(docx,
                insert(replace(docx, "p0", 2, 6), "one", "One", "____"),
                insert(replace(docx, "p0", 9, 13), "two", "Two", "____"),
                insert(replace(docx, "p0", 16, 20), "three", "Three", "____"));

        List<Long> ids = new ArrayList<>();
        for (CTSdtRun sdt : paragraphs(edited).getFirst().getSdtArray()) {
            ids.add(sdt.getSdtPr().getId().getVal().longValue());
        }
        assertThat(ids).hasSize(4).doesNotHaveDuplicates().allMatch(id -> id > 0 && id <= Integer.MAX_VALUE);
    }

    // ---------------------------------------------------------------- refusals

    @Test
    void aPlaceWhoseEdgeFallsInsideALinkIsRefused() {
        byte[] docx = docx("<w:p><w:hyperlink w:anchor=\"top\"><w:r><w:t>Read the rules</w:t></w:r></w:hyperlink></w:p>");

        assertRefused(docx, insert(at(docx, "p0", 4), "x", "X", null), FillSpotPlacementException.Reason.INSIDE_LINK);
        assertRefused(docx, insert(replace(docx, "p0", 0, 14), "x", "X", null), FillSpotPlacementException.Reason.INSIDE_LINK);
    }

    @Test
    void aPlaceInsideAFieldWordWorksOutIsRefused() {
        byte[] docx = docx(paragraph(run("Page "),
                "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> PAGE </w:instrText></w:r>"
                        + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>12</w:t></w:r>"
                        + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>",
                run(" of the form")));

        assertRefused(docx, insert(at(docx, "p0", 6), "x", "X", null), FillSpotPlacementException.Reason.INSIDE_FIELD_CODE);
        assertRefused(docx, insert(replace(docx, "p0", 5, 7), "x", "X", null), FillSpotPlacementException.Reason.INSIDE_FIELD_CODE);
        assertRefused(docx, insert(replace(docx, "p0", 0, 10), "x", "X", null), FillSpotPlacementException.Reason.INSIDE_FIELD_CODE);
        // Right before the field starts is outside it.
        assertThat(extracted(apply(docx, insert(at(docx, "p0", 5), "page.label", "Page", null))))
                .isInstanceOf(DocxExtractionOutcome.Supported.class);
    }

    @Test
    void aPlaceInAHeaderIsRefused() {
        byte[] docx = docx(paragraph(run("Body")));
        DocxAnchor header = new DocxAnchor(DocumentPartKind.HEADER, "p0", AnchorPlacement.AT, 0, 0, DocxAnchor.hashOf(""),
                PoiDocxStructuralExtractor.PARSER_VERSION, null);

        assertRefused(docx, insert(header, "x", "X", null), FillSpotPlacementException.Reason.HEADER_FOOTER);
        assertRefused(docx, new SpotEdit.Retag(DocumentPartKind.FOOTER, "p0/sdt0", "x", "X"), FillSpotPlacementException.Reason.HEADER_FOOTER);
    }

    @Test
    void aPlaceChosenOnChangedTextOrAnotherReadingIsRefusedAsStale() {
        byte[] docx = docx(paragraph(run("Name: ____")));
        DocxAnchor good = replace(docx, "p0", 6, 10);
        DocxAnchor changedText = new DocxAnchor(good.part(), good.paragraphNodeId(), good.placement(), 6, 10,
                DocxAnchor.hashOf("Name: _____"), good.parserVersion(), null);
        DocxAnchor otherReader = new DocxAnchor(good.part(), good.paragraphNodeId(), good.placement(), 6, 10,
                good.anchorTextHash(), "brownie-docx-graph-v2+poi-5.5.1", null);
        DocxAnchor pastTheEnd = new DocxAnchor(good.part(), good.paragraphNodeId(), good.placement(), 6, 30,
                good.anchorTextHash(), good.parserVersion(), null);

        assertRefused(docx, insert(changedText, "x", "X", null), FillSpotPlacementException.Reason.ANCHOR_STALE);
        assertRefused(docx, insert(otherReader, "x", "X", null), FillSpotPlacementException.Reason.ANCHOR_STALE);
        assertRefused(docx, insert(pastTheEnd, "x", "X", null), FillSpotPlacementException.Reason.ANCHOR_STALE);
    }

    @Test
    void aParagraphControlOrTagThatIsNotThereIsNotFound() {
        byte[] docx = docx(paragraph(run("Name")));
        DocxAnchor missing = new DocxAnchor(DocumentPartKind.MAIN_DOCUMENT, "p9", AnchorPlacement.AT, 0, 0, DocxAnchor.hashOf(""),
                PoiDocxStructuralExtractor.PARSER_VERSION, null);

        assertRefused(docx, insert(missing, "x", "X", null), FillSpotPlacementException.Reason.NOT_FOUND);
        assertRefused(docx, new SpotEdit.Retag(DocumentPartKind.MAIN_DOCUMENT, "p0/sdt4", "x", "X"), FillSpotPlacementException.Reason.NOT_FOUND);
        assertRefused(docx, new SpotEdit.Unwrap("nothing"), FillSpotPlacementException.Reason.NOT_FOUND);
        assertRefused(docx, new SpotEdit.Untag("nothing"), FillSpotPlacementException.Reason.NOT_FOUND);
    }

    @Test
    void aReplacedTextHoldingAPictureOrAControlIsRefused() {
        byte[] docx = docx(paragraph(run("a "), "<w:sdt><w:sdtPr><w:tag w:val=\"kept\"/></w:sdtPr><w:sdtContent><w:r><w:t>k</w:t></w:r>"
                + "</w:sdtContent></w:sdt>", run(" b")));

        assertRefused(docx, insert(replace(docx, "p0", 0, 4), "x", "X", null), FillSpotPlacementException.Reason.PROTECTED);
    }

    @Test
    void aTagThatIsNotASafeFieldIdIsAMistake() {
        byte[] docx = docx(paragraph(run("Name")));

        assertThatThrownBy(() -> editor.apply(docx, List.of(insert(at(docx, "p0", 0), "Customer Name", "Customer", null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nothingIsWrittenWhenOneOfSeveralEditsIsRefused() {
        byte[] docx = docx(paragraph(run("Name: ____")));

        assertThatThrownBy(() -> editor.apply(docx, List.of(
                        insert(replace(docx, "p0", 6, 10), "name", "Name", "____"),
                        new SpotEdit.Unwrap("missing"))))
                .isInstanceOf(FillSpotPlacementException.class);
    }

    // ---------------------------------------------------------------- the result is fillable

    @Test
    void anInsertedSpotReadsBackSupportedAndFillsInItsNeighboursStyle() {
        byte[] docx = docx(paragraph(run(ARIAL_14_RED, "Full name: ")));

        byte[] edited = apply(docx, insert(at(docx, "p0", 11), "full.name", "Full name", null));

        assertFillsWithNeighbourStyle(edited, "full.name", FieldCardinality.SCALAR);
    }

    // ---------------------------------------------------------------- helpers

    private void assertFillsWithNeighbourStyle(byte[] edited, String tag, FieldCardinality cardinality) {
        assertThat(extracted(edited)).isInstanceOf(DocxExtractionOutcome.Supported.class);
        CandidateBindingReport report = FieldBindingCandidateProposer.propose(graph(edited));
        assertThat(report.candidates()).extracting(candidate -> candidate.fieldId()).contains(tag);
        FilledDocument filled = new PoiTemplateFiller().fill(edited, List.of(field(tag, cardinality)),
                new DocumentContent(Map.of(tag, new FieldValue.TextValue("Grace Hopper"))));
        assertThat(filled.reopenedBodyText()).contains("Grace Hopper");
    }

    private void assertRefused(byte[] docx, SpotEdit edit, FillSpotPlacementException.Reason reason) {
        assertThatThrownBy(() -> editor.apply(docx, List.of(edit)))
                .isInstanceOfSatisfying(FillSpotPlacementException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private static void assertControl(CTSdtRun sdt, String tag, String alias, boolean placeholder) {
        assertThat(sdt.getSdtPr().getTag().getVal()).isEqualTo(tag);
        assertThat(sdt.getSdtPr().getAlias().getVal()).isEqualTo(alias);
        assertThat(sdt.getSdtPr().isSetShowingPlcHdr()).isEqualTo(placeholder);
    }

    private byte[] apply(byte[] docx, SpotEdit... edits) {
        return editor.apply(docx, List.of(edits)).docxBytes();
    }

    private static SpotEdit.Insert insert(DocxAnchor anchor, String tag, String alias, String blankText) {
        return new SpotEdit.Insert(anchor, tag, alias, blankText);
    }

    private static DocxAnchor at(byte[] docx, String nodeId, int point) {
        return anchor(docx, nodeId, AnchorPlacement.AT, point, point);
    }

    private static DocxAnchor replace(byte[] docx, String nodeId, int start, int end) {
        return anchor(docx, nodeId, AnchorPlacement.REPLACE, start, end);
    }

    private static DocxAnchor whole(byte[] docx, String nodeId) {
        return anchor(docx, nodeId, AnchorPlacement.WHOLE_LINE, 0, 0);
    }

    /** An anchor as the page would send it: read from the graph of the same bytes. */
    private static DocxAnchor anchor(byte[] docx, String nodeId, AnchorPlacement placement, int start, int end) {
        return new DocxAnchor(DocumentPartKind.MAIN_DOCUMENT, nodeId, placement, start, end, DocxAnchor.hashOf(anchorText(docx, nodeId)),
                PoiDocxStructuralExtractor.PARSER_VERSION, null);
    }

    static String anchorText(byte[] docx, String nodeId) {
        return ParagraphAnchorText.of(find(graph(docx).parts().getFirst().root(), nodeId));
    }

    private static StructuralNode find(StructuralNode node, String nodeId) {
        if (nodeId.equals(node.nodeId())) {
            return node;
        }
        for (StructuralNode child : node.children()) {
            StructuralNode found = find(child, nodeId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static DocxStructuralGraph graph(byte[] docx) {
        if (!(extracted(docx) instanceof DocxExtractionOutcome.Supported supported)) {
            throw new AssertionError("The document does not read as supported.");
        }
        return supported.graph();
    }

    static DocxExtractionOutcome extracted(byte[] docx) {
        try {
            return EXTRACTOR.extract(new ByteArrayInputStream(docx));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static FieldDefinition field(String tag, FieldCardinality cardinality) {
        return new FieldDefinition(tag, FieldType.TEXT, cardinality, FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag(tag));
    }

    private static List<CTP> paragraphs(byte[] docx) {
        try (XWPFDocument document = open(docx)) {
            List<CTP> paragraphs = new ArrayList<>();
            document.getParagraphs().forEach(paragraph -> paragraphs.add((CTP) paragraph.getCTP().copy()));
            return paragraphs;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> childNames(CTP paragraph) {
        List<String> names = new ArrayList<>();
        try (var cursor = paragraph.newCursor()) {
            if (cursor.toFirstChild()) {
                do {
                    names.add(cursor.getName().getLocalPart());
                } while (cursor.toNextSibling());
            }
        }
        return names;
    }

    private static XWPFDocument open(byte[] docx) throws IOException {
        return new XWPFDocument(new ByteArrayInputStream(docx));
    }

    static byte[] docx(String bodyXml) {
        try {
            return RawDocx.builder().document(bodyXml).build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String paragraph(String... runs) {
        return "<w:p>" + String.join("", runs) + "</w:p>";
    }

    static String run(String text) {
        return run("", text);
    }

    static String run(String properties, String text) {
        return "<w:r>" + properties + "<w:t xml:space=\"preserve\">" + escape(text) + "</w:t></w:r>";
    }

    static String table(String... rows) {
        return "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol/><w:gridCol/></w:tblGrid>" + String.join("", rows) + "</w:tbl><w:p/>";
    }

    static String row(String... cells) {
        return "<w:tr>" + String.join("", cells) + "</w:tr>";
    }

    static String cell(String paragraphs) {
        return "<w:tc><w:tcPr/>" + paragraphs + "</w:tc>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
