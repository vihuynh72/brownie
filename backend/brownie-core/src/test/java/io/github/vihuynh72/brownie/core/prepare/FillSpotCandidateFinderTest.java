package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The finder over outlines built by hand, one kind of place or guard per
 * test: what becomes a candidate, where its anchor points, what the rules
 * call it, and what is only counted for a notice.
 */
class FillSpotCandidateFinderTest {

    private static final String PARSER = "test-parser-v3";

    // ---------------------------------------------------------------- blanks in text

    @Test
    void aLineOfUnderscoresIsReplacedAndNamedByTheWordsBeforeIt() {
        FoundSpots found = find(body("p0", "Full name: ______"));

        SpotCandidate candidate = only(found);
        assertEquals(SpotCandidate.Kind.UNDERSCORES, candidate.kind());
        assertEquals(AnchorPlacement.REPLACE, candidate.anchor().placement());
        assertEquals(11, candidate.anchor().start());
        assertEquals(17, candidate.anchor().end());
        assertEquals("p0", candidate.anchor().paragraphNodeId());
        assertEquals(DocxAnchor.hashOf("Full name: ______"), candidate.anchor().anchorTextHash());
        assertEquals(PARSER, candidate.anchor().parserVersion());
        assertEquals("Full name", candidate.rulesLabel());
        assertEquals(FieldType.TEXT, candidate.rulesType());
        assertEquals("______", candidate.blankText());
        assertEquals(SpotCandidate.Tier.HIGH, candidate.tier());
        assertFalse(candidate.signatureLike());
    }

    @Test
    void aDateMaskIsOneBlankAndADate() {
        SpotCandidate candidate = only(find(body("p0", "Start: __/__/____")));

        assertEquals("__/__/____", candidate.blankText());
        assertEquals(FieldType.DATE, candidate.rulesType());
        assertEquals("Start", candidate.rulesLabel());
    }

    @Test
    void instructionWordsComeOffTheLabel() {
        assertEquals("Name", only(find(body("p0", "Please write your name here: ________"))).rulesLabel());
        assertEquals("Date of birth", only(find(body("p0", "Enter date of birth (required)*: ____"))).rulesLabel());
        assertEquals(FieldType.DATE, only(find(body("p0", "Enter date of birth (required)*: ____"))).rulesType());
    }

    @Test
    void aBlankWithNothingBeforeItIsNamedByTheWordsAfterItAndOtherwiseCounted() {
        assertEquals("Full name", only(find(body("p0", "________ (full name)"))).rulesLabel());
        FoundSpots unnamed = find(body("p0", "______"), body("p1", "_____"));
        assertEquals(List.of("Blank 1", "Blank 2"), labels(unnamed));
    }

    @Test
    void aQuestionNamesTheBlankRightAfterIt() {
        assertEquals("How did you hear about us", only(find(body("p0", "How did you hear about us? ______"))).rulesLabel());
    }

    @Test
    void bracketedPromptsOfEveryKindAreReplacedAndShowTheirOwnWords() {
        FoundSpots found = find(body("p0", "[Company] <Client name> {{due date}} \u00ABAmount\u00BB ${reference} \u3010Ward\u3011"));

        assertEquals(List.of("Company", "Client name", "Due date", "Amount", "Reference", "Ward"), labels(found));
        assertTrue(found.candidates().stream().allMatch(candidate -> candidate.kind() == SpotCandidate.Kind.BRACKET));
        assertEquals("[Company]", found.candidates().getFirst().blankText());
        assertEquals(FieldType.DATE, found.candidates().get(2).rulesType());
        assertEquals(SpotCandidate.Tier.MEDIUM, found.candidates().get(4).tier());
        assertEquals(SpotCandidate.Tier.HIGH, found.candidates().getFirst().tier());
    }

    @Test
    void referencesAndEditorialNotesInBracketsAreNotPlaces() {
        assertTrue(find(body("p0", "As shown [1], [12], [a], [sic], [iv], [1, 2] and [...] in the report.")).candidates().isEmpty());
    }

    @Test
    void dotsAreABlankButNotInATableOfContents() {
        assertEquals(SpotCandidate.Kind.DOT_LEADER, only(find(body("p0", "Total amount ........"))).kind());
        FormOutline.Paragraph toc = new FormOutline.Paragraph("k0", DocumentPartKind.MAIN_DOCUMENT, FormOutline.Region.BODY, "p0",
                "Introduction........3", "toc 1", false, null, List.of(run(0, 21)));
        FormOutline.Paragraph caption = new FormOutline.Paragraph("k1", DocumentPartKind.MAIN_DOCUMENT, FormOutline.Region.BODY, "p1",
                "Figure 1: [Name]", "Caption", false, null, List.of(run(0, 16)));
        assertTrue(find(toc, caption).candidates().isEmpty());
    }

