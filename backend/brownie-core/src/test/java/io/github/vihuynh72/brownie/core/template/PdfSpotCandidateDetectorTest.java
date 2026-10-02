package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.PdfFontFamily;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfPoint;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detector on pages built by hand, so each shape and each look-alike is
 * tested on its own. Words are laid out as a reader would record them: a
 * box from the baseline up by seven tenths of the size, each character
 * half the size wide.
 */
class PdfSpotCandidateDetectorTest {

    private static final String SANS = "Helvetica";
    /** A long line of ordinary text, so that the page has a right margin like a real one. */
    private static final String BODY = "Please complete every part of this form and return it to the front desk by Friday.";

    @Test
    void aRunOfUnderscoresIsABlankWrittenOnAboveItsBaseline() {
        Page page = new Page().line(72, 100, 11, SANS, "Full", "name:", "______________________________");

        PdfSpotCandidate candidate = only(PdfSpotCandidateDetector.detect(page.graph()));

        assertEquals(PdfSpotCandidate.Kind.UNDERSCORES, candidate.kind());
        assertEquals("c1", candidate.id());
        assertEquals(1, candidate.pageNumber());
        PdfRect underscores = page.word("______________________________").box();
        assertEquals(underscores.x(), candidate.box().x(), 1e-9);
        assertEquals(underscores.width(), candidate.box().width(), 1e-9);
        assertEquals(100, candidate.box().bottom(), 1e-9);
        assertEquals("Full name", candidate.labelGuess());
        assertEquals(new PdfTextStyle(PdfFontFamily.SANS, false, 11), candidate.style());
        assertFalse(candidate.signatureLike());
    }

    @Test
    void sixDotsAreALeaderAndThreeAreNot() {
        Page page = new Page()
                .line(72, 100, 11, SANS, "Date", "of", "birth:", "..........")
                .line(72, 140, 11, SANS, "Wait", "...", "and", "see", "what", "happens", "next", "on", "this", "long", "line");

        PdfSpotCandidate candidate = only(PdfSpotCandidateDetector.detect(page.graph()));

        assertEquals(PdfSpotCandidate.Kind.DOT_LEADER, candidate.kind());
        assertEquals("Date of birth", candidate.labelGuess());
    }

    @Test
    void aLabelWithRoomAfterItIsABlankAndOneWithoutRoomIsNot() {
        Page page = new Page()
                .line(72, 60, 10, SANS, BODY.split(" "))
                .line(72, 100, 11, SANS, "Email:")
                .line(72, 140, 11, SANS, "Note:", "see", "the", "back", "of", "this", "page.");

        PdfSpotCandidate candidate = only(PdfSpotCandidateDetector.detect(page.graph()));

        assertEquals(PdfSpotCandidate.Kind.LABEL_SPACE, candidate.kind());
        PdfRect label = page.word("Email:").box();
        assertEquals(label.right() + 4, candidate.box().x(), 1e-9);
        assertTrue(candidate.box().width() >= PdfSpotCandidateDetector.MIN_LABEL_SPACE);
        assertEquals("Email", candidate.labelGuess());
    }

    @Test
    void aLabelThatLeadsToOneOfTheFormsOwnFieldsIsThatFieldsLabel() {
        Page page = new Page()
                .line(72, 60, 10, SANS, BODY.split(" "))
                .line(72, 100, 11, SANS, "Email:");
        page.widget(new PdfRect(250, 88, 200, 16));

        assertEquals(List.of(), PdfSpotCandidateDetector.detect(page.graph()));
    }

    @Test
    void aLineWithNothingAboveItIsABlankButAnUnderlineOrASeparatorIsNot() {
        Page page = new Page()
                .line(72, 60, 10, SANS, BODY.split(" "))
                .line(72, 100, 12, "Times-Roman", "Address:")
                .rule(130, 102, 500, 102)
                .line(72, 200, 14, SANS, "Heading")
                .rule(72, 202, 130, 202)
                .rule(40, 700, 572, 700)
                .rule(72, 300, 100, 300);

        PdfSpotCandidate candidate = only(PdfSpotCandidateDetector.detect(page.graph()));

        assertEquals(PdfSpotCandidate.Kind.RULE, candidate.kind());
        assertEquals(130, candidate.box().x(), 1e-9);
        assertEquals(370, candidate.box().width(), 1e-9);
        assertEquals(102, candidate.box().bottom(), 1e-9);
        assertEquals("Address", candidate.labelGuess());
        assertEquals(PdfFontFamily.SERIF, candidate.style().family());
        assertEquals(12, candidate.style().sizePt());
    }