    @Test
    void underlinedSpacesAreABlank() {
        FormOutline.Paragraph paragraph = paragraph("p0", "Name:       ", List.of(run(0, 6), new FormOutline.Run(6, 12, true, false, false, false)));

        SpotCandidate candidate = only(find(paragraph));
        assertEquals(SpotCandidate.Kind.UNDERLINED_BLANK, candidate.kind());
        assertEquals(6, candidate.anchor().start());
        assertEquals("      ", candidate.blankText());
    }

    @Test
    void aTabThatDrawsALineAfterALabelGetsTheSpotJustBeforeIt() {
        FormOutline.Paragraph paragraph = paragraph("p0", "Name:\t", List.of(run(0, 6), new FormOutline.Tab(5, "underscore")));

        SpotCandidate candidate = only(find(paragraph));
        assertEquals(SpotCandidate.Kind.TAB_LEADER, candidate.kind());
        assertEquals(AnchorPlacement.AT, candidate.anchor().placement());
        assertEquals(5, candidate.anchor().start());
        assertEquals("Name", candidate.rulesLabel());
        assertNull(candidate.blankText());
    }

    @Test
    void aShortLabelEndingTheLineWithAColonGetsTheSpotAtTheEnd() {
        SpotCandidate candidate = only(find(body("p0", "Name of child: ")));

        assertEquals(SpotCandidate.Kind.LABEL_AT_END, candidate.kind());
        assertEquals(15, candidate.anchor().start());
        assertEquals("Name of child", candidate.rulesLabel());
        assertEquals(SpotCandidate.Tier.MEDIUM, candidate.tier());

        FormOutline.Paragraph heading = new FormOutline.Paragraph("k0", DocumentPartKind.MAIN_DOCUMENT, FormOutline.Region.BODY, "p0",
                "Your details:", "Heading 2", true, null, List.of(run(0, 13)));
        assertTrue(find(heading).candidates().isEmpty());
        assertTrue(find(body("p0", "This sentence is far too long to be any kind of label at all:")).candidates().isEmpty());
    }

    @Test
    void aLabelCellBesideTheCellThatHoldsItsAnswerIsOnlyALabel() {
        FormOutline.Paragraph control = withAtoms(cell("tbl0", 0, 1, 4, ""),
                List.of(new FormOutline.Control(0, "tbl0/row0/cell1/p0/sdt0", "name", null, "x", FormOutline.ControlKind.TEXT)));
        FormOutline.Paragraph field = withAtoms(cell("tbl0", 1, 1, 4, ""), List.of(new FormOutline.FormField(0, 0, "FORMTEXT", null)));
        FoundSpots found = find(
                cell("tbl0", 0, 0, 4, "Name:"), control,
                cell("tbl0", 1, 0, 4, "Email:"), field,
                cell("tbl0", 2, 0, 4, "City:"), cell("tbl0", 2, 1, 4, "____________"),
                cell("tbl0", 3, 0, 4, "Phone:"), cell("tbl0", 3, 1, 4, ""));

        assertEquals(List.of(SpotCandidate.Kind.EXISTING_TAGGED_CONTROL, SpotCandidate.Kind.FORM_FIELD, SpotCandidate.Kind.UNDERSCORES,
                SpotCandidate.Kind.EMPTY_CELL), found.candidates().stream().map(SpotCandidate::kind).toList());
        assertEquals(List.of("Name", "Email", "City", "Phone"), labels(found));

        FoundSpots notesBeside = find(cell("tbl0", 0, 0, 1, "Notes:"), cell("tbl0", 0, 1, 1, "Please write clearly."));
        assertEquals(SpotCandidate.Kind.LABEL_AT_END, only(notesBeside).kind(), "text that is no place leaves the label its own");
    }

    /**
     * Boxed-label forms write each answer inside its label's own cell, so a
     * label in the cell to the right is a place of its own and not the
     * answer to the one on its left, nor is a blank that has its own label
     * before it. Every label keeps its place.
     */
    @Test
    void aLabelCellBesideAnotherLabelKeepsItsOwnPlace() {
        FoundSpots row = find(cell("tbl0", 0, 0, 1, "Name:"), cell("tbl0", 0, 1, 1, "Date of birth:"), cell("tbl0", 0, 2, 1, "Phone:"));
        assertEquals(List.of("Name", "Date of birth", "Phone"), labels(row));
        assertTrue(row.candidates().stream().allMatch(candidate -> candidate.kind() == SpotCandidate.Kind.LABEL_AT_END));

        FoundSpots grid = find(
                cell("tbl0", 0, 0, 2, "Name:"), cell("tbl0", 0, 1, 2, "Date:"),
                cell("tbl0", 1, 0, 2, "Address:"), cell("tbl0", 1, 1, 2, "Phone:"));
        assertEquals(List.of("Name", "Date", "Address", "Phone"), labels(grid));

        FormOutline.Paragraph labelledControl = withAtoms(cell("tbl0", 1, 1, 2, "Email: "),
                List.of(new FormOutline.Control(7, "tbl0/row1/cell1/p0/sdt0", "email", null, "x", FormOutline.ControlKind.TEXT)));
        FoundSpots labelledPlacesBeside = find(
                cell("tbl0", 0, 0, 2, "Name:"), cell("tbl0", 0, 1, 2, "Phone: ________"),
                cell("tbl0", 1, 0, 2, "City:"), labelledControl);
        assertEquals(List.of(SpotCandidate.Kind.LABEL_AT_END, SpotCandidate.Kind.UNDERSCORES, SpotCandidate.Kind.LABEL_AT_END,
                SpotCandidate.Kind.EXISTING_TAGGED_CONTROL), labelledPlacesBeside.candidates().stream().map(SpotCandidate::kind).toList());
        assertEquals(List.of("Name", "Phone", "City"), labels(labelledPlacesBeside).subList(0, 3));

        FoundSpots blankAfterSpaces = find(cell("tbl0", 0, 0, 1, "Name:"), cell("tbl0", 0, 1, 1, "   ________"));
        assertEquals(SpotCandidate.Kind.UNDERSCORES, only(blankAfterSpaces).kind(), "spaces before the blank still make it the answer");
    }

    // ---------------------------------------------------------------- guards

    @Test
    void hiddenTextLinkTextAndTextAFieldShowsAreNeverPlaces() {
        FormOutline.Paragraph hidden = paragraph("p0", "Name: ____", List.of(run(0, 6), new FormOutline.Run(6, 10, false, true, false, false)));
        FormOutline.Paragraph link = paragraph("p1", "See ____ online", List.of(new FormOutline.Run(0, 15, false, false, true, false)));
        FormOutline.Paragraph field = paragraph("p2", "Page ....", List.of(run(0, 5), new FormOutline.Run(5, 9, false, false, false, true)));

        assertTrue(find(hidden, link, field).candidates().isEmpty());
    }

    @Test
    void aPlaceForASignatureIsACandidateTheRulesDeclineAndIsCounted() {
        FoundSpots found = find(body("p0", "Signature: __________\tDate: __/__/____"));

        assertEquals(2, found.candidates().size());
        assertTrue(found.candidates().get(0).signatureLike());
        assertFalse(found.candidates().get(1).signatureLike());
        assertEquals("Date", found.candidates().get(1).rulesLabel());
        assertTrue(found.notices().contains(new PreparationNotice(PreparationNotice.SIGNATURE_LINES_LEFT, 1, null)));
    }

    @Test
    void anUnnamedLineUnderALettersClosingIsWhereTheWriterSigns() {
        FoundSpots found = find(body("p0", "Yours sincerely,"), body("p1", ""), body("p2", "____________"), body("p3", "[Your name]"));

        assertEquals("Signature", found.candidates().getFirst().rulesLabel());
        assertTrue(found.candidates().getFirst().signatureLike());
        assertEquals("Name", found.candidates().get(1).rulesLabel());
    }

    @Test
    void checkboxesOfEveryKindAreOnlyCounted() {
        FormOutline.Paragraph glyphs = body("p0", "\u2610 Yes \u2612 No");
        FormOutline.Paragraph field = paragraph("p1", "Member", List.of(run(0, 6), new FormOutline.CheckboxField(0)));
        FormOutline.Paragraph control = paragraph("p2", "Agree ", List.of(run(0, 6),
                new FormOutline.Control(6, "p2/sdt1", null, null, "\u2610", FormOutline.ControlKind.CHECKBOX)));

        FoundSpots found = find(glyphs, field, control);

        assertTrue(found.candidates().isEmpty());
        assertEquals(List.of(new PreparationNotice(PreparationNotice.CHECKBOXES_LEFT, 4, null)), found.notices());
    }

    @Test
    void blanksWhereTheFillerCannotWriteAreCountedNotFound() {
        FormOutline.Paragraph header = new FormOutline.Paragraph("h0", DocumentPartKind.HEADER, FormOutline.Region.HEADER_FOOTER, "p0",
                "Ref: ______", null, false, null, List.of(run(0, 11)));
        FormOutline.Paragraph textBox = new FormOutline.Paragraph("t0", DocumentPartKind.MAIN_DOCUMENT, FormOutline.Region.TEXT_BOX, null,
                "[Name] and ....", null, false, null, List.of(run(0, 15)));
        FormOutline.Paragraph nested = new FormOutline.Paragraph("n0", DocumentPartKind.MAIN_DOCUMENT, FormOutline.Region.NESTED_TABLE, null,
                "__/__/____", null, false, null, List.of(run(0, 10)));

        FoundSpots found = find(header, textBox, nested);

        assertTrue(found.candidates().isEmpty());
        assertEquals(List.of(new PreparationNotice(PreparationNotice.BLANKS_OUTSIDE_BODY, 4, null)), found.notices());
    }