    @Test
    void anEmptyCellIsABlankButACellWithWordsTheTableAroundItOrATinyBoxIsNot() {
        Page page = new Page()
                .line(72, 60, 10, SANS, BODY.split(" "))
                .rect(new PdfRect(72, 100, 300, 50))
                .rect(new PdfRect(72, 100, 150, 25))
                .rect(new PdfRect(222, 100, 150, 25))
                .rect(new PdfRect(72, 125, 150, 25))
                .rect(new PdfRect(222, 125, 150, 25))
                .line(76, 118, 11, SANS, "Painting")
                .rect(new PdfRect(400, 400, 20, 10));

        List<PdfSpotCandidate> candidates = PdfSpotCandidateDetector.detect(page.graph());

        assertEquals(3, candidates.size(), candidates.toString());
        assertTrue(candidates.stream().allMatch(candidate -> candidate.kind() == PdfSpotCandidate.Kind.EMPTY_BOX));
        assertEquals(new PdfRect(223.5, 101.5, 147, 22), candidates.get(0).box());
    }

    @Test
    void anEmptyCellOfAGridIsNamedByItsColumnsHeaderAboveTheTableAndItsRowNeverByTheValueBesideIt() {
        Page page = new Page()
                .line(72, 60, 10, SANS, BODY.split(" "))
                .line(72, 95, 11, SANS, "Course")
                .line(222, 95, 11, SANS, "Year")
                .rect(new PdfRect(72, 100, 150, 25)).rect(new PdfRect(222, 100, 150, 25))
                .rect(new PdfRect(72, 125, 150, 25)).rect(new PdfRect(222, 125, 150, 25))
                .rect(new PdfRect(72, 150, 150, 25)).rect(new PdfRect(222, 150, 150, 25))
                .line(76, 118, 11, SANS, "Painting");

        List<PdfSpotCandidate> candidates = PdfSpotCandidateDetector.detect(page.graph());

        assertEquals(List.of("Year (Painting)", "Course (row 2)", "Year (row 2)", "Course (row 3)", "Year (row 3)"),
                candidates.stream().map(PdfSpotCandidate::labelGuess).toList());
        assertEquals("column: Year; row: Painting", candidates.get(0).contextText());
        assertEquals("column: Course; row 2", candidates.get(1).contextText());
        assertTrue(candidates.stream().allMatch(candidate -> "P1G1".equals(candidate.gridKey())));
        assertEquals(List.of("Painting"), candidates.get(0).tableValues());
        assertFalse(candidates.get(0).forOfficeUse());
    }

    @Test
    void aGridWhoseFirstRowHoldsWordsInEveryCellIsNamedByThatRowAndALoneBoxIsInNoGrid() {
        Page page = new Page()
                .line(72, 60, 10, SANS, BODY.split(" "))
                .rect(new PdfRect(72, 100, 150, 25)).rect(new PdfRect(222, 100, 150, 25))
                .rect(new PdfRect(72, 125, 150, 25)).rect(new PdfRect(222, 125, 150, 25))
                .rect(new PdfRect(72, 150, 150, 25)).rect(new PdfRect(222, 150, 150, 25))
                .line(76, 118, 11, SANS, "Name")
                .line(226, 118, 11, SANS, "Phone")
                .line(72, 300, 11, SANS, "Notes")
                .rect(new PdfRect(72, 310, 300, 60));

        List<PdfSpotCandidate> candidates = PdfSpotCandidateDetector.detect(page.graph());

        assertEquals(List.of("Name (row 1)", "Phone (row 1)", "Name (row 2)", "Phone (row 2)", "Notes"),
                candidates.stream().map(PdfSpotCandidate::labelGuess).toList());
        assertEquals(List.of(), candidates.get(0).tableValues(), "no value is printed under the header");
        assertNull(candidates.get(4).gridKey());
    }