    // ---------------------------------------------------------------- the form's own controls and fields

    @Test
    void aControlWithASafeUniqueTagIsKeptAndOthersAreTaggedOrReTagged() {
        FormOutline.Paragraph paragraph = paragraph("p0", "Name: Title: Other: Copy: Copy: ", List.of(run(0, 32),
                new FormOutline.Control(6, "p0/sdt1", "full.name", null, "", FormOutline.ControlKind.TEXT),
                new FormOutline.Control(13, "p0/sdt3", "Customer Title", "Title of the customer", "", FormOutline.ControlKind.TEXT),
                new FormOutline.Control(20, "p0/sdt5", null, null, "Click or tap here to enter text.", FormOutline.ControlKind.TEXT),
                new FormOutline.Control(26, "p0/sdt7", "copy", null, "", FormOutline.ControlKind.DATE),
                new FormOutline.Control(32, "p0/sdt9", "copy", null, "", FormOutline.ControlKind.TEXT)));

        List<SpotCandidate> candidates = find(paragraph).candidates();

        assertEquals(5, candidates.size());
        assertEquals("full.name", candidates.get(0).keptTag());
        assertEquals("Full name", candidates.get(0).rulesLabel());
        assertEquals(AnchorPlacement.EXISTING_CONTROL, candidates.get(0).anchor().placement());
        assertEquals("p0/sdt1", candidates.get(0).anchor().controlNodeId());
        assertNull(candidates.get(1).keptTag());
        assertEquals(SpotCandidate.Kind.EXISTING_TAGGED_CONTROL, candidates.get(1).kind());
        assertEquals("Title of the customer", candidates.get(1).rulesLabel());
        assertEquals(SpotCandidate.Kind.EXISTING_UNTAGGED_CONTROL, candidates.get(2).kind());
        assertEquals("Other", candidates.get(2).rulesLabel());
        assertNull(candidates.get(3).keptTag(), "a tag used twice is re-tagged");
        assertEquals(FieldType.DATE, candidates.get(3).rulesType());
        assertNull(candidates.get(4).keptTag());
    }

    @Test
    void theTagsTheFormsControlsKeepAreKnownSoNoNewIdTakesOne() {
        FormOutline.Paragraph header = new FormOutline.Paragraph("h0", DocumentPartKind.HEADER, FormOutline.Region.HEADER_FOOTER, "p0",
                "", null, false, null, List.of(new FormOutline.Control(0, "p0/sdt0", "company", null, "x", FormOutline.ControlKind.TEXT)));
        FormOutline.Paragraph body = paragraph("p0", "Kept: Twice: Twice: Tick: ", List.of(run(0, 26),
                new FormOutline.Control(6, "p0/sdt1", "kept", null, "", FormOutline.ControlKind.TEXT),
                new FormOutline.Control(13, "p0/sdt3", "twice", null, "", FormOutline.ControlKind.TEXT),
                new FormOutline.Control(20, "p0/sdt5", "twice", null, "", FormOutline.ControlKind.TEXT),
                new FormOutline.Control(26, "p0/sdt7", "tick", null, "", FormOutline.ControlKind.CHECKBOX)));

        assertEquals(Set.of("company", "kept", "tick"), find(header, body).standingTags(),
                "a tag used twice is replaced on both controls, so it does not stand");
    }

    @Test
    void aKeptTagIsTypedByItsOwnWordsAsATemplateMadeFromTheFormWouldTypeIt() {
        FormOutline.Paragraph paragraph = paragraph("p0", "Due: Phone: ", List.of(run(0, 12),
                new FormOutline.Control(5, "p0/sdt1", "dueDate", null, "", FormOutline.ControlKind.TEXT),
                new FormOutline.Control(12, "p0/sdt3", "day.phone", null, "", FormOutline.ControlKind.TEXT)));

        List<SpotCandidate> candidates = find(paragraph).candidates();

        assertEquals(List.of("dueDate", "day.phone"), candidates.stream().map(SpotCandidate::keptTag).toList());
        assertEquals(List.of(FieldType.DATE, FieldType.TEXT), candidates.stream().map(SpotCandidate::rulesType).toList());
        assertEquals(List.of(FieldType.TEXT, FieldType.TEXT), find(body("p1", "Place of birth: ____ Day phone: ____")).candidates().stream()
                .map(SpotCandidate::rulesType).toList());
    }

    @Test
    void noBlankReadFromTheTextRunsAcrossAnEmptyFieldOrAControl() {
        FormOutline.Paragraph field = paragraph("p0", "Name: ______", List.of(run(0, 12),
                new FormOutline.FormField(9, 9, "FILLIN", "Your name")));
        assertEquals(List.of(SpotCandidate.Kind.FORM_FIELD), find(field).candidates().stream().map(SpotCandidate::kind).toList());

        FormOutline.Paragraph underlined = paragraph("p0", "Town:      ", List.of(run(0, 5),
                new FormOutline.Run(5, 11, true, false, false, false),
                new FormOutline.Control(8, "p0/sdt1", null, null, "", FormOutline.ControlKind.TEXT)));
        List<SpotCandidate> candidates = find(underlined).candidates();
        assertEquals(List.of(SpotCandidate.Kind.UNDERLINED_BLANK, SpotCandidate.Kind.EXISTING_UNTAGGED_CONTROL,
                SpotCandidate.Kind.UNDERLINED_BLANK), candidates.stream().map(SpotCandidate::kind).toList());
        assertEquals(List.of(5, 8, 8), candidates.stream().map(candidate -> candidate.anchor().start()).toList());
    }

    @Test
    void aFormsOwnFieldIsReplacedAndNamedByItsOwnWords() {
        FormOutline.Paragraph paragraph = paragraph("p0", "Dear \u00ABFirstName\u00BB,", List.of(run(0, 17),
                new FormOutline.FormField(5, 16, "MERGEFIELD", "FirstName")));

        SpotCandidate candidate = only(find(paragraph));
        assertEquals(SpotCandidate.Kind.FORM_FIELD, candidate.kind());
        assertEquals(5, candidate.anchor().start());
        assertEquals(16, candidate.anchor().end());
        assertEquals("First name", candidate.rulesLabel());
        assertEquals("\u00ABFirstName\u00BB", candidate.blankText());
    }

    // ---------------------------------------------------------------- tables

    @Test
    void anEmptyCellIsNamedByTheLabelBesideItOrElseTheHeaderAboveIt() {
        FoundSpots found = find(
                cell("tbl0", 0, 0, 3, "Name"), cell("tbl0", 0, 1, 3, ""),
                cell("tbl0", 1, 0, 3, "Items"), cell("tbl0", 1, 1, 3, "Quantity"),
                cell("tbl0", 2, 0, 3, "Pens"), cell("tbl0", 2, 1, 3, ""));

        assertEquals(List.of("Name", "Pens"), labels(found));
        assertEquals(SpotCandidate.Kind.EMPTY_CELL, found.candidates().getFirst().kind());
        assertEquals(SpotCandidate.Tier.HIGH, found.candidates().getFirst().tier());
        assertEquals("T1R1", found.candidates().getFirst().rowKey());
        assertEquals("tbl0/row0/cell1/p0", found.candidates().getFirst().anchor().paragraphNodeId());
        assertEquals(SpotCandidate.Tier.HIGH, found.candidates().get(1).tier());
    }

    @Test
    void aCellUnderAHeaderRowIsNamedByItsColumnAndItsRowNeverByTheValueBesideIt() {
        FoundSpots found = find(
                cell("tbl0", 0, 0, 4, "Course"), cell("tbl0", 0, 1, 4, "Year"),
                cell("tbl0", 1, 0, 4, "Painting"), cell("tbl0", 1, 1, 4, ""),
                cell("tbl0", 2, 0, 4, ""), cell("tbl0", 2, 1, 4, ""),
                cell("tbl0", 3, 0, 4, ""), cell("tbl0", 3, 1, 4, ""));

        assertEquals(List.of("Year (Painting)", "Course (row 2)", "Year (row 2)", "Course (row 3)", "Year (row 3)"), labels(found));
        SpotCandidate year = found.candidates().getFirst();
        assertEquals("column: Year; row: Painting", year.context());
        assertEquals("column: Course; row 2", found.candidates().get(1).context());
        assertEquals(List.of("Painting"), year.tableValues(), "the header row is not a value");

        FoundSpots oneEach = find(
                cell("tbl0", 0, 0, 3, "Course"), cell("tbl0", 0, 1, 3, "Year"),
                cell("tbl0", 1, 0, 3, "Painting"), cell("tbl0", 1, 1, 3, ""),
                cell("tbl0", 2, 0, 3, ""), cell("tbl0", 2, 1, 3, "2024"));
        assertEquals(List.of("Year", "Course"), labels(oneEach), "a header with one place under it needs no row");
        assertEquals(List.of("Painting", "2024"), oneEach.candidates().getFirst().tableValues());
        assertTrue(found.candidates().stream().allMatch(candidate -> candidate.tier() == SpotCandidate.Tier.HIGH));
    }