    @Test
    void aGridWithAnEmptyCornerIsNamedByItsFirstRowAndTheCornerIsNotOneOfItsCells() {
        Page page = new Page()
                .line(72, 60, 10, SANS, BODY.split(" "))
                .rect(new PdfRect(72, 100, 100, 25)).rect(new PdfRect(172, 100, 100, 25)).rect(new PdfRect(272, 100, 100, 25))
                .rect(new PdfRect(72, 125, 100, 25)).rect(new PdfRect(172, 125, 100, 25)).rect(new PdfRect(272, 125, 100, 25))
                .rect(new PdfRect(72, 150, 100, 25)).rect(new PdfRect(172, 150, 100, 25)).rect(new PdfRect(272, 150, 100, 25))
                .line(176, 118, 11, SANS, "Name")
                .line(276, 118, 11, SANS, "Year")
                .line(76, 143, 11, SANS, "Painting")
                .line(76, 168, 11, SANS, "Drawing");

        List<PdfSpotCandidate> candidates = PdfSpotCandidateDetector.detect(page.graph());
        List<PdfSpotCandidate> cells = candidates.stream().filter(candidate -> candidate.gridKey() != null).toList();

        assertEquals(List.of("Name (Painting)", "Year (Painting)", "Name (Drawing)", "Year (Drawing)"),
                cells.stream().map(PdfSpotCandidate::labelGuess).toList());
        assertTrue(cells.stream().allMatch(candidate -> "P1G1".equals(candidate.gridKey())));
        assertEquals(List.of("Painting", "Drawing"), cells.get(0).tableValues());
        PdfSpotCandidate corner = only(candidates.stream().filter(candidate -> candidate.gridKey() == null).toList());
        assertEquals(new PdfRect(73.5, 101.5, 97, 22), corner.box(), "the empty corner is a box of its own, kept with no grid");
    }

    @Test
    void aLabelEndingWithAColonBesideAGridCellNamesItRatherThanAHeadingAboveTheTable() {
        Page page = new Page()
                .line(72, 60, 10, SANS, BODY.split(" "))
                .line(160, 95, 11, SANS, "Contact")
                .rect(new PdfRect(72, 100, 80, 25)).rect(new PdfRect(152, 100, 220, 25))
                .rect(new PdfRect(72, 125, 80, 25)).rect(new PdfRect(152, 125, 220, 25))
                .line(76, 118, 11, SANS, "Name:")
                .line(76, 143, 11, SANS, "Phone:");

        List<PdfSpotCandidate> candidates = PdfSpotCandidateDetector.detect(page.graph());

        assertEquals(List.of("Name", "Phone"), candidates.stream().map(PdfSpotCandidate::labelGuess).toList());
        assertTrue(candidates.stream().allMatch(candidate -> "P1G1".equals(candidate.gridKey())));
    }

    @Test
    void aPlaceNothingCloseNamesIsNamedByTheNearestWordsLeftOfItOrAShortLineFurtherAbove() {
        Page page = new Page()
                .line(72, 60, 10, SANS, BODY.split(" "))
                .line(72, 200, 11, SANS, "Comments")
                .rule(72, 245, 400, 245)
                .line(72, 400, 11, SANS, "Reference")
                .rule(300, 402, 500, 402);

        List<PdfSpotCandidate> candidates = PdfSpotCandidateDetector.detect(page.graph());

        assertEquals(List.of("Comments", "Reference"), candidates.stream().map(PdfSpotCandidate::labelGuess).toList());
    }

    @Test
    void aPlaceInABoxKeptForTheOfficeIsMarkedSo() {
        Page page = new Page()
                .line(72, 60, 10, SANS, BODY.split(" "))
                .line(72, 100, 11, SANS, "Full", "name:", "____________________")
                .rect(new PdfRect(60, 200, 400, 80))
                .line(72, 220, 11, SANS, "For", "office", "use", "only")
                .line(72, 250, 11, SANS, "Received", "by:", "____________________");

        List<PdfSpotCandidate> candidates = PdfSpotCandidateDetector.detect(page.graph());

        assertEquals(List.of(false, true), candidates.stream().map(PdfSpotCandidate::forOfficeUse).toList());
    }