    @Test
    void aHeaderRowWithAnEmptyCornerStillNamesEveryColumn() {
        FoundSpots found = find(
                cell("tbl0", 0, 0, 3, ""), cell("tbl0", 0, 1, 3, "Name"), cell("tbl0", 0, 2, 3, "Year"),
                cell("tbl0", 1, 0, 3, "Painting"), cell("tbl0", 1, 1, 3, ""), cell("tbl0", 1, 2, 3, ""),
                cell("tbl0", 2, 0, 3, "Drawing"), cell("tbl0", 2, 1, 3, ""), cell("tbl0", 2, 2, 3, ""));

        assertEquals(List.of("Name (Painting)", "Year (Painting)", "Name (Drawing)", "Year (Drawing)"), labels(found));
        assertEquals(List.of("Painting", "Drawing"), found.candidates().getFirst().tableValues());

        FoundSpots timesheet = find(
                cell("tbl0", 0, 0, 2, ""), cell("tbl0", 0, 1, 2, "Mon"), cell("tbl0", 0, 2, 2, "Tue"),
                cell("tbl0", 1, 0, 2, "Hours"), cell("tbl0", 1, 1, 2, ""), cell("tbl0", 1, 2, 2, ""));
        assertEquals(List.of("Mon", "Tue"), labels(timesheet));
    }

    @Test
    void aCellWithNothingBesideItInATableWithNoHeaderRowIsNamedByTheTopOfItsColumn() {
        FoundSpots found = find(
                cell("tbl0", 0, 0, 3, "Name:"), cell("tbl0", 0, 1, 3, ""), cell("tbl0", 0, 2, 3, "Year"),
                cell("tbl0", 1, 0, 3, "Phone:"), cell("tbl0", 1, 1, 3, ""), cell("tbl0", 1, 2, 3, ""));

        assertEquals(List.of("Name", "Phone", "Year"), labels(found));
    }

    @Test
    void aColonLabelBesideACellOrAHeaderThatOnlySaysAnswerLeavesTheCellToItsLabel() {
        FoundSpots colon = find(
                cell("tbl0", 0, 0, 3, "Applicant"), cell("tbl0", 0, 1, 3, "Ana Lee"),
                cell("tbl0", 1, 0, 3, "Phone:"), cell("tbl0", 1, 1, 3, ""),
                cell("tbl0", 2, 0, 3, "Email:"), cell("tbl0", 2, 1, 3, ""));
        assertEquals(List.of("Phone", "Email"), labels(colon));

        FoundSpots answers = find(
                cell("tbl0", 0, 0, 3, "Question"), cell("tbl0", 0, 1, 3, "Answer"),
                cell("tbl0", 1, 0, 3, "Home town"), cell("tbl0", 1, 1, 3, ""),
                cell("tbl0", 2, 0, 3, "Street"), cell("tbl0", 2, 1, 3, ""));
        assertEquals(List.of("Home town", "Street"), labels(answers));

        FoundSpots instruction = find(
                cell("tbl0", 0, 0, 3, "Requester details"), cell("tbl0", 0, 1, 3, "Please complete"),
                cell("tbl0", 1, 0, 3, "Name"), cell("tbl0", 1, 1, 3, ""),
                cell("tbl0", 2, 0, 3, "Phone"), cell("tbl0", 2, 1, 3, ""));
        assertEquals(List.of("Name", "Phone"), labels(instruction), "a header that says what to do names no value");
    }

    @Test
    void theRowThatCanRepeatIsNamedByItsColumnsAloneAndTheRowsItStandsForByTheirNumber() {
        FoundSpots found = find(
                cell("tbl0", 0, 0, 4, "Item"), cell("tbl0", 0, 1, 4, "Cost"),
                cell("tbl0", 1, 0, 4, ""), cell("tbl0", 1, 1, 4, ""),
                cell("tbl0", 2, 0, 4, ""), cell("tbl0", 2, 1, 4, ""),
                cell("tbl0", 3, 0, 4, ""), cell("tbl0", 3, 1, 4, ""));

        assertEquals(List.of("Item", "Cost", "Item (row 2)", "Cost (row 2)", "Item (row 3)", "Cost (row 3)"), labels(found));
    }

    @Test
    void aPlaceKeptForTheOfficeIsOnlyAGuessTheNamingStepMayLeaveOut() {
        FormOutline.Paragraph heading = cell("tbl0", 0, 0, 1, "For office use only");
        FormOutline.Paragraph received = new FormOutline.Paragraph("main#tbl0/row0/cell0/p1", DocumentPartKind.MAIN_DOCUMENT,
                FormOutline.Region.TOP_TABLE_CELL, "tbl0/row0/cell0/p1", "Received by: ________", null, false, heading.cell(),
                List.of(run(0, 21)));
        FoundSpots inACell = find(heading, received);
        FoundSpots underAHeading = find(body("p0", "Office use"), body("p1", "Plot number: ______"),
                body("p2", "Fee paid: ______"), body("p3", "Thank you."), body("p4", "Full name: ______"));

        assertEquals(SpotCandidate.Tier.MEDIUM, only(inACell).tier());
        assertEquals(List.of(SpotCandidate.Tier.MEDIUM, SpotCandidate.Tier.MEDIUM, SpotCandidate.Tier.HIGH),
                underAHeading.candidates().stream().map(SpotCandidate::tier).toList());
    }

    @Test
    void aRowKeepingTheRestOfATableForTheOfficeMakesEveryPlaceBelowItAGuess() {
        FoundSpots found = find(
                cell("tbl0", 0, 0, 5, "Name:"), cell("tbl0", 0, 1, 5, ""),
                cell("tbl0", 1, 0, 5, "Phone:"), cell("tbl0", 1, 1, 5, ""),
                cell("tbl0", 2, 0, 5, "For office use only"), cell("tbl0", 2, 1, 5, "For office use only"),
                cell("tbl0", 3, 0, 5, "Received by:"), cell("tbl0", 3, 1, 5, ""),
                cell("tbl0", 4, 0, 5, "Fee paid:"), cell("tbl0", 4, 1, 5, ""));

        assertEquals(List.of("Name", "Phone", "Received by", "Fee paid"), labels(found));
        assertEquals(List.of(SpotCandidate.Tier.HIGH, SpotCandidate.Tier.HIGH, SpotCandidate.Tier.MEDIUM, SpotCandidate.Tier.MEDIUM),
                found.candidates().stream().map(SpotCandidate::tier).toList());
    }

    @Test
    void aPlaceNothingBesideNamesIsNamedByAnEarlierLabelOnItsLineThenTheLineAboveAndOnlyThenNumbered() {
        FoundSpots found = find(body("p0", "Name ______ ______"), body("p1", "Comments"), body("p2", "____________"),
                body("p3", "This line is a sentence that runs on far too long to be the name of anything at all."),
                body("p4", "____________"));

        assertEquals(List.of("Name", "Name", "Comments", "Blank 1"), labels(found));

        FoundSpots signHere = find(body("p0", "Terms"),
                body("p1", "Please sign here to show that you have read and agree to all of the terms above ________"));
        assertTrue(only(signHere).signatureLike(), "the words before it still say it is for signing");
    }

    @Test
    void aCellThatContinuesAMergeIsNeverAPlaceAndLabelsTheCellBesideItWithTheMergedCellsWords() {
        FormOutline.Paragraph continued = cell("tbl0", 2, 0, 3, "");
        FormOutline.Cell position = continued.cell();
        continued = new FormOutline.Paragraph(continued.key(), continued.part(), continued.region(), continued.nodeId(), "", null, false,
                new FormOutline.Cell(position.table(), position.tableNodeId(), position.rowNodeId(), 2, 0, 3, true), List.of());
        FoundSpots found = find(
                cell("tbl0", 0, 0, 3, "Name"), cell("tbl0", 0, 1, 3, ""),
                cell("tbl0", 1, 0, 3, "Address"), cell("tbl0", 1, 1, 3, ""),
                continued, cell("tbl0", 2, 1, 3, ""));

        assertEquals(List.of("tbl0/row0/cell1/p0", "tbl0/row1/cell1/p0", "tbl0/row2/cell1/p0"),
                found.candidates().stream().map(candidate -> candidate.anchor().paragraphNodeId()).toList());
        assertEquals(List.of("Name", "Address", "Address"), labels(found));
    }

    @Test
    void theLastRowOfTheFirstTableUnderAHeaderIsOfferedToRepeat() {
        FoundSpots found = find(
                cell("tbl0", 0, 0, 2, "Item"), cell("tbl0", 0, 1, 2, "Cost"),
                cell("tbl0", 1, 0, 2, ""), cell("tbl0", 1, 1, 2, ""));

        assertEquals(List.of("Item", "Cost"), labels(found));
        assertEquals(SpotCandidate.Tier.HIGH, found.candidates().getFirst().tier(), "an empty cell under a header is a place to fill");
        assertEquals(List.of("T1R2"), found.offeredRowKeys());
        assertEquals(Map.of(), found.collapsedRows());
    }

    @Test
    void identicalEmptyRowsAtTheEndOfTheFirstTableAreOfferedAsOneRow() {
        FoundSpots found = find(
                cell("tbl0", 0, 0, 4, "Item"), cell("tbl0", 0, 1, 4, "Cost"),
                cell("tbl0", 1, 0, 4, ""), cell("tbl0", 1, 1, 4, ""),
                cell("tbl0", 2, 0, 4, ""), cell("tbl0", 2, 1, 4, ""),
                cell("tbl0", 3, 0, 4, ""), cell("tbl0", 3, 1, 4, ""));

        assertEquals(List.of("T1R2"), found.offeredRowKeys());
        assertEquals(Map.of("T1R2", List.of(
                        new FoundSpots.CollapsedRow("T1R3", "tbl0/row2"),
                        new FoundSpots.CollapsedRow("T1R4", "tbl0/row3"))),
                found.collapsedRows());
    }