    @Test
    void theSamePlaceFoundTwiceIsKeptOnceFromItsMostExactShape() {
        Page page = new Page()
                .line(72, 60, 10, SANS, BODY.split(" "))
                .line(72, 100, 11, SANS, "Address:")
                .rule(130, 102, 500, 102);

        PdfSpotCandidate candidate = only(PdfSpotCandidateDetector.detect(page.graph()));

        assertEquals(PdfSpotCandidate.Kind.RULE, candidate.kind());
    }

    @Test
    void aPlaceOverOneOfTheFormsOwnFieldsIsLeftToTheField() {
        Page page = new Page().line(72, 100, 11, SANS, "Name:", "______________________________");
        page.widget(page.word("______________________________").box());

        assertEquals(List.of(), PdfSpotCandidateDetector.detect(page.graph()));
    }

    @Test
    void aSignatureLineIsFlaggedNotDroppedAndADesignerIsNotASignature() {
        Page page = new Page()
                .line(72, 60, 10, SANS, BODY.split(" "))
                .line(72, 100, 11, SANS, "Signature:")
                .rule(140, 102, 340, 102)
                .line(72, 160, 11, SANS, "Designer:")
                .rule(140, 162, 340, 162)
                .line(72, 220, 11, SANS, "Initials:", "________");

        List<PdfSpotCandidate> candidates = PdfSpotCandidateDetector.detect(page.graph());

        assertEquals(List.of(true, false, true), candidates.stream().map(PdfSpotCandidate::signatureLike).toList());
        assertEquals(List.of("Signature", "Designer", "Initials"), candidates.stream().map(PdfSpotCandidate::labelGuess).toList());
    }

    @Test
    void candidatesAreNumberedInReadingOrderAndCappedAtTwoHundred() {
        Page page = new Page();
        for (int row = 0; row < 60; row++) {
            for (int column = 0; column < 5; column++) {
                page.line(20 + column * 110, 30 + row * 12, 4, SANS, "Item:", "______");
            }
        }

        List<PdfSpotCandidate> candidates = PdfSpotCandidateDetector.detect(page.graph());

        assertEquals(PdfSpotCandidateDetector.MAX_CANDIDATES, candidates.size());
        assertEquals("c1", candidates.get(0).id());
        assertEquals("c200", candidates.get(199).id());
        for (int index = 1; index < candidates.size(); index++) {
            assertTrue(candidates.get(index).box().y() >= candidates.get(index - 1).box().y());
        }
    }

    @Test
    void theStyleComesFromTheLabelsFontAndNeverOutgrowsTheBox() {
        Page page = new Page()
                .line(72, 60, 10, SANS, BODY.split(" "))
                .line(72, 100, 10.3, "ABCDEF+Times-Bold", "Surname:", "_______________")
                .line(72, 160, 25, "Courier", "Code:", "__________");

        List<PdfSpotCandidate> candidates = PdfSpotCandidateDetector.detect(page.graph());

        assertEquals(new PdfTextStyle(PdfFontFamily.SERIF, true, 10.5), candidates.get(0).style());
        PdfSpotCandidate code = candidates.get(1);
        assertEquals(PdfFontFamily.MONO, code.style().family());
        assertTrue(code.style().sizePt() <= 0.8 * code.box().height() + 1e-9, code.style() + " in " + code.box());
    }

    @Test
    void theWordsAroundAPlaceAreKeptShort() {
        String[] many = (BODY + " " + BODY).split(" ");
        List<String> words = new ArrayList<>(List.of(many));
        words.add("______________");
        Page page = new Page().line(20, 100, 4, SANS, words.toArray(String[]::new));

        PdfSpotCandidate candidate = only(PdfSpotCandidateDetector.detect(page.graph()));

        assertTrue(candidate.contextText().codePointCount(0, candidate.contextText().length()) <= 80);
    }