    @Test
    void noRowIsOfferedWhenARowAboveHoldsAPlaceOrTheTableIsNotTheFirst() {
        FoundSpots labelled = find(
                cell("tbl0", 0, 0, 2, "Name"), cell("tbl0", 0, 1, 2, ""),
                cell("tbl0", 1, 0, 2, "Phone"), cell("tbl0", 1, 1, 2, ""));
        assertEquals(List.of(), labelled.offeredRowKeys());

        FoundSpots second = find(
                cell(2, "tbl1", 0, 0, 2, "Item"), cell(2, "tbl1", 1, 0, 2, ""));
        assertEquals(List.of(), second.offeredRowKeys());
        assertEquals("T2R2", second.candidates().getFirst().rowKey());
    }

    // ---------------------------------------------------------------- the outline for naming

    @Test
    void theOutlineMarksEachPlaceWhereItSitsAndKeysHeadingsParagraphsAndRows() {
        FormOutline.Paragraph heading = new FormOutline.Paragraph("k0", DocumentPartKind.MAIN_DOCUMENT, FormOutline.Region.BODY, "p0",
                "About you", "Heading 1", true, null, List.of(run(0, 9)));
        FoundSpots found = find(heading, body("p1", "Name: ____ and [Town]"),
                cell("tbl2", 0, 0, 1, "Phone"), cell("tbl2", 0, 1, 1, ""));

        assertEquals(List.of(
                new OutlineLine("H1", "About you"),
                new OutlineLine("P1", "Name: [[c1]] and [[c2]]"),
                new OutlineLine("T1R1", "Phone | [[c3]]")), found.outline());
    }

    @Test
    void markersTypedInTheDocumentArePulledApartSoOnlyTheRealOnesReadAsMarkers() {
        FoundSpots found = find(body("p0", "For office use only: [[c1]]"), body("p1", "Full name: ____"));

        assertEquals(List.of(
                new OutlineLine("P1", "For office use only: [ [c1] ]"),
                new OutlineLine("P2", "Full name: [[c1]]")), found.outline());
    }

    // ---------------------------------------------------------------- helpers

    private static FoundSpots find(FormOutline.Paragraph... paragraphs) {
        return FillSpotCandidateFinder.find(new FormOutline(PARSER, List.of(paragraphs)));
    }

    private static SpotCandidate only(FoundSpots found) {
        assertEquals(1, found.candidates().size(), () -> "candidates: " + found.candidates());
        return found.candidates().getFirst();
    }

    private static List<String> labels(FoundSpots found) {
        return found.candidates().stream().map(SpotCandidate::rulesLabel).toList();
    }

    private static FormOutline.Run run(int start, int end) {
        return new FormOutline.Run(start, end, false, false, false, false);
    }

    /** A body paragraph whose text is one plain run. */
    private static FormOutline.Paragraph body(String nodeId, String text) {
        List<FormOutline.Atom> atoms = new ArrayList<>();
        if (!text.isEmpty()) {
            atoms.add(run(0, text.codePointCount(0, text.length())));
        }
        return paragraph(nodeId, text, atoms);
    }

    private static FormOutline.Paragraph paragraph(String nodeId, String text, List<FormOutline.Atom> atoms) {
        return new FormOutline.Paragraph("main#" + nodeId, DocumentPartKind.MAIN_DOCUMENT, FormOutline.Region.BODY, nodeId, text, null,
                false, null, atoms);
    }

    private static FormOutline.Paragraph withAtoms(FormOutline.Paragraph paragraph, List<FormOutline.Atom> atoms) {
        return new FormOutline.Paragraph(paragraph.key(), paragraph.part(), paragraph.region(), paragraph.nodeId(), paragraph.anchorText(),
                paragraph.styleName(), paragraph.heading(), paragraph.cell(), atoms);
    }

    private static FormOutline.Paragraph cell(String tableNodeId, int row, int column, int rows, String text) {
        return cell(1, tableNodeId, row, column, rows, text);
    }

    private static FormOutline.Paragraph cell(int table, String tableNodeId, int row, int column, int rows, String text) {
        String rowNodeId = tableNodeId + "/row" + row;
        String nodeId = rowNodeId + "/cell" + column + "/p0";
        List<FormOutline.Atom> atoms = text.isEmpty() ? List.of() : List.of(run(0, text.length()));
        return new FormOutline.Paragraph("main#" + nodeId, DocumentPartKind.MAIN_DOCUMENT, FormOutline.Region.TOP_TABLE_CELL, nodeId, text,
                null, false, new FormOutline.Cell(table, tableNodeId, rowNodeId, row, column, rows), atoms);
    }
}