    @Test
    void aPageStoredSidewaysIsSearchedTheWayItsTextReads() {
        // A page turned 90 degrees whose text runs upward on the page as stored, so it reads left to right on screen.
        Page page = new Page(90);
        PdfFormGraph.CropBox crop = page.crop;
        PdfRect labelShown = new PdfRect(72, 100 - 7.7, 5 * 5.5, 7.7);
        PdfRect runShown = new PdfRect(72 + 5 * 5.5 + 3, 100 - 7.7, 20 * 5.5, 7.7);
        page.words(List.of(
                new PdfFormGraph.Word("Name:", PdfBoxGeometry.fromDisplayed(labelShown, crop, 90), SANS, 11, 90),
                new PdfFormGraph.Word("_".repeat(20), PdfBoxGeometry.fromDisplayed(runShown, crop, 90), SANS, 11, 90)));

        PdfSpotCandidate candidate = only(PdfSpotCandidateDetector.detect(page.graph()));

        PdfRect shown = PdfBoxGeometry.toDisplayed(candidate.box(), crop, 90);
        assertEquals(runShown.x(), shown.x(), 1e-9);
        assertEquals(runShown.width(), shown.width(), 1e-9);
        assertEquals(runShown.bottom(), shown.bottom(), 1e-9);
        assertEquals("Name", candidate.labelGuess());
    }

    @Test
    void aScanHasNoCandidatesAndAPointedBoxIsTwoInchesWideAtThePoint() {
        Page scan = new Page().noText();

        assertEquals(List.of(), PdfSpotCandidateDetector.detect(scan.graph()));
        PdfBoxSuggestion suggestion = PdfSpotCandidateDetector.boxSuggestion(scan.graph(), 1, new PdfPoint(200, 300));
        assertEquals(new PdfRect(200, 300 - 15.4 / 2, 144, 15.4), rounded(suggestion.box()));
        assertEquals(PdfTextStyle.DEFAULT, suggestion.style());
        assertNull(suggestion.labelGuess());
    }

    @Test
    void pointingJustAfterALabelGivesTheSpaceToTheMargin() {
        Page page = new Page()
                .line(72, 60, 10, SANS, BODY.split(" "))
                .line(72, 100, 11, SANS, "Phone", "number:");
        PdfRect label = page.word("number:").box();

        PdfBoxSuggestion suggestion = PdfSpotCandidateDetector.boxSuggestion(page.graph(), 1, new PdfPoint(label.right() + 20, 96));

        assertEquals(label.right() + 20, suggestion.box().x(), 1e-9);
        assertTrue(suggestion.box().width() > 200);
        assertEquals("Phone number", suggestion.labelGuess());
        assertEquals(new PdfTextStyle(PdfFontFamily.SANS, false, 11), suggestion.style());
        assertEquals(suggestion, PdfSpotCandidateDetector.boxSuggestion(page.graph(), 1, new PdfPoint(label.right() + 20, 96)));
    }

    @Test
    void choosingALineGivesTheSpaceAfterItOrABoxBelowWhenItRunsToTheMargin() {
        // The first line runs to within a few points of the margin, a text line of 11 points at half its size per letter.
        Page page = new Page()
                .line(72, 60, 11, SANS, BODY.split(" "))
                .line(72, 100, 11, SANS, "Emergency", "contact:");

        PdfBoxSuggestion beside = PdfSpotCandidateDetector.boxSuggestion(page.graph(), 1, 1);
        PdfBoxSuggestion below = PdfSpotCandidateDetector.boxSuggestion(page.graph(), 1, 0);

        assertEquals(page.word("contact:").box().right() + 4, beside.box().x(), 1e-9);
        assertEquals("Emergency contact", beside.labelGuess());
        assertTrue(below.box().y() > page.graph().pages().get(0).lines().get(0).box().bottom());
        assertEquals(144, below.box().width(), 1e-9);
    }

    @Test
    void wordsNamedOnALineGetTheBlankAfterThemOrTheSpaceAfterThemAndOtherwiseTheLinesBox() {
        Page page = new Page()
                .line(72, 60, 10, SANS, BODY.split(" "))
                .line(72, 100, 10, SANS, "Company:", "________", "Phone:", "________")
                .line(72, 140, 10, SANS, "Emergency", "contact:");
        List<PdfFormGraph.Word> blanks = page.graph().pages().get(0).lines().get(1).words().stream()
                .filter(word -> word.text().startsWith("_")).toList();

        // "Company:" is the first 8 code points of its line and "Phone:" ends at 24: each gets the blank just after it.
        PdfBoxSuggestion company = PdfSpotCandidateDetector.boxSuggestionAfter(page.graph(), 1, 1, 8);
        PdfBoxSuggestion phone = PdfSpotCandidateDetector.boxSuggestionAfter(page.graph(), 1, 1, 24);
        assertEquals(blanks.get(0).box().x(), company.box().x(), 1e-9);
        assertEquals(blanks.get(0).box().width(), company.box().width(), 1e-9);
        assertEquals(blanks.get(0).box().bottom(), company.box().bottom(), 1e-9);
        assertEquals("Company", company.labelGuess());
        assertEquals(blanks.get(1).box().x(), phone.box().x(), 1e-9);
        assertEquals("Phone", phone.labelGuess());

        // After a label with room beside it, the box runs from just after the words to the margin, the same box a line choice gives.
        PdfBoxSuggestion contact = PdfSpotCandidateDetector.boxSuggestionAfter(page.graph(), 1, 2, 18);
        assertEquals(page.word("contact:").box().right() + 4, contact.box().x(), 1e-9);
        assertEquals(PdfSpotCandidateDetector.boxSuggestion(page.graph(), 1, 2), contact);
        // Words that end inside a word name that word; nothing named at all is the line's own box.
        assertEquals(contact, PdfSpotCandidateDetector.boxSuggestionAfter(page.graph(), 1, 2, 12));
        assertEquals(PdfSpotCandidateDetector.boxSuggestion(page.graph(), 1, 0),
                PdfSpotCandidateDetector.boxSuggestionAfter(page.graph(), 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> PdfSpotCandidateDetector.boxSuggestionAfter(page.graph(), 1, 3, 0));
    }

    @Test
    void twoBlankLinesSetCloserThanALinesHeightAreBothKeptWithoutCoveringEachOther() {
        String blank = "______________________________";
        for (double spacing : new double[] {12, 13}) {
            Page page = new Page()
                    .line(72, 60, 10, SANS, BODY.split(" "))
                    .line(72, 100, 11, SANS, "Address:", blank)
                    .line(126, 100 + spacing, 11, SANS, blank);

            List<PdfSpotCandidate> candidates = PdfSpotCandidateDetector.detect(page.graph());

            assertEquals(2, candidates.size(), spacing + ": " + candidates);
            PdfRect upper = candidates.get(0).box();
            PdfRect lower = candidates.get(1).box();
            assertFalse(TemplateBindingValidator.covers(upper, lower), spacing + ": " + upper + " and " + lower);
            assertEquals(100 + spacing, lower.bottom(), 1e-9, "the lower blank keeps its baseline");
            assertTrue(lower.height() >= PdfSpotCandidateDetector.MIN_LINE_HEIGHT);
            assertTrue(candidates.get(1).style().sizePt() <= 0.8 * lower.height() + 1e-9);
        }
    }

    @Test
    void whatTheCropBoxCutsAwayIsNotLookedAt() {
        Page page = new Page()
                .crop(592)
                .line(72, 60, 10, SANS, BODY.split(" "))
                .line(72, 100, 11, SANS, "Full", "name:", "______________________________")
                .line(72, 656, 11, SANS, "Office", "use:", "______________________________")
                .rule(72, 700, 300, 700)
                .rect(new PdfRect(72, 600, 100, 30));

        PdfSpotCandidate candidate = only(PdfSpotCandidateDetector.detect(page.graph()));

        assertEquals("Full name", candidate.labelGuess());
    }

    @Test
    void aBlankBesideFinePrintGetsTheSmallestSizeABoxMayHave() {
        Page page = new Page()
                .line(72, 60, 10, SANS, BODY.split(" "))
                .line(72, 100, 3, SANS, "Office", "reference:", "________________");

        PdfSpotCandidate candidate = only(PdfSpotCandidateDetector.detect(page.graph()));

        assertEquals(TemplateDerivationService.MIN_BOX_TEXT_SIZE, candidate.style().sizePt());
        assertEquals(TemplateDerivationService.MIN_BOX_TEXT_SIZE,
                TemplateDerivationService.styleBeside(page.graph(), 1, new PdfRect(110, 90, 100, 12)).sizePt(),
                "a box drawn beside the fine print too");
    }

    // ---- building pages by hand ----

    private static PdfSpotCandidate only(List<PdfSpotCandidate> candidates) {
        assertEquals(1, candidates.size(), candidates.toString());
        return candidates.get(0);
    }

    private static PdfRect rounded(PdfRect rect) {
        return new PdfRect(round(rect.x()), round(rect.y()), round(rect.width()), round(rect.height()));
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static final class Page {

        PdfFormGraph.CropBox crop = new PdfFormGraph.CropBox(0, 0, 612, 792);
        final int rotation;
        final List<PdfFormGraph.Line> lines = new ArrayList<>();
        final List<PdfFormGraph.Rule> rules = new ArrayList<>();
        final List<PdfRect> rects = new ArrayList<>();
        final List<PdfRect> widgets = new ArrayList<>();
        boolean hasText = true;

        Page() {
            this(0);
        }

        Page(int rotation) {
            this.rotation = rotation;
        }

        /** One line of words at a baseline, each word followed by a space as wide as a character. */
        Page line(double x, double baseline, double size, String font, String... texts) {
            List<PdfFormGraph.Word> words = new ArrayList<>();
            double at = x;
            for (String text : texts) {
                double width = text.length() * size / 2;
                words.add(new PdfFormGraph.Word(text, new PdfRect(at, baseline - 0.7 * size, width, 0.7 * size), font, size, 0));
                at += width + size / 2;
            }
            return words(words);
        }

        Page words(List<PdfFormGraph.Word> words) {
            PdfRect box = words.get(0).box();
            StringBuilder text = new StringBuilder();
            for (PdfFormGraph.Word word : words) {
                box = box.union(word.box());
                text.append(text.isEmpty() ? "" : " ").append(word.text());
            }
            lines.add(new PdfFormGraph.Line(lines.size(), text.toString(), box, words));
            return this;
        }

        Page rule(double x0, double y0, double x1, double y1) {
            rules.add(new PdfFormGraph.Rule(new PdfPoint(x0, y0), new PdfPoint(x1, y1)));
            return this;
        }

        Page rect(PdfRect rect) {
            rects.add(rect);
            return this;
        }

        Page widget(PdfRect box) {
            widgets.add(box);
            return this;
        }

        /** A crop box as wide as the page and {@code height} tall, keeping the page's top edge. */
        Page crop(double height) {
            crop = new PdfFormGraph.CropBox(0, 792 - height, 612, height);
            return this;
        }

        Page noText() {
            hasText = false;
            return this;
        }

        PdfFormGraph.Word word(String text) {
            return lines.stream().flatMap(line -> line.words().stream()).filter(word -> word.text().equals(text)).findFirst().orElseThrow();
        }

        PdfFormGraph graph() {
            List<PdfFormGraph.Field> fields = new ArrayList<>();
            for (int index = 0; index < widgets.size(); index++) {
                fields.add(new PdfFormGraph.Field("f" + index, PdfFormGraph.FieldKind.TEXT, false, false, false, false, null, null, null,
                        List.of(new PdfFormGraph.Widget(1, widgets.get(index)))));
            }
            PdfFormGraph.AcroForm form = fields.isEmpty()
                    ? PdfFormGraph.AcroForm.absent()
                    : new PdfFormGraph.AcroForm(true, PdfFormGraph.XfaKind.NONE, false, fields, 0);
            PdfFormGraph.Page page = new PdfFormGraph.Page(1, crop, rotation, 1, hasText && !lines.isEmpty(), lines, rules, rects, List.of());
            return new PdfFormGraph("test", List.of(page), form, new PdfFormGraph.Risks(false, false, false));
        }
    }
}
